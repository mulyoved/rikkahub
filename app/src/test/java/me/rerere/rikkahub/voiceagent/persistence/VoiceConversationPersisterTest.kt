package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceConversationPersisterTest {
    @Test
    fun `append user tool and assistant turns creates visible Hermes tool record`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()

        val updated = conversation
            .let { persister.appendUserTurn(it, "User question") }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Ask Hermes this",
                    status = VoiceToolRecordStatus.Complete("Hermes answer"),
                )
            }
            .let { persister.appendAssistantTurn(it, "Assistant reply", interrupted = false) }

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.ASSISTANT),
            updated.currentMessages.map { it.role },
        )
        assertEquals("User question", updated.currentMessages[0].parts.text())
        assertEquals("Assistant reply", updated.currentMessages[2].parts.text())

        val tool = updated.currentMessages[1].parts.single() as UIMessagePart.Tool
        assertEquals("call-1", tool.toolCallId)
        assertEquals("ask_hermes", tool.toolName)
        assertEquals("Ask Hermes this", tool.input.promptJson())
        assertTrue(tool.output.text().contains("Hermes answer"))
    }

    @Test
    fun `pending to complete upsert replaces matching call id in latest assistant message`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Updated prompt",
                    status = VoiceToolRecordStatus.Complete("Final answer"),
                )
            }

        val assistantMessage = conversation.currentMessages.single()
        val tools = assistantMessage.parts.filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("call-1", tools.single().toolCallId)
        assertEquals("Updated prompt", tools.single().input.promptJson())
        assertEquals("Final answer", tools.single().output.text())
    }

    @Test
    fun `new call id after user turn appends tool message without changing old assistant`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let { persister.appendAssistantTurn(it, "Old assistant reply", interrupted = false) }
            .let { persister.appendUserTurn(it, "New user request") }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-2",
                    prompt = "Fresh prompt",
                    status = VoiceToolRecordStatus.Complete("Fresh answer"),
                )
            }

        assertEquals(
            listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            conversation.currentMessages.map { it.role },
        )
        assertEquals("Old assistant reply", conversation.currentMessages[0].parts.text())
        assertEquals("New user request", conversation.currentMessages[1].parts.text())

        val oldAssistantTools = conversation.currentMessages[0].parts.filterIsInstance<UIMessagePart.Tool>()
        assertTrue(oldAssistantTools.isEmpty())

        val tool = conversation.currentMessages[2].parts.single() as UIMessagePart.Tool
        assertEquals("call-2", tool.toolCallId)
        assertEquals("Fresh prompt", tool.input.promptJson())
        assertEquals("Fresh answer", tool.output.text())
    }

    @Test
    fun `failed status records failure text as tool output`() {
        val persister = VoiceConversationPersister()

        val conversation = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Failed("Hermes failed"),
        )

        val tool = conversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertEquals("Hermes failed", tool.output.text())
    }

    @Test
    fun `interrupted assistant turn records voice status metadata while preserving text`() {
        val persister = VoiceConversationPersister()

        val conversation = persister.appendAssistantTurn(
            conversation = emptyConversation(),
            text = "Partial answer",
            interrupted = true,
        )

        val textPart = conversation.currentMessages.single().parts.single() as UIMessagePart.Text
        assertEquals("Partial answer", textPart.text)
        assertEquals("interrupted", textPart.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    private fun emptyConversation(): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = emptyList(),
    )

    private fun String.promptJson(): String = JsonInstant
        .parseToJsonElement(this)
        .jsonObject["prompt"]!!
        .jsonPrimitive
        .content

    private fun List<UIMessagePart>.text(): String = filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
