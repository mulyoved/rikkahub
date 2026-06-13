package me.rerere.rikkahub.voiceagent.hermes

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesQueueRecordTest {
    private val persister = VoiceConversationPersister()

    @Test
    fun `reads queued running and complete Hermes records from conversation`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-queued",
                    prompt = "first request",
                    status = VoiceToolRecordStatus.Queued,
                    jobId = "job-queued",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-running",
                    prompt = "second request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-running",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "call-complete",
                    prompt = "third request",
                    status = VoiceToolRecordStatus.Complete("done"),
                    jobId = "job-complete",
                    resultAnnounced = false,
                )
            }

        val records = conversation.hermesQueueRecords()

        assertEquals(listOf("call-queued", "call-running", "call-complete"), records.map { it.callId })
        assertEquals(HermesQueueStatus.Queued, records[0].status)
        assertEquals(HermesQueueStatus.Running, records[1].status)
        assertEquals(HermesQueueStatus.Complete, records[2].status)
        assertEquals("first request", records[0].prompt)
        assertEquals("done", records[2].answer)
        assertFalse(records[2].resultAnnounced)
    }

    @Test
    fun `queue snapshot separates active jobs and unannounced terminal results`() {
        val conversation = Conversation.ofId(Uuid.random())
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "active",
                    prompt = "active request",
                    status = VoiceToolRecordStatus.Running,
                    jobId = "job-active",
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "new-result",
                    prompt = "new terminal request",
                    status = VoiceToolRecordStatus.Complete("new answer"),
                    jobId = "job-new",
                    resultAnnounced = false,
                )
            }
            .let {
                persister.upsertHermesTool(
                    conversation = it,
                    callId = "old-result",
                    prompt = "old terminal request",
                    status = VoiceToolRecordStatus.Complete("old answer"),
                    jobId = "job-old",
                    resultAnnounced = true,
                )
            }

        val snapshot = HermesQueueSnapshot.from(conversation)

        assertEquals(listOf("active"), snapshot.active.map { it.callId })
        assertEquals(listOf("new-result"), snapshot.unannouncedTerminal.map { it.callId })
        assertEquals(listOf("old-result"), snapshot.announcedTerminal.map { it.callId })
        assertTrue(snapshot.toPromptSummary().contains("active request"))
        assertTrue(snapshot.toPromptSummary().contains("new answer"))
        assertFalse(snapshot.toPromptSummary().contains("old answer"))
    }
}
