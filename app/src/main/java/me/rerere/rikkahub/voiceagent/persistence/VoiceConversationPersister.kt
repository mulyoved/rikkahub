package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.buildJsonObject
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
        )

        val currentMessages = conversation.currentMessages
        val latestAssistantIndex = currentMessages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (latestAssistantIndex < 0) {
            return conversation.appendMessage(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool),
                )
            )
        }

        val latestAssistant = currentMessages[latestAssistantIndex]
        val hasExistingTool = latestAssistant.parts.any {
            it is UIMessagePart.Tool && it.toolCallId == callId
        }
        if (!hasExistingTool) {
            return conversation.appendMessage(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(tool),
                )
            )
        }

        val updatedMessages = currentMessages.toMutableList()
        updatedMessages[latestAssistantIndex] = latestAssistant.copy(
            parts = latestAssistant.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == callId) tool else part
            }
        )
        return conversation.updateCurrentMessages(updatedMessages)
    }

    private fun Conversation.appendMessage(message: UIMessage): Conversation {
        return updateCurrentMessages(currentMessages + message)
    }

    private fun VoiceToolRecordStatus.toOutputParts(): List<UIMessagePart> {
        return when (this) {
            VoiceToolRecordStatus.Pending -> emptyList()
            is VoiceToolRecordStatus.Complete -> listOf(UIMessagePart.Text(answer))
            is VoiceToolRecordStatus.Failed -> listOf(UIMessagePart.Text(message))
        }
    }

    private companion object {
        const val ASK_HERMES_TOOL_NAME = "ask_hermes"
    }
}
