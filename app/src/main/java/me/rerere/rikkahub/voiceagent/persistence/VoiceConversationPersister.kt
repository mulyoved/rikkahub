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
                part is UIMessagePart.Tool && part.isPendingHermesTool(callId)
            }
        }
        if (existingToolIndex >= 0) {
            val updatedMessages = currentMessages.toMutableList()
            val existingMessage = currentMessages[existingToolIndex]
            updatedMessages[existingToolIndex] = existingMessage.copy(
                parts = existingMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.isPendingHermesTool(callId)) tool else part
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
    }
}
