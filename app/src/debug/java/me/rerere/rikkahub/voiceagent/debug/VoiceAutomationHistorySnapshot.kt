package me.rerere.rikkahub.voiceagent.debug

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.persistence.VOICE_EVENT_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_GROUNDED_JOB_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_GROUNDED_RESULT_HASH_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SESSION_ID_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SOURCE_AGENT
import me.rerere.rikkahub.voiceagent.persistence.VOICE_SOURCE_KEY
import me.rerere.rikkahub.voiceagent.persistence.VOICE_STATUS_KEY
import kotlin.uuid.Uuid

@Serializable
internal data class VoiceAutomationHistoryRecord(
    val identityHash: String,
    val jobHash: String? = null,
    val sessionHash: String? = null,
    val userTurnHash: String? = null,
    val status: String,
    val announcement: String,
    val requestHash: String? = null,
    val argumentHash: String? = null,
    val resultHash: String? = null,
)

@Serializable
internal data class VoiceAutomationTranscriptRecord(
    val role: String,
    val status: String,
    val eventHash: String? = null,
    val sessionHash: String? = null,
    val groundedJobHash: String? = null,
    val groundedResultHash: String? = null,
)

@Serializable
internal data class VoiceAutomationHistorySnapshot(
    val conversationHash: String,
    val recordCount: Int,
    val transcriptCount: Int,
    val records: List<VoiceAutomationHistoryRecord>,
    val transcripts: List<VoiceAutomationTranscriptRecord>,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        // Keep a worst-case snapshot (all optional hashes populated) below the
        // helper's 64 KiB transport contract.
        private const val MAX_RECORDS = 48
        private const val MAX_TRANSCRIPTS = 48
        private val DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")
        private val ALLOWED_TRANSCRIPT_STATUSES = setOf(
            "partial",
            "complete",
            "interrupted",
            "session-closed-before-final",
        )
        private val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        fun empty(conversationHash: String) = VoiceAutomationHistorySnapshot(
            conversationHash = conversationHash,
            recordCount = 0,
            transcriptCount = 0,
            records = emptyList(),
            transcripts = emptyList(),
        )

        fun from(conversationId: Uuid, conversation: Conversation): VoiceAutomationHistorySnapshot {
            val records = conversation.hermesQueueRecords().map { record ->
                VoiceAutomationHistoryRecord(
                    identityHash = hashIdentifier("${record.callId}\u0000${record.jobId.orEmpty()}"),
                    jobHash = record.jobId?.let(::hashIdentifier),
                    sessionHash = record.voiceSessionId?.let(::hashIdentifier),
                    userTurnHash = record.originatingUserTurnId?.let(::hashIdentifier),
                    status = record.status.wireName,
                    announcement = record.announcement.wireName,
                    requestHash = record.requestHash?.let(::sanitizeDigest),
                    argumentHash = record.argumentHash?.let(::sanitizeDigest),
                    resultHash = record.resultHash?.let(::sanitizeDigest),
                )
            }
            val transcripts = conversation.currentMessages.flatMap { message ->
                message.parts.filterIsInstance<UIMessagePart.Text>().mapNotNull { part ->
                    val metadata = part.metadata ?: return@mapNotNull null
                    val isVoice = metadata.string(VOICE_SOURCE_KEY) == VOICE_SOURCE_AGENT
                    val groundedJobId = metadata.string(VOICE_GROUNDED_JOB_ID_KEY)
                    if (!isVoice && groundedJobId == null) return@mapNotNull null
                    val rawStatus = metadata.string(VOICE_STATUS_KEY)
                    VoiceAutomationTranscriptRecord(
                        role = when (message.role) {
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            else -> "other"
                        },
                        status = rawStatus?.takeIf(ALLOWED_TRANSCRIPT_STATUSES::contains) ?: "unknown",
                        eventHash = metadata.string(VOICE_EVENT_ID_KEY)?.let(::hashIdentifier),
                        sessionHash = metadata.string(VOICE_SESSION_ID_KEY)?.let(::hashIdentifier),
                        groundedJobHash = groundedJobId?.let(::hashIdentifier),
                        groundedResultHash = metadata.string(VOICE_GROUNDED_RESULT_HASH_KEY)
                            ?.let(::sanitizeDigest),
                    )
                }
            }
            return VoiceAutomationHistorySnapshot(
                conversationHash = hashIdentifier(conversationId.toString()),
                recordCount = records.size,
                transcriptCount = transcripts.size,
                records = records.takeLast(MAX_RECORDS),
                transcripts = transcripts.takeLast(MAX_TRANSCRIPTS),
            )
        }

        private fun sanitizeDigest(value: String): String =
            value.lowercase().takeIf(DIGEST_PATTERN::matches) ?: hashIdentifier(value)

        private fun hashIdentifier(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(prefix = "sha256:", separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }

        private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull
    }
}
