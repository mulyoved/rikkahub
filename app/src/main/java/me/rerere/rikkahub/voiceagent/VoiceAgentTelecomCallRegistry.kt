package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch

interface VoiceAgentTelecomCall {
    fun disconnectFromApp()
}

@JvmInline
value class VoiceAgentTelecomAttemptId(val value: Long)

data class VoiceAgentTelecomFailure(
    val diagnosticName: String,
    val detail: String,
)

class VoiceAgentTelecomAttemptStartException(
    val attemptId: VoiceAgentTelecomAttemptId,
    val failure: VoiceAgentTelecomFailure,
    cause: Throwable,
) : IllegalStateException(failure.detail, cause)

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
) {
    constructor() : this(afterActivationOutcomeSelected = { _, _ -> })

    private val lock = Any()
    private val attempts = mutableMapOf<VoiceAgentTelecomAttemptId, AttemptRecord>()
    private var nextAttemptId = 0L
    private var currentAttemptId: VoiceAgentTelecomAttemptId? = null

    fun beginAttempt(): VoiceAgentTelecomAttemptId {
        while (true) {
            when (val decision = decideBeginAttempt()) {
                is BeginAttemptDecision.Allocated -> {
                    val retirement = decision.routeRetirement
                    val cleanupError = retirement?.let { routeRetirement ->
                        runCatching {
                            routeRetirement.connection.disconnectFromApp()
                        }.exceptionOrNull().also { error ->
                            finishRetiring(
                                id = routeRetirement.id,
                                record = routeRetirement.record,
                                connection = routeRetirement.connection,
                                cleanupError = error,
                            )
                        }
                    }
                    decision.supersededPublication?.publish()
                    if (cleanupError != null) {
                        failAllocatedAttemptAfterRouteCleanup(decision.id, cleanupError)
                    }
                    return decision.id
                }
                is BeginAttemptDecision.Join -> decision.attempt.awaitResult().getOrThrow()
                is BeginAttemptDecision.Retry -> {
                    val result = runCatching { decision.connection.disconnectFromApp() }
                    finishRetiring(
                        id = decision.id,
                        record = decision.record,
                        connection = decision.connection,
                        cleanupError = result.exceptionOrNull(),
                    )
                    result.getOrThrow()
                }
            }
        }
    }

    private fun decideBeginAttempt(): BeginAttemptDecision = synchronized(lock) {
        attempts.entries.firstNotNullOfOrNull { (id, record) ->
            registryOwnedPredecessorDecisionLocked(id, record)
        }?.let { return@synchronized it }

        check(nextAttemptId < Long.MAX_VALUE) { "Telecom attempt IDs exhausted" }
        val id = VoiceAgentTelecomAttemptId(nextAttemptId + 1)
        val previousId = currentAttemptId
        val previousRecord = previousId?.let(attempts::get)
        var supersededPublication: OutcomePublication? = null
        var routeRetirement: AllocatedRouteRetirement? = null
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
            is AttemptPhase.Active -> {
                check(phase.ownership == RetirementOwnership.RouteLease) {
                    "Registry-owned Telecom active attempt escaped begin decision"
                }
                previousRecord.phase = AttemptPhase.Retiring(
                    connection = phase.connection,
                    failure = checkNotNull(supersededFailure),
                    attempt = RetirementAttempt(),
                    ownership = phase.ownership,
                    execution = RetirementExecution.Synchronous,
                )
                routeRetirement = AllocatedRouteRetirement(
                    id = checkNotNull(previousId),
                    record = previousRecord,
                    connection = phase.connection,
                )
            }
            is AttemptPhase.Activating -> error(
                "Registry-owned Telecom activation escaped begin decision",
            )
            is AttemptPhase.Retiring,
            is AttemptPhase.RetirementFailed,
            is AttemptPhase.Failed,
            null,
            -> Unit
        }
        nextAttemptId = id.value
        attempts[id] = AttemptRecord(id)
        currentAttemptId = id
        BeginAttemptDecision.Allocated(id, supersededPublication, routeRetirement)
    }

    private fun registryOwnedPredecessorDecisionLocked(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
    ): BeginAttemptDecision? = when (val phase = record.phase) {
        is AttemptPhase.RetirementFailed -> {
            val ownership = phase.ownership as? RetirementOwnership.Registry ?: return null
            requireExactClaim(id, ownership)
            record.phase = AttemptPhase.Retiring(
                connection = phase.connection,
                failure = phase.outcomeFailure,
                attempt = RetirementAttempt(),
                ownership = ownership,
                execution = RetirementExecution.Synchronous,
            )
            BeginAttemptDecision.Retry(id, record, phase.connection)
        }
        is AttemptPhase.Retiring -> {
            val ownership = phase.ownership as? RetirementOwnership.Registry ?: return null
            requireExactClaim(id, ownership)
            BeginAttemptDecision.Join(phase.attempt)
        }
        is AttemptPhase.Active -> {
            val ownership = phase.ownership as? RetirementOwnership.Registry ?: return null
            requireExactClaim(id, ownership)
            record.phase = AttemptPhase.Retiring(
                connection = phase.connection,
                failure = replacementRequestedFailure(id),
                attempt = RetirementAttempt(),
                ownership = ownership,
                execution = RetirementExecution.Synchronous,
            )
            BeginAttemptDecision.Retry(id, record, phase.connection)
        }
        is AttemptPhase.Activating -> {
            val ownership = phase.ownership as? RetirementOwnership.Registry ?: return null
            requireExactClaim(id, ownership)
            val attempt = RetirementAttempt()
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

    private fun failAllocatedAttemptAfterRouteCleanup(
        id: VoiceAgentTelecomAttemptId,
        cleanupError: Throwable,
    ): Nothing {
        val failure = VoiceAgentTelecomFailure(
            diagnosticName = "telecom_supersession_cleanup_failed",
            detail = cleanupError.message ?: cleanupError.javaClass.simpleName,
        )
        var publication: OutcomePublication? = null
        val outcome = VoiceAgentTelecomOutcome.Failed(failure)
        synchronized(lock) {
            attempts[id]?.takeIf { record ->
                currentAttemptId == id && record.phase == AttemptPhase.Pending
            }?.also { record ->
                record.phase = AttemptPhase.Failed(failure)
                publication = selectOutcomeLocked(record, outcome)
            }
        }
        publication?.publish()
        throw VoiceAgentTelecomAttemptStartException(id, failure, cleanupError)
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
        var joinedAttempt: RetirementAttempt? = null
        var shouldDisconnect = false
        val accepted = synchronized(lock) {
            when (val phase = record.phase) {
                is AttemptPhase.Activating -> {
                    if (phase.connection !== connection || currentAttemptId != id) {
                        val failure = cancelledFailure(id)
                        record.phase = AttemptPhase.Retiring(
                            connection = connection,
                            failure = failure,
                            attempt = RetirementAttempt(),
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
                            attempt = RetirementAttempt(),
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
                        attempt = RetirementAttempt(),
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

    internal fun claimRouteLease(id: VoiceAgentTelecomAttemptId): TelecomVoiceAgentRouteLease = synchronized(lock) {
        val record = requireNotNull(attempts[id]) { "Unknown Telecom attempt ${id.value}" }
        when (val phase = record.phase) {
            is AttemptPhase.Active -> {
                (phase.ownership as? RetirementOwnership.Registry)?.let { ownership ->
                    check(ownership.claim.attemptId == id) {
                        "Telecom cleanup claim does not match attempt ${id.value}"
                    }
                }
                record.phase = phase.copy(ownership = RetirementOwnership.RouteLease)
                record.outcomeAcknowledged = true
                TelecomVoiceAgentRouteLease(id, this)
            }
            is AttemptPhase.RetirementFailed -> {
                if (phase.ownership is RetirementOwnership.Registry) {
                    record.outcomeAcknowledged = true
                    throw phase.cleanupError
                }
                error("Telecom attempt ${id.value} cleanup belongs to its route lease")
            }
            else -> error("Telecom attempt ${id.value} is not active: ${phase.javaClass.simpleName}")
        }
    }

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
            caller = RetirementCaller.RouteLease,
        )
    }

    fun isOwnedAttemptActive(id: VoiceAgentTelecomAttemptId): Boolean = synchronized(lock) {
        attempts[id]?.phase is AttemptPhase.Active
    }

    fun retireAttempt(id: VoiceAgentTelecomAttemptId, failure: VoiceAgentTelecomFailure) {
        retireAttempt(id, failure, caller = RetirementCaller.Registry)
    }

    private fun retireAttempt(
        id: VoiceAgentTelecomAttemptId,
        failure: VoiceAgentTelecomFailure,
        caller: RetirementCaller,
    ) {
        var record: AttemptRecord? = null
        var connection: VoiceAgentTelecomCall? = null
        var publication: OutcomePublication? = null
        var joinedAttempt: RetirementAttempt? = null
        synchronized(lock) {
            val candidate = attempts[id] ?: return
            when (val phase = candidate.phase) {
                AttemptPhase.Pending -> {
                    candidate.phase = AttemptPhase.Failed(failure)
                    publication = selectOutcomeLocked(
                        candidate,
                        VoiceAgentTelecomOutcome.Failed(failure),
                    )
                    if (currentAttemptId == id) currentAttemptId = null
                }
                is AttemptPhase.Activating -> {
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = RetirementAttempt(),
                        ownership = phase.ownership,
                        execution = RetirementExecution.DeferredToActivation,
                    )
                }
                is AttemptPhase.Active -> {
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = failure,
                        attempt = RetirementAttempt(),
                        ownership = when (caller) {
                            RetirementCaller.Registry -> phase.ownership
                            RetirementCaller.RouteLease -> RetirementOwnership.RouteLease
                        },
                        execution = RetirementExecution.Synchronous,
                    )
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.RetirementFailed -> {
                    candidate.phase = AttemptPhase.Retiring(
                        connection = phase.connection,
                        failure = phase.outcomeFailure,
                        attempt = RetirementAttempt(),
                        ownership = phase.ownership,
                        execution = RetirementExecution.Synchronous,
                    )
                    record = candidate
                    connection = phase.connection
                }
                is AttemptPhase.Retiring -> joinedAttempt = phase.attempt
                is AttemptPhase.Failed,
                -> Unit
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
                    attempt = RetirementAttempt(),
                    ownership = phase.ownership,
                    execution = RetirementExecution.Callback,
                )
                is AttemptPhase.Active -> record.phase = AttemptPhase.Retiring(
                    connection = connection,
                    failure = disconnectedFailure(duringActivation = false),
                    attempt = RetirementAttempt(),
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
        var retirementAttempt: RetirementAttempt? = null
        synchronized(lock) {
            val (id, record) = attemptForConnectionLocked(connection) ?: return
            val currentPhase = record.phase
            if (
                currentPhase is AttemptPhase.Retiring &&
                currentPhase.execution == RetirementExecution.Synchronous
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
        retirementAttempt?.publish(result)
        publication?.publish()
    }

    private fun finishRetiring(
        id: VoiceAgentTelecomAttemptId,
        record: AttemptRecord,
        connection: VoiceAgentTelecomCall,
        cleanupError: Throwable?,
    ) {
        var publication: OutcomePublication? = null
        var retirementAttempt: RetirementAttempt? = null
        synchronized(lock) {
            val phase = record.phase
            if (phase is AttemptPhase.Retiring && phase.connection === connection) {
                retirementAttempt = phase.attempt
                if (cleanupError == null) {
                    publication = terminalizeLocked(id, record, phase.failure)
                } else {
                    record.phase = AttemptPhase.RetirementFailed(
                        connection = connection,
                        outcomeFailure = phase.failure,
                        cleanupError = cleanupError,
                        ownership = phase.ownership,
                    )
                    if (currentAttemptId == id) currentAttemptId = null
                    if (phase.ownership is RetirementOwnership.Registry) {
                        publication = selectOutcomeLocked(
                            record,
                            VoiceAgentTelecomOutcome.CleanupFailed(phase.failure, cleanupError),
                        )
                    }
                }
            }
        }
        retirementAttempt?.publish(
            cleanupError?.let { Result.failure(it) } ?: Result.success(Unit),
        )
        publication?.publish()
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

    private fun requireExactClaim(
        id: VoiceAgentTelecomAttemptId,
        ownership: RetirementOwnership.Registry,
    ) {
        check(ownership.claim.attemptId == id) {
            "Telecom cleanup claim does not match attempt ${id.value}"
        }
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
        val registryOwnership = RetirementOwnership.Registry(RegistryCleanupClaim(id))
        var phase: AttemptPhase = AttemptPhase.Pending
        var selectedOutcome: VoiceAgentTelecomOutcome? = null
        var outcomeAcknowledged = false
    }

    private class RetirementAttempt {
        private val completed = CountDownLatch(1)
        private var result: Result<Unit>? = null

        fun publish(value: Result<Unit>) {
            check(result == null) { "Telecom retirement attempt already completed" }
            result = value
            completed.countDown()
        }

        fun awaitResult(): Result<Unit> {
            var interrupted = false
            while (true) {
                try {
                    completed.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
            return requireNotNull(result)
        }
    }

    private sealed interface BeginAttemptDecision {
        data class Allocated(
            val id: VoiceAgentTelecomAttemptId,
            val supersededPublication: OutcomePublication?,
            val routeRetirement: AllocatedRouteRetirement?,
        ) : BeginAttemptDecision

        data class Join(val attempt: RetirementAttempt) : BeginAttemptDecision

        data class Retry(
            val id: VoiceAgentTelecomAttemptId,
            val record: AttemptRecord,
            val connection: VoiceAgentTelecomCall,
        ) : BeginAttemptDecision
    }

    private data class AllocatedRouteRetirement(
        val id: VoiceAgentTelecomAttemptId,
        val record: AttemptRecord,
        val connection: VoiceAgentTelecomCall,
    )

    private class RegistryCleanupClaim(
        val attemptId: VoiceAgentTelecomAttemptId,
    )

    private sealed interface RetirementOwnership {
        data class Registry(val claim: RegistryCleanupClaim) : RetirementOwnership

        data object RouteLease : RetirementOwnership
    }

    private sealed interface RetirementExecution {
        data object DeferredToActivation : RetirementExecution

        data object Callback : RetirementExecution

        data object Synchronous : RetirementExecution
    }

    private sealed interface RetirementCaller {
        data object Registry : RetirementCaller

        data object RouteLease : RetirementCaller
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
            val attempt: RetirementAttempt,
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
