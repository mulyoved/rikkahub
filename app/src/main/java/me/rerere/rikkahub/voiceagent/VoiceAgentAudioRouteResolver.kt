package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.ContinuationInterceptor

internal fun interface VoiceAgentTelecomOutcomeTimeout {
    suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome?
}

private object DefaultVoiceAgentTelecomOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = withTimeoutOrNull(timeoutMs) { observe() }
}

class VoiceAgentAudioRouteResolver internal constructor(
    private val gateway: VoiceAgentTelecomGateway,
    private val registry: VoiceAgentTelecomCallRegistry,
    private val timeoutMs: Long,
    private val outcomeTimeout: VoiceAgentTelecomOutcomeTimeout,
) {
    constructor(
        gateway: VoiceAgentTelecomGateway,
        registry: VoiceAgentTelecomCallRegistry,
        timeoutMs: Long = 3_000L,
    ) : this(gateway, registry, timeoutMs, DefaultVoiceAgentTelecomOutcomeTimeout)

    suspend fun resolve(): VoiceAgentRouteResolution {
        val attempt = beginAttemptRespectingCancellation()
        val resolution = try {
            resolveAttempt(attempt)
        } catch (cancellation: CancellationException) {
            cleanupCancelledAttempt(attempt, cancellation)
            throw cancellation
        }
        val delivery = FinalResolutionDelivery(resolution)
        return try {
            deliverResolution(delivery)
        } catch (cancellation: CancellationException) {
            delivery.attachCleanupFailureTo(cancellation)
            throw cancellation
        }
    }

    private suspend fun resolveAttempt(
        attempt: VoiceAgentTelecomAttemptId,
    ): VoiceAgentRouteResolution {
        gateway.register().exceptionOrNull()?.let { error ->
            return fallback(attempt, "telecom_register_failed", error)
        }
        gateway.startCall(attempt).exceptionOrNull()?.let { error ->
            return fallback(attempt, "telecom_start_failed", error)
        }
        return when (val outcome = outcomeTimeout.awaitOutcome(timeoutMs) { registry.observeOutcome(attempt) }) {
            VoiceAgentTelecomOutcome.Active -> registry.consumeActiveOutcome(attempt)
            is VoiceAgentTelecomOutcome.Failed -> {
                registry.acknowledgeOutcome(attempt)
                VoiceAgentRouteResolution.Resolved(
                    DirectFallbackVoiceAgentRouteLease(outcome.failure),
                )
            }
            is VoiceAgentTelecomOutcome.CleanupFailed -> {
                registry.acknowledgeOutcome(attempt)
                throw outcome.cleanupError
            }
            null -> fallback(
                attempt,
                "telecom_connection_timeout",
                IllegalStateException("Android Telecom did not become active within ${timeoutMs}ms"),
            )
        }
    }

    private suspend fun beginAttemptRespectingCancellation(): VoiceAgentTelecomAttemptId {
        val result = runCatching(registry::beginAttempt)
        val cancellation = runCatching {
            currentCoroutineContext().ensureActive()
        }.exceptionOrNull() as? CancellationException
        if (cancellation != null) {
            val beginFailure = result.exceptionOrNull()
            beginFailure?.let { cancellation.addSuppressedDistinct(it) }
            when (val startResult = result.getOrNull()) {
                is VoiceAgentTelecomAttemptStartResult.Allocated -> {
                    cleanupCancelledAttempt(startResult.attemptId, cancellation)
                }
                is VoiceAgentTelecomAttemptStartResult.CleanupFailed -> {
                    cancellation.addSuppressedDistinct(startResult.error)
                }
                null -> Unit
            }
            throw cancellation
        }
        return when (val startResult = result.getOrThrow()) {
            is VoiceAgentTelecomAttemptStartResult.Allocated -> startResult.attemptId
            is VoiceAgentTelecomAttemptStartResult.CleanupFailed -> throw startResult.error
        }
    }

    private suspend fun cleanupCancelledAttempt(
        attempt: VoiceAgentTelecomAttemptId,
        cancellation: CancellationException,
    ) {
        withContext(NonCancellable) {
            val retirementError = runCatching {
                registry.retireAttempt(
                    attempt,
                    VoiceAgentTelecomFailure(
                        diagnosticName = "telecom_resolution_cancelled",
                        detail = cancellation.message ?: cancellation.javaClass.simpleName,
                    ),
                )
            }.exceptionOrNull()
            val acknowledgementError = runCatching {
                registry.awaitOutcome(attempt)
            }.exceptionOrNull()
            retirementError?.let { cancellation.addSuppressedDistinct(it) }
            acknowledgementError?.let { cancellation.addSuppressedDistinct(it) }
        }
    }

    private suspend fun fallback(
        attempt: VoiceAgentTelecomAttemptId,
        name: String,
        error: Throwable,
    ): VoiceAgentRouteResolution {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        return when (retired) {
            VoiceAgentTelecomOutcome.Active -> registry.consumeActiveOutcome(attempt)
            is VoiceAgentTelecomOutcome.Failed -> VoiceAgentRouteResolution.Resolved(
                DirectFallbackVoiceAgentRouteLease(retired.failure),
            )
            is VoiceAgentTelecomOutcome.CleanupFailed -> throw retired.cleanupError
        }
    }

    private suspend fun deliverResolution(
        delivery: FinalResolutionDelivery,
    ): VoiceAgentRouteResolution = suspendCancellableCoroutine { continuation ->
        val dispatcher = continuation.context[ContinuationInterceptor] as? CoroutineDispatcher
            ?: error("Voice Agent route delivery requires a coroutine dispatcher")
        continuation.invokeOnCancellation(delivery::cleanupUndeliveredResolution)
        dispatcher.dispatch(continuation.context) {
            continuation.resume(delivery.resolution) { cancellation, _, _ ->
                delivery.cleanupUndeliveredResolution(cancellation)
            }
        }
    }
}

