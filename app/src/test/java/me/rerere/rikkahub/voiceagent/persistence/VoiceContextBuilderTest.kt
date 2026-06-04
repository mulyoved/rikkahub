package me.rerere.rikkahub.voiceagent.persistence

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceContextBuilderTest {
    @Test
    fun `build keeps the last twenty text turns in order`() {
        val conversation = conversationWith(
            (1..25).map { index -> UIMessage.user("message $index") }
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Answer briefly.",
            conversation = conversation,
        )

        assertEquals(
            "You are Hermes in RikkaHub voice mode.\nAnswer briefly.",
            context.systemInstruction,
        )
        assertEquals(20, context.turns.size)
        assertEquals("message 6", context.turns.first().text)
        assertEquals("message 25", context.turns.last().text)
    }

    @Test
    fun `build maps assistant role to model and other roles to user`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("hello"),
                UIMessage.assistant("hi"),
                UIMessage.system("system reminder"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("user", "model", "user"), context.turns.map { it.role })
        assertEquals(listOf("hello", "hi", "system reminder"), context.turns.map { it.text })
    }

    @Test
    fun `build filters blank and non text messages`() {
        val conversation = conversationWith(
            listOf(
                UIMessage.user("included"),
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   "))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "call-1",
                            toolName = "ask_hermes",
                            input = "{}",
                            output = emptyList(),
                        )
                    ),
                ),
                UIMessage.assistant("also included"),
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals(listOf("included", "also included"), context.turns.map { it.text })
    }

    @Test
    fun `build applies max turns after filtering blank and non text messages`() {
        val skippedToolMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "ask_hermes",
                    input = "{}",
                    output = emptyList(),
                )
            ),
        )
        val blankMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   ")))
        val conversation = conversationWith(
            listOf(
                UIMessage.user("text 1"),
                skippedToolMessage,
                UIMessage.assistant("text 2"),
                blankMessage,
                UIMessage.user("text 3"),
                skippedToolMessage,
                blankMessage,
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
            maxTurns = 3,
        )

        assertEquals(listOf("text 1", "text 2", "text 3"), context.turns.map { it.text })
    }

    @Test
    fun `build trims joined text before storing turns`() {
        val conversation = conversationWith(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("  first line"),
                        UIMessagePart.Text("second line  "),
                    ),
                )
            )
        )

        val context = VoiceContextBuilder().build(
            assistantName = "Hermes",
            assistantPrompt = "Prompt",
            conversation = conversation,
        )

        assertEquals("first line\nsecond line", context.turns.single().text)
    }

    private fun conversationWith(messages: List<UIMessage>): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = messages.map(MessageNode::of),
    )
}
