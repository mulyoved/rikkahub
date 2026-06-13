package me.rerere.rikkahub.voiceagent.persistence

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
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
        assertEquals("complete", tool.metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("Ask Hermes this", tool.input.promptJson())
        assertTrue(tool.output.text().contains("Hermes answer"))
        val toolOutput = tool.output.single() as UIMessagePart.Text
        assertEquals("complete", toolOutput.metadata!!["voice_tool_status"]!!.jsonPrimitive.content)

        val assistantReply = updated.currentMessages[2].parts.single() as UIMessagePart.Text
        assertEquals("complete", assistantReply.metadata!!["voice_status"]!!.jsonPrimitive.content)
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
        assertEquals("complete", tools.single().metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("Updated prompt", tools.single().input.promptJson())
        assertEquals("Final answer", tools.single().output.text())
    }

    @Test
    fun `voice transcript upsert keeps streaming fragments in one visible turn`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let { persister.upsertUserTranscriptTurn(it, "hel", turnId = "user-1") }
            .let { persister.upsertUserTranscriptTurn(it, "hello", turnId = "user-1") }
            .let { persister.upsertAssistantTranscriptTurn(it, "h", interrupted = false, turnId = "assistant-1") }
            .let { persister.upsertAssistantTranscriptTurn(it, "hi", interrupted = true, turnId = "assistant-1") }

        assertEquals(
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            conversation.currentMessages.map { it.role },
        )
        assertEquals("hello", conversation.currentMessages[0].parts.text())
        assertEquals("hi", conversation.currentMessages[1].parts.text())
        val assistantText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text
        assertEquals("interrupted", assistantText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert transcript keeps same turn id from different voice sessions`() {
        val persister = VoiceConversationPersister()
        val conversation = Conversation.ofId(Uuid.random())

        val afterFirst = persister.upsertUserTranscriptTurn(
            conversation = conversation,
            text = "first session text",
            turnId = "user-1",
            sessionId = "session-a",
            status = VoiceTranscriptStatus.Complete,
        )
        val afterSecond = persister.upsertUserTranscriptTurn(
            conversation = afterFirst,
            text = "second session text",
            turnId = "user-1",
            sessionId = "session-b",
            status = VoiceTranscriptStatus.Complete,
        )

        val texts = afterSecond.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }

        assertEquals(listOf("first session text", "second session text"), texts)
    }

    @Test
    fun `upsert transcript with session id does not replace legacy transcript without session id`() {
        val persister = VoiceConversationPersister()
        val conversation = Conversation.ofId(Uuid.random())

        val legacyTranscript = persister.upsertUserTranscriptTurn(
            conversation = conversation,
            text = "legacy text",
            turnId = "user-1",
            sessionId = null,
            status = VoiceTranscriptStatus.Complete,
        )
        val newSessionTranscript = persister.upsertUserTranscriptTurn(
            conversation = legacyTranscript,
            text = "new session text",
            turnId = "user-1",
            sessionId = "session-b",
            status = VoiceTranscriptStatus.Complete,
        )

        val texts = newSessionTranscript.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }

        assertEquals(listOf("legacy text", "new session text"), texts)
    }

    @Test
    fun `user transcript upsert preserves partial and session closed statuses`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hel",
                    turnId = "user-1",
                    status = VoiceTranscriptStatus.Partial,
                )
            }
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hello",
                    turnId = "user-1",
                    status = VoiceTranscriptStatus.SessionClosedBeforeFinal,
                )
            }

        assertEquals(1, conversation.currentMessages.size)
        val userText = conversation.currentMessages.single().parts.single() as UIMessagePart.Text
        assertEquals("hello", userText.text)
        assertEquals("session-closed-before-final", userText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant transcript upsert preserves partial complete and session closed statuses`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "h",
                    interrupted = false,
                    turnId = "assistant-1",
                    status = VoiceTranscriptStatus.Partial,
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "hi",
                    interrupted = false,
                    turnId = "assistant-1",
                    status = VoiceTranscriptStatus.Complete,
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "later",
                    interrupted = false,
                    turnId = "assistant-2",
                    status = VoiceTranscriptStatus.SessionClosedBeforeFinal,
                )
            }

        assertEquals(2, conversation.currentMessages.size)
        val completeText = conversation.currentMessages[0].parts.single() as UIMessagePart.Text
        val closedText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text
        assertEquals("hi", completeText.text)
        assertEquals("complete", completeText.metadata!!["voice_status"]!!.jsonPrimitive.content)
        assertEquals("later", closedText.text)
        assertEquals("session-closed-before-final", closedText.metadata!!["voice_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `voice transcript upsert replaces matching turn even when tool record interleaves`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let { persister.upsertUserTranscriptTurn(it, "hel", turnId = "user-1") }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }
            .let { persister.upsertUserTranscriptTurn(it, "hello", turnId = "user-1") }

        assertEquals(2, conversation.currentMessages.size)
        assertEquals("hello", conversation.currentMessages[0].parts.text())
        val tool = conversation.currentMessages[1].parts.single() as UIMessagePart.Tool
        assertEquals("call-1", tool.toolCallId)
    }

    @Test
    fun `complete upsert updates pending tool before newer assistant turn without appending duplicate`() {
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
            .let { persister.appendAssistantTurn(it, "Assistant reply", interrupted = false) }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Updated prompt",
                    status = VoiceToolRecordStatus.Complete("Final answer"),
                )
            }

        assertEquals(2, conversation.currentMessages.size)
        assertEquals("Assistant reply", conversation.currentMessages[1].parts.text())

        val tools = conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("call-1", tools.single().toolCallId)
        assertEquals("Updated prompt", tools.single().input.promptJson())
        assertEquals("Final answer", tools.single().output.text())
    }

    @Test
    fun `pending status records machine readable pending status on tool metadata`() {
        val persister = VoiceConversationPersister()

        val conversation = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Pending,
        )

        val tool = conversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertEquals("pending", tool.metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertTrue(tool.output.isEmpty())
    }

    @Test
    fun `pending upsert with reused call id appends after completed Hermes record`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Complete("Original answer"),
                )
            }
            .let { persister.appendAssistantTurn(it, "Later assistant reply", interrupted = false) }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Reused prompt",
                    status = VoiceToolRecordStatus.Pending,
                )
            }

        assertEquals(3, conversation.currentMessages.size)

        val tools = conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(2, tools.size)
        assertEquals("call-1", tools[0].toolCallId)
        assertEquals("Original prompt", tools[0].input.promptJson())
        assertEquals("Original answer", tools[0].output.text())
        assertEquals("complete", tools[0].metadata!!["voice_tool_status"]!!.jsonPrimitive.content)

        assertEquals("call-1", tools[1].toolCallId)
        assertEquals("Reused prompt", tools[1].input.promptJson())
        assertEquals("pending", tools[1].metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertTrue(tools[1].output.isEmpty())
    }

    @Test
    fun `terminal upsert with reused call id replaces latest Hermes terminal record`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Failed("Tool call canceled by session end"),
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Original prompt",
                    status = VoiceToolRecordStatus.Complete("Late answer"),
                )
            }

        val tools = conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(1, tools.size)
        assertEquals("complete", tools.single().metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("Late answer", tools.single().output.text())
    }

    @Test
    fun `upsert ignores non Hermes tool with same call id`() {
        val persister = VoiceConversationPersister()
        val normalTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "normal_tool",
            input = """{"value":"original"}""",
            output = listOf(UIMessagePart.Text("normal result")),
        )
        val conversation = emptyConversation()
            .updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(normalTool),
                    )
                )
            )

        val updated = persister.upsertHermesTool(
            conversation = conversation,
            callId = "call-1",
            prompt = "Hermes prompt",
            status = VoiceToolRecordStatus.Complete("Hermes answer"),
        )

        val tools = updated.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(2, tools.size)
        assertEquals(normalTool, tools[0])
        assertEquals("ask_hermes", tools[1].toolName)
        assertEquals("Hermes prompt", tools[1].input.promptJson())
        assertEquals("Hermes answer", tools[1].output.text())
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
    fun `failed status records machine readable failure status as tool output metadata`() {
        val persister = VoiceConversationPersister()

        val conversation = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Failed("Hermes failed"),
        )

        val tool = conversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        val output = tool.output.single() as UIMessagePart.Text
        assertEquals("failed", tool.metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("Hermes failed", output.text)
        assertEquals("failed", output.metadata!!["voice_tool_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Hermes tool records preserve job id metadata across statuses`() {
        val persister = VoiceConversationPersister()

        val pending = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Pending,
            jobId = "job-1",
        )
        assertHermesToolJobId(pending, "job-1")

        val complete = persister.upsertHermesTool(
            conversation = pending,
            callId = "call-1",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Complete("Answer"),
            jobId = "job-1",
        )
        assertHermesToolJobId(complete, "job-1")

        val failed = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-2",
            prompt = "Prompt",
            status = VoiceToolRecordStatus.Failed("Hermes job expired"),
            jobId = "job-2",
        )
        assertHermesToolJobId(failed, "job-2")
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

    @Test
    fun `voice artifacts include session source identifiers status and timestamps`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertUserTranscriptTurn(
                    conversation = it,
                    text = "hello",
                    turnId = "user-1",
                    sessionId = "session-1",
                )
            }
            .let {
                persister.upsertAssistantTranscriptTurn(
                    conversation = it,
                    text = "hi",
                    interrupted = true,
                    turnId = "assistant-1",
                    sessionId = "session-1",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "Ask Hermes",
                    status = VoiceToolRecordStatus.Complete("Hermes answer"),
                    sessionId = "session-1",
                )
            }

        val userText = conversation.currentMessages[0].parts.single() as UIMessagePart.Text
        val assistantText = conversation.currentMessages[1].parts.single() as UIMessagePart.Text
        val tool = conversation.currentMessages[2].parts.single() as UIMessagePart.Tool
        val toolOutput = tool.output.single() as UIMessagePart.Text

        assertVoiceMetadata(
            metadata = userText.metadata!!,
            sessionId = "session-1",
            eventId = "user-1",
            status = "complete",
        )
        assertVoiceMetadata(
            metadata = assistantText.metadata!!,
            sessionId = "session-1",
            eventId = "assistant-1",
            status = "interrupted",
        )
        assertVoiceMetadata(
            metadata = tool.metadata!!,
            sessionId = "session-1",
            eventId = "call-1",
            status = "complete",
            callId = "call-1",
        )
        assertVoiceMetadata(
            metadata = toolOutput.metadata!!,
            sessionId = "session-1",
            eventId = "call-1",
            status = "complete",
            callId = "call-1",
        )
    }

    @Test
    fun `Hermes queued and running statuses persist job metadata`() {
        val persister = VoiceConversationPersister()
        val queuedConversation = persister.upsertHermesTool(
            conversation = emptyConversation(),
            callId = "call-1",
            prompt = "queued prompt",
            status = VoiceToolRecordStatus.Queued,
            sessionId = "session-1",
            jobId = "job-1",
        )

        val queuedTool = queuedConversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        val queuedMetadata = queuedTool.metadata!!

        assertEquals("queued", queuedMetadata["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("job-1", queuedMetadata["voice_tool_job_id"]!!.jsonPrimitive.content)
        assertEquals("false", queuedMetadata["voice_tool_result_announced"]!!.jsonPrimitive.content)
        assertTrue(queuedMetadata.containsKey("voice_tool_created_at"))
        assertTrue(queuedMetadata.containsKey("voice_tool_updated_at"))

        val runningConversation = persister.upsertHermesTool(
            conversation = queuedConversation,
            callId = "call-1",
            prompt = "queued prompt",
            status = VoiceToolRecordStatus.Running,
            sessionId = "session-1",
            jobId = "job-1",
        )

        val runningTool = runningConversation.currentMessages.single().parts.single() as UIMessagePart.Tool
        val runningMetadata = runningTool.metadata!!

        assertEquals("running", runningMetadata["voice_tool_status"]!!.jsonPrimitive.content)
        assertEquals("job-1", runningMetadata["voice_tool_job_id"]!!.jsonPrimitive.content)
        assertEquals("false", runningMetadata["voice_tool_result_announced"]!!.jsonPrimitive.content)
        assertTrue(runningMetadata.containsKey("voice_tool_created_at"))
        assertTrue(runningMetadata.containsKey("voice_tool_updated_at"))
    }

    @Test
    fun `mark Hermes result announced updates only matching terminal tool`() {
        val persister = VoiceConversationPersister()
        val conversation = emptyConversation()
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-1",
                    prompt = "prompt",
                    status = VoiceToolRecordStatus.Complete("answer"),
                    jobId = "job-1",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-2",
                    prompt = "other prompt",
                    status = VoiceToolRecordStatus.Complete("other answer"),
                    jobId = "job-2",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-3",
                    prompt = "active prompt",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-3",
                    resultAnnounced = false,
                )
            }
            .let { persister.markHermesToolResultAnnounced(it, callId = "call-1") }
            .let { persister.markHermesToolResultAnnounced(it, callId = "call-3") }

        val tools = conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(listOf("call-1", "call-2", "call-3"), tools.map { it.toolCallId })

        val toolsByCallId = tools.associateBy { it.toolCallId }

        assertEquals("true", toolsByCallId.getValue("call-1").metadata!!["voice_tool_result_announced"]!!.jsonPrimitive.content)
        val call1Output = toolsByCallId.getValue("call-1").output.single() as UIMessagePart.Text
        assertEquals("true", call1Output.metadata!!["voice_tool_result_announced"]!!.jsonPrimitive.content)

        assertEquals("false", toolsByCallId.getValue("call-2").metadata!!["voice_tool_result_announced"]!!.jsonPrimitive.content)
        val call2Output = toolsByCallId.getValue("call-2").output.single() as UIMessagePart.Text
        assertEquals("false", call2Output.metadata!!["voice_tool_result_announced"]!!.jsonPrimitive.content)

        assertEquals("false", toolsByCallId.getValue("call-3").metadata!!["voice_tool_result_announced"]!!.jsonPrimitive.content)
        assertTrue(toolsByCallId.getValue("call-3").output.isEmpty())
    }

    private fun emptyConversation(): Conversation = Conversation.ofId(
        id = Uuid.random(),
        messages = emptyList(),
    )

    private fun assertVoiceMetadata(
        metadata: kotlinx.serialization.json.JsonObject,
        sessionId: String,
        eventId: String,
        status: String,
        callId: String? = null,
    ) {
        assertEquals("voice_agent", metadata["voice_source"]!!.jsonPrimitive.content)
        assertEquals(sessionId, metadata["voice_session_id"]!!.jsonPrimitive.content)
        assertEquals(eventId, metadata["voice_event_id"]!!.jsonPrimitive.content)
        assertEquals(status, metadata["voice_status"]!!.jsonPrimitive.content)
        assertTrue(metadata["voice_created_at"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(metadata["voice_updated_at"]!!.jsonPrimitive.content.isNotBlank())
        if (callId != null) {
            assertEquals(callId, metadata["voice_call_id"]!!.jsonPrimitive.content)
        }
    }

    private fun assertHermesToolJobId(conversation: Conversation, jobId: String) {
        val tool = conversation.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .last()
        assertEquals(jobId, tool.metadata!!["voice_tool_job_id"]!!.jsonPrimitive.content)
        tool.output.filterIsInstance<UIMessagePart.Text>().forEach { output ->
            assertEquals(jobId, output.metadata!!["voice_tool_job_id"]!!.jsonPrimitive.content)
        }
    }

    private fun String.promptJson(): String = JsonInstant
        .parseToJsonElement(this)
        .jsonObject["prompt"]!!
        .jsonPrimitive
        .content

    private fun List<UIMessagePart>.text(): String = filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
