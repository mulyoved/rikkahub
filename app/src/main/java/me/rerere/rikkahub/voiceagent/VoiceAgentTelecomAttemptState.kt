package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred

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

sealed interface VoiceAgentTelecomOutcome {
    data object Active : VoiceAgentTelecomOutcome

    data class Failed(val failure: VoiceAgentTelecomFailure) : VoiceAgentTelecomOutcome

    data class CleanupFailed(
        val failure: VoiceAgentTelecomFailure,
        val cleanupError: Throwable,
    ) : VoiceAgentTelecomOutcome
}

internal class AttemptRecord(id: VoiceAgentTelecomAttemptId) {
    val registryOwnership = RetirementOwnership.Registry(id)
    var phase: AttemptPhase = AttemptPhase.Pending
    var outcome: AttemptOutcomeState = AttemptOutcomeState.Unselected(
        CompletableDeferred(),
    )

    val completion: CompletableDeferred<VoiceAgentTelecomOutcome>
        get() = outcome.completion

    fun selectOutcome(selected: VoiceAgentTelecomOutcome): OutcomePublication? {
        val unselected = outcome as? AttemptOutcomeState.Unselected ?: return null
        outcome = AttemptOutcomeState.Selected.Unacknowledged(unselected.completion, selected)
        return OutcomePublication(unselected.completion, selected)
    }

    fun acknowledgePublishedOutcome() {
        if (!completion.isCompleted) return
        val selected = outcome as? AttemptOutcomeState.Selected.Unacknowledged ?: return
        outcome = AttemptOutcomeState.Selected.Acknowledged(selected.completion, selected.outcome)
    }

    fun selectedOutcome(): VoiceAgentTelecomOutcome? =
        (outcome as? AttemptOutcomeState.Selected)?.outcome

    fun hasAcknowledgedOutcome(): Boolean = outcome is AttemptOutcomeState.Selected.Acknowledged
}

internal sealed interface AttemptOutcomeState {
    val completion: CompletableDeferred<VoiceAgentTelecomOutcome>

    data class Unselected(
        override val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
    ) : AttemptOutcomeState

    sealed interface Selected : AttemptOutcomeState {
        val outcome: VoiceAgentTelecomOutcome

        data class Unacknowledged(
            override val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
            override val outcome: VoiceAgentTelecomOutcome,
        ) : Selected

        data class Acknowledged(
            override val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
            override val outcome: VoiceAgentTelecomOutcome,
        ) : Selected
    }
}

internal data class OutcomePublication(
    val completion: CompletableDeferred<VoiceAgentTelecomOutcome>,
    val outcome: VoiceAgentTelecomOutcome,
) {
    fun publish() {
        completion.complete(outcome)
    }
}

internal sealed interface BeginAttemptDecision {
    data class Allocated(
        val id: VoiceAgentTelecomAttemptId,
        val supersededPublication: OutcomePublication?,
    ) : BeginAttemptDecision

    data class CleanupFailed(val error: Throwable) : BeginAttemptDecision

    data class Join(val attempt: SynchronousAttemptResult) : BeginAttemptDecision

    data class JoinUndeliveredRoute(
        val attempt: SynchronousAttemptResult,
    ) : BeginAttemptDecision

    data class JoinFailurePublication(
        val publication: RetirementFailurePublication,
    ) : BeginAttemptDecision

    data class Retry(
        val id: VoiceAgentTelecomAttemptId,
        val record: AttemptRecord,
        val connection: VoiceAgentTelecomCall,
    ) : BeginAttemptDecision

    data class RetryUndeliveredRoute(
        val id: VoiceAgentTelecomAttemptId,
        val lease: TelecomVoiceAgentRouteLease,
        val attempt: SynchronousAttemptResult,
    ) : BeginAttemptDecision
}

internal sealed interface ActiveOutcomeConsumptionDecision {
    data class Return(
        val resolution: VoiceAgentRouteResolution,
    ) : ActiveOutcomeConsumptionDecision

    data class Join(
        val attempt: SynchronousAttemptResult,
    ) : ActiveOutcomeConsumptionDecision

    data class JoinFailurePublication(
        val publication: RetirementFailurePublication,
    ) : ActiveOutcomeConsumptionDecision
}

