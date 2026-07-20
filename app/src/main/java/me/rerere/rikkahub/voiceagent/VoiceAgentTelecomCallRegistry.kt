package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

class VoiceAgentTelecomCallRegistry internal constructor(
    private val probe: VoiceAgentTelecomRegistryProbe = NoOpVoiceAgentTelecomRegistryProbe,
) {
    constructor() : this(NoOpVoiceAgentTelecomRegistryProbe)

    private val lock = Any()
    private val attempts = mutableMapOf<VoiceAgentTelecomAttemptId, AttemptRecord>()
    private var nextAttemptId = 0L
    private var currentAttemptId: VoiceAgentTelecomAttemptId? = null

    fun beginAttempt(): VoiceAgentTelecomAttemptStartResult {
        while (true) {
            when (val decision = decideBeginAttempt()) {
                is BeginAttemptDecision.Allocated -> {
                    decision.supersededPublication?.publish()
                    return VoiceAgentTelecomAttemptStartResult.Allocated(decision.id)
                }
                is BeginAttemptDecision.CleanupFailed -> {
                    return VoiceAgentTelecomAttemptStartResult.CleanupFailed(decision.error)
                }
                is BeginAttemptDecision.Join -> {
                    val cleanupError = decision.attempt.awaitResult().exceptionOrNull()
                    if (cleanupError != null) {
                        return VoiceAgentTelecomAttemptStartResult.CleanupFailed(cleanupError)
                    }
                }
                is BeginAttemptDecision.JoinUndeliveredRoute -> {
                    probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining)
                    val cleanupError = decision.attempt.awaitResult().exceptionOrNull()
                    if (cleanupError != null) {
                        return VoiceAgentTelecomAttemptStartResult.CleanupFailed(cleanupError)
                    }
                }
                is BeginAttemptDecision.Retry -> {
                    val result = runCatching { decision.connection.disconnectFromApp() }
                    finishRetiring(
                        id = decision.id,
                        record = decision.record,
                        connection = decision.connection,
                        cleanupError = result.exceptionOrNull(),
                    )
                    result.exceptionOrNull()?.let { cleanupError ->
                        return VoiceAgentTelecomAttemptStartResult.CleanupFailed(cleanupError)
                    }
                }
                is BeginAttemptDecision.RetryUndeliveredRoute -> {
                    val result = runCatching(decision.lease::retire)
                    completeUndeliveredRouteRetry(decision, result)
                    result.exceptionOrNull()?.let { cleanupError ->
                        return VoiceAgentTelecomAttemptStartResult.CleanupFailed(cleanupError)
                    }
                }
            }
        }
    }

    private fun decideBeginAttempt(): BeginAttemptDecision = synchronized(lock) {
        attempts.entries.firstNotNullOfOrNull { (id, record) ->
            predecessorDecision(id, record)
        }?.let { return@synchronized it }

        check(nextAttemptId < Long.MAX_VALUE) { "Telecom attempt IDs exhausted" }
        val id = VoiceAgentTelecomAttemptId(nextAttemptId + 1)
        val previousId = currentAttemptId
        val previousRecord = previousId?.let(attempts::get)
        var supersededPublication: OutcomePublication? = null
        val supersededFailure = previousId?.let {
            VoiceAgentTelecomFailure(
                diagnosticName = "telecom_attempt_superseded",
                detail = "Telecom attempt ${it.value} superseded by attempt ${id.value}",
            )
        }
        when (val phase = previousRecord?.phase) {
            AttemptPhase.Pending -> {
                val outcome = VoiceAgentTelecomOutcome.Failed(checkNotNull(supersededFailure))
                previousRecord.phase = AttemptPhase.Failed(outcome.failure)
                supersededPublication = previousRecord.selectOutcome(outcome)
            }
            is AttemptPhase.Active,
            is AttemptPhase.Activating,
            is AttemptPhase.CleaningUndeliveredRoute,
            is AttemptPhase.Retiring,
            is AttemptPhase.RetirementFailed,
            is AttemptPhase.RetiredUndeliveredRoute,
            -> error("Unfinished Telecom predecessor escaped begin admission")
            is AttemptPhase.Failed,
            null,
            -> Unit
        }
        nextAttemptId = id.value
        attempts[id] = AttemptRecord(id)
        currentAttemptId = id
        BeginAttemptDecision.Allocated(id, supersededPublication)
    }

    fun activate(
        id: VoiceAgentTelecomAttemptId,
        connection: VoiceAgentTelecomCall,
        makeActive: () -> Unit = {},
    ): Boolean {
        val record = synchronized(lock) {
            attempts[id]?.takeIf { candidate ->
                currentAttemptId == id && candidate.phase == AttemptPhase.Pending
            }?.also { candidate ->
                candidate.phase = AttemptPhase.Activating(
                    connection = connection,
                    ownership = candidate.registryOwnership,
                )
            }
        }
        if (record == null) {
            connection.disconnectFromApp()
            return false
        }

        val activationError = runCatching(makeActive).exceptionOrNull()
        var publication: OutcomePublication? = null
        var joinedAttempt: SynchronousAttemptResult? = null
        var shouldDisconnect = false
        val accepted = synchronized(lock) {
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> {
                    if (phase.connection !== connection || currentAttemptId != id) {
                        val failure = cancelledFailure(id)
                        record.phase = AttemptPhase.Retiring(
                            connection = connection,
                            failure = failure,
                            attempt = SynchronousAttemptResult(),
                            execution = RetirementExecution.RegistrySynchronous(phase.ownership),
                        )
                        shouldDisconnect = true
                        false
                    } else if (activationError != null) {
                        val failure = activationFailure(activationError)
                        record.phase = AttemptPhase.Retiring(
                            connection = connection,
                            failure = failure,
                            attempt = SynchronousAttemptResult(),
                            execution = RetirementExecution.RegistrySynchronous(phase.ownership),
                        )
                        shouldDisconnect = true
                        false
                    } else {
                        record.phase = AttemptPhase.Active.Registry(
                            connection = connection,
                            ownership = phase.ownership,
                        )
                        publication = checkNotNull(
                            record.selectOutcome(VoiceAgentTelecomOutcome.Active),
                        )
                        true
                    }
                }
                is AttemptPhase.Retiring -> {
                    if (phase.connection === connection) {
                        when (val execution = phase.execution) {
                            is RetirementExecution.RegistryDeferredToActivation -> {
                                record.phase = phase.copy(
                                    execution = RetirementExecution.RegistrySynchronous(execution.ownership),
                                )
                                shouldDisconnect = true
                            }
                            is RetirementExecution.RegistryCallback,
                            is RetirementExecution.RouteCallback,
                            -> shouldDisconnect = true
                            is RetirementExecution.RegistrySynchronous,
                            is RetirementExecution.RouteSynchronous,
                            is RetirementExecution.RegistryPublishingFailure,
                            is RetirementExecution.RoutePublishingFailure,
                            -> joinedAttempt = phase.attempt
                        }
                    } else {
                        shouldDisconnect = true
                    }
                    false
                }
                AttemptPhase.Pending,
                is AttemptPhase.Active,
                is AttemptPhase.CleaningUndeliveredRoute,
                is AttemptPhase.Failed,
                is AttemptPhase.RetiredUndeliveredRoute,
                -> {
                    shouldDisconnect = true
                    false
                }
                is AttemptPhase.RetirementFailed -> {
                    shouldDisconnect = phase.connection !== connection
                    false
                }
            }
        }
        publication?.let { selected ->
            probe.onEvent(
                VoiceAgentTelecomRegistryProbeEvent.ActivationOutcomeSelected(id, selected.outcome),
            )
        }
        joinedAttempt?.awaitResult()?.getOrThrow()

        if (!shouldDisconnect) {
            publication?.publish()
            return accepted
        }

        val cleanupResult = runCatching {
            connection.disconnectFromApp()
        }
        finishRetiring(id, record, connection, cleanupResult.exceptionOrNull())
        publication?.publish()
        cleanupResult.getOrThrow()
        return accepted
    }

    fun fail(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        var publication: OutcomePublication? = null
        synchronized(lock) {
            val record = attempts[id] ?: return@synchronized
            when (val phase = record.phase) {
                AttemptPhase.Pending -> {
                    record.phase = AttemptPhase.Failed(failure)
                    publication = record.selectOutcome(
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
                }
                is AttemptPhase.Activating -> {
                    record.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = SynchronousAttemptResult(),
                        execution = RetirementExecution.RegistryDeferredToActivation(phase.ownership),
                    )
                }
                is AttemptPhase.Active,
                is AttemptPhase.CleaningUndeliveredRoute,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
                is AttemptPhase.RetiredUndeliveredRoute,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
        publication?.publish()
    }

    suspend fun observeOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val completion = synchronized(lock) {
            requireNotNull(attempts[id]) { "Unknown Telecom attempt ${id.value}" }.completion
        }
        return completion.await()
    }

    fun acknowledgeOutcome(id: VoiceAgentTelecomAttemptId) {
        synchronized(lock) {
            val record = attempts[id] ?: return
            when (record.phase) {
                is AttemptPhase.Active,
                is AttemptPhase.CleaningUndeliveredRoute,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
                is AttemptPhase.RetiredUndeliveredRoute,
                -> record.acknowledgePublishedOutcome()
                is AttemptPhase.Failed -> {
                    attempts.remove(id)
                    if (currentAttemptId == id) currentAttemptId = null
                }
                AttemptPhase.Pending,
                is AttemptPhase.Activating,
                -> Unit
            }
        }
    }

    suspend fun awaitOutcome(id: VoiceAgentTelecomAttemptId): VoiceAgentTelecomOutcome {
        val outcome = observeOutcome(id)
        acknowledgeOutcome(id)
        return outcome
    }

    internal fun consumeActiveOutcome(
        id: VoiceAgentTelecomAttemptId,
    ): VoiceAgentRouteResolution {
        while (true) {
            when (val decision = decideActiveOutcomeConsumption(id)) {
                is ActiveOutcomeConsumptionDecision.Return -> {
                    if (decision.resolution is VoiceAgentRouteResolution.Resolved) {
                        probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.ActiveOutcomeClaimed)
                    }
                    return decision.resolution
                }
                is ActiveOutcomeConsumptionDecision.Join -> {
                    probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.RouteRetirementJoining)
                    decision.attempt.awaitResult()
                }
            }
        }
    }

    private fun decideActiveOutcomeConsumption(
        id: VoiceAgentTelecomAttemptId,
    ): ActiveOutcomeConsumptionDecision = synchronized(lock) {
        val record = attempts[id] ?: return@synchronized ActiveOutcomeConsumptionDecision.Return(
            supersededActiveConsumption(),
        )
        check(record.selectedOutcome() == VoiceAgentTelecomOutcome.Active) {
            "Telecom attempt ${id.value} did not select an active outcome"
        }
        when (val phase = record.phase) {
            is AttemptPhase.Active.Registry -> {
                requireExactRegistryOwnership(id, phase.ownership)
                val lease = TelecomVoiceAgentRouteLease(id, this)
                record.phase = AttemptPhase.Active.RouteDelivered(
                    connection = phase.connection,
                    lease = lease,
                )
                record.acknowledgePublishedOutcome()
                ActiveOutcomeConsumptionDecision.Return(
                    VoiceAgentRouteResolution.Resolved(lease),
                )
            }
            is AttemptPhase.Active.RouteDelivered -> {
                ActiveOutcomeConsumptionDecision.Return(supersededActiveConsumption())
            }
            is AttemptPhase.CleaningUndeliveredRoute -> {
                record.acknowledgePublishedOutcome()
                ActiveOutcomeConsumptionDecision.Join(phase.claim.attempt)
            }
            is AttemptPhase.Failed -> {
                attempts.remove(id)
                if (currentAttemptId == id) currentAttemptId = null
                ActiveOutcomeConsumptionDecision.Return(supersededActiveConsumption())
            }
            is AttemptPhase.Retiring -> {
                record.acknowledgePublishedOutcome()
                ActiveOutcomeConsumptionDecision.Join(phase.attempt)
            }
            is AttemptPhase.RetirementFailed -> {
                record.acknowledgePublishedOutcome()
                ActiveOutcomeConsumptionDecision.Return(
                    VoiceAgentRouteResolution.CleanupFailed(phase.cleanupError),
                )
            }
            is AttemptPhase.RetiredUndeliveredRoute -> {
                record.acknowledgePublishedOutcome()
                ActiveOutcomeConsumptionDecision.Join(phase.retry.attempt)
            }
            AttemptPhase.Pending,
            is AttemptPhase.Activating,
            -> error("Telecom attempt ${id.value} active outcome is not consumable: ${phase.javaClass.simpleName}")
        }
    }

    private fun supersededActiveConsumption() = VoiceAgentRouteResolution.Superseded(
        VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom),
    )

    internal suspend fun awaitOutcomeIfPresent(
        id: VoiceAgentTelecomAttemptId,
    ): VoiceAgentTelecomOutcome? {
        val completion = synchronized(lock) { attempts[id]?.completion } ?: return null
        val outcome = completion.await()
        acknowledgeOutcome(id)
        return outcome
    }

    internal fun retireOwnedAttempt(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
    ) {
        retireAttempt(
            id = id,
            failure = cancelledFailure(id),
            expectedOwnership = ExpectedRetirementOwnership.RouteLease(lease),
        )
    }

    internal fun retainUndeliveredRouteLease(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
        cleanupError: Throwable,
    ) {
        synchronized(lock) {
            val record = requireNotNull(attempts[id]) {
                "Unknown Telecom attempt ${id.value} for undelivered route retention"
            }
            val phase = record.phase as? AttemptPhase.RetirementFailed.RouteLease
                ?: error("Telecom attempt ${id.value} did not retain a failed route retirement")
            check(phase.cleanupError === cleanupError) {
                "Telecom attempt ${id.value} retained a different cleanup failure"
            }
            val ownership = phase.ownership
            check(ownership.lease === lease) {
                "Telecom attempt ${id.value} retained a different route lease"
            }
            record.phase = phase.copy(
                ownership = ownership.copy(delivery = RouteLeaseDelivery.RetainedUndelivered),
            )
        }
    }

    internal fun claimUndeliveredRouteCleanup(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
    ): UndeliveredRouteCleanupClaim = synchronized(lock) {
        val record = requireNotNull(attempts[id]) {
            "Unknown Telecom attempt ${id.value} for undelivered cleanup claim"
        }
        val phase = record.phase as? AttemptPhase.Active.RouteDelivered
            ?: error("Telecom attempt ${id.value} is not active for undelivered cleanup")
        check(phase.lease === lease) {
            "Telecom attempt ${id.value} route delivery was already claimed"
        }
        val claim = UndeliveredRouteCleanupClaim(lease)
        record.phase = AttemptPhase.CleaningUndeliveredRoute(
            connection = phase.connection,
            lease = lease,
            claim = claim,
        )
        claim
    }

    internal fun retireClaimedUndeliveredRoute(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
        claim: UndeliveredRouteCleanupClaim,
    ) {
        val work = synchronized(lock) {
            val record = requireNotNull(attempts[id]) {
                "Unknown Telecom attempt ${id.value} for claimed undelivered cleanup"
            }
            val phase = record.phase as? AttemptPhase.CleaningUndeliveredRoute
                ?: error("Telecom attempt ${id.value} lost its cleanup claim")
            check(phase.lease === lease && phase.claim === claim) {
                "Telecom attempt ${id.value} cleanup claim does not match its lease"
            }
            record.phase = AttemptPhase.Retiring(
                connection = phase.connection,
                failure = cancelledFailure(id),
                attempt = claim.attempt,
                execution = RetirementExecution.RouteSynchronous(phase.ownership),
            )
            ClaimedUndeliveredCleanupWork(record, phase.connection)
        }
        val result = runCatching { work.connection.disconnectFromApp() }
        finishRetiring(id, work.record, work.connection, result.exceptionOrNull())
        result.getOrThrow()
    }

    internal fun completeUndeliveredRouteCleanup(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
        claim: UndeliveredRouteCleanupClaim,
        result: Result<Unit>,
    ) {
        synchronized(lock) {
            if (result.isFailure) {
                val record = requireNotNull(attempts[id]) {
                    "Telecom attempt ${id.value} lost its failed cleanup claim"
                }
                val phase = record.phase as? AttemptPhase.RetirementFailed.RouteLease
                    ?: error("Telecom attempt ${id.value} did not retain claimed cleanup failure")
                val ownership = phase.ownership
                val claimed = ownership.delivery as? RouteLeaseDelivery.CleanupClaimed
                check(ownership.lease === lease && claimed?.claim === claim) {
                    "Telecom attempt ${id.value} completed a different cleanup claim"
                }
                record.phase = phase.copy(
                    ownership = ownership.copy(delivery = RouteLeaseDelivery.RetainedUndelivered),
                )
            } else {
                check(attempts[id] == null) {
                    "Telecom attempt ${id.value} kept ownership after successful claimed cleanup"
                }
            }
        }
        claim.publishCompletion(result)
    }

    internal fun rejectUndeliveredRouteCleanup(
        id: VoiceAgentTelecomAttemptId,
        lease: TelecomVoiceAgentRouteLease,
        claim: UndeliveredRouteCleanupClaim,
        error: Throwable,
    ) {
        synchronized(lock) {
            val record = requireNotNull(attempts[id]) {
                "Telecom attempt ${id.value} lost its rejected cleanup claim"
            }
            val phase = record.phase as? AttemptPhase.CleaningUndeliveredRoute
                ?: error("Telecom attempt ${id.value} lost its rejected cleanup claim")
            check(phase.lease === lease && phase.claim === claim) {
                "Telecom attempt ${id.value} rejected a different cleanup claim"
            }
            record.phase = AttemptPhase.RetirementFailed.RouteLease(
                connection = phase.connection,
                outcomeFailure = cancelledFailure(id),
                cleanupError = error,
                ownership = RetirementOwnership.RouteLease(
                    lease = lease,
                    delivery = RouteLeaseDelivery.RetainedUndelivered,
                ),
            )
            if (currentAttemptId == id) currentAttemptId = null
        }
        claim.publishAttemptAndCompletion(Result.failure(error))
    }

    private fun completeUndeliveredRouteRetry(
        decision: BeginAttemptDecision.RetryUndeliveredRoute,
        result: Result<Unit>,
    ) {
        val completion = synchronized(lock) {
            val record = requireNotNull(attempts[decision.id]) {
                "Telecom attempt ${decision.id.value} lost its retained route retry record"
            }
            when (val phase = record.phase) {
                is AttemptPhase.RetirementFailed.RouteLease -> {
                    check(result.isFailure) {
                        "Telecom attempt ${decision.id.value} retained a successful route retry"
                    }
                    val ownership = phase.ownership
                    val retry = ownership.delivery as? RouteLeaseDelivery.RetryingUndelivered
                    requireExactUndeliveredRouteRetry(decision, ownership, retry)
                    UndeliveredRouteRetryCompletion.Failed(record, phase, checkNotNull(ownership))
                }
                is AttemptPhase.RetiredUndeliveredRoute -> {
                    check(result.isSuccess) {
                        "Telecom attempt ${decision.id.value} retired a failed route retry"
                    }
                    requireExactUndeliveredRouteRetry(decision, phase.ownership, phase.retry)
                    UndeliveredRouteRetryCompletion.Succeeded(record, phase)
                }
                else -> error("Telecom attempt ${decision.id.value} lost its retained route retry phase")
            }
        }
        if (completion is UndeliveredRouteRetryCompletion.Succeeded) {
            probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.UndeliveredRouteRetryResultPublishing)
        }
        decision.attempt.publish(result)
        synchronized(lock) {
            when (completion) {
                is UndeliveredRouteRetryCompletion.Failed -> {
                    check(attempts[decision.id] === completion.record && completion.record.phase === completion.phase) {
                        "Telecom attempt ${decision.id.value} changed before failed route retry publication"
                    }
                    completion.record.phase = completion.phase.copy(
                        ownership = completion.ownership.copy(
                            delivery = RouteLeaseDelivery.RetainedUndelivered,
                        ),
                    )
                }
                is UndeliveredRouteRetryCompletion.Succeeded -> {
                    check(attempts[decision.id] === completion.record && completion.record.phase === completion.phase) {
                        "Telecom attempt ${decision.id.value} changed before successful route retry publication"
                    }
                    check(terminalizeLocked(decision.id, completion.record, completion.phase.failure) == null) {
                        "Telecom attempt ${decision.id.value} republished its active outcome"
                    }
                }
            }
        }
    }

    private fun requireExactUndeliveredRouteRetry(
        decision: BeginAttemptDecision.RetryUndeliveredRoute,
        ownership: RetirementOwnership.RouteLease?,
        retry: RouteLeaseDelivery.RetryingUndelivered?,
    ) {
        check(ownership?.lease === decision.lease && retry?.attempt === decision.attempt) {
            "Telecom attempt ${decision.id.value} lost its retained route retry ownership"
        }
    }

    fun isOwnedAttemptActive(id: VoiceAgentTelecomAttemptId): Boolean = synchronized(lock) {
        attempts[id]?.phase is AttemptPhase.Active
    }

    fun retireAttempt(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        retireAttempt(
            id = id,
            failure = failure,
            expectedOwnership = ExpectedRetirementOwnership.Registry(id),
        )
    }

    private fun retireAttempt(
        id: VoiceAgentTelecomAttemptId,
        failure: VoiceAgentTelecomFailure,
        expectedOwnership: ExpectedRetirementOwnership,
    ) {
        var record: AttemptRecord? = null
        var connection: VoiceAgentTelecomCall? = null
        var publication: OutcomePublication? = null
        var joinedAttempt: SynchronousAttemptResult? = null
        synchronized(lock) {
            val candidate = attempts[id] ?: return
            when (val phase = candidate.phase) {
                AttemptPhase.Pending -> {
                    requireExpectedOwnership(id, candidate.registryOwnership, expectedOwnership)
                    candidate.phase = AttemptPhase.Failed(failure)
                    publication = candidate.selectOutcome(
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
                    if (currentAttemptId == id) currentAttemptId = null
                }
                is AttemptPhase.Activating -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = SynchronousAttemptResult(),
                        execution = RetirementExecution.RegistryDeferredToActivation(phase.ownership),
                    )
                }
                is AttemptPhase.Active -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = SynchronousAttemptResult(),
                        execution = synchronousRetirementExecution(phase.ownership),
                    )
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.CleaningUndeliveredRoute -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    joinedAttempt = phase.claim.attempt
                }
                is AttemptPhase.RetirementFailed -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = phase.outcomeFailure,
                        attempt = SynchronousAttemptResult(),
                        execution = synchronousRetirementExecution(phase.ownership),
                    )
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.RetiredUndeliveredRoute -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    joinedAttempt = phase.retry.attempt
                }
                is AttemptPhase.Retiring -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    joinedAttempt = phase.attempt
                }
                is AttemptPhase.Failed -> Unit
            }
        }
        publication?.publish()
        joinedAttempt?.awaitResult()?.getOrThrow()

        val retiringRecord = record ?: return
        val retiringConnection = connection ?: return
        val cleanupResult = runCatching {
            retiringConnection.disconnectFromApp()
        }
        finishRetiring(
            id = id,
            record = retiringRecord,
            connection = retiringConnection,
            cleanupError = cleanupResult.exceptionOrNull(),
        )
        cleanupResult.getOrThrow()
    }

    fun retiring(connection: VoiceAgentTelecomCall) {
        synchronized(lock) {
            val (_, record) = attemptForConnectionLocked(connection) ?: return
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> record.phase = AttemptPhase.Retiring(
                    connection = connection,
                    failure = disconnectedFailure(duringActivation = true),
                    attempt = SynchronousAttemptResult(),
                    execution = RetirementExecution.RegistryCallback(phase.ownership),
                )
                is AttemptPhase.Active -> record.phase = AttemptPhase.Retiring(
                    connection = connection,
                    failure = disconnectedFailure(duringActivation = false),
                    attempt = SynchronousAttemptResult(),
                    execution = callbackRetirementExecution(phase.ownership),
                )
                AttemptPhase.Pending,
                is AttemptPhase.CleaningUndeliveredRoute,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
                is AttemptPhase.RetiredUndeliveredRoute,
                is AttemptPhase.Failed,
                -> Unit
            }
        }
    }

    fun retired(connection: VoiceAgentTelecomCall, result: Result<Unit>) {
        var publication: OutcomePublication? = null
        var retirementAttempt: SynchronousAttemptResult? = null
        var failedRetirementPublication: FailedRetirementPublication? = null
        synchronized(lock) {
            val (id, record) = attemptForConnectionLocked(connection) ?: return
            val currentPhase = record.phase
            if (
                currentPhase is AttemptPhase.Retiring &&
                (currentPhase.execution.isSynchronous || currentPhase.execution.isPublishingFailure)
            ) {
                return@synchronized
            }
            val phase = record.phase
            val failure: VoiceAgentTelecomFailure
            val ownership: RetirementOwnership
            when (phase) {
                is AttemptPhase.Activating -> {
                    failure = disconnectedFailure(duringActivation = true)
                    ownership = phase.ownership
                }
                is AttemptPhase.Active -> {
                    failure = disconnectedFailure(duringActivation = false)
                    ownership = phase.ownership
                }
                is AttemptPhase.Retiring -> {
                    retirementAttempt = phase.attempt
                    failure = phase.failure
                    ownership = phase.ownership
                }
                is AttemptPhase.RetirementFailed -> {
                    failure = phase.outcomeFailure
                    ownership = phase.ownership
                }
                is AttemptPhase.RetiredUndeliveredRoute -> return
                is AttemptPhase.CleaningUndeliveredRoute -> return
                AttemptPhase.Pending,
                is AttemptPhase.Failed,
                -> return
            }
            val cleanupError = result.exceptionOrNull()
            if (cleanupError == null) {
                publication = terminalizeLocked(id, record, failure)
            } else {
                val retiringPhase = phase as? AttemptPhase.Retiring
                if (retiringPhase != null) {
                    failedRetirementPublication = stageFailedRetirementPublicationLocked(
                        id = id,
                        record = record,
                        phase = retiringPhase,
                        cleanupError = cleanupError,
                    )
                } else {
                    record.phase = when (ownership) {
                        is RetirementOwnership.Registry -> AttemptPhase.RetirementFailed.Registry(
                            connection = connection,
                            outcomeFailure = failure,
                            cleanupError = cleanupError,
                            ownership = ownership,
                        )
                        is RetirementOwnership.RouteLease -> AttemptPhase.RetirementFailed.RouteLease(
                            connection = connection,
                            outcomeFailure = failure,
                            cleanupError = cleanupError,
                            ownership = ownership,
                        )
                    }
                    if (currentAttemptId == id) currentAttemptId = null
                    if (ownership is RetirementOwnership.Registry) {
                        publication = record.selectOutcome(
                            VoiceAgentTelecomOutcome.CleanupFailed(failure, cleanupError),
                        )
                    }
                }
            }
        }
        val failedPublication = failedRetirementPublication
        if (failedPublication != null) {
            publishFailedRetirement(failedPublication)
        } else {
            retirementAttempt?.publish(result)
            publication?.publish()
        }
    }

    private fun finishRetiring(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        connection: VoiceAgentTelecomCall,
        cleanupError: Throwable?,
    ) {
        var publication: OutcomePublication? = null
        var retirementAttempt: SynchronousAttemptResult? = null
        var failedRetirementPublication: FailedRetirementPublication? = null
        synchronized(lock) {
            val phase = record.phase
            if (phase is AttemptPhase.Retiring && phase.connection === connection) {
                if (cleanupError == null) {
                    retirementAttempt = phase.attempt
                    val routeOwnership = phase.ownership as? RetirementOwnership.RouteLease
                    val retainedRetry = routeOwnership?.delivery as? RouteLeaseDelivery.RetryingUndelivered
                    if (routeOwnership != null && retainedRetry != null) {
                        record.phase = AttemptPhase.RetiredUndeliveredRoute(
                            failure = phase.failure,
                            ownership = routeOwnership,
                            retry = retainedRetry,
                        )
                        if (currentAttemptId == id) currentAttemptId = null
                    } else {
                        publication = terminalizeLocked(id, record, phase.failure)
                    }
                } else if (!phase.execution.isPublishingFailure) {
                    failedRetirementPublication = stageFailedRetirementPublicationLocked(
                        id = id,
                        record = record,
                        phase = phase,
                        cleanupError = cleanupError,
                    )
                }
            }
        }
        val failedPublication = failedRetirementPublication
        if (failedPublication != null) {
            publishFailedRetirement(failedPublication)
        } else {
            retirementAttempt?.publish(Result.success(Unit))
            publication?.publish()
        }
    }

    private fun stageFailedRetirementPublicationLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        phase: AttemptPhase.Retiring,
        cleanupError: Throwable,
    ): FailedRetirementPublication {
        val publishingPhase = phase.copy(execution = phase.execution.publishingFailure())
        record.phase = publishingPhase
        return FailedRetirementPublication(
            id = id,
            record = record,
            phase = publishingPhase,
            cleanupError = cleanupError,
        )
    }

    private fun publishFailedRetirement(failedPublication: FailedRetirementPublication) {
        probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublishing)

        var outcomePublication: OutcomePublication? = null
        synchronized(lock) {
            val (id, record, phase, cleanupError) = failedPublication
            if (attempts[id] !== record || record.phase !== phase) return@synchronized

            phase.attempt.publish(Result.failure(cleanupError))
            record.phase = when (val ownership = phase.ownership) {
                is RetirementOwnership.Registry -> AttemptPhase.RetirementFailed.Registry(
                    connection = phase.connection,
                    outcomeFailure = phase.failure,
                    cleanupError = cleanupError,
                    ownership = ownership,
                )
                is RetirementOwnership.RouteLease -> AttemptPhase.RetirementFailed.RouteLease(
                    connection = phase.connection,
                    outcomeFailure = phase.failure,
                    cleanupError = cleanupError,
                    ownership = ownership,
                )
            }
            if (currentAttemptId == id) currentAttemptId = null
            if (phase.ownership is RetirementOwnership.Registry) {
                outcomePublication = record.selectOutcome(
                    VoiceAgentTelecomOutcome.CleanupFailed(phase.failure, cleanupError),
                )
            }
        }
        probe.onEvent(VoiceAgentTelecomRegistryProbeEvent.FailedRetirementResultPublished)
        outcomePublication?.publish()
    }

    private fun terminalizeLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        failure: VoiceAgentTelecomFailure,
    ): OutcomePublication? {
        record.phase = AttemptPhase.Failed(failure)
        val publication = record.selectOutcome(
            VoiceAgentTelecomOutcome.Failed(failure),
        )
        if (currentAttemptId == id) currentAttemptId = null
        if (record.hasAcknowledgedOutcome() && attempts[id] === record) {
            attempts.remove(id)
        }
        return publication
    }

    private fun attemptForConnectionLocked(
        connection: VoiceAgentTelecomCall,
    ): Pair<VoiceAgentTelecomAttemptId, AttemptRecord>? = attempts.entries.firstNotNullOfOrNull { entry ->
        val phaseConnection = when (val phase = entry.value.phase) {
            is AttemptPhase.Activating -> phase.connection
            is AttemptPhase.Active -> phase.connection
            is AttemptPhase.CleaningUndeliveredRoute -> phase.connection
            is AttemptPhase.Retiring -> phase.connection
            is AttemptPhase.RetirementFailed -> phase.connection
            is AttemptPhase.RetiredUndeliveredRoute -> null
            AttemptPhase.Pending,
            is AttemptPhase.Failed,
            -> null
        }
        (entry.key to entry.value).takeIf { phaseConnection === connection }
    }

}
