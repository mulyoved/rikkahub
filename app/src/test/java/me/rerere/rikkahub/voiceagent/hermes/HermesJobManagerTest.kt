package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.FakeVoiceConversationStore
import me.rerere.rikkahub.voiceagent.FakeVoiceToolApi
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesJobManagerTest {
    private val persister = VoiceConversationPersister()

    @Test
    fun `submitted job keeps polling after session bridge detaches`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 7L)
        manager.submit(callId = "call-1", prompt = "slow request")
        assertEquals("call-1" to "slow request", toolApi.awaitRequest("call-1"))
        manager.detachBridge(bridge)

        toolApi.complete(response(callId = "call-1", answer = "detached answer"))
        conversationStore.awaitHermesRecord("call-1") {
            it.status == HermesQueueStatus.Complete && it.answer == "detached answer"
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-1" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("detached answer", record.answer)
        assertFalse(record.resultAnnounced)
        assertTrue(bridge.completionFollowUps.isEmpty())
        assertFalse(toolApi.wasCancelled("call-1"))
    }

    @Test
    fun `resume polls persisted active jobs and announces when bridge is attached`() = runTest {
        val initialConversation = Conversation.ofId(Uuid.random()).let {
            persister.upsertHermesTool(
                conversation = it,
                callId = "call-resume",
                prompt = "resume request",
                status = VoiceToolRecordStatus.Running,
                jobId = "job-resume",
            )
        }
        val toolApi = FakeVoiceToolApi().apply {
            scriptPollSucceeded(jobId = "job-resume", callId = "call-resume", answer = "resumed answer")
        }
        val conversationStore = FakeVoiceConversationStore(initialConversation)
        val bridge = RecordingHermesBridge()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.attachBridge(bridge = bridge, sessionId = 9L)
        manager.resumeActiveJobs()
        conversationStore.awaitHermesRecord("call-resume") {
            it.status == HermesQueueStatus.Complete && it.resultAnnounced
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-resume" }
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("resumed answer", record.answer)
        assertTrue(record.resultAnnounced)
        assertEquals(
            listOf(
                CompletionFollowUp(
                    callId = "call-resume",
                    prompt = "resume request",
                    answer = "resumed answer",
                    sessionId = 9L,
                )
            ),
            bridge.completionFollowUps,
        )
    }

    @Test
    fun `explicit cancel cancels remote job and persists canceled`() = runTest {
        val toolApi = FakeVoiceToolApi()
        val conversationStore = FakeVoiceConversationStore()
        val manager = manager(toolApi = toolApi, conversationStore = conversationStore, scope = this)

        manager.submit(callId = "call-cancel", prompt = "cancel request")
        assertEquals("call-cancel" to "cancel request", toolApi.awaitRequest("call-cancel"))
        conversationStore.awaitHermesRecord("call-cancel") {
            it.status == HermesQueueStatus.Queued && it.jobId != null
        }

        manager.cancel("call-cancel")
        toolApi.awaitRemoteCancelled("call-cancel")
        conversationStore.awaitHermesRecord("call-cancel") {
            it.status == HermesQueueStatus.Canceled
        }

        val record = conversationStore.conversation.value.hermesQueueRecords().single { it.callId == "call-cancel" }
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals("Hermes job canceled.", record.error)
    }

    private fun manager(
        toolApi: FakeVoiceToolApi,
        conversationStore: FakeVoiceConversationStore,
        scope: CoroutineScope,
    ) = HermesJobManager(
        toolApi = toolApi,
        conversationStore = conversationStore,
        persister = persister,
        scope = scope,
        dispatcher = Dispatchers.Default,
        pollIntervalMs = 10L,
        pollRetryDelayMs = 1L,
        maxElapsedMs = 1_000L,
    )

    private fun response(callId: String, answer: String) = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile-test",
        profileLabel = "Hermes Test",
        elapsedMs = 42L,
    )

    private suspend fun FakeVoiceConversationStore.awaitHermesRecord(
        callId: String,
        predicate: (HermesQueueRecord) -> Boolean,
    ): HermesQueueRecord = withTimeout(500) {
        while (true) {
            conversation.value.hermesQueueRecords()
                .firstOrNull { it.callId == callId && predicate(it) }
                ?.let { return@withTimeout it }
            delay(10)
        }
        error("unreachable")
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)
}

private class RecordingHermesBridge : HermesSessionBridge {
    val queuedAcknowledgements = mutableListOf<Pair<String, Long>>()
    val completionFollowUps = mutableListOf<CompletionFollowUp>()
    var failCompletionFollowUp = false

    override fun sendQueuedAcknowledgement(callId: String, sessionId: Long): Boolean {
        queuedAcknowledgements += callId to sessionId
        return true
    }

    override fun sendCompletionFollowUp(
        callId: String,
        prompt: String,
        answer: String,
        sessionId: Long,
    ): Boolean {
        if (failCompletionFollowUp) return false
        completionFollowUps += CompletionFollowUp(
            callId = callId,
            prompt = prompt,
            answer = answer,
            sessionId = sessionId,
        )
        return true
    }
}

private data class CompletionFollowUp(
    val callId: String,
    val prompt: String,
    val answer: String,
    val sessionId: Long,
)