internal fun predecessorDecision(
    id: VoiceAgentTelecomAttemptId,
    record: AttemptRecord,
): BeginAttemptDecision? = when (val phase = record.phase) {
    is AttemptPhase.RetirementFailed.Registry -> {
        requireExactRegistryOwnership(id, phase.ownership)
        record.phase = AttemptPhase.Retiring(
            connection = phase.connection,
            failure = phase.outcomeFailure,
            attempt = SynchronousAttemptResult(),
            execution = RetirementExecution.RegistrySynchronous(phase.ownership),
        )
        BeginAttemptDecision.Retry(id, record, phase.connection)
    }
    is AttemptPhase.RetirementFailed.RouteLease -> {
        when (val delivery = phase.ownership.delivery) {
            RouteLeaseDelivery.Delivered -> BeginAttemptDecision.CleanupFailed(phase.cleanupError)
            RouteLeaseDelivery.RetainedUndelivered -> {
                val attempt = SynchronousAttemptResult()
                record.phase = phase.copy(
                    ownership = phase.ownership.copy(
                        delivery = RouteLeaseDelivery.RetryingUndelivered(attempt),
                    ),
                )
                BeginAttemptDecision.RetryUndeliveredRoute(
                    id = id,
                    lease = phase.ownership.lease,
                    attempt = attempt,
                )
            }
            is RouteLeaseDelivery.RetryingUndelivered -> {
                BeginAttemptDecision.JoinUndeliveredRoute(delivery.attempt)
            }
            is RouteLeaseDelivery.CleanupClaimed -> {
                BeginAttemptDecision.JoinUndeliveredRoute(delivery.claim.attempt)
            }
        }
    }
    is AttemptPhase.Retiring -> {
        when (val ownership = phase.ownership) {
            is RetirementOwnership.Registry -> requireExactRegistryOwnership(id, ownership)
            is RetirementOwnership.RouteLease -> {
                val delivery = ownership.delivery
                if (delivery is RouteLeaseDelivery.RetryingUndelivered) {
                    return BeginAttemptDecision.JoinUndeliveredRoute(delivery.attempt)
                }
            }
        }
        BeginAttemptDecision.Join(phase.attempt)
    }
    is AttemptPhase.RetiredUndeliveredRoute -> {
        BeginAttemptDecision.JoinUndeliveredRoute(phase.retry.attempt)
    }
    is AttemptPhase.PublishingFailure -> {
        BeginAttemptDecision.JoinFailurePublication(phase.publication)
    }
    is AttemptPhase.CleaningUndeliveredRoute -> {
        BeginAttemptDecision.JoinUndeliveredRoute(phase.claim.attempt)
    }
    is AttemptPhase.Active -> {
        (phase.ownership as? RetirementOwnership.Registry)?.let { ownership ->
            requireExactRegistryOwnership(id, ownership)
        }
        record.phase = AttemptPhase.Retiring(
            connection = phase.connection,
            failure = replacementRequestedFailure(id),
            attempt = SynchronousAttemptResult(),
            execution = synchronousRetirementExecution(phase.ownership),
        )
        BeginAttemptDecision.Retry(id, record, phase.connection)
    }
    is AttemptPhase.Activating -> {
        val ownership = phase.ownership
        requireExactRegistryOwnership(id, ownership)
        val attempt = SynchronousAttemptResult()
        record.phase = AttemptPhase.Retiring(
            connection = phase.connection,
            failure = replacementRequestedFailure(id),
            attempt = attempt,
            execution = RetirementExecution.RegistryDeferredToActivation(ownership),
        )
        BeginAttemptDecision.Join(attempt)
    }
    AttemptPhase.Pending,
    is AttemptPhase.Failed,
    -> null
}

internal data class FailedRetirementPublication(
    val id: VoiceAgentTelecomAttemptId,
    val record: AttemptRecord,
    val phase: AttemptPhase.PublishingFailure.Retirement,
)

internal class RetirementFailurePublication(
    val attempt: SynchronousAttemptResult,
) {
    private val finalized = SynchronousAttemptResult()

    fun awaitResult(): Result<Unit> {
        val result = attempt.awaitResult()
        finalized.awaitResult().getOrThrow()
        return result
    }

    fun publishFinalized() {
        finalized.publish(Result.success(Unit))
    }
}

internal data class ClaimedUndeliveredCleanupWork(
    val record: AttemptRecord,
    val connection: VoiceAgentTelecomCall,
)

internal sealed interface UndeliveredRouteRetryCompletion {
    data class Failed(
        val record: AttemptRecord,
        val phase: AttemptPhase.RetirementFailed.RouteLease,
        val ownership: RetirementOwnership.RouteLease,
    ) : UndeliveredRouteRetryCompletion

    data class Succeeded(
        val record: AttemptRecord,
        val phase: AttemptPhase.RetiredUndeliveredRoute,
    ) : UndeliveredRouteRetryCompletion
}

