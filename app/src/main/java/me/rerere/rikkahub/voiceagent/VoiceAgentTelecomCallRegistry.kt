package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

interface VoiceAgentTelecomCall {
    fun disconnectFromApp()
}

@JvmInline
value class VoiceAgentTelecomAttemptId(val value: Long)

data class VoiceAgentTelecomFailure(
    val diagnosticName: String,
    val detail: String,
)

sealed interface VoiceAgentTelecomAttemptStartResult {
    data class Allocated(
        val attemptId: VoiceAgentTelecomAttemptId,
    ) : VoiceAgentTelecomAttemptStartResult

    data class CleanupFailed(
        val error: Throwable,
    ) : VoiceAgentTelecomAttemptStartResult
}

internal sealed interface VoiceAgentTelecomActiveConsumptionResult {
    data class Claimed(
        val lease: TelecomVoiceAgentRouteLease,
    ) : VoiceAgentTelecomActiveConsumptionResult

    data class Superseded(
        val metadata: VoiceAgentRouteMetadata,
    ) : VoiceAgentTelecomActiveConsumptionResult
}

sealed interface VoiceAgentTelecomOutcome {
    data object Active : VoiceAgentTelecomOutcome

    data class Failed(val failure: VoiceAgentTelecomFailure) : VoiceAgentTelecomOutcome

    data class CleanupFailed(
        val failure: VoiceAgentTelecomFailure,
        val cleanupError: Throwable,
    ) : VoiceAgentTelecomOutcome
}

