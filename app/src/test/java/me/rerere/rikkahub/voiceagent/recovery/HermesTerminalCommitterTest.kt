package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueRecord
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.ValidatedHermesRecoverySnapshot
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class HermesTerminalCommitterTest {

    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val callId = "call-committer-1"
    private val jobId = "job-committer-1"
    private val recoveryKey = "rec-key-committer-1"

    private val fakeDao = FakeHermesRecoveryDAODouble()
    private val ledger = HermesRecoveryLedger(fakeDao)
    private val writer = HermesToolRecordWriter()
    private val persister = VoiceTranscriptPersister()

    private class TestRecoveryClock(var currentEpoch: Long = 5000L) : RecoveryClock {
        override fun epochMillis(): Long = currentEpoch
        override fun elapsedRealtimeMillis(): Long = currentEpoch
    }

    private val clock = TestRecoveryClock()
    private val committer = HermesTerminalCommitter(ledger = ledger, clock = clock)

    private fun createDefaultActiveEntry(): HermesRecoveryEntry = HermesRecoveryEntry(
        recoveryKey = recoveryKey,
        conversationId = conversationId,
        callId = callId,
        jobId = jobId,
        producer = HERMES_PRODUCER,
        originalVoiceSessionHash = "s-hash",
        originalArgumentHash = "a-hash",
        originalOwnerHash = "o-hash",
        originalEndpointHash = "e-hash",
        acceptedAt = 1000L,
        automaticDeadlineAt = 1000L + 86400000L,
        recoveryState = HermesRecoveryState.Active,
        lastAttemptAt = 1000L,
        cancelRequestedAt = 2000L,
    )

    private fun setupStoreWithQueuedRecord(): Pair<InMemoryVoiceConversationStore, HermesQueueStore> {
        val initialConv = Conversation.ofId(conversationId)
        val conversationStore = InMemoryVoiceConversationStore(initialConv)
        val queueStore = HermesQueueStore(
            conversationStore = conversationStore,
            writer = writer,
            transcriptPersister = persister,
        )
        return conversationStore to queueStore
    }

    @Test
    fun `commitTerminal for Complete writes Finished SuppressedNotEnabled and clears cancel`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        queueStore.persistActive(
            callId = callId,
            prompt = "Compute data",
            status = VoiceToolRecordStatus.Queued,
            jobId = jobId,
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Complete,
            answer = "Completed result",
            error = null,
            resultHash = "sha256:result_hash_abc",
        )

        clock.currentEpoch = 6000L
        val result = committer.commitTerminal(
            queueStore = queueStore,
            entry = entry,
            snapshot = snapshot,
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)

        // Verify conversation record
        val records = convStore.conversation.first().hermesQueueRecords()
        assertEquals(1, records.size)
        val record = records.first()
        assertEquals(HermesQueueStatus.Complete, record.status)
        assertEquals("Completed result", record.answer)
        assertNull(record.error)
        assertEquals("sha256:result_hash_abc", record.resultHash)

        // Verify ledger entry
        val updatedLedger = ledger.find(recoveryKey)
        assertNotNull(updatedLedger)
        assertEquals(HermesRecoveryState.Finished, updatedLedger!!.recoveryState)
        assertNull(updatedLedger.dormantReason)
        assertEquals(6000L, updatedLedger.terminalCommittedAt)
        assertNull(updatedLedger.terminalDeadlineAt)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, updatedLedger.notificationDisposition)
        assertEquals(6000L, updatedLedger.notificationDispositionChangedAt)
        assertNull(updatedLedger.cancelRequestedAt)
        assertEquals(6000L, updatedLedger.lastAttemptAt)
    }

    @Test
    fun `commitTerminal for Failed writes safeMessage to queue but safeMessage does not enter ledger`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        queueStore.persistActive(
            callId = callId,
            prompt = "Compute data",
            status = VoiceToolRecordStatus.Queued,
            jobId = jobId,
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        val safeErrorMsg = "Safe internal execution failure"
        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Failed,
            answer = null,
            error = safeErrorMsg,
            resultHash = null,
        )

        clock.currentEpoch = 7000L
        val result = committer.commitTerminal(
            queueStore = queueStore,
            entry = entry,
            snapshot = snapshot,
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)

        // Verify conversation record
        val record = convStore.conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Failed, record.status)
        assertEquals(safeErrorMsg, record.error)
        assertNull(record.answer)

        // Verify ledger entry has NO safe error text
        val updatedLedger = ledger.find(recoveryKey)!!
        assertEquals(HermesRecoveryState.Finished, updatedLedger.recoveryState)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, updatedLedger.notificationDisposition)
        assertEquals(7000L, updatedLedger.terminalCommittedAt)
    }

    @Test
    fun `commitTerminal for Expired tombstone writes Expired to queue and Finished to ledger`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        queueStore.persistActive(
            callId = callId,
            prompt = "Compute data",
            status = VoiceToolRecordStatus.Queued,
            jobId = jobId,
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        val safeErrorMsg = "Job expired on server"
        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Expired,
            answer = null,
            error = safeErrorMsg,
            resultHash = null,
        )

        clock.currentEpoch = 8000L
        val result = committer.commitTerminal(
            queueStore = queueStore,
            entry = entry,
            snapshot = snapshot,
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)

        val record = convStore.conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Expired, record.status)
        assertEquals(safeErrorMsg, record.error)

        val updatedLedger = ledger.find(recoveryKey)!!
        assertEquals(HermesRecoveryState.Finished, updatedLedger.recoveryState)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, updatedLedger.notificationDisposition)
    }

    @Test
    fun `commitTerminal for Canceled writes Canceled to queue and Finished to ledger`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        queueStore.persistActive(
            callId = callId,
            prompt = "Compute data",
            status = VoiceToolRecordStatus.Queued,
            jobId = jobId,
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        val safeErrorMsg = "Job was canceled"
        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Canceled,
            answer = null,
            error = safeErrorMsg,
            resultHash = null,
        )

        clock.currentEpoch = 9000L
        val result = committer.commitTerminal(
            queueStore = queueStore,
            entry = entry,
            snapshot = snapshot,
        )

        assertEquals(HermesQueuePersistenceResult.Mutated, result)

        val record = convStore.conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Canceled, record.status)
        assertEquals(safeErrorMsg, record.error)

        val updatedLedger = ledger.find(recoveryKey)!!
        assertEquals(HermesRecoveryState.Finished, updatedLedger.recoveryState)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, updatedLedger.notificationDisposition)
    }

    @Test
    fun `commitTerminal rollback when ledger update throws leaves conversation unchanged`() = runTest {
        val throwingDao = object : HermesRecoveryDAO {
            override suspend fun find(recoveryKey: String): HermesRecoveryEntity? = null
            override suspend fun active(): List<HermesRecoveryEntity> = emptyList()
            override suspend fun dormant(): List<HermesRecoveryEntity> = emptyList()
            override suspend fun forConversation(conversationId: String): List<HermesRecoveryEntity> = emptyList()
            override suspend fun insert(entry: HermesRecoveryEntity): Long = 1L
            override suspend fun update(entry: HermesRecoveryEntity) {
                throw IllegalStateException("Ledger update DB crash")
            }
            override suspend fun deleteOrphans(): Int = 0
        }

        val throwingLedger = HermesRecoveryLedger(throwingDao)
        val throwingCommitter = HermesTerminalCommitter(ledger = throwingLedger, clock = clock)

        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        queueStore.persistActive(
            callId = callId,
            prompt = "Compute data",
            status = VoiceToolRecordStatus.Queued,
            jobId = jobId,
        )

        val entry = createDefaultActiveEntry()

        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Complete,
            answer = "Answer",
            error = null,
            resultHash = "sha256:hash",
        )

        try {
            throwingCommitter.commitTerminal(
                queueStore = queueStore,
                entry = entry,
                snapshot = snapshot,
            )
            fail("Expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals("Ledger update DB crash", expected.message)
        }

        // Conversation record should remain Queued (not mutated to Complete)
        val record = convStore.conversation.first().hermesQueueRecords().first()
        assertEquals(HermesQueueStatus.Queued, record.status)
    }

    @Test
    fun `commitTerminal equivalent terminal is idempotent no-op`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        val snapshot = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Complete,
            answer = "Answer",
            error = null,
            resultHash = "sha256:hash",
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        // First commit
        val firstResult = committer.commitTerminal(queueStore, entry, snapshot)
        assertEquals(HermesQueuePersistenceResult.Mutated, firstResult)

        // Second commit with identical snapshot
        val secondResult = committer.commitTerminal(queueStore, entry, snapshot)
        assertEquals(HermesQueuePersistenceResult.Equivalent, secondResult)

        val updatedLedger = ledger.find(recoveryKey)!!
        assertEquals(HermesRecoveryState.Finished, updatedLedger.recoveryState)
    }

    @Test
    fun `commitTerminal conflicting terminal returns Conflict`() = runTest {
        val (convStore, queueStore) = setupStoreWithQueuedRecord()
        val snapshot1 = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Complete,
            answer = "Answer 1",
            error = null,
            resultHash = "sha256:hash1",
        )

        val entry = createDefaultActiveEntry()
        ledger.insert(entry)

        committer.commitTerminal(queueStore, entry, snapshot1)

        val snapshot2 = ValidatedHermesRecoverySnapshot(
            jobId = jobId,
            callId = callId,
            status = HermesQueueStatus.Complete,
            answer = "Different Answer",
            error = null,
            resultHash = "sha256:hash2",
        )

        val secondResult = committer.commitTerminal(queueStore, entry, snapshot2)
        assertEquals(HermesQueuePersistenceResult.Conflict, secondResult)
    }
}

private class FakeHermesRecoveryDAODouble : HermesRecoveryDAO {
    private val storage = mutableMapOf<String, HermesRecoveryEntity>()

    override suspend fun find(recoveryKey: String): HermesRecoveryEntity? = storage[recoveryKey]

    override suspend fun active(): List<HermesRecoveryEntity> =
        storage.values.filter { it.recoveryState == HermesRecoveryState.Active.name }

    override suspend fun dormant(): List<HermesRecoveryEntity> =
        storage.values.filter { it.recoveryState == HermesRecoveryState.Dormant.name }

    override suspend fun forConversation(conversationId: String): List<HermesRecoveryEntity> =
        storage.values.filter { it.conversationId == conversationId }

    override suspend fun insert(entry: HermesRecoveryEntity): Long {
        if (storage.containsKey(entry.recoveryKey)) return -1L
        storage[entry.recoveryKey] = entry
        return 1L
    }

    override suspend fun update(entry: HermesRecoveryEntity) {
        storage[entry.recoveryKey] = entry
    }

    override suspend fun deleteOrphans(): Int = 0
}
