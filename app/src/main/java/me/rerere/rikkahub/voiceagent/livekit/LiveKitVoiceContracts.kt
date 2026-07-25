package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationCorrelationKind
import java.net.URI
import java.security.MessageDigest

private val LIVEKIT_IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,128}$")
private val LIVEKIT_HASH = Regex("sha256:[0-9a-f]{64}")

@Serializable
internal data class LiveKitReadyMessage(
    val version: Int,
    val voiceSessionId: String,
    val kind: String,
    val observedAt: String,
    val eventIdHash: String,
)

internal fun parseLiveKitReadyMessage(payload: String): LiveKitReadyMessage? {
    val message = runCatching {
        Json.decodeFromString<LiveKitReadyMessage>(payload)
    }.getOrNull() ?: return null
    if (
        message.version != 1 ||
        message.kind != "ready" ||
        !LIVEKIT_HASH.matches(message.eventIdHash)
    ) return null
    if (Json.encodeToString(message) != payload) return null
    return message
}

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
        require(livekitUrl.isSecureLiveKitUrl()) { "LiveKit URL is invalid" }
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

internal data class LiveKitAutomationCorrelation(
    val kind: VoiceAutomationCorrelationKind,
    val hash: String,
)

internal fun LiveKitSessionDetails.automationCorrelations(): List<LiveKitAutomationCorrelation> =
    listOf(
        LiveKitAutomationCorrelation(
            VoiceAutomationCorrelationKind.SESSION,
            voiceSessionId.liveKitAutomationHash(),
        ),
        LiveKitAutomationCorrelation(
            VoiceAutomationCorrelationKind.ROOM,
            roomName.liveKitAutomationHash(),
        ),
        LiveKitAutomationCorrelation(
            VoiceAutomationCorrelationKind.PARTICIPANT,
            mobileParticipantIdentity.liveKitAutomationHash(),
        ),
        LiveKitAutomationCorrelation(
            VoiceAutomationCorrelationKind.PARTICIPANT,
            agentParticipantIdentity.liveKitAutomationHash(),
        ),
        LiveKitAutomationCorrelation(
            VoiceAutomationCorrelationKind.DISPATCH,
            dispatchId.liveKitAutomationHash(),
        ),
    )

internal fun liveKitWorkerReadyHash(activeTraceId: String): String =
    "$activeTraceId:worker_ready".liveKitAutomationHash()

private fun String.liveKitAutomationHash(): String =
    "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

private fun String.isLiveKitIdentifier(): Boolean = LIVEKIT_IDENTIFIER.matches(this)

private fun String.isSecureLiveKitUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return uri.scheme.equals("wss", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        (uri.rawPath.isEmpty() || uri.rawPath == "/") &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}