private class FinalResolutionDelivery(
    val resolution: VoiceAgentRouteResolution,
) {
    private var cleanup: FinalDeliveryCleanup = FinalDeliveryCleanup.Pending

    @Synchronized
    fun cleanupUndeliveredResolution(cancellation: Throwable?) {
        val cleanupError = when (val state = cleanup) {
            FinalDeliveryCleanup.Pending -> when (val undelivered = resolution) {
                is VoiceAgentRouteResolution.Resolved -> {
                    when (val retirement = undelivered.lease.retireUndelivered()) {
                        UndeliveredRouteRetirement.Retired -> null
                        is UndeliveredRouteRetirement.Retained -> retirement.error
                    }
                }
                is VoiceAgentRouteResolution.CleanupFailed -> undelivered.error
                is VoiceAgentRouteResolution.Superseded -> null
            }
            FinalDeliveryCleanup.Retired -> return
            is FinalDeliveryCleanup.Failed -> state.error
        }
        cleanup = cleanupError?.let(FinalDeliveryCleanup::Failed) ?: FinalDeliveryCleanup.Retired
        if (cleanupError != null && cancellation != null) cancellation.addSuppressedDistinct(cleanupError)
    }

    @Synchronized
    fun attachCleanupFailureTo(cancellation: Throwable) {
        val state = cleanup
        if (state is FinalDeliveryCleanup.Failed) {
            cancellation.addSuppressedDistinct(state.error)
        }
    }
}

private sealed interface FinalDeliveryCleanup {
    data object Pending : FinalDeliveryCleanup

    data object Retired : FinalDeliveryCleanup

    data class Failed(val error: Throwable) : FinalDeliveryCleanup
}

private fun Throwable.addSuppressedDistinct(error: Throwable) {
    if (error !== this && suppressed.none { it === error }) addSuppressed(error)
}
