package me.rerere.rikkahub.voiceagent.debug

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAutomationHistorySnapshotTest {
    @Test
    fun `snapshot exposes state and hashes without conversation text or raw identity`() {
        val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val writer = HermesToolRecordWriter(nowIso = { "2026-09-09T00:00:00Z" })
        val withResult = writer.upsertHermesTool(
            conversation = Conversation.ofId(conversationId),
            callId = "private-call-id",
            prompt = "private prompt text",
            status = VoiceToolRecordStatus.Complete("private answer text"),
            sessionId = "private-session-id",
            jobId = "private-job-id",
            announceOnWrite = true,
            originatingUserTurnId = "private-user-turn-id",
            requestHash = "not-a-wire-digest",
            argumentHash = HASH_A,
            resultHash = HASH_B,
        )
        val conversation = VoiceTranscriptPersister().upsertAssistantTranscriptTurn(
            conversation = withResult,
            text = "private transcript text",
            interrupted = false,
            turnId = "private-event-id",
            sessionId = "private-session-id",
            groundedJobId = "private-job-id",
            groundedResultHash = HASH_B,
        )

        val snapshot = VoiceAutomationHistorySnapshot.from(conversationId, conversation)
        val json = snapshot.toJson()

        assertEquals(1, snapshot.recordCount)
        assertEquals(1, snapshot.transcriptCount)
        assertEquals("complete", snapshot.records.single().status)
        assertEquals("announced", snapshot.records.single().announcement)
        assertEquals(HASH_A, snapshot.records.single().argumentHash)
        assertEquals(HASH_B, snapshot.records.single().resultHash)
        assertEquals("assistant", snapshot.transcripts.single().role)
        assertEquals(HASH_B, snapshot.transcripts.single().groundedResultHash)
        assertTrue(snapshot.records.single().requestHash!!.matches(Regex("sha256:[0-9a-f]{64}")))
        listOf(
            conversationId.toString(),
            "private-call-id",
            "private prompt text",
            "private answer text",
            "private-session-id",
            "private-job-id",
            "private-user-turn-id",
            "private transcript text",
            "private-event-id",
            "not-a-wire-digest",
        ).forEach { privateValue -> assertFalse(privateValue, json.contains(privateValue)) }
    }

    @Test
    fun `snapshot bounds worst case payload while retaining total counts`() {
        val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val writer = HermesToolRecordWriter(nowIso = { "2026-09-09T00:00:00Z" })
        val withRecords = (0 until 65).fold(Conversation.ofId(conversationId)) { current, index ->
            writer.upsertHermesTool(
                conversation = current,
                callId = "call-$index",
                prompt = "prompt-$index",
                status = VoiceToolRecordStatus.Complete("answer-$index"),
                sessionId = "session-$index",
                jobId = "job-$index",
                announceOnWrite = true,
                originatingUserTurnId = "turn-$index",
                requestHash = HASH_A,
                argumentHash = HASH_A,
                resultHash = HASH_B,
            )
        }
        val conversation = (0 until 65).fold(withRecords) { current, index ->
            VoiceTranscriptPersister().upsertAssistantTranscriptTurn(
                conversation = current,
                text = "transcript-$index",
                interrupted = false,
                turnId = "event-$index",
                sessionId = "session-$index",
                groundedJobId = "job-$index",
                groundedResultHash = HASH_B,
            )
        }

        val snapshot = VoiceAutomationHistorySnapshot.from(conversationId, conversation)

        assertEquals(65, snapshot.recordCount)
        assertEquals(65, snapshot.transcriptCount)
        assertEquals(48, snapshot.records.size)
        assertEquals(48, snapshot.transcripts.size)
        assertTrue(snapshot.toJson().toByteArray().size < 65_536)
    }

    private companion object {
        const val HASH_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