internal sealed interface RetirementOwnership {
    data class Registry(val attemptId: VoiceAgentTelecomAttemptId) : RetirementOwnership

    data class RouteLease(
        val lease: TelecomVoiceAgentRouteLease,
        val delivery: RouteLeaseDelivery,
    ) : RetirementOwnership
}

internal sealed interface ExpectedRetirementOwnership {
    data class Registry(val attemptId: VoiceAgentTelecomAttemptId) : ExpectedRetirementOwnership

    data class RouteLease(
        val lease: TelecomVoiceAgentRouteLease,
    ) : ExpectedRetirementOwnership
}

internal sealed interface RouteLeaseDelivery {
    data object Delivered : RouteLeaseDelivery

    data object RetainedUndelivered : RouteLeaseDelivery

    data class CleanupClaimed(
        val claim: UndeliveredRouteCleanupClaim,
    ) : RouteLeaseDelivery

    data class RetryingUndelivered(
        val attempt: SynchronousAttemptResult,
    ) : RouteLeaseDelivery
}

internal data class RetryingUndeliveredRouteOwnership(
    val lease: TelecomVoiceAgentRouteLease,
    val attempt: SynchronousAttemptResult,
) {
    val ownership = RetirementOwnership.RouteLease(
        lease = lease,
        delivery = RouteLeaseDelivery.RetryingUndelivered(attempt),
    )
}

internal sealed interface RetirementExecution {
    val ownership: RetirementOwnership

    data class RegistryDeferredToActivation(
        override val ownership: RetirementOwnership.Registry,
    ) : RetirementExecution

    data class RegistryCallback(
        override val ownership: RetirementOwnership.Registry,
    ) : RetirementExecution

    data class RegistrySynchronous(
        override val ownership: RetirementOwnership.Registry,
    ) : RetirementExecution

    data class RouteCallback(
        override val ownership: RetirementOwnership.RouteLease,
    ) : RetirementExecution

    data class RouteSynchronous(
        override val ownership: RetirementOwnership.RouteLease,
    ) : RetirementExecution

}

internal fun synchronousRetirementExecution(ownership: RetirementOwnership): RetirementExecution = when (ownership) {
    is RetirementOwnership.Registry -> RetirementExecution.RegistrySynchronous(ownership)
    is RetirementOwnership.RouteLease -> RetirementExecution.RouteSynchronous(ownership)
}

internal fun callbackRetirementExecution(ownership: RetirementOwnership): RetirementExecution = when (ownership) {
    is RetirementOwnership.Registry -> RetirementExecution.RegistryCallback(ownership)
    is RetirementOwnership.RouteLease -> RetirementExecution.RouteCallback(ownership)
}

internal val RetirementExecution.isSynchronous: Boolean
    get() = this is RetirementExecution.RegistrySynchronous || this is RetirementExecution.RouteSynchronous

internal sealed interface AttemptPhase {
    data object Pending : AttemptPhase

    data class Activating(
        val connection: VoiceAgentTelecomCall,
        val ownership: RetirementOwnership.Registry,
    ) : AttemptPhase

    sealed interface Active : AttemptPhase {
        val connection: VoiceAgentTelecomCall
        val ownership: RetirementOwnership

        data class Registry(
            override val connection: VoiceAgentTelecomCall,
            override val ownership: RetirementOwnership.Registry,
        ) : Active

        data class RouteDelivered(
            override val connection: VoiceAgentTelecomCall,
            val lease: TelecomVoiceAgentRouteLease,
        ) : Active {
            override val ownership = RetirementOwnership.RouteLease(
                lease = lease,
                delivery = RouteLeaseDelivery.Delivered,
            )
        }
    }

    data class CleaningUndeliveredRoute(
        val connection: VoiceAgentTelecomCall,
        val lease: TelecomVoiceAgentRouteLease,
        val claim: UndeliveredRouteCleanupClaim,
    ) : AttemptPhase {
        val ownership = RetirementOwnership.RouteLease(
            lease = lease,
            delivery = RouteLeaseDelivery.CleanupClaimed(claim),
        )
    }

    data class Retiring(
        val connection: VoiceAgentTelecomCall,
        val failure: VoiceAgentTelecomFailure,
        val attempt: SynchronousAttemptResult,
        val execution: RetirementExecution,
    ) : AttemptPhase {
        val ownership: RetirementOwnership
            get() = execution.ownership
    }

