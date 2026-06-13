package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_CREATED_AT_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_JOB_ID_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_RESULT_ANNOUNCED_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_SOURCE_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_STATUS_KEY
import me.rerere.rikkahub.voiceagent.hermes.HERMES_TOOL_UPDATED_AT_KEY
import kotlin.time.Clock

sealed interface VoiceToolRecordStatus {
    data object Pending : VoiceToolRecordStatus
    data object Queued : VoiceToolRecordStatus
    data object Running : VoiceToolRecordStatus
    data class Complete(val answer: String) : VoiceToolRecordStatus
    data class Failed(val message: String) : VoiceToolRecordStatus
    data class Expired(val message: String) : VoiceToolRecordStatus
    data class Canceled(val message: String) : VoiceToolRecordStatus
}

enum class VoiceTranscriptStatus(val statusName: String) {
    Partial("partial"),
    Complete("complete"),
    Interrupted("interrupted"),
    SessionClosedBeforeFinal("session-closed-before-final"),
}

class VoiceConversationPersister {
    fun appendUserTurn(
        conversation: Conversation,
        text: String,
    ): Conversation = conversation.appendMessage(UIMessage.user(text))

    fun appendAssistantTurn(
        conversation: Conversation,
        text: String,
        interrupted: Boolean,
        sessionId: String? = null,
        turnId: String? = null,
    ): Conversation {
        return conversation.appendMessage(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(
                        text = text,
                        metadata = voiceArtifactMetadata(
                            sessionId = sessionId,
                            eventId = turnId,
                            status = if (interrupted) "interrupted" else "complete",
                        ),
                    )
                ),
            )
        )
    }

    fun upsertUserTranscriptTurn(
        conversation: Conversation,
        text: String,
        turnId: String,
        sessionId: String? = null,
        status: VoiceTranscriptStatus = VoiceTranscriptStatus.Complete,
    ): Conversation = upsertTranscriptTurn(
        conversation = conversation,
        message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = voiceTranscriptMetadata(
                        role = VOICE_TRANSCRIPT_USER_ROLE,
                        turnId = turnId,
                        sessionId = sessionId,
                        status = status.statusName,
                    ),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_USER_ROLE,
        turnId = turnId,
        sessionId = sessionId,
    )

    fun upsertAssistantTranscriptTurn(
        conversation: Conversation,
        text: String,
        interrupted: Boolean,
        turnId: String,
        sessionId: String? = null,
        status: VoiceTranscriptStatus = if (interrupted) {
            VoiceTranscriptStatus.Interrupted
        } else {
            VoiceTranscriptStatus.Complete
        },
    ): Conversation = upsertTranscriptTurn(
        conversation = conversation,
        message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = voiceTranscriptMetadata(
                        role = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
                        turnId = turnId,
                        sessionId = sessionId,
                        status = status.statusName,
                    ),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
        turnId = turnId,
        sessionId = sessionId,
    )

    fun upsertHermesTool(
        conversation: Conversation,
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        sessionId: String? = null,
        jobId: String? = null,
        resultAnnounced: Boolean = false,
    ): Conversation {
        val currentMessages = conversation.currentMessages
        val existingToolIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool && part.shouldReplaceHermesTool(callId = callId, newStatus = status)
            }
        }
        val existingTool = if (existingToolIndex >= 0) {
            currentMessages[existingToolIndex]
                .parts
                .filterIsInstance<UIMessagePart.Tool>()
                .lastOrNull { it.shouldReplaceHermesTool(callId = callId, newStatus = status) }
        } else {
            null
        }
        val createdAt = existingTool?.metadata?.get(HERMES_TOOL_CREATED_AT_KEY)?.jsonPrimitive?.content
        val tool = UIMessagePart.Tool(
            toolCallId = callId,
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = JsonInstant.encodeToString(
                buildJsonObject {
                    put("prompt", prompt)
                }
            ),
            output = status.toOutputParts(
                sessionId = sessionId,
                callId = callId,
                jobId = jobId,
                resultAnnounced = resultAnnounced,
                createdAt = createdAt,
            ),
            metadata = status.toMetadata(
                sessionId = sessionId,
                callId = callId,
                jobId = jobId,
                resultAnnounced = resultAnnounced,
                createdAt = createdAt,
            ),
        )

        if (existingToolIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingToolIndex]
            updatedMessages[existingToolIndex] = existingMessage.copy(
                parts = existingMessage.parts.map { part ->
                    if (
                        part is UIMessagePart.Tool &&
                        part.shouldReplaceHermesTool(callId = callId, newStatus = status)
                    ) {
                        tool
                    } else {
                        part
                    }
                }
            )
            return conversation.updateCurrentMessages(updatedMessages)
        }

        return conversation.appendMessage(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(tool),
            )
        )
    }

    fun markHermesToolResultAnnounced(
        conversation: Conversation,
        callId: String,
    ): Conversation {
        val currentMessages = conversation.currentMessages
        val messageIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part -> part is UIMessagePart.Tool && part.isTerminalHermesTool(callId) }
        }
        if (messageIndex < 0) return conversation

        val partIndex = currentMessages[messageIndex].parts.indexOfLast { part ->
            part is UIMessagePart.Tool && part.isTerminalHermesTool(callId)
        }
        if (partIndex < 0) return conversation

        val updatedMessages = currentMessages.mapIndexed { currentMessageIndex, message ->
            if (currentMessageIndex != messageIndex) {
                message
            } else {
                message.copy(
                    parts = message.parts.mapIndexed { currentPartIndex, part ->
                        if (currentPartIndex == partIndex && part is UIMessagePart.Tool) {
                            part.copy(
                                metadata = part.metadata.withResultAnnounced(),
                                output = part.output.map { outputPart ->
                                    if (outputPart is UIMessagePart.Text) {
                                        outputPart.copy(metadata = outputPart.metadata.withResultAnnounced())
                                    } else {
                                        outputPart
                                    }
                                },
                            )
                        } else {
                            part
                        }
                    }
                )
            }
        }
        return conversation.updateCurrentMessages(updatedMessages)
    }

    private fun upsertTranscriptTurn(
        conversation: Conversation,
        message: UIMessage,
        transcriptRole: String,
        turnId: String,
        sessionId: String?,
    ): Conversation {
        if (message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.isBlank()) {
            return conversation
        }

        val currentMessages = conversation.currentMessages
        val existingIndex = currentMessages.indexOfLast {
            it.isVoiceTranscript(transcriptRole = transcriptRole, turnId = turnId, sessionId = sessionId)
        }
        if (existingIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingIndex]
            updatedMessages[existingIndex] = message.copy(id = existingMessage.id)
            return conversation.updateCurrentMessages(updatedMessages)
        }

        return conversation.appendMessage(message)
    }

    private fun Conversation.appendMessage(message: UIMessage): Conversation {
        return updateCurrentMessages(currentMessages + message)
    }

    private fun VoiceToolRecordStatus.toOutputParts(
        sessionId: String?,
        callId: String,
        jobId: String?,
        resultAnnounced: Boolean,
        createdAt: String?,
    ): List<UIMessagePart> {
        val text = when (this) {
            VoiceToolRecordStatus.Pending,
            VoiceToolRecordStatus.Queued,
            VoiceToolRecordStatus.Running,
                -> null

            is VoiceToolRecordStatus.Complete -> answer
            is VoiceToolRecordStatus.Failed -> message
            is VoiceToolRecordStatus.Expired -> message
            is VoiceToolRecordStatus.Canceled -> message
        } ?: return emptyList()

        return listOf(
            UIMessagePart.Text(
                text,
                metadata = toMetadata(
                    sessionId = sessionId,
                    callId = callId,
                    jobId = jobId,
                    resultAnnounced = resultAnnounced,
                    createdAt = createdAt,
                ),
            )
        )
    }

    private fun UIMessagePart.Tool.shouldReplaceHermesTool(
        callId: String,
        newStatus: VoiceToolRecordStatus,
    ): Boolean {
        if (!isHermesTool(callId)) return false
        return when (newStatus) {
            VoiceToolRecordStatus.Pending,
            VoiceToolRecordStatus.Queued,
            VoiceToolRecordStatus.Running,
                -> isActiveHermesTool()

            is VoiceToolRecordStatus.Complete,
            is VoiceToolRecordStatus.Failed,
            is VoiceToolRecordStatus.Expired,
            is VoiceToolRecordStatus.Canceled,
                -> true
        }
    }

    private fun UIMessagePart.Tool.isHermesTool(callId: String): Boolean {
        return toolCallId == callId && toolName == VoiceAgentToolNames.ASK_HERMES
    }

    private fun UIMessagePart.Tool.isActiveHermesTool(): Boolean {
        return metadata?.get(HERMES_TOOL_STATUS_KEY)?.jsonPrimitive?.content in setOf(
            VoiceToolRecordStatus.Pending.statusName,
            VoiceToolRecordStatus.Queued.statusName,
            VoiceToolRecordStatus.Running.statusName,
        )
    }

    private fun UIMessagePart.Tool.isTerminalHermesTool(callId: String): Boolean {
        if (!isHermesTool(callId)) return false
        return metadata?.get(HERMES_TOOL_STATUS_KEY)?.jsonPrimitive?.content in setOf(
            VoiceToolRecordStatus.Complete("").statusName,
            VoiceToolRecordStatus.Failed("").statusName,
            VoiceToolRecordStatus.Expired("").statusName,
            VoiceToolRecordStatus.Canceled("").statusName,
        )
    }

    private fun VoiceToolRecordStatus.toMetadata(
        sessionId: String?,
        callId: String,
        jobId: String?,
        resultAnnounced: Boolean,
        createdAt: String?,
    ) = buildJsonObject {
        put(HERMES_TOOL_SOURCE_KEY, VoiceAgentToolNames.ASK_HERMES)
        put(HERMES_TOOL_STATUS_KEY, statusName)
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, resultAnnounced)
        jobId?.let { put(HERMES_TOOL_JOB_ID_KEY, it) }
        val timestamp = Clock.System.now().toString()
        put(HERMES_TOOL_CREATED_AT_KEY, createdAt ?: timestamp)
        put(HERMES_TOOL_UPDATED_AT_KEY, timestamp)
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = callId,
            status = statusName,
            callId = callId,
        )
    }

    private fun voiceTranscriptMetadata(
        role: String,
        turnId: String,
        sessionId: String?,
        status: String,
    ) = buildJsonObject {
        put(VOICE_TRANSCRIPT_ROLE_KEY, role)
        put(VOICE_TRANSCRIPT_TURN_ID_KEY, turnId)
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = turnId,
            status = status,
        )
    }

    private fun voiceArtifactMetadata(sessionId: String?, eventId: String?, status: String) = buildJsonObject {
        putVoiceArtifactMetadata(
            sessionId = sessionId,
            eventId = eventId,
            status = status,
        )
    }

    private fun JsonObjectBuilder.putVoiceArtifactMetadata(
        sessionId: String?,
        eventId: String?,
        status: String,
        callId: String? = null,
    ) {
        val timestamp = Clock.System.now().toString()
        put(VOICE_STATUS_KEY, status)
        if (sessionId != null) {
            put(VOICE_SOURCE_KEY, VOICE_SOURCE_AGENT)
            put(VOICE_SESSION_ID_KEY, sessionId)
            eventId?.let { put(VOICE_EVENT_ID_KEY, it) }
            callId?.let { put(VOICE_CALL_ID_KEY, it) }
            put(VOICE_CREATED_AT_KEY, timestamp)
            put(VOICE_UPDATED_AT_KEY, timestamp)
        }
    }

    private fun JsonObject?.withResultAnnounced(): JsonObject = buildJsonObject {
        this@withResultAnnounced?.forEach { (existingKey, existingValue) -> put(existingKey, existingValue) }
        put(HERMES_TOOL_RESULT_ANNOUNCED_KEY, true)
        put(HERMES_TOOL_UPDATED_AT_KEY, Clock.System.now().toString())
    }

    private fun UIMessage.isVoiceTranscript(
        transcriptRole: String,
        turnId: String,
        sessionId: String?,
    ): Boolean {
        return parts.any { part ->
            if (part !is UIMessagePart.Text) return@any false
            val metadata = part.metadata ?: return@any false
            val roleMatches = metadata[VOICE_TRANSCRIPT_ROLE_KEY]?.jsonPrimitive?.content == transcriptRole
            val turnMatches = metadata[VOICE_TRANSCRIPT_TURN_ID_KEY]?.jsonPrimitive?.content == turnId
            val existingSessionId = metadata[VOICE_SESSION_ID_KEY]?.jsonPrimitive?.content
            val sessionMatches = if (sessionId == null) {
                existingSessionId == null
            } else {
                existingSessionId == sessionId
            }
            roleMatches && turnMatches && sessionMatches
        }
    }

    private val VoiceToolRecordStatus.statusName: String
        get() = when (this) {
            VoiceToolRecordStatus.Pending -> "pending"
            VoiceToolRecordStatus.Queued -> "queued"
            VoiceToolRecordStatus.Running -> "running"
            is VoiceToolRecordStatus.Complete -> "complete"
            is VoiceToolRecordStatus.Failed -> "failed"
            is VoiceToolRecordStatus.Expired -> "expired"
            is VoiceToolRecordStatus.Canceled -> "canceled"
        }

    private companion object {
        const val VOICE_SOURCE_KEY = "voice_source"
        const val VOICE_SOURCE_AGENT = "voice_agent"
        const val VOICE_SESSION_ID_KEY = "voice_session_id"
        const val VOICE_EVENT_ID_KEY = "voice_event_id"
        const val VOICE_CALL_ID_KEY = "voice_call_id"
        const val VOICE_STATUS_KEY = "voice_status"
        const val VOICE_CREATED_AT_KEY = "voice_created_at"
        const val VOICE_UPDATED_AT_KEY = "voice_updated_at"
        const val VOICE_TRANSCRIPT_ROLE_KEY = "voice_transcript_role"
        const val VOICE_TRANSCRIPT_TURN_ID_KEY = "voice_transcript_turn_id"
        const val VOICE_TRANSCRIPT_USER_ROLE = "user"
        const val VOICE_TRANSCRIPT_ASSISTANT_ROLE = "assistant"
    }
}
