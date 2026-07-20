package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.serialization.Serializable

private val LIVEKIT_IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,128}$")

@Serializable
data class LiveKitSessionRequest(
    val conversationId: String,
    val traceId: String,
) {
    init {
        require(conversationId.isLiveKitIdentifier()) { "LiveKit conversation identifier is invalid" }
        require(traceId.isLiveKitIdentifier()) { "LiveKit trace identifier is invalid" }
    }
}

@Serializable
data class LiveKitSessionDetails(
    val livekitUrl: String,
    val participantToken: String,
    val roomName: String,
    val voiceSessionId: String,
    val mobileParticipantIdentity: String,
    val agentParticipantIdentity: String,
    val dispatchId: String,
    val expiresAt: String,
) {
    init {
        require(livekitUrl.isNotBlank()) { "LiveKit URL is invalid" }
        require(participantToken.isNotBlank()) { "LiveKit participant token is invalid" }
        require(roomName.isLiveKitIdentifier()) { "LiveKit room identifier is invalid" }
        require(voiceSessionId.isLiveKitIdentifier()) { "LiveKit voice session identifier is invalid" }
        require(mobileParticipantIdentity.isLiveKitIdentifier()) {
            "LiveKit mobile participant identifier is invalid"
        }
        require(agentParticipantIdentity.isLiveKitIdentifier()) {
            "LiveKit agent participant identifier is invalid"
        }
        require(dispatchId.isLiveKitIdentifier()) { "LiveKit dispatch identifier is invalid" }
        require(expiresAt.isNotBlank()) { "LiveKit expiry is invalid" }
    }

    override fun toString(): String =
        "LiveKitSessionDetails(" +
            "livekitUrl=[redacted], " +
            "participantToken=[redacted], " +
            "roomName=$roomName, " +
            "voiceSessionId=$voiceSessionId, " +
            "mobileParticipantIdentity=$mobileParticipantIdentity, " +
            "agentParticipantIdentity=$agentParticipantIdentity, " +
            "dispatchId=$dispatchId, " +
            "expiresAt=$expiresAt" +
            ")"
}

private fun String.isLiveKitIdentifier(): Boolean = LIVEKIT_IDENTIFIER.matches(this)
