package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LiveKitVoicePersistenceBridgeTest {
    @Test
    fun `retained events update ordinary job transcript and announced result history`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)

        bridge.handle(AGENT_IDENTITY, acceptedEventJson())
        bridge.handle(AGENT_IDENTITY, jobStateJson("job_running", "evt_running"))
        bridge.handle(AGENT_IDENTITY, jobStateJson("still_working", "evt_working"))
        assertTrue(store.conversation.value.hermesQueueRecords().single().stillWorkingAnnounced)
        bridge.handle(AGENT_IDENTITY, succeededEventJson("Hermes answer"))
        bridge.handle(
            AGENT_IDENTITY,
            assistantTranscriptJson(
                text = "Spoken answer",
                groundedJobId = JOB_ID,
                groundedResultHash = voiceSha256("Hermes answer"),
            ),
        )
        val response = bridge.handle(AGENT_IDENTITY, deliveryAnnouncedJson())

        val record = store.conversation.value.hermesQueueRecords().single()
        assertEquals("", response)
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("Hermes answer", record.answer)
        assertTrue(record.resultAnnounced)
        assertEquals(2, store.conversation.value.currentMessages.size)
    }

    @Test
    fun `equivalent event JSON is one history write with transport-only responses`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)
        val canonical = acceptedEventJson()
        val reordered = Json.parseToJsonElement(canonical)
            .jsonObject
            .entries
            .reversed()
            .joinToString(separator = ", ", prefix = "{ ", postfix = " }") { (key, value) ->
                Json.encodeToString(key) + " : " + value
            }

        val responses = listOf(canonical, reordered).map { bridge.handle(AGENT_IDENTITY, it) }

        assertEquals(listOf("", ""), responses)
        assertEquals(1, store.conversation.value.hermesQueueRecords().size)
        assertEquals(1, store.updateCalls)
    }

    @Test
    fun `same event ID with changed meaning is a conflict`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)
        bridge.handle(AGENT_IDENTITY, acceptedEventJson())

        val failure = runCatching {
            bridge.handle(AGENT_IDENTITY, acceptedEventJson(prompt = "changed question"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("private question", store.conversation.value.hermesQueueRecords().single().prompt)
    }

    @Test
    fun `history write failure is returned by the handler without damaging the conversation`() = runTest {
        val conversation = Conversation.ofId(Uuid.random()).copy(title = "existing title")
        val store = RecordingVoiceConversationStore(conversation, failNextUpdate = true)
        val bridge = bridge(store)

        val failure = runCatching {
            bridge.handle(AGENT_IDENTITY, acceptedEventJson())
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("existing title", store.conversation.value.title)
        assertTrue(store.conversation.value.hermesQueueRecords().isEmpty())

        assertEquals("", bridge.handle(AGENT_IDENTITY, acceptedEventJson()))
        assertEquals("existing title", store.conversation.value.title)
        assertEquals(1, store.conversation.value.hermesQueueRecords().size)
    }

    @Test
    fun `caller session and ownership mismatches have no side effects`() = runTest {
        val payloads = listOf(
            "wrong-agent" to acceptedEventJson(),
            AGENT_IDENTITY to acceptedEventJson().replace(VOICE_SESSION_ID, "lvs_other"),
            AGENT_IDENTITY to acceptedEventJson(ownerHash = hash('9')),
        )

        payloads.forEach { (caller, payload) ->
            val store = RecordingVoiceConversationStore()
            val failure = runCatching { bridge(store).handle(caller, payload) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(store.conversation.value.hermesQueueRecords().isEmpty())
        }
    }

    @Test
    fun `history owner closes its conversation once and rejects later writes`() = runTest {
        val store = RecordingVoiceConversationStore()
        val bridge = bridge(store)

        bridge.close()
        bridge.close()
        val failure = runCatching {
            bridge.handle(AGENT_IDENTITY, acceptedEventJson())
        }.exceptionOrNull()

        assertEquals(1, store.closeCalls)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `orphan job states do not create blank Hermes history`() = runTest {
        val orphanEvents = listOf(
            jobStateJson("job_running", "evt_running"),
            jobStateJson("still_working", "evt_working"),
            succeededEventJson("Hermes answer"),
            jobStateJson("job_failed", "evt_failed", ",\"failureReason\":\"failed\""),
            jobStateJson("job_expired", "evt_expired", ",\"failureReason\":\"expired\""),
            jobStateJson("job_canceled", "evt_canceled", ",\"failureReason\":\"canceled\""),
        )

        orphanEvents.forEach { payload ->
            val store = RecordingVoiceConversationStore()

            val failure = runCatching { bridge(store).handle(AGENT_IDENTITY, payload) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(store.conversation.value.hermesQueueRecords().isEmpty())
        }
    }

    @Test
    fun `current session events cannot reuse a Hermes record owned by another session`() = runTest {
        val initial = conversationWithHermesRecord(
            voiceSessionId = "lvs_other",
            status = VoiceToolRecordStatus.Queued,
        )

        listOf(
            acceptedEventJson(),
            jobStateJson("job_running", "evt_running"),
            jobStateJson("still_working", "evt_working"),
            succeededEventJson("Hermes answer"),
        ).forEach { payload ->
            val store = RecordingVoiceConversationStore(initial)

            val failure = runCatching { bridge(store).handle(AGENT_IDENTITY, payload) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            val record = store.conversation.value.hermesQueueRecords().single()
            assertEquals("lvs_other", record.voiceSessionId)
            assertEquals(HermesQueueStatus.Queued, record.status)
        }
    }

    @Test
    fun `grounded transcript cannot use a completed result from another session`() = runTest {
        val resultHash = voiceSha256("Hermes answer")
        val initial = conversationWithHermesRecord(
            voiceSessionId = "lvs_other",
            status = VoiceToolRecordStatus.Complete("Hermes answer"),
            resultHash = resultHash,
        )
        val store = RecordingVoiceConversationStore(initial)

        val failure = runCatching {
            bridge(store).handle(
                AGENT_IDENTITY,
                assistantTranscriptJson(
                    text = "Spoken answer",
                    groundedJobId = JOB_ID,
                    groundedResultHash = resultHash,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(initial.currentMessages, store.conversation.value.currentMessages)
    }
}

private fun conversationWithHermesRecord(
    voiceSessionId: String,
    status: VoiceToolRecordStatus,
    resultHash: String? = null,
): Conversation = HermesToolRecordWriter(nowIso = { "2026-09-08T12:00:09Z" }).upsertHermesTool(
    conversation = Conversation.ofId(Uuid.random()),
    callId = TOOL_CALL_ID,
    prompt = "private question",
    status = status,
    sessionId = voiceSessionId,
    jobId = JOB_ID,
    originatingUserTurnId = "turn_1",
    requestHash = REQUEST_HASH,
    argumentHash = ARGUMENT_HASH,
    resultHash = resultHash,
    producer = HERMES_PRODUCER,
)

private fun bridge(store: VoiceConversationStore): LiveKitVoiceHistoryBridge {
    val transcriptPersister = VoiceTranscriptPersister()
    return LiveKitVoiceHistoryBridge(
        voiceSessionId = VOICE_SESSION_ID,
        agentIdentity = AGENT_IDENTITY,
        expectedCorrelation = CORRELATION,
        queueStore = HermesQueueStore(
            conversationStore = store,
            writer = HermesToolRecordWriter(nowIso = { "2026-09-08T12:00:09Z" }),
            transcriptPersister = transcriptPersister,
            persistenceSessionId = { VOICE_SESSION_ID },
        ),
        transcriptPersister = transcriptPersister,
        conversationStore = store,
    )
}

private class RecordingVoiceConversationStore(
    initial: Conversation = Conversation.ofId(Uuid.random()),
    private var failNextUpdate: Boolean = false,
) : VoiceConversationStore {
    private val state = MutableStateFlow(initial)
    override val conversation: StateFlow<Conversation> = state
    var updateCalls = 0
        private set
    var closeCalls = 0
        private set

    override suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T {
        if (failNextUpdate) {
            failNextUpdate = false
            error("history write failed")
        }
        val (updated, result) = transform(state.value)
        commit(result)
        state.value = updated
        updateCalls += 1
        return result
    }

    override fun close() {
        closeCalls += 1
    }
}

private fun acceptedEventJson(
    prompt: String = "private question",
    ownerHash: String = OWNER_HASH,
): String = canonicalJson(
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-09-08T12:00:00Z","userTurnId":"turn_1","requestHash":"$REQUEST_HASH","toolCallId":"$TOOL_CALL_ID","argumentHash":"$ARGUMENT_HASH","jobId":"$JOB_ID","ownerHash":"$ownerHash","conversationHash":"$CONVERSATION_HASH","voiceSessionHash":"$VOICE_SESSION_HASH","roomHash":"$ROOM_HASH","traceHash":"$TRACE_HASH","prompt":"$prompt"}""",
)

private fun jobStateJson(kind: String, eventId: String, suffix: String = ""): String = canonicalJson(
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"$eventId","kind":"$kind","observedAt":"2026-09-08T12:00:01Z","userTurnId":"turn_1","requestHash":"$REQUEST_HASH","toolCallId":"$TOOL_CALL_ID","argumentHash":"$ARGUMENT_HASH","jobId":"$JOB_ID","ownerHash":"$OWNER_HASH","conversationHash":"$CONVERSATION_HASH","voiceSessionHash":"$VOICE_SESSION_HASH","roomHash":"$ROOM_HASH","traceHash":"$TRACE_HASH"$suffix}""",
)

private fun succeededEventJson(answer: String): String = jobStateJson(
    kind = "job_succeeded",
    eventId = "evt_succeeded",
    suffix = ""","resultHash":"${voiceSha256(answer)}","answer":"$answer"""",
)

private fun assistantTranscriptJson(
    text: String,
    groundedJobId: String,
    groundedResultHash: String,
): String = canonicalJson(
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_transcript","kind":"transcript","observedAt":"2026-09-08T12:00:02Z","turnId":"assistant_1","role":"assistant","text":"$text","interrupted":false,"groundedJobId":"$groundedJobId","groundedResultHash":"$groundedResultHash"}""",
)

private fun deliveryAnnouncedJson(): String = canonicalJson(
    """{"version":1,"voiceSessionId":"$VOICE_SESSION_ID","eventId":"evt_announced","kind":"delivery_announced","observedAt":"2026-09-08T12:00:03Z","toolCallId":"$TOOL_CALL_ID","jobId":"$JOB_ID","assistantTurnId":"assistant_1"}""",
)

private fun canonicalJson(payload: String): String =
    CanonicalVoiceExperienceJson.encodeObject(Json.parseToJsonElement(payload).jsonObject)

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)

private const val VOICE_SESSION_ID = "lvs_1"
private const val AGENT_IDENTITY = "agent_1"
private const val TOOL_CALL_ID = "call_1"
private const val JOB_ID = "job_1"
private const val REQUEST_HASH =
    "sha256:5555555555555555555555555555555555555555555555555555555555555555"
private const val ARGUMENT_HASH =
    "sha256:6666666666666666666666666666666666666666666666666666666666666666"
private const val OWNER_HASH =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val CONVERSATION_HASH =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private val VOICE_SESSION_HASH = voiceSha256(VOICE_SESSION_ID)
private const val ROOM_HASH =
    "sha256:3333333333333333333333333333333333333333333333333333333333333333"
private const val TRACE_HASH =
    "sha256:4444444444444444444444444444444444444444444444444444444444444444"
private val CORRELATION = LiveKitJobCorrelation(
    ownerHash = OWNER_HASH,
    conversationHash = CONVERSATION_HASH,
    voiceSessionHash = VOICE_SESSION_HASH,
    roomHash = ROOM_HASH,
    traceHash = TRACE_HASH,
)