class VoiceAgentTelecomCallRegistry internal constructor(
    private val afterActivationOutcomeSelected: (
        VoiceAgentTelecomAttemptId,
        VoiceAgentTelecomOutcome,
    ) -> Unit,
    private val beforeFailedRetirementResultPublished: () -> Unit,
    private val afterFailedRetirementResultPublished: () -> Unit = {},
) {
    internal constructor(
        afterActivationOutcomeSelected: (
            VoiceAgentTelecomAttemptId,
            VoiceAgentTelecomOutcome,
        ) -> Unit,
    ) : this(
        afterActivationOutcomeSelected,
        beforeFailedRetirementResultPublished = {},
        afterFailedRetirementResultPublished = {},
    )

    constructor() : this(
        afterActivationOutcomeSelected = { _, _ -> },
        beforeFailedRetirementResultPublished = {},
        afterFailedRetirementResultPublished = {},
    )

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
            }
        }
    }

    private fun decideBeginAttempt(): BeginAttemptDecision = synchronized(lock) {
        attempts.entries.firstNotNullOfOrNull { (id, record) ->
            predecessorDecisionLocked(id, record)
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
                supersededPublication = selectOutcomeLocked(previousRecord, outcome)
            }
            is AttemptPhase.Active,
            is AttemptPhase.Activating,
            is AttemptPhase.Retiring,
            is AttemptPhase.RetirementFailed,
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

    private fun predecessorDecisionLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
    ): BeginAttemptDecision? = when (val phase = record.phase) {
        is AttemptPhase.RetirementFailed -> {
            when (val ownership = phase.ownership) {
                is RetirementOwnership.Registry -> {
                    requireExactRegistryOwnership(id, ownership)
                    record.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = phase.outcomeFailure,
                        attempt = SynchronousAttemptResult(),
                        ownership = ownership,
                        execution = RetirementExecution.Synchronous,
                    )
                    BeginAttemptDecision.Retry(id, record, phase.connection)
                }
                RetirementOwnership.RouteLease -> BeginAttemptDecision.CleanupFailed(phase.cleanupError)
            }
        }
        is AttemptPhase.Retiring -> {
            (phase.ownership as? RetirementOwnership.Registry)?.let { ownership ->
                requireExactRegistryOwnership(id, ownership)
            }
            BeginAttemptDecision.Join(phase.attempt)
        }
        is AttemptPhase.Active -> {
            (phase.ownership as? RetirementOwnership.Registry)?.let { ownership ->
                requireExactRegistryOwnership(id, ownership)
            }
            record.phase = AttemptPhase.Retiring(
                connection = phase.connection,
                failure = replacementRequestedFailure(id),
                attempt = SynchronousAttemptResult(),
                ownership = phase.ownership,
                execution = RetirementExecution.Synchronous,
            )
            BeginAttemptDecision.Retry(id, record, phase.connection)
        }
        is AttemptPhase.Activating -> {
            val ownership = phase.ownership as? RetirementOwnership.Registry ?: return null
            requireExactRegistryOwnership(id, ownership)
            val attempt = SynchronousAttemptResult()
            record.phase = AttemptPhase.Retiring(
                connection = phase.connection,
                failure = replacementRequestedFailure(id),
                attempt = attempt,
                ownership = ownership,
                execution = RetirementExecution.DeferredToActivation,
            )
            BeginAttemptDecision.Join(attempt)
        }
        AttemptPhase.Pending,
        is AttemptPhase.Failed,
        -> null
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
                            ownership = phase.ownership,
                            execution = RetirementExecution.Synchronous,
                        )
                        shouldDisconnect = true
                        false
                    } else if (activationError != null) {
                        val failure = activationFailure(activationError)
                        record.phase = AttemptPhase.Retiring(
                            connection = connection,
                            failure = failure,
                            attempt = SynchronousAttemptResult(),
                            ownership = phase.ownership,
                            execution = RetirementExecution.Synchronous,
                        )
                        shouldDisconnect = true
                        false
                    } else {
                        record.phase = AttemptPhase.Active(
                            connection = connection,
                            ownership = phase.ownership,
                        )
                        publication = checkNotNull(
                            selectOutcomeLocked(record, VoiceAgentTelecomOutcome.Active),
                        )
                        true
                    }
                }
                is AttemptPhase.Retiring -> {
                    if (phase.connection === connection) {
                        when (phase.execution) {
                            RetirementExecution.DeferredToActivation -> {
                                record.phase = phase.copy(execution = RetirementExecution.Synchronous)
                                shouldDisconnect = true
                            }
                            RetirementExecution.Callback -> shouldDisconnect = true
                            RetirementExecution.Synchronous -> joinedAttempt = phase.attempt
                            RetirementExecution.PublishingFailure -> joinedAttempt = phase.attempt
                        }
                    } else {
                        shouldDisconnect = true
                    }
                    false
                }
                AttemptPhase.Pending,
                is AttemptPhase.Active,
                is AttemptPhase.Failed,
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
            afterActivationOutcomeSelected(id, selected.outcome)
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
                    publication = selectOutcomeLocked(
                        record,
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
                }
                is AttemptPhase.Activating -> {
                    record.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = SynchronousAttemptResult(),
                        ownership = phase.ownership,
                        execution = RetirementExecution.DeferredToActivation,
                    )
                }
                is AttemptPhase.Active,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
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
            if (!record.completion.isCompleted) return
            when (record.phase) {
                is AttemptPhase.Active,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
                -> record.outcomeAcknowledged = true
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
    ): VoiceAgentTelecomActiveConsumptionResult = synchronized(lock) {
        val record = attempts[id] ?: return@synchronized supersededActiveConsumption()
        check(record.selectedOutcome == VoiceAgentTelecomOutcome.Active) {
            "Telecom attempt ${id.value} did not select an active outcome"
        }
        when (val phase = record.phase) {
            is AttemptPhase.Active -> {
                val ownership = phase.ownership as? RetirementOwnership.Registry
                    ?: return@synchronized supersededActiveConsumption()
                requireExactRegistryOwnership(id, ownership)
                record.phase = phase.copy(ownership = RetirementOwnership.RouteLease)
                record.outcomeAcknowledged = true
                VoiceAgentTelecomActiveConsumptionResult.Claimed(
                    TelecomVoiceAgentRouteLease(id, this),
                )
            }
            is AttemptPhase.Failed -> {
                attempts.remove(id)
                if (currentAttemptId == id) {
                    currentAttemptId = null
                }
                supersededActiveConsumption()
            }
            is AttemptPhase.Retiring,
            is AttemptPhase.RetirementFailed,
            -> {
                record.outcomeAcknowledged = true
                supersededActiveConsumption()
            }
            AttemptPhase.Pending,
            is AttemptPhase.Activating,
            -> error("Telecom attempt ${id.value} active outcome is not consumable: ${phase.javaClass.simpleName}")
        }
    }

    private fun supersededActiveConsumption() = VoiceAgentTelecomActiveConsumptionResult.Superseded(
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

    fun retireOwnedAttempt(id: VoiceAgentTelecomAttemptId) {
        retireAttempt(
            id = id,
            failure = cancelledFailure(id),
            expectedOwnership = RetirementOwnership.RouteLease,
        )
    }

    fun isOwnedAttemptActive(id: VoiceAgentTelecomAttemptId): Boolean = synchronized(lock) {
        attempts[id]?.phase is AttemptPhase.Active
    }

    fun retireAttempt(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        retireAttempt(
            id = id,
            failure = failure,
            expectedOwnership = RetirementOwnership.Registry(id),
        )
    }

    private fun retireAttempt(
        id: VoiceAgentTelecomAttemptId,
        failure: VoiceAgentTelecomFailure,
        expectedOwnership: RetirementOwnership,
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
                    publication = selectOutcomeLocked(
                        candidate,
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
                        ownership = phase.ownership,
                        execution = RetirementExecution.DeferredToActivation,
                    )
                }
                is AttemptPhase.Active -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = SynchronousAttemptResult(),
                        ownership = phase.ownership,
                        execution = RetirementExecution.Synchronous,
                    )
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.RetirementFailed -> {
                    requireExpectedOwnership(id, phase.ownership, expectedOwnership)
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = phase.outcomeFailure,
                        attempt = SynchronousAttemptResult(),
                        ownership = phase.ownership,
                        execution = RetirementExecution.Synchronous,
                    )
                    record = candidate
                    connection = phase.connection
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
                    ownership = phase.ownership,
                    execution = RetirementExecution.Callback,
                )
                is AttemptPhase.Active -> record.phase = AttemptPhase.Retiring(
                    connection = connection,
                    failure = disconnectedFailure(duringActivation = false),
                    attempt = SynchronousAttemptResult(),
                    ownership = phase.ownership,
                    execution = RetirementExecution.Callback,
                )
                AttemptPhase.Pending,
                is AttemptPhase.Retiring,
                is AttemptPhase.RetirementFailed,
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
                (
                    currentPhase.execution == RetirementExecution.Synchronous ||
                        currentPhase.execution == RetirementExecution.PublishingFailure
                    )
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
                    record.phase = AttemptPhase.RetirementFailed(
                        connection = connection,
                        outcomeFailure = failure,
                        cleanupError = cleanupError,
                        ownership = ownership,
                    )
                    if (currentAttemptId == id) currentAttemptId = null
                    if (ownership is RetirementOwnership.Registry) {
                        publication = selectOutcomeLocked(
                            record,
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
                    publication = terminalizeLocked(id, record, phase.failure)
                } else if (phase.execution != RetirementExecution.PublishingFailure) {
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
        val publishingPhase = phase.copy(execution = RetirementExecution.PublishingFailure)
        record.phase = publishingPhase
        return FailedRetirementPublication(
            id = id,
            record = record,
            phase = publishingPhase,
            cleanupError = cleanupError,
        )
    }

    private fun publishFailedRetirement(failedPublication: FailedRetirementPublication) {
        beforeFailedRetirementResultPublished()

        var outcomePublication: OutcomePublication? = null
        synchronized(lock) {
            val (id, record, phase, cleanupError) = failedPublication
            if (attempts[id] !== record || record.phase !== phase) return@synchronized

            phase.attempt.publish(Result.failure(cleanupError))
            record.phase = AttemptPhase.RetirementFailed(
                connection = phase.connection,
                outcomeFailure = phase.failure,
                cleanupError = cleanupError,
                ownership = phase.ownership,
            )
            if (currentAttemptId == id) currentAttemptId = null
            if (phase.ownership is RetirementOwnership.Registry) {
                outcomePublication = selectOutcomeLocked(
                    record,
                    VoiceAgentTelecomOutcome.CleanupFailed(phase.failure, cleanupError),
                )
            }
        }
        afterFailedRetirementResultPublished()
        outcomePublication?.publish()
    }

    private fun terminalizeLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        failure: VoiceAgentTelecomFailure,
    ): OutcomePublication? {
        record.phase = AttemptPhase.Failed(failure)
        val publication = selectOutcomeLocked(
            record,
            VoiceAgentTelecomOutcome.Failed(failure),
        )
        if (currentAttemptId == id) currentAttemptId = null
        if (record.outcomeAcknowledged && attempts[id] === record) {
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
            is AttemptPhase.Retiring -> phase.connection
            is AttemptPhase.RetirementFailed -> phase.connection
            AttemptPhase.Pending,
            is AttemptPhase.Failed,
            -> null
        }
        (entry.key to entry.value).takeIf { phaseConnection === connection }
    }

    private fun cancelledFailure(id: VoiceAgentTelecomAttemptId) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_attempt_cancelled",
        detail = "Telecom attempt ${id.value} canceled by cleanup",
    )

    private fun replacementRequestedFailure(id: VoiceAgentTelecomAttemptId) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_attempt_superseded",
        detail = "Telecom attempt ${id.value} superseded by replacement request",
    )

    private fun requireExactRegistryOwnership(
        id: VoiceAgentTelecomAttemptId,
        ownership: RetirementOwnership.Registry,
    ) {
        check(ownership.attemptId == id) {
            "Telecom registry ownership does not match attempt ${id.value}"
        }
    }

    private fun requireExpectedOwnership(
        id: VoiceAgentTelecomAttemptId,
        actual: RetirementOwnership,
        expected: RetirementOwnership,
    ) {
        check(actual == expected) {
            "Telecom attempt ${id.value} cleanup ownership does not match its caller"
        }
        (actual as? RetirementOwnership.Registry)?.let { requireExactRegistryOwnership(id, it) }
    }

    private fun activationFailure(error: Throwable) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_activation_failed",
        detail = error.message ?: error.javaClass.simpleName,
    )

    private fun disconnectedFailure(duringActivation: Boolean) = VoiceAgentTelecomFailure(
        diagnosticName = "telecom_connection_disconnected",
        detail = if (duringActivation) {
            "Telecom connection disconnected during activation"
        } else {
            "Telecom connection disconnected"
        },
    )

    private fun selectOutcomeLocked(
        record: AttemptRecord,
        outcome: VoiceAgentTelecomOutcome,
    ): OutcomePublication? {
        if (record.selectedOutcome != null) return null
        record.selectedOutcome = outcome
        return OutcomePublication(record.completion, outcome)
    }

    private data class OutcomePublication(
        val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
        val outcome: VoiceAgentTelecomOutcome,
    ) {
        fun publish() {
            completion.complete(outcome)
        }
    }

    private class AttemptRecord(id: VoiceAgentTelecomAttemptId) {
        val completion = CompletableDeferred<VoiceAgentTelecomOutcome>()
        val registryOwnership = RetirementOwnership.Registry(id)
        var phase: AttemptPhase = AttemptPhase.Pending
        var selectedOutcome: VoiceAgentTelecomOutcome? = null
        var outcomeAcknowledged = false
    }

    private sealed interface BeginAttemptDecision {
        data class Allocated(
            val id: VoiceAgentTelecomAttemptId,
            val supersededPublication: OutcomePublication?,
        ) : BeginAttemptDecision

        data class CleanupFailed(val error: Throwable) : BeginAttemptDecision

        data class Join(val attempt: SynchronousAttemptResult) : BeginAttemptDecision

        data class Retry(
            val id: VoiceAgentTelecomAttemptId,
            val record: AttemptRecord,
            val connection: VoiceAgentTelecomCall,
        ) : BeginAttemptDecision
    }

    private data class FailedRetirementPublication(
        val id: VoiceAgentTelecomAttemptId,
        val record: AttemptRecord,
        val phase: AttemptPhase.Retiring,
        val cleanupError: Throwable,
    )

    private sealed interface RetirementOwnership {
        data class Registry(val attemptId: VoiceAgentTelecomAttemptId) : RetirementOwnership

        data object RouteLease : RetirementOwnership
    }

    private sealed interface RetirementExecution {
        data object DeferredToActivation : RetirementExecution

        data object Callback : RetirementExecution

        data object Synchronous : RetirementExecution

        data object PublishingFailure : RetirementExecution
    }

    private sealed interface AttemptPhase {
        data object Pending : AttemptPhase

        data class Activating(
            val connection: VoiceAgentTelecomCall,
            val ownership: RetirementOwnership,
        ) : AttemptPhase

        data class Active(
            val connection: VoiceAgentTelecomCall,
            val ownership: RetirementOwnership,
        ) : AttemptPhase

        data class Retiring(
            val connection: VoiceAgentTelecomCall,
            val failure: VoiceAgentTelecomFailure,
            val attempt: SynchronousAttemptResult,
            val ownership: RetirementOwnership,
            val execution: RetirementExecution,
        ) : AttemptPhase

        data class RetirementFailed(
            val connection: VoiceAgentTelecomCall,
            val outcomeFailure: VoiceAgentTelecomFailure,
            val cleanupError: Throwable,
            val ownership: RetirementOwnership,
        ) : AttemptPhase

        data class Failed(val failure: VoiceAgentTelecomFailure) : AttemptPhase
    }
}
