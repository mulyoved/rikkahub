package me.rerere.rikkahub.voiceagent

import kotlin.uuid.Uuid

internal data class VoiceAgentCallRequest(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
)
