package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    suspend fun resolve(): VoiceAgentRouteLease {
        val attempt = beginAttemptRespectingCancellation()
        try {
            gateway.register().exceptionOrNull()?.let {
                return fallback(attempt, "telecom_register_failed", it)
            }
            gateway.startCall(attempt).exceptionOrNull()?.let {
                return fallback(attempt, "telecom_start_failed", it)
            }
            return when (val outcome = outcomeTimeout.awaitOutcome(timeoutMs) { registry.observeOutcome(attempt) }) {
                VoiceAgentTelecomOutcome.Active -> {
                    registry.claimRouteLease(attempt)
                }
                is VoiceAgentTelecomOutcome.Failed -> {
                    registry.acknowledgeOutcome(attempt)
                    DirectFallbackVoiceAgentRouteLease(outcome.failure)
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
        } catch (cancellation: CancellationException) {
            cleanupCancelledAttempt(attempt, cancellation)
            throw cancellation
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
    ): VoiceAgentRouteLease {
        val failure = VoiceAgentTelecomFailure(name, error.message ?: error.javaClass.simpleName)
        registry.fail(attempt, failure)
        val retired = withContext(NonCancellable) {
            registry.awaitOutcome(attempt)
        }
        return when (retired) {
            VoiceAgentTelecomOutcome.Active -> registry.claimRouteLease(attempt)
            is VoiceAgentTelecomOutcome.Failed -> DirectFallbackVoiceAgentRouteLease(retired.failure)
            is VoiceAgentTelecomOutcome.CleanupFailed -> throw retired.cleanupError
        }
    }

    private fun Throwable.addSuppressedDistinct(error: Throwable) {
        if (error !== this && suppressed.none { it === error }) addSuppressed(error)
    }
}
