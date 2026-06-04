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

object VoiceContextBuilder {
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
                .takeLast(maxTurns)
                .mapNotNull { message -> message.toGeminiTurn() },
        )
    }

    private fun buildSystemInstruction(
        assistantName: String,
        assistantPrompt: String,
    ): String = buildString {
        appendLine("You are running in RikkaHub voice mode.")
        appendLine("Assistant name: $assistantName")
        appendLine("Assistant prompt:")
        append(assistantPrompt)
    }

    private fun UIMessage.toGeminiTurn(): GeminiContentTurn? {
        val text = parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n") { it.text }

        if (text.isBlank()) return null

        return GeminiContentTurn(
            role = if (role == MessageRole.ASSISTANT) "model" else "user",
            text = text,
        )
    }
}
