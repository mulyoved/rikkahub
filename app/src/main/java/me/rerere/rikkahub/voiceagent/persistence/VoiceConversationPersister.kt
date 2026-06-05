package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

sealed interface VoiceToolRecordStatus {
    data object Pending : VoiceToolRecordStatus
    data class Complete(val answer: String) : VoiceToolRecordStatus
    data class Failed(val message: String) : VoiceToolRecordStatus
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
    ): Conversation {
        return conversation.appendMessage(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text(
                        text = text,
                        metadata = buildJsonObject {
                            put("voice_status", if (interrupted) "interrupted" else "complete")
                        },
                    )
                ),
            )
        )
    }

    fun upsertUserTranscriptTurn(
        conversation: Conversation,
        text: String,
        turnId: String,
    ): Conversation = upsertTranscriptTurn(
        conversation = conversation,
        message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = voiceTranscriptMetadata(role = VOICE_TRANSCRIPT_USER_ROLE, turnId = turnId),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_USER_ROLE,
        turnId = turnId,
    )

    fun upsertAssistantTranscriptTurn(
        conversation: Conversation,
        text: String,
        interrupted: Boolean,
        turnId: String,
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
                        status = if (interrupted) "interrupted" else "complete",
                    ),
                )
            ),
        ),
        transcriptRole = VOICE_TRANSCRIPT_ASSISTANT_ROLE,
        turnId = turnId,
    )

    fun upsertHermesTool(
        conversation: Conversation,
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
    ): Conversation {
        val tool = UIMessagePart.Tool(
            toolCallId = callId,
            toolName = ASK_HERMES_TOOL_NAME,
            input = JsonInstant.encodeToString(
                buildJsonObject {
                    put("prompt", prompt)
                }
            ),
            output = status.toOutputParts(),
            metadata = status.toMetadata(),
        )

        val currentMessages = conversation.currentMessages
        val existingToolIndex = currentMessages.indexOfLast { message ->
            message.parts.any { part ->
                part is UIMessagePart.Tool &&
                    part.isHermesTool(callId) &&
                    (status !is VoiceToolRecordStatus.Pending || part.isPendingHermesTool(callId))
            }
        }
        if (existingToolIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingToolIndex]
            updatedMessages[existingToolIndex] = existingMessage.copy(
                parts = existingMessage.parts.map { part ->
                    if (
                        part is UIMessagePart.Tool &&
                        part.isHermesTool(callId) &&
                        (status !is VoiceToolRecordStatus.Pending || part.isPendingHermesTool(callId))
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

    private fun upsertTranscriptTurn(
        conversation: Conversation,
        message: UIMessage,
        transcriptRole: String,
        turnId: String,
    ): Conversation {
        if (message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }.isBlank()) {
            return conversation
        }

        val currentMessages = conversation.currentMessages
        val existingIndex = currentMessages.indexOfLast { it.isVoiceTranscript(transcriptRole, turnId) }
        if (existingIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingIndex]
            updatedMessages[existingIndex] = message.copy(id = existingMessage.id)
            return conversation.updateCurrentMessages(
                updatedMessages
            )
        }

        return conversation.appendMessage(message)
    }

    private fun Conversation.appendMessage(message: UIMessage): Conversation {
        return updateCurrentMessages(currentMessages + message)
    }

    private fun VoiceToolRecordStatus.toOutputParts(): List<UIMessagePart> {
        return when (this) {
            VoiceToolRecordStatus.Pending -> emptyList()
            is VoiceToolRecordStatus.Complete -> listOf(UIMessagePart.Text(answer, metadata = toMetadata()))
            is VoiceToolRecordStatus.Failed -> listOf(UIMessagePart.Text(message, metadata = toMetadata()))
        }
    }

    private fun UIMessagePart.Tool.isHermesTool(callId: String): Boolean {
        return toolCallId == callId && toolName == ASK_HERMES_TOOL_NAME
    }

    private fun UIMessagePart.Tool.isPendingHermesTool(callId: String): Boolean {
        return isHermesTool(callId) &&
            metadata?.get(HERMES_TOOL_STATUS_KEY)?.jsonPrimitive?.content == VoiceToolRecordStatus.Pending.statusName
    }

    private fun VoiceToolRecordStatus.toMetadata() = buildJsonObject {
        put(HERMES_TOOL_SOURCE_KEY, ASK_HERMES_TOOL_NAME)
        put(HERMES_TOOL_STATUS_KEY, statusName)
    }

    private fun voiceTranscriptMetadata(role: String, turnId: String, status: String? = null) = buildJsonObject {
        put(VOICE_TRANSCRIPT_ROLE_KEY, role)
        put(VOICE_TRANSCRIPT_TURN_ID_KEY, turnId)
        status?.let { put("voice_status", it) }
    }

    private fun UIMessage.isVoiceTranscript(transcriptRole: String, turnId: String): Boolean {
        return parts.any { part ->
            part is UIMessagePart.Text &&
                part.metadata?.get(VOICE_TRANSCRIPT_ROLE_KEY)?.jsonPrimitive?.content == transcriptRole &&
                part.metadata?.get(VOICE_TRANSCRIPT_TURN_ID_KEY)?.jsonPrimitive?.content == turnId
        }
    }

    private val VoiceToolRecordStatus.statusName: String
        get() = when (this) {
            VoiceToolRecordStatus.Pending -> "pending"
            is VoiceToolRecordStatus.Complete -> "complete"
            is VoiceToolRecordStatus.Failed -> "failed"
        }

    private companion object {
        const val ASK_HERMES_TOOL_NAME = "ask_hermes"
        const val HERMES_TOOL_SOURCE_KEY = "voice_tool_source"
        const val HERMES_TOOL_STATUS_KEY = "voice_tool_status"
        const val VOICE_TRANSCRIPT_ROLE_KEY = "voice_transcript_role"
        const val VOICE_TRANSCRIPT_TURN_ID_KEY = "voice_transcript_turn_id"
        const val VOICE_TRANSCRIPT_USER_ROLE = "user"
        const val VOICE_TRANSCRIPT_ASSISTANT_ROLE = "assistant"
    }
}
