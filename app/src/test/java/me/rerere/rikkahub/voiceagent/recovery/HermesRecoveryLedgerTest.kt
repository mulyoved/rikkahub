package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.PersistedConversationFolder
import me.rerere.rikkahub.service.ConversationPersistenceGateway
import me.rerere.rikkahub.service.ConversationPersistenceOrchestrator
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.SynchronizedVoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

class HermesRecoveryLedgerTest {

    private val sampleConversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val writer = HermesToolRecordWriter()

    @Test
    fun `active entry requires all validation hashes`() = runTest {
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val activeEntry = HermesRecoveryEntry(
            recoveryKey = "active-key",
            conversationId = sampleConversationId,
            callId = "call-1",
            jobId = "job-1",
            producer = "hermes",
            originalVoiceSessionHash = "hash-session",
            originalArgumentHash = "hash-arg",
            originalOwnerHash = "hash-owner",
            originalEndpointHash = "hash-ep",
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Active,
            dormantReason = null,
            lastAttemptAt = 1000L,
            cancelRequestedAt = null,
            notificationDisposition = HermesNotificationDisposition.Undecided,
            notificationDispositionChangedAt = 1000L,
        )

        assertTrue(ledger.insert(activeEntry))
        assertEquals(1, fakeDao.storage.size)

        // Missing session hash
        try {
            ledger.insert(activeEntry.copy(recoveryKey = "bad-1", originalVoiceSessionHash = null))
            fail("Expected IllegalArgumentException for missing originalVoiceSessionHash in Active state")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        // Missing argument hash
        try {
            ledger.insert(activeEntry.copy(recoveryKey = "bad-2", originalArgumentHash = null))
            fail("Expected IllegalArgumentException for missing originalArgumentHash in Active state")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        // Missing owner hash
        try {
            ledger.insert(activeEntry.copy(recoveryKey = "bad-3", originalOwnerHash = null))
            fail("Expected IllegalArgumentException for missing originalOwnerHash in Active state")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        // Missing endpoint hash
        try {
            ledger.insert(activeEntry.copy(recoveryKey = "bad-4", originalEndpointHash = null))
            fail("Expected IllegalArgumentException for missing originalEndpointHash in Active state")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `dormant and finished entries allow null validation hashes`() = runTest {
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val dormantEntry = HermesRecoveryEntry(
            recoveryKey = "dormant-key",
            conversationId = sampleConversationId,
            callId = "call-1",
            jobId = "job-1",
            producer = "hermes",
            originalVoiceSessionHash = null,
            originalArgumentHash = null,
            originalOwnerHash = null,
            originalEndpointHash = null,
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.LegacyIncomplete,
            lastAttemptAt = 1000L,
        )

        assertTrue(ledger.insert(dormantEntry))
        val foundDormant = ledger.find("dormant-key")
        assertNotNull(foundDormant)
        assertEquals(HermesRecoveryState.Dormant, foundDormant?.recoveryState)
        assertEquals(HermesDormantReason.LegacyIncomplete, foundDormant?.dormantReason)
        assertNull(foundDormant?.originalVoiceSessionHash)

        val finishedEntry = dormantEntry.copy(
            recoveryKey = "finished-key",
            recoveryState = HermesRecoveryState.Finished,
            dormantReason = null,
            terminalCommittedAt = 3000L,
        )

        assertTrue(ledger.insert(finishedEntry))
        val foundFinished = ledger.find("finished-key")
        assertNotNull(foundFinished)
        assertEquals(HermesRecoveryState.Finished, foundFinished?.recoveryState)
        assertEquals(3000L, foundFinished?.terminalCommittedAt)
    }

    @Test
    fun `conversion preserves all fields and enum mappings`() {
        val entry = HermesRecoveryEntry(
            recoveryKey = "full-key",
            conversationId = sampleConversationId,
            callId = "call-full",
            jobId = "job-full",
            producer = "hermes-agent",
            originalVoiceSessionHash = "s-hash",
            originalArgumentHash = "a-hash",
            originalOwnerHash = "o-hash",
            originalEndpointHash = "e-hash",
            acceptedAt = 1111L,
            automaticDeadlineAt = 2222L,
            recoveryState = HermesRecoveryState.Active,
            dormantReason = HermesDormantReason.AuthUnavailable,
            lastAttemptAt = 3333L,
            cancelRequestedAt = 4444L,
            notificationDisposition = HermesNotificationDisposition.PendingPost,
            notificationDispositionChangedAt = 5555L,
            terminalCommittedAt = 6666L,
            terminalDeadlineAt = 7777L,
            notificationNextAttemptAt = 8888L,
            notificationAttemptCount = 2,
        )

        val entity = entry.toEntity()
        assertEquals("full-key", entity.recoveryKey)
        assertEquals(sampleConversationId.toString(), entity.conversationId)
        assertEquals("Active", entity.recoveryState)
        assertEquals("AuthUnavailable", entity.dormantReason)
        assertEquals("PendingPost", entity.notificationDisposition)
        assertEquals(2, entity.notificationAttemptCount)

        val restored = entity.toEntry()
        assertEquals(entry, restored)
    }

    @Test
    fun `dao operations filter and manipulate entries properly`() = runTest {
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val conv1 = Uuid.random()
        val conv2 = Uuid.random()

        val active1 = HermesRecoveryEntry(
            recoveryKey = "k1",
            conversationId = conv1,
            callId = "c1",
            jobId = "j1",
            producer = "hermes",
            originalVoiceSessionHash = "s1",
            originalArgumentHash = "a1",
            originalOwnerHash = "o1",
            originalEndpointHash = "e1",
            acceptedAt = 100L,
            automaticDeadlineAt = 200L,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = 100L,
        )

        val active2 = active1.copy(recoveryKey = "k2", conversationId = conv2)
        val finished1 = active1.copy(recoveryKey = "k3", recoveryState = HermesRecoveryState.Finished)

        ledger.insert(active1)
        ledger.insert(active2)
        ledger.insert(finished1)

        val activeList = ledger.active()
        assertEquals(2, activeList.size)
        assertTrue(activeList.any { it.recoveryKey == "k1" })
        assertTrue(activeList.any { it.recoveryKey == "k2" })

        val conv1List = ledger.forConversation(conv1)
        assertEquals(2, conv1List.size)
        assertTrue(conv1List.any { it.recoveryKey == "k1" })
        assertTrue(conv1List.any { it.recoveryKey == "k3" })

        // Update
        val updatedK1 = active1.copy(lastAttemptAt = 500L)
        ledger.update(updatedK1)
        assertEquals(500L, ledger.find("k1")?.lastAttemptAt)
    }

    @Test
    fun `in memory store updateAtomically runs transform then commit and rolls back on failure`() = runTest {
        val initial = Conversation.ofId(sampleConversationId).copy(title = "Initial")
        val store = InMemoryVoiceConversationStore(initial)

        // Success case
        val result = store.updateAtomically(
            transform = { conv -> conv.copy(title = "Updated") to "commit-token" },
            commit = { token -> assertEquals("commit-token", token) },
        )
        assertEquals("commit-token", result)
        assertEquals("Updated", store.conversation.first().title)

        // Commit failure case: store must not commit changes
        val commitError = RuntimeException("commit failed")
        try {
            store.updateAtomically(
                transform = { conv -> conv.copy(title = "Dirty") to "fail-token" },
                commit = { throw commitError },
            )
            fail("Expected commit exception")
        } catch (e: RuntimeException) {
            assertSame(commitError, e)
        }
        assertEquals("Updated", store.conversation.first().title)
    }

    @Test
    fun `synchronized store wraps updateAtomically`() = runTest {
        val initial = Conversation.ofId(sampleConversationId).copy(title = "Initial")
        val inMemory = InMemoryVoiceConversationStore(initial)
        val syncStore = SynchronizedVoiceConversationStore(inMemory)

        val result = syncStore.updateAtomically(
            transform = { conv -> conv.copy(title = "Synced") to 42 },
            commit = { value -> assertEquals(42, value) },
        )
        assertEquals(42, result)
        assertEquals("Synced", syncStore.conversation.first().title)
    }

    @Test
    fun `persistence orchestrator invokes primaryTransaction and rolls back session and index on failure`() = runTest {
        val gateway = RecordingAtomicGateway(
            persistedLocation = PersistedConversationFolder(true, Uuid.random(), null),
        )
        val orchestrator = ConversationPersistenceOrchestrator(gateway)
        val conv = Conversation.ofId(sampleConversationId).copy(title = "Orchestrated")

        var primaryTxRan = false
        orchestrator.persist(
            conversationId = sampleConversationId,
            conversation = conv,
            preservePersistedLocation = true,
            primaryTransaction = {
                primaryTxRan = true
            },
        )

        assertTrue("primaryTransaction must be executed", primaryTxRan)
        assertEquals(listOf("read", "update(tx)", "session", "index"), gateway.events)

        // Now test failure in primaryTransaction
        gateway.events.clear()
        val txError = IllegalStateException("tx error")
        try {
            orchestrator.persist(
                conversationId = sampleConversationId,
                conversation = conv,
                preservePersistedLocation = true,
                primaryTransaction = {
                    throw txError
                },
            )
            fail("Expected txError")
        } catch (e: IllegalStateException) {
            assertSame(txError, e)
        }

        assertEquals(listOf("read", "update(tx)"), gateway.events)
        assertFalse(gateway.events.contains("session"))
        assertFalse(gateway.events.contains("index"))
    }

    @Test
    fun `atomic acceptance update rolls back tool record when ledger throws`() = runTest {
        val initialConversation = Conversation.ofId(sampleConversationId)
        val store = InMemoryVoiceConversationStore(initialConversation)
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val invalidEntryMissingHash = HermesRecoveryEntry(
            recoveryKey = "rec-fail",
            conversationId = sampleConversationId,
            callId = "call-1",
            jobId = "job-1",
            producer = "hermes",
            originalVoiceSessionHash = null, // Missing hash causes validation exception
            originalArgumentHash = null,
            originalOwnerHash = null,
            originalEndpointHash = null,
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = 1000L,
        )

        try {
            store.updateAtomically(
                transform = { conversation ->
                    val updated = writer.upsertHermesTool(
                        conversation = conversation,
                        callId = "call-1",
                        prompt = "calculate pi",
                        status = VoiceToolRecordStatus.Queued,
                        jobId = "job-1",
                    )
                    updated to invalidEntryMissingHash
                },
                commit = { entry ->
                    ledger.insert(entry)
                },
            )
            fail("Expected IllegalArgumentException from ledger validation")
        } catch (expected: IllegalArgumentException) {
            // expected
        }

        // Assert no tool record remains in the conversation store
        val current = store.conversation.first()
        assertTrue("No tool records should remain after ledger throw", current.hermesQueueRecords().isEmpty())
        assertNull("No ledger row should exist", ledger.find("rec-fail"))
    }

    @Test
    fun `atomic acceptance update rolls back ledger entry when conversation write throws`() = runTest {
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val validEntry = HermesRecoveryEntry(
            recoveryKey = "rec-valid",
            conversationId = sampleConversationId,
            callId = "call-1",
            jobId = "job-1",
            producer = "hermes",
            originalVoiceSessionHash = "s-hash",
            originalArgumentHash = "a-hash",
            originalOwnerHash = "o-hash",
            originalEndpointHash = "e-hash",
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = 1000L,
        )

        val writeError = IllegalStateException("database write failed")
        val gateway = RecordingAtomicGateway(
            persistedLocation = PersistedConversationFolder(true, Uuid.random(), null),
            writeFailure = writeError,
        )
        val orchestrator = ConversationPersistenceOrchestrator(gateway)

        var ledgerInserted = false
        try {
            orchestrator.persist(
                conversationId = sampleConversationId,
                conversation = Conversation.ofId(sampleConversationId),
                preservePersistedLocation = true,
                primaryTransaction = {
                    ledger.insert(validEntry)
                    ledgerInserted = true
                },
            )
            fail("Expected writeError")
        } catch (e: IllegalStateException) {
            assertSame(writeError, e)
        }

        assertFalse("Ledger insert inside primaryTransaction should not run when conversation write throws beforehand", ledgerInserted)
    }

    @Test
    fun `atomic acceptance update makes both conversation record and ledger entry visible on success`() = runTest {
        val initialConversation = Conversation.ofId(sampleConversationId)
        val store = InMemoryVoiceConversationStore(initialConversation)
        val fakeDao = FakeHermesRecoveryDAO()
        val ledger = HermesRecoveryLedger(fakeDao)

        val validEntry = HermesRecoveryEntry(
            recoveryKey = "rec-success",
            conversationId = sampleConversationId,
            callId = "call-success",
            jobId = "job-success",
            producer = "hermes",
            originalVoiceSessionHash = "s-hash",
            originalArgumentHash = "a-hash",
            originalOwnerHash = "o-hash",
            originalEndpointHash = "e-hash",
            acceptedAt = 1000L,
            automaticDeadlineAt = 2000L,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = 1000L,
        )

        val result = store.updateAtomically(
            transform = { conversation ->
                val updated = writer.upsertHermesTool(
                    conversation = conversation,
                    callId = "call-success",
                    prompt = "summarize log",
                    status = VoiceToolRecordStatus.Queued,
                    jobId = "job-success",
                )
                updated to validEntry
            },
            commit = { entry ->
                ledger.insert(entry)
            },
        )

        assertEquals("rec-success", result.recoveryKey)

        // Verify conversation has the tool record
        val currentConv = store.conversation.first()
        val records = currentConv.hermesQueueRecords()
        assertEquals(1, records.size)
        assertEquals("job-success", records[0].jobId)
        assertEquals("call-success", records[0].callId)

        // Verify ledger has the entry
        val found = ledger.find("rec-success")
        assertNotNull(found)
        assertEquals("job-success", found?.jobId)
        assertEquals(HermesRecoveryState.Active, found?.recoveryState)
    }

    private class RecordingAtomicGateway(
        private val persistedLocation: PersistedConversationFolder,
        private val writeFailure: Throwable? = null,
    ) : ConversationPersistenceGateway {
        val events = mutableListOf<String>()
        var primaryConversation: Conversation? = null
        var sessionConversation: Conversation? = null
        var indexedConversation: Conversation? = null

        override suspend fun <T> serialize(
            conversationId: Uuid,
            persistPrimary: suspend (PersistedConversationFolder) -> T,
            onPrimaryCommitted: suspend (T) -> Unit,
            postPrimary: suspend (T) -> Unit,
        ): T {
            events += "read"
            val result = persistPrimary(persistedLocation)
            onPrimaryCommitted(result)
            postPrimary(result)
            return result
        }

        override suspend fun insertPrimary(
            conversation: Conversation,
            primaryTransaction: suspend () -> Unit,
        ) {
            events += "insert(tx)"
            writeFailure?.let { throw it }
            primaryConversation = conversation
            primaryTransaction()
        }

        override suspend fun updatePrimary(
            conversation: Conversation,
            primaryTransaction: suspend () -> Unit,
        ) {
            events += "update(tx)"
            writeFailure?.let { throw it }
            primaryConversation = conversation
            primaryTransaction()
        }

        override fun synchronizeSession(conversationId: Uuid, conversation: Conversation) {
            events += "session"
            sessionConversation = conversation
        }

        override suspend fun index(conversation: Conversation) {
            events += "index"
            indexedConversation = conversation
        }
    }

    private class FakeHermesRecoveryDAO : HermesRecoveryDAO {
        val storage = mutableMapOf<String, HermesRecoveryEntity>()

        override suspend fun find(key: String): HermesRecoveryEntity? = storage[key]

        override suspend fun active(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == "Active" }

        override suspend fun dormant(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == "Dormant" }

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
}