    sealed interface PublishingFailure : AttemptPhase {
        val connection: VoiceAgentTelecomCall
        val outcomeFailure: VoiceAgentTelecomFailure
        val cleanupError: Throwable
        val ownership: RetirementOwnership
        val publication: RetirementFailurePublication

        sealed interface Retirement : PublishingFailure

        data class RegistryRetirement(
            override val connection: VoiceAgentTelecomCall,
            override val outcomeFailure: VoiceAgentTelecomFailure,
            override val cleanupError: Throwable,
            override val ownership: RetirementOwnership.Registry,
            override val publication: RetirementFailurePublication,
        ) : Retirement

        data class RouteRetirement(
            override val connection: VoiceAgentTelecomCall,
            override val outcomeFailure: VoiceAgentTelecomFailure,
            override val cleanupError: Throwable,
            override val ownership: RetirementOwnership.RouteLease,
            override val publication: RetirementFailurePublication,
        ) : Retirement

        data class UndeliveredCleanupScheduling(
            override val connection: VoiceAgentTelecomCall,
            val lease: TelecomVoiceAgentRouteLease,
            val claim: UndeliveredRouteCleanupClaim,
            override val outcomeFailure: VoiceAgentTelecomFailure,
            override val cleanupError: Throwable,
            override val publication: RetirementFailurePublication,
        ) : PublishingFailure {
            override val ownership = RetirementOwnership.RouteLease(
                lease = lease,
                delivery = RouteLeaseDelivery.CleanupClaimed(claim),
            )
        }
    }

    sealed interface RetirementFailed : AttemptPhase {
        val connection: VoiceAgentTelecomCall
        val outcomeFailure: VoiceAgentTelecomFailure
        val cleanupError: Throwable
        val ownership: RetirementOwnership

        data class Registry(
            override val connection: VoiceAgentTelecomCall,
            override val outcomeFailure: VoiceAgentTelecomFailure,
            override val cleanupError: Throwable,
            override val ownership: RetirementOwnership.Registry,
        ) : RetirementFailed

        data class RouteLease(
            override val connection: VoiceAgentTelecomCall,
            override val outcomeFailure: VoiceAgentTelecomFailure,
            override val cleanupError: Throwable,
            override val ownership: RetirementOwnership.RouteLease,
        ) : RetirementFailed
    }

    data class RetiredUndeliveredRoute(
        val failure: VoiceAgentTelecomFailure,
        val retry: RetryingUndeliveredRouteOwnership,
    ) : AttemptPhase {
        val ownership: RetirementOwnership.RouteLease
            get() = retry.ownership
    }

    data class Failed(val failure: VoiceAgentTelecomFailure) : AttemptPhase
}

internal fun cancelledFailure(id: VoiceAgentTelecomAttemptId) = VoiceAgentTelecomFailure(
    diagnosticName = "telecom_attempt_cancelled",
    detail = "Telecom attempt ${id.value} canceled by cleanup",
)

internal fun replacementRequestedFailure(id: VoiceAgentTelecomAttemptId) = VoiceAgentTelecomFailure(
    diagnosticName = "telecom_attempt_superseded",
    detail = "Telecom attempt ${id.value} superseded by replacement request",
)

internal fun requireExactRegistryOwnership(
    id: VoiceAgentTelecomAttemptId,
    ownership: RetirementOwnership.Registry,
) {
    check(ownership.attemptId == id) {
        "Telecom registry ownership does not match attempt ${id.value}"
    }
}

internal fun requireExpectedOwnership(
    id: VoiceAgentTelecomAttemptId,
    actual: RetirementOwnership,
    expected: ExpectedRetirementOwnership,
) {
    val matches = when (expected) {
        is ExpectedRetirementOwnership.Registry -> actual == RetirementOwnership.Registry(expected.attemptId)
        is ExpectedRetirementOwnership.RouteLease -> {
            actual is RetirementOwnership.RouteLease && actual.lease === expected.lease
        }
    }
    check(matches) {
        "Telecom attempt ${id.value} cleanup ownership does not match its caller"
    }
    (actual as? RetirementOwnership.Registry)?.let { requireExactRegistryOwnership(id, it) }
}

internal fun activationFailure(error: Throwable) = VoiceAgentTelecomFailure(
    diagnosticName = "telecom_activation_failed",
    detail = error.message ?: error.javaClass.simpleName,
)

internal fun disconnectedFailure(duringActivation: Boolean) = VoiceAgentTelecomFailure(
    diagnosticName = "telecom_connection_disconnected",
    detail = if (duringActivation) {
        "Telecom connection disconnected during activation"
    } else {
        "Telecom connection disconnected"
    },
)
