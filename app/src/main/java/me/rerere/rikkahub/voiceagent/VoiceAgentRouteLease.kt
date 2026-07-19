package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner

data class VoiceAgentRouteMetadata(
    val owner: VoiceAudioRouteOwner,
    val failure: VoiceAgentTelecomFailure? = null,
)

sealed interface VoiceAgentRouteResolution {
    data class Resolved(
        val lease: VoiceAgentRouteLease,
    ) : VoiceAgentRouteResolution

    data class Superseded(
        val metadata: VoiceAgentRouteMetadata,
    ) : VoiceAgentRouteResolution

    data class CleanupFailed(
        val error: Throwable,
    ) : VoiceAgentRouteResolution
}

sealed interface VoiceAgentRouteLease {
    val metadata: VoiceAgentRouteMetadata
    val isUsable: Boolean
    fun retire()
}

internal sealed interface UndeliveredRouteRetirement {
    data object Retired : UndeliveredRouteRetirement

    data class Retained(
        val error: Throwable,
    ) : UndeliveredRouteRetirement
}

internal class TelecomVoiceAgentRouteLease(
    private val attemptId: VoiceAgentTelecomAttemptId,
    private val registry: VoiceAgentTelecomCallRegistry,
) : VoiceAgentRouteLease {
    private val retirement = RetryableRetirement()

    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isUsable: Boolean
        get() = registry.isOwnedAttemptActive(attemptId)

    override fun retire() = retirement.retire {
        registry.retireOwnedAttempt(attemptId, this)
    }

    fun retireUndelivered(): UndeliveredRouteRetirement {
        val cleanupError = runCatching(::retire).exceptionOrNull()
            ?: return UndeliveredRouteRetirement.Retired
        registry.retainUndeliveredRouteLease(attemptId, this, cleanupError)
        return UndeliveredRouteRetirement.Retained(cleanupError)
    }
}

internal class DirectFallbackVoiceAgentRouteLease(
    failure: VoiceAgentTelecomFailure,
) : VoiceAgentRouteLease {
    override val metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure)
    override val isUsable = true

    override fun retire() = Unit
}

internal fun VoiceAgentRouteLease.retireUndelivered(): UndeliveredRouteRetirement = when (this) {
    is TelecomVoiceAgentRouteLease -> retireUndelivered()
    is DirectFallbackVoiceAgentRouteLease -> {
        retire()
        UndeliveredRouteRetirement.Retired
    }
}
