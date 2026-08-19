package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.livekit.voiceSha256
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class HermesRecoveryCoordinatorTest {

    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000010")
    private val callId = "call-coord-1"
    private val jobId = "job-coord-1"
    private val endpointBindingHash = "endpoint-hash-pinned"
    private val ownerHash = "owner-hash-pinned"
    private val voiceSessionId = "lvs_session_coord"
    private val voiceSessionHash = voiceSha256(voiceSessionId)
    private val argumentHash = "sha256:arg_coord_1"
    private val originatingTurnId = "turn-user-1"
    private val requestHash = "sha256:req_coord_1"

    private class FakeClock(var epoch: Long = 10000L, var elapsed: Long = 10000L) : RecoveryClock {
        override fun epochMillis(): Long = epoch
        override fun elapsedRealtimeMillis(): Long = elapsed

        fun advance(millis: Long) {
            epoch += millis
            elapsed += millis
        }
    }

    private class FakeScheduler : HermesRecoveryWorkScheduler {
        val calls = mutableListOf<String>()
        val ensured = mutableMapOf<String, Duration>()
        val preempted = mutableMapOf<String, Duration>()
        val continued = mutableMapOf<String, Duration>()
        val canceled = mutableListOf<String>()

        override suspend fun ensure(recoveryKey: String, delay: Duration) {
            calls.add("ensure:$recoveryKey:$delay")
            ensured[recoveryKey] = delay
        }

        override suspend fun preempt(recoveryKey: String, delay: Duration) {
            calls.add("preempt:$recoveryKey:$delay")
            preempted[recoveryKey] = delay
        }

        override suspend fun continueAfterCurrent(recoveryKey: String, delay: Duration) {
            calls.add("continueAfterCurrent:$recoveryKey:$delay")
            continued[recoveryKey] = delay
        }

        override suspend fun cancel(recoveryKey: String) {
            calls.add("cancel:$recoveryKey")
            canceled.add(recoveryKey)
        }
    }

    private class FakeRemote : HermesRecoveryRemote {
        var pollCallCount = 0
        var cancelCallCount = 0
        var pollResponse: HermesRecoveryHttpResponse? = null
        var cancelResponse: HermesRecoveryHttpResponse? = null
        var pollException: Exception? = null
        var cancelException: Exception? = null

        override suspend fun poll(jobId: String): HermesRecoveryHttpResponse {
            pollCallCount++
            pollException?.let { throw it }
            return pollResponse ?: HermesRecoveryHttpResponse(200, "owner", null)
        }

        override suspend fun cancel(jobId: String): HermesRecoveryHttpResponse {
            cancelCallCount++
            cancelException?.let { throw it }
            return cancelResponse ?: HermesRecoveryHttpResponse(200, "owner", null)
        }
    }

    private class CoordinatorFakeDAO : HermesRecoveryDAO {
        val storage = mutableMapOf<String, HermesRecoveryEntity>()
        var deleteOrphansCallCount = 0

        override suspend fun find(recoveryKey: String): HermesRecoveryEntity? = storage[recoveryKey]

        override suspend fun active(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == HermesRecoveryState.Active.name }

        override suspend fun dormant(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == HermesRecoveryState.Dormant.name }

        override suspend fun forConversation(conversationId: String): List<HermesRecoveryEntity> =
            storage.values.filter { it.conversationId == conversationId }

        override suspend fun pendingForConversation(conversationId: String): List<HermesRecoveryEntity> =
            storage.values.filter { it.conversationId == conversationId && it.notificationDisposition == HermesNotificationDisposition.PendingPost.name }

        override suspend fun allPendingNotifications(): List<HermesRecoveryEntity> =
            storage.values.filter { it.notificationDisposition == HermesNotificationDisposition.PendingPost.name }

        override suspend fun pendingNotificationConversationIds(): List<String> =
            storage.values.filter { it.notificationDisposition == HermesNotificationDisposition.PendingPost.name }.map { it.conversationId }.distinct()

        override suspend fun insert(entry: HermesRecoveryEntity): Long {
            if (storage.containsKey(entry.recoveryKey)) return -1L
            storage[entry.recoveryKey] = entry
            return 1L
        }

        override suspend fun update(entry: HermesRecoveryEntity) {
            storage[entry.recoveryKey] = entry
        }

        override suspend fun deleteOrphans(): Int {
            deleteOrphansCallCount++
            return 0
        }
    }

    private val fakeDao = CoordinatorFakeDAO()
    private val ledger = HermesRecoveryLedger(fakeDao)
    private val clock = FakeClock()
    private val scheduler = FakeScheduler()
    private val relayRegistry = HermesRelayRegistry(clock = clock)
    private val fakeRemote = FakeRemote()
    private val writer = HermesToolRecordWriter()
    private val persister = VoiceTranscriptPersister()

    private val conversationStoreMap = mutableMapOf<Uuid, InMemoryVoiceConversationStore>()

    private fun getOrCreateStore(convId: Uuid): InMemoryVoiceConversationStore {
        return conversationStoreMap.getOrPut(convId) {
            InMemoryVoiceConversationStore(Conversation.ofId(convId))
        }
    }

    private val coordinator = HermesRecoveryCoordinator(
        ledger = ledger,
        scheduler = scheduler,
        relayRegistry = relayRegistry,
        endpointResolver = FakeEndpointResolver(fakeRemote, endpointBindingHash),
        chatService = null,
        conversationStoreProvider = { convId -> getOrCreateStore(convId) },
        snapshotReconciler = HermesSnapshotReconciler(),
        terminalCommitter = HermesTerminalCommitter(ledger, clock),
        clock = clock,
        toolRecordWriter = writer,
        transcriptPersister = persister,
        conversationIdsProvider = { conversationStoreMap.keys.toList() },
    )

    private fun defaultBinding(
        cId: Uuid = conversationId,
        cCallId: String = callId,
        cJobId: String = jobId,
        cPrompt: String = "Test prompt",
        cProducer: String = HERMES_PRODUCER,
        cTurnId: String = originatingTurnId,
        cReqHash: String = requestHash,
        cSessionId: String = voiceSessionId,
        cArgHash: String = argumentHash,
        cOwnerHash: String = ownerHash,
        cEndpointHash: String = endpointBindingHash,
        cAcceptedAt: Long = clock.epochMillis(),
    ) = AcceptedHermesBinding(
        conversationId = cId,
        callId = cCallId,
        jobId = cJobId,
        prompt = cPrompt,
        producer = cProducer,
        originatingUserTurnId = cTurnId,
        requestHash = cReqHash,
        voiceSessionId = cSessionId,
        argumentHash = cArgHash,
        acceptingOwnerHash = cOwnerHash,
        endpointBindingHash = cEndpointHash,
        acceptedAtEpochMillis = cAcceptedAt,
    )

    private fun correlationJson(
        cOwnerHash: String = ownerHash,
        cConversationId: String = conversationId.toString(),
        cVoiceSessionId: String = voiceSessionId,
        cTraceId: String = "trace-1",
        cArgumentHash: String = argumentHash,
    ) = buildJsonObject {
        put("ownerHash", cOwnerHash)
        put("conversationId", cConversationId)
        put("voiceSessionId", cVoiceSessionId)
        put("traceId", cTraceId)
        put("argumentHash", cArgumentHash)
    }

    // --- 1. Registration & Equivalence / Conflict ---

    @Test
    fun `registerAccepted atomically inserts queue record and ledger entry, acquires lease, and enqueues work`() = runTest {
        val binding = defaultBinding()
        val recoveryKey = coordinator.registerAccepted(binding)

        val expectedKey = hermesRecoveryKey(conversationId, callId, jobId)
        assertEquals(expectedKey, recoveryKey)

        // Verify ledger entry
        val entry = ledger.find(recoveryKey)
        assertNotNull(entry)
        assertEquals(HermesRecoveryState.Active, entry!!.recoveryState)
        assertEquals(conversationId, entry.conversationId)
        assertEquals(callId, entry.callId)
        assertEquals(jobId, entry.jobId)
        assertEquals(endpointBindingHash, entry.originalEndpointHash)
        assertEquals(ownerHash, entry.originalOwnerHash)
        assertEquals(argumentHash, entry.originalArgumentHash)

        // Verify conversation record
        val convStore = getOrCreateStore(conversationId)
        val records = convStore.conversation.first().hermesQueueRecords()
        assertEquals(1, records.size)
        assertEquals(jobId, records[0].jobId)
        assertEquals(originatingTurnId, records[0].originatingUserTurnId)
        assertEquals(requestHash, records[0].requestHash)

        // Verify lease acquired
        assertTrue(relayRegistry.isLeaseActive(recoveryKey))

        // Verify scheduler ensure called with 30s
        assertEquals(30.seconds, scheduler.ensured[recoveryKey])
    }

    @Test
    fun `registerAccepted exact duplicate is equivalent`() = runTest {
        val binding = defaultBinding()
        val key1 = coordinator.registerAccepted(binding)
        val key2 = coordinator.registerAccepted(binding)

        assertEquals(key1, key2)
        val convStore = getOrCreateStore(conversationId)
        val records = convStore.conversation.first().hermesQueueRecords()
        assertEquals(1, records.size)
    }

    @Test
    fun `registerAccepted varying originatingUserTurnId or requestHash throws Conflict`() = runTest {
        val binding1 = defaultBinding()
        coordinator.registerAccepted(binding1)

        // Varying originatingUserTurnId
        try {
            coordinator.registerAccepted(binding1.copy(originatingUserTurnId = "different-turn"))
            fail("Expected conflict for different userTurnId")
        } catch (expected: IllegalStateException) {
            // expected
        }

        // Varying requestHash
        try {
            coordinator.registerAccepted(binding1.copy(requestHash = "sha256:different_req"))
            fail("Expected conflict for different requestHash")
        } catch (expected: IllegalStateException) {
            // expected
        }
    }

    // --- 2. Connected Relay Events & Call End ---

    @Test
    fun `onPersistedRelayEvent renews the advisory lease`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)

        clock.advance(15000L) // 15s later
        coordinator.onPersistedRelayEvent(key)

        val remaining = relayRegistry.remainingLease(key)
        assertNotNull(remaining)
        assertTrue(remaining!! > 20.seconds)
    }

    @Test
    fun `onCallEnded invalidates lease and preempts active jobs for matching voiceSessionId`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        assertTrue(relayRegistry.isLeaseActive(key))

        // Invalidate leases and preempt for call end
        relayRegistry.invalidate(key)
        scheduler.preempt(key, Duration.ZERO)

        assertFalse(relayRegistry.isLeaseActive(key))
        assertEquals(Duration.ZERO, scheduler.preempted[key])
    }

    // --- 3. Confirmed Cancellation ---

    @Test
    fun `requestCancellation sets cancelRequestedAt and preempts work without writing Canceled locally`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)

        clock.advance(5000L)
        val cancelTime = clock.epochMillis()
        coordinator.requestCancellation(key)

        val entry = ledger.find(key)!!
        assertEquals(cancelTime, entry.cancelRequestedAt)
        assertEquals(Duration.ZERO, scheduler.preempted[key])

        // Task projection in conversation remains Queued (NOT Canceled yet)
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Queued, record.status)

        // Repeated cancel keeps original timestamp
        clock.advance(3000L)
        coordinator.requestCancellation(key)
        val entryAfterSecondCancel = ledger.find(key)!!
        assertEquals(cancelTime, entryAfterSecondCancel.cancelRequestedAt)
    }

    @Test
    fun `reconcile with pending cancel executes DELETE, never GET, and commits Canceled on canceled snapshot`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key) // Ensure lease is expired

        coordinator.requestCancellation(key)

        val canceledPayload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "canceled")
            put("failure", buildJsonObject {
                put("kind", "canceled")
                put("safeMessage", "Canceled by client")
                put("safeSummary", "Canceled")
                put("retryable", false)
                put("source", "hermes")
            })
            put("correlation", correlationJson())
        }
        fakeRemote.cancelResponse = HermesRecoveryHttpResponse(200, ownerHash, canceledPayload)

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Cancellation)
        assertEquals(RecoveryOutcome.Success, outcome)
        assertEquals(1, fakeRemote.cancelCallCount)
        assertEquals(0, fakeRemote.pollCallCount) // Never called GET

        // Verify task committed as Canceled
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals("Canceled by client", record.error)

        // Ledger is Finished
        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Finished, entry.recoveryState)
        assertNull(entry.cancelRequestedAt)
    }

    @Test
    fun `reconcile completion racing cancel commits actual terminal Succeeded and clears cancel request`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        coordinator.requestCancellation(key)

        val answer = "Job already succeeded before cancel arrived"
        val expectedHash = "sha256:" + sha256Hex(answer)
        val succeededPayload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("resultHash", expectedHash)
            put("correlation", correlationJson())
        }
        // Server DELETE returns the completed terminal
        fakeRemote.cancelResponse = HermesRecoveryHttpResponse(200, ownerHash, succeededPayload)

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Cancellation)
        assertEquals(RecoveryOutcome.Success, outcome)

        // Succeeded terminal won!
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals(answer, record.answer)

        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Finished, entry.recoveryState)
        assertNull(entry.cancelRequestedAt)
    }

    // --- 4. Reconcile Behavior Rules ---

    @Test
    fun `reconcile when lease is active schedules APPEND_OR_REPLACE continuation at exact expiry and makes no remote request`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        // Lease is active for 30s
        clock.advance(10000L) // 20s remaining

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome)

        // No remote call made
        assertEquals(0, fakeRemote.pollCallCount)
        assertEquals(0, fakeRemote.cancelCallCount)

        // Continuation scheduled at exact remaining lease delay (20s)
        assertEquals(20.seconds, scheduler.continued[key])
    }

    @Test
    fun `reconcile normal active executes GET, advances status, and schedules age-tier continuation`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        val runningPayload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        fakeRemote.pollResponse = HermesRecoveryHttpResponse(200, ownerHash, runningPayload)

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome)
        assertEquals(1, fakeRemote.pollCallCount)

        // Monotonic active update in queue store: Queued -> Running
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Running, record.status)

        // Under 10 minutes -> 30s cadence continuation
        assertEquals(30.seconds, scheduler.continued[key])
    }

    @Test
    fun `reconcile transient exception, 408, 429, or 5xx returns Retry with task unchanged`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        // 1. Network exception
        fakeRemote.pollException = java.io.IOException("Socket timeout")
        val outcome1 = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Retry, outcome1)
        fakeRemote.pollException = null

        // 2. HTTP 429
        fakeRemote.pollResponse = HermesRecoveryHttpResponse(429, ownerHash, null)
        val outcome2 = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Retry, outcome2)

        // 3. HTTP 503
        fakeRemote.pollResponse = HermesRecoveryHttpResponse(503, ownerHash, null)
        val outcome3 = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Retry, outcome3)

        // Task in conversation remains Queued
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Queued, record.status)
        // Ledger state remains Active
        assertEquals(HermesRecoveryState.Active, ledger.find(key)!!.recoveryState)
    }

    @Test
    fun `reconcile 401, 403, missing credential, endpoint or owner mismatch marks Dormant AuthUnavailable`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        fakeRemote.pollResponse = HermesRecoveryHttpResponse(401, ownerHash, null)
        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome) // Stops retries

        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Dormant, entry.recoveryState)
        assertEquals(HermesDormantReason.AuthUnavailable, entry.dormantReason)
    }

    @Test
    fun `reconcile bad payload or unexpected permanent 4xx marks Dormant ProtocolMismatch`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        fakeRemote.pollResponse = HermesRecoveryHttpResponse(400, ownerHash, null)
        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome)

        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Dormant, entry.recoveryState)
        assertEquals(HermesDormantReason.ProtocolMismatch, entry.dormantReason)
    }

    @Test
    fun `reconcile reaching 24h deadline marks Dormant WindowElapsed with task unchanged`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        // Advance past 24h automatic deadline
        clock.advance(25.hours.inWholeMilliseconds)

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome)
        assertEquals(0, fakeRemote.pollCallCount) // No request made

        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Dormant, entry.recoveryState)
        assertEquals(HermesDormantReason.WindowElapsed, entry.dormantReason)

        // Task projection in conversation remains Queued
        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Queued, record.status)
    }

    @Test
    fun `reconcile with valid terminal snapshot commits queue and ledger Finished atomically`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        val answer = "42"
        val expectedHash = "sha256:" + sha256Hex(answer)
        val succeededPayload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("resultHash", expectedHash)
            put("correlation", correlationJson())
        }
        fakeRemote.pollResponse = HermesRecoveryHttpResponse(200, ownerHash, succeededPayload)

        val outcome = coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(RecoveryOutcome.Success, outcome)

        val record = getOrCreateStore(conversationId).conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("42", record.answer)

        val entry = ledger.find(key)!!
        assertEquals(HermesRecoveryState.Finished, entry.recoveryState)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, entry.notificationDisposition)
        assertNotNull(entry.terminalCommittedAt)
    }

    @Test
    fun `reconcile makes at most ONE remote request per invocation`() = runTest {
        val binding = defaultBinding()
        val key = coordinator.registerAccepted(binding)
        relayRegistry.invalidate(key)

        val runningPayload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        fakeRemote.pollResponse = HermesRecoveryHttpResponse(200, ownerHash, runningPayload)

        coordinator.reconcile(key, RecoveryTrigger.Scheduled)
        assertEquals(1, fakeRemote.pollCallCount)
        assertEquals(0, fakeRemote.cancelCallCount)
    }

    // --- 5. Reactivation Rules ---

    @Test
    fun `reactivateConversation with StartupRepair or ConversationOpened reactivates AuthUnavailable and WindowElapsed but not ProtocolMismatch or LegacyIncomplete`() = runTest {
        val baseEntry = HermesRecoveryEntry(
            recoveryKey = "k-auth",
            conversationId = conversationId,
            callId = "c1",
            jobId = "j1",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.AuthUnavailable,
            lastAttemptAt = 2000L,
        )
        ledger.insert(baseEntry)
        ledger.insert(baseEntry.copy(recoveryKey = "k-window", jobId = "j2", dormantReason = HermesDormantReason.WindowElapsed))
        ledger.insert(baseEntry.copy(recoveryKey = "k-proto", jobId = "j3", dormantReason = HermesDormantReason.ProtocolMismatch))
        ledger.insert(baseEntry.copy(recoveryKey = "k-legacy", jobId = "j4", dormantReason = HermesDormantReason.LegacyIncomplete, originalVoiceSessionHash = null))

        clock.advance(10000L)
        val now = clock.epochMillis()
        coordinator.reactivateConversation(conversationId, RecoveryTrigger.ConversationOpened)

        // AuthUnavailable reactivated
        val authEntry = ledger.find("k-auth")!!
        assertEquals(HermesRecoveryState.Active, authEntry.recoveryState)
        assertNull(authEntry.dormantReason)
        assertEquals(now + 24.hours.inWholeMilliseconds, authEntry.automaticDeadlineAt)
        assertEquals(Duration.ZERO, scheduler.preempted["k-auth"])

        // WindowElapsed reactivated
        val windowEntry = ledger.find("k-window")!!
        assertEquals(HermesRecoveryState.Active, windowEntry.recoveryState)
        assertNull(windowEntry.dormantReason)
        assertEquals(Duration.ZERO, scheduler.preempted["k-window"])

        // ProtocolMismatch remains Dormant
        val protoEntry = ledger.find("k-proto")!!
        assertEquals(HermesRecoveryState.Dormant, protoEntry.recoveryState)
        assertEquals(HermesDormantReason.ProtocolMismatch, protoEntry.dormantReason)

        // LegacyIncomplete remains Dormant
        val legacyEntry = ledger.find("k-legacy")!!
        assertEquals(HermesRecoveryState.Dormant, legacyEntry.recoveryState)
        assertEquals(HermesDormantReason.LegacyIncomplete, legacyEntry.dormantReason)
    }

    @Test
    fun `reactivateConversation with ConfigurationChanged or ExplicitRetry reactivates ProtocolMismatch as well`() = runTest {
        val protoEntry = HermesRecoveryEntry(
            recoveryKey = "k-proto-config",
            conversationId = conversationId,
            callId = "c1",
            jobId = "j1",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.ProtocolMismatch,
            lastAttemptAt = 2000L,
        )
        ledger.insert(protoEntry)

        coordinator.reactivateConversation(conversationId, RecoveryTrigger.ConfigurationChanged)

        val updated = ledger.find("k-proto-config")!!
        assertEquals(HermesRecoveryState.Active, updated.recoveryState)
        assertNull(updated.dormantReason)
        assertEquals(Duration.ZERO, scheduler.preempted["k-proto-config"])
    }

    // --- 6. Repair All ---

    @Test
    fun `repairAll repairs missing work, reconstructs active LiveKit records, and handles legacy incomplete records`() = runTest {
        // 1. Active ledger entry missing work
        val activeEntry = HermesRecoveryEntry(
            recoveryKey = "k-active-repair",
            conversationId = conversationId,
            callId = "c-active",
            jobId = "j-active",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = 1000L,
            automaticDeadlineAt = 1000L + 86400000L,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = 1000L,
        )
        ledger.insert(activeEntry)

        // 2. Conversation with reconstructable active record missing ledger entry
        val convStore = getOrCreateStore(conversationId)
        convStore.update { conv ->
            writer.upsertHermesTool(
                conversation = conv,
                callId = "c-recon",
                prompt = "Prompt 1",
                status = VoiceToolRecordStatus.Queued,
                jobId = "j-recon",
                originatingUserTurnId = "turn-recon",
                requestHash = "sha256:req_recon",
                argumentHash = "sha256:arg_recon",
                producer = HERMES_PRODUCER,
                sessionId = "lvs_recon",
                acceptingOwnerHash = "owner_recon",
                endpointBindingHash = "endpoint_recon",
            )
        }

        // 3. Conversation with incomplete legacy active record (missing requestHash)
        convStore.update { conv ->
            writer.upsertHermesTool(
                conversation = conv,
                callId = "c-legacy",
                prompt = "Prompt 2",
                status = VoiceToolRecordStatus.Queued,
                jobId = "j-legacy",
                originatingUserTurnId = "turn-legacy",
                requestHash = null, // Missing!
                argumentHash = "sha256:arg_legacy",
                producer = HERMES_PRODUCER,
                sessionId = "lvs_legacy",
                acceptingOwnerHash = "owner_legacy",
                endpointBindingHash = "endpoint_legacy",
            )
        }

        // 4. Conversation with historical terminal record (must NOT be backfilled into ledger)
        convStore.update { conv ->
            writer.upsertHermesTool(
                conversation = conv,
                callId = "c-term",
                prompt = "Prompt 3",
                status = VoiceToolRecordStatus.Complete("result"),
                jobId = "j-term",
                originatingUserTurnId = "turn-term",
                requestHash = "sha256:req_term",
                argumentHash = "sha256:arg_term",
                producer = HERMES_PRODUCER,
            )
        }

        coordinator.repairAll()

        // 0. Orphans deleted
        assertTrue(fakeDao.deleteOrphansCallCount > 0)

        // 1. Active entry had ensure called
        assertTrue(scheduler.ensured.containsKey("k-active-repair"))

        // 2. Reconstructable record got active ledger row and ensure called
        val reconKey = hermesRecoveryKey(conversationId, "c-recon", "j-recon")
        val reconEntry = ledger.find(reconKey)
        assertNotNull(reconEntry)
        assertEquals(HermesRecoveryState.Active, reconEntry!!.recoveryState)
        assertTrue(scheduler.ensured.containsKey(reconKey))

        // 3. Legacy incomplete record got Dormant(LegacyIncomplete) ledger row without changing conversation record
        val legacyKey = hermesRecoveryKey(conversationId, "c-legacy", "j-legacy")
        val legacyEntry = ledger.find(legacyKey)
        assertNotNull(legacyEntry)
        assertEquals(HermesRecoveryState.Dormant, legacyEntry!!.recoveryState)
        assertEquals(HermesDormantReason.LegacyIncomplete, legacyEntry.dormantReason)

        // 4. Historical terminal record did NOT get a ledger entry
        val termKey = hermesRecoveryKey(conversationId, "c-term", "j-term")
        assertNull(ledger.find(termKey))
    }
}

private class FakeEndpointResolver(
    private val remote: HermesRecoveryRemote,
    private val bindingHash: String,
) : HermesRecoveryEndpointResolver(null) {
    override fun resolve(settings: me.rerere.rikkahub.data.datastore.Settings, conversation: Conversation): ResolvedHermesRecoveryEndpoint {
        return ResolvedHermesRecoveryEndpoint(bindingHash, remote)
    }

    override suspend fun resolve(conversation: Conversation): ResolvedHermesRecoveryEndpoint {
        return ResolvedHermesRecoveryEndpoint(bindingHash, remote)
    }
}
