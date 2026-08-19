package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.FakeVoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceAgentToolNames
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueueStoreTest {
    private val writer = HermesToolRecordWriter()
    private val transcriptPersister = VoiceTranscriptPersister()

    @Test
    fun `visible message uses the finished template for complete records and marks message written`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-1",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val appended = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertTrue(appended)
        val texts = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes finished: the prompt\n\nthe answer"), texts)
        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-1" }
        assertTrue(record.messageWritten)

        val secondAppend = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertFalse(secondAppend)
        val textsAfterSecondCall = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes finished: the prompt\n\nthe answer"), textsAfterSecondCall)
    }

    @Test
    fun `visible message uses the could-not-finish template with reason for failed records`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Failed("boom"),
                jobId = "job-1",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val appended = store.appendVisibleResultMessageIfNeeded(callId = "call-1", jobId = "job-1")

        assertTrue(appended)
        val texts = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .map { it.text }
        assertEquals(listOf("Hermes could not finish: the prompt (boom)"), texts)
    }

    @Test
    fun `latestRecord matches identity including null jobId`() = runTest {
        // The writer merges a null-jobId record into a later jobId-bearing upsert
        // (jobId adoption), so coexisting records for the same call are hand-built:
        // (jobId=null, Pending) alongside (jobId=job-1, Running).
        val initialConversation = conversationOf(
            hermesToolPart(callId = "call-1", jobId = null, status = HermesQueueStatus.Pending),
            hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running),
        )
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val pendingRecord = store.latestRecord(callId = "call-1", jobId = null)
        assertNotNull(pendingRecord)
        assertEquals(null, pendingRecord!!.jobId)
        assertEquals(HermesQueueStatus.Pending, pendingRecord.status)
        assertEquals("job-1", store.latestRecord(callId = "call-1", jobId = "job-1")?.jobId)
        assertNull(store.latestRecord(callId = "call-1", jobId = "job-2"))
        assertNull(store.latestRecord(callId = "missing", jobId = null))
    }

    @Test
    fun `activeRecords keeps latest per identity and drops terminal`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-1",
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-2",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-2",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val active = store.activeRecords()
        assertEquals(listOf("call-1"), active.map { it.callId })
    }

    @Test
    fun `unannouncedTerminalRecords drops announced and non-terminal`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-1",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-1",
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-2",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Complete("the answer"),
                jobId = "job-2",
                announceOnWrite = true,
            )
        }.let {
            writer.upsertHermesTool(
                conversation = it,
                callId = "call-3",
                prompt = "the prompt",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-3",
            )
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        val unannounced = store.unannouncedTerminalRecords()
        assertEquals(listOf("call-1"), unannounced.map { it.callId })
    }

    @Test
    fun `latestCancelCandidate honors requireUnsubmitted`() = runTest {
        // Coexisting records for call-1 (hand-built; the writer would merge them):
        // an unsubmitted (jobId=null, Pending) record and a later (jobId=job-1, Running) one.
        val initialConversation = conversationOf(
            hermesToolPart(callId = "call-1", jobId = null, status = HermesQueueStatus.Pending),
            hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running),
        )
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val store = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = transcriptPersister,
        )

        assertEquals(
            "job-1",
            store.latestCancelCandidate(callId = "call-1", requireUnsubmitted = false)?.jobId,
        )
        val unsubmitted = store.latestCancelCandidate(callId = "call-1", requireUnsubmitted = true)
        assertNotNull(unsubmitted)
        assertEquals(null, unsubmitted!!.jobId)
        assertEquals(HermesQueueStatus.Pending, unsubmitted.status)
    }

    // --- Monotonic recovery transitions ---

    @Test
    fun `persistValidatedRecoverySnapshot handles Queued to Queued as Equivalent`() = runTest {
        val conversationStore = FakeVoiceConversationStore(
            conversationOf(
                hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Queued)
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Queued,
                answer = null,
                error = null,
                resultHash = null,
            )
        )

        assertEquals(HermesQueuePersistenceResult.Equivalent, result)
        assertEquals(HermesQueueStatus.Queued, store.latestRecord("call-1", "job-1")?.status)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles Queued to Running as Mutated and preserves announcement`() = runTest {
        val initialRecord = HermesQueueRecord(
            callId = "call-1",
            jobId = "job-1",
            prompt = "the prompt",
            status = HermesQueueStatus.Queued,
            answer = null,
            error = null,
            announcement = HermesAnnouncementState.StillWorkingAnnounced,
            createdAt = "2026-07-08T00:00:00Z",
            updatedAt = "2026-07-08T00:00:01Z",
        )
        val conversationStore = FakeVoiceConversationStore(
            Conversation.ofId(Uuid.random()).updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "call-1",
                                toolName = VoiceAgentToolNames.ASK_HERMES,
                                input = """{"prompt":"the prompt"}""",
                                output = emptyList(),
                                metadata = initialRecord.toMetadata("2026-07-08T00:00:01Z"),
                            )
                        ),
                    )
                )
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Running,
                answer = null,
                error = null,
                resultHash = null,
            )
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)
        val record = store.latestRecord("call-1", "job-1")
        assertEquals(HermesQueueStatus.Running, record?.status)
        assertEquals(HermesAnnouncementState.StillWorkingAnnounced, record?.announcement)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles Running to Queued regression as Stale`() = runTest {
        val conversationStore = FakeVoiceConversationStore(
            conversationOf(
                hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running)
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Queued,
                answer = null,
                error = null,
                resultHash = null,
            )
        )

        assertEquals(HermesQueuePersistenceResult.Stale, result)
        assertEquals(HermesQueueStatus.Running, store.latestRecord("call-1", "job-1")?.status)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles active to first terminal Complete as Mutated`() = runTest {
        val conversationStore = FakeVoiceConversationStore(
            conversationOf(
                hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running)
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Complete,
                answer = "recovered answer",
                error = null,
                resultHash = "hash-123",
            )
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)
        val record = store.latestRecord("call-1", "job-1")
        assertEquals(HermesQueueStatus.Complete, record?.status)
        assertEquals("recovered answer", record?.answer)
        assertEquals("hash-123", record?.resultHash)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles active to first terminal Failed with safeMessage as Mutated`() = runTest {
        val conversationStore = FakeVoiceConversationStore(
            conversationOf(
                hermesToolPart(callId = "call-1", jobId = "job-1", status = HermesQueueStatus.Running)
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Failed,
                answer = null,
                error = "Safe error explanation for user",
                resultHash = null,
            )
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)
        val record = store.latestRecord("call-1", "job-1")
        assertEquals(HermesQueueStatus.Failed, record?.status)
        assertEquals("Safe error explanation for user", record?.error)
        assertNull(record?.answer)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles equivalent terminal repeats as Equivalent`() = runTest {
        for (terminalStatus in listOf(
            HermesQueueStatus.Complete to ("ans" to "hash-1"),
            HermesQueueStatus.Failed to ("error message" to null),
            HermesQueueStatus.Expired to ("expired message" to null),
            HermesQueueStatus.Canceled to ("canceled message" to null),
        )) {
            val (status, payload) = terminalStatus
            val (answerOrError, resultHash) = payload
            val answer = if (status == HermesQueueStatus.Complete) answerOrError else null
            val error = if (status != HermesQueueStatus.Complete) answerOrError else null

            val initialRecord = HermesQueueRecord(
                callId = "call-1",
                jobId = "job-1",
                prompt = "the prompt",
                status = status,
                answer = answer,
                error = error,
                announcement = HermesAnnouncementState.NotAnnounced,
                createdAt = "2026-07-08T00:00:00Z",
                updatedAt = "2026-07-08T00:00:01Z",
                resultHash = resultHash,
            )
            val conversationStore = FakeVoiceConversationStore(
                Conversation.ofId(Uuid.random()).updateCurrentMessages(
                    listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Tool(
                                    toolCallId = "call-1",
                                    toolName = VoiceAgentToolNames.ASK_HERMES,
                                    input = """{"prompt":"the prompt"}""",
                                    output = listOfNotNull(
                                        (answer ?: error)?.let { UIMessagePart.Text(it, metadata = null) }
                                    ),
                                    metadata = initialRecord.toMetadata("2026-07-08T00:00:01Z"),
                                )
                            ),
                        )
                    )
                )
            )
            val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

            val result = store.persistValidatedRecoverySnapshot(
                ValidatedHermesRecoverySnapshot(
                    jobId = "job-1",
                    callId = "call-1",
                    status = status,
                    answer = answer,
                    error = error,
                    resultHash = resultHash,
                )
            )

            assertEquals("status=$status", HermesQueuePersistenceResult.Equivalent, result)
        }
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles terminal to active regression as Stale`() = runTest {
        for (terminalStatus in listOf(HermesQueueStatus.Complete, HermesQueueStatus.Failed, HermesQueueStatus.Expired, HermesQueueStatus.Canceled)) {
            for (activeStatus in listOf(HermesQueueStatus.Pending, HermesQueueStatus.Queued, HermesQueueStatus.Running)) {
                val initialRecord = HermesQueueRecord(
                    callId = "call-1",
                    jobId = "job-1",
                    prompt = "the prompt",
                    status = terminalStatus,
                    answer = if (terminalStatus == HermesQueueStatus.Complete) "done" else null,
                    error = if (terminalStatus != HermesQueueStatus.Complete) "fail" else null,
                    announcement = HermesAnnouncementState.NotAnnounced,
                    createdAt = "2026-07-08T00:00:00Z",
                    updatedAt = "2026-07-08T00:00:01Z",
                )
                val conversationStore = FakeVoiceConversationStore(
                    Conversation.ofId(Uuid.random()).updateCurrentMessages(
                        listOf(
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = "call-1",
                                        toolName = VoiceAgentToolNames.ASK_HERMES,
                                        input = """{"prompt":"the prompt"}""",
                                        output = listOfNotNull(
                                            (initialRecord.answer ?: initialRecord.error)?.let { UIMessagePart.Text(it, metadata = null) }
                                        ),
                                        metadata = initialRecord.toMetadata("2026-07-08T00:00:01Z"),
                                    )
                                ),
                            )
                        )
                    )
                )
                val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

                val result = store.persistValidatedRecoverySnapshot(
                    ValidatedHermesRecoverySnapshot(
                        jobId = "job-1",
                        callId = "call-1",
                        status = activeStatus,
                        answer = null,
                        error = null,
                        resultHash = null,
                    )
                )

                assertEquals("from=$terminalStatus to=$activeStatus", HermesQueuePersistenceResult.Stale, result)
            }
        }
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles same status changed answer, error, or resultHash as Conflict`() = runTest {
        val completeRecord = HermesQueueRecord(
            callId = "call-1",
            jobId = "job-1",
            prompt = "the prompt",
            status = HermesQueueStatus.Complete,
            answer = "original answer",
            error = null,
            announcement = HermesAnnouncementState.NotAnnounced,
            createdAt = "2026-07-08T00:00:00Z",
            updatedAt = "2026-07-08T00:00:01Z",
            resultHash = "hash-1",
        )
        val conversationStore = FakeVoiceConversationStore(
            Conversation.ofId(Uuid.random()).updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "call-1",
                                toolName = VoiceAgentToolNames.ASK_HERMES,
                                input = """{"prompt":"the prompt"}""",
                                output = listOf(UIMessagePart.Text("original answer", metadata = null)),
                                metadata = completeRecord.toMetadata("2026-07-08T00:00:01Z"),
                            )
                        ),
                    )
                )
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        // Changed answer
        val diffAnswer = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Complete,
                answer = "different answer",
                error = null,
                resultHash = "hash-1",
            )
        )
        assertEquals(HermesQueuePersistenceResult.Conflict, diffAnswer)

        // Changed resultHash
        val diffHash = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Complete,
                answer = "original answer",
                error = null,
                resultHash = "hash-2",
            )
        )
        assertEquals(HermesQueuePersistenceResult.Conflict, diffHash)

        // Failed record with changed error
        val failedStore = FakeVoiceConversationStore(
            Conversation.ofId(Uuid.random()).updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "call-2",
                                toolName = VoiceAgentToolNames.ASK_HERMES,
                                input = """{"prompt":"the prompt"}""",
                                output = listOf(UIMessagePart.Text("original failure", metadata = null)),
                                metadata = HermesQueueRecord(
                                    callId = "call-2",
                                    jobId = "job-2",
                                    prompt = "the prompt",
                                    status = HermesQueueStatus.Failed,
                                    answer = null,
                                    error = "original failure",
                                    announcement = HermesAnnouncementState.NotAnnounced,
                                    createdAt = "2026-07-08T00:00:00Z",
                                    updatedAt = "2026-07-08T00:00:01Z",
                                ).toMetadata("2026-07-08T00:00:01Z"),
                            )
                        ),
                    )
                )
            )
        )
        val store2 = HermesQueueStore(failedStore, writer, transcriptPersister)
        val diffError = store2.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-2",
                callId = "call-2",
                status = HermesQueueStatus.Failed,
                answer = null,
                error = "different failure",
                resultHash = null,
            )
        )
        assertEquals(HermesQueuePersistenceResult.Conflict, diffError)
    }

    @Test
    fun `persistValidatedRecoverySnapshot handles terminal to different terminal as Conflict`() = runTest {
        val completeRecord = HermesQueueRecord(
            callId = "call-1",
            jobId = "job-1",
            prompt = "the prompt",
            status = HermesQueueStatus.Complete,
            answer = "original answer",
            error = null,
            announcement = HermesAnnouncementState.NotAnnounced,
            createdAt = "2026-07-08T00:00:00Z",
            updatedAt = "2026-07-08T00:00:01Z",
            resultHash = "hash-1",
        )
        val conversationStore = FakeVoiceConversationStore(
            Conversation.ofId(Uuid.random()).updateCurrentMessages(
                listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "call-1",
                                toolName = VoiceAgentToolNames.ASK_HERMES,
                                input = """{"prompt":"the prompt"}""",
                                output = listOf(UIMessagePart.Text("original answer", metadata = null)),
                                metadata = completeRecord.toMetadata("2026-07-08T00:00:01Z"),
                            )
                        ),
                    )
                )
            )
        )
        val store = HermesQueueStore(conversationStore, writer, transcriptPersister)

        val result = store.persistValidatedRecoverySnapshot(
            ValidatedHermesRecoverySnapshot(
                jobId = "job-1",
                callId = "call-1",
                status = HermesQueueStatus.Failed,
                answer = null,
                error = "failed now",
                resultHash = null,
            )
        )
        assertEquals(HermesQueuePersistenceResult.Conflict, result)
    }

    private fun conversationOf(vararg tools: UIMessagePart.Tool): Conversation =
        Conversation.ofId(Uuid.random()).updateCurrentMessages(
            listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList()))
        )

    /** Hand-builds an ask_hermes tool part so records the writer would merge can coexist. */
    private fun hermesToolPart(
        callId: String,
        jobId: String?,
        status: HermesQueueStatus,
    ): UIMessagePart.Tool {
        val record = HermesQueueRecord(
            callId = callId,
            jobId = jobId,
            prompt = "the prompt",
            status = status,
            answer = null,
            error = null,
            announcement = HermesAnnouncementState.NotAnnounced,
            createdAt = "2026-07-08T00:00:00Z",
            updatedAt = "2026-07-08T00:00:01Z",
        )
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = VoiceAgentToolNames.ASK_HERMES,
            input = """{"prompt":"the prompt"}""",
            output = emptyList(),
            metadata = record.toMetadata(nowIso = "2026-07-08T00:00:01Z"),
        )
    }
}
