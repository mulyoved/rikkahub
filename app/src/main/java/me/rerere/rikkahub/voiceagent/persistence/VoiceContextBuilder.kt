package me.rerere.rikkahub.voiceagent.persistence

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn

data class VoiceContext(
    val systemInstruction: String,
    val turns: List<GeminiContentTurn>,
)

class VoiceContextBuilder {
    fun build(
        assistantName: String,
        assistantPrompt: String,
        conversation: Conversation,
        maxTurns: Int = 20,
    ): VoiceContext {
        return VoiceContext(
            systemInstruction = buildSystemInstruction(
                assistantName = assistantName,
                assistantPrompt = assistantPrompt,
            ),
            turns = conversation.currentMessages
                .mapNotNull { message -> message.toGeminiTurn() }
                .takeLast(maxTurns),
        )
    }

    private fun buildSystemInstruction(
        assistantName: String,
        assistantPrompt: String,
    ): String = "You are $assistantName in RikkaHub voice mode.\n$assistantPrompt"

    private fun UIMessage.toGeminiTurn(): GeminiContentTurn? {
        val text = parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }
            .trim()

        if (text.isBlank()) return null

        return GeminiContentTurn(
            role = if (role == MessageRole.ASSISTANT) "model" else "user",
            text = text,
        )
    }
}
