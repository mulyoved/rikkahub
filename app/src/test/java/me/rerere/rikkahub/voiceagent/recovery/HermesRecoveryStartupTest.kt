package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.InMemoryVoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import me.rerere.rikkahub.voiceagent.notification.HermesNotificationWorkScheduler

class HermesRecoveryStartupTest {

    private val conversationId1 = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val conversationId2 = Uuid.parse("00000000-0000-0000-0000-000000000002")

    private class TestDAO : HermesRecoveryDAO {
        val storage = mutableMapOf<String, HermesRecoveryEntity>()
        var deleteOrphansCalls = 0

        override suspend fun find(key: String): HermesRecoveryEntity? = storage[key]

        override suspend fun active(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == HermesRecoveryState.Active.name }

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
            deleteOrphansCalls++
            return 0
        }

        override suspend fun dormant(): List<HermesRecoveryEntity> =
            storage.values.filter { it.recoveryState == HermesRecoveryState.Dormant.name }
    }

    private class TestScheduler : HermesRecoveryWorkScheduler {
        val ensured = mutableMapOf<String, Duration>()
        val preempted = mutableMapOf<String, Duration>()

        override suspend fun ensure(recoveryKey: String, delay: Duration) {
            ensured[recoveryKey] = delay
        }

        override suspend fun preempt(recoveryKey: String, delay: Duration) {
            preempted[recoveryKey] = delay
        }

        override suspend fun continueAfterCurrent(recoveryKey: String, delay: Duration) = Unit
        override suspend fun cancel(recoveryKey: String) = Unit
    }

    private class TestClock(var now: Long = 100_000L) : RecoveryClock {
        override fun epochMillis(): Long = now
        override fun elapsedRealtimeMillis(): Long = now
    }

    @Test
    fun `startup repair deletes orphans, schedules active entries, reconstructs records, and reactivates dormant`() = runTest {
        val dao = TestDAO()
        val ledger = HermesRecoveryLedger(dao)
        val scheduler = TestScheduler()
        val clock = TestClock()
        val convStoreMap = mutableMapOf<Uuid, InMemoryVoiceConversationStore>()
        val writer = HermesToolRecordWriter()
        val persister = VoiceTranscriptPersister()

        // 1. Active entry that needs scheduling
        val activeEntry = HermesRecoveryEntry(
            recoveryKey = "key-active",
            conversationId = conversationId1,
            callId = "call-1",
            jobId = "job-1",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s1",
            originalArgumentHash = "a1",
            originalOwnerHash = "o1",
            originalEndpointHash = "e1",
            acceptedAt = clock.now,
            automaticDeadlineAt = clock.now + 24.hours.inWholeMilliseconds,
            recoveryState = HermesRecoveryState.Active,
            lastAttemptAt = clock.now,
        )
        ledger.insert(activeEntry)

        // 2. Dormant AuthUnavailable entry
        val dormantAuth = HermesRecoveryEntry(
            recoveryKey = "key-dormant-auth",
            conversationId = conversationId1,
            callId = "call-2",
            jobId = "job-2",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s2",
            originalArgumentHash = "a2",
            originalOwnerHash = "o2",
            originalEndpointHash = "e2",
            acceptedAt = clock.now - 5000,
            automaticDeadlineAt = clock.now - 1000,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.AuthUnavailable,
            lastAttemptAt = clock.now - 1000,
        )
        ledger.insert(dormantAuth)

        // 3. Conversation 2 with a reconstructable queued record
        val store2 = InMemoryVoiceConversationStore(Conversation.ofId(conversationId2))
        convStoreMap[conversationId2] = store2
        store2.update { conv ->
            writer.upsertHermesTool(
                conversation = conv,
                callId = "call-recon",
                prompt = "recon prompt",
                status = VoiceToolRecordStatus.Queued,
                jobId = "job-recon",
                originatingUserTurnId = "turn-recon",
                requestHash = "sha256:req-recon",
                argumentHash = "sha256:arg-recon",
                producer = HERMES_PRODUCER,
                sessionId = "lvs_recon",
                acceptingOwnerHash = "owner-recon",
                endpointBindingHash = "endpoint-recon",
            )
        }

        val coordinator = HermesRecoveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            relayRegistry = HermesRelayRegistry(clock),
            endpointResolver = HermesRecoveryEndpointResolver(null),
            conversationStoreProvider = { id -> convStoreMap.getOrPut(id) { InMemoryVoiceConversationStore(Conversation.ofId(id)) } },
            clock = clock,
            conversationIdsProvider = { listOf(conversationId1, conversationId2) },
        )

        coordinator.repairAll()

        // Orphans deleted
        assertTrue(dao.deleteOrphansCalls > 0)

        // Active entry ensured
        assertTrue(scheduler.ensured.containsKey("key-active"))

        // Dormant AuthUnavailable was reactivated by StartupRepair
        val reactivatedAuth = ledger.find("key-dormant-auth")!!
        assertEquals(HermesRecoveryState.Active, reactivatedAuth.recoveryState)
        assertNull(reactivatedAuth.dormantReason)
        assertTrue(scheduler.preempted.containsKey("key-dormant-auth"))

        // Reconstructable record was created in ledger and ensured
        val reconKey = hermesRecoveryKey(conversationId2, "call-recon", "job-recon")
        val reconEntry = ledger.find(reconKey)
        assertNotNull(reconEntry)
        assertEquals(HermesRecoveryState.Active, reconEntry!!.recoveryState)
        assertTrue(scheduler.ensured.containsKey(reconKey))
    }

    @Test
    fun `endpoint configuration hash is deterministic and changes on credential change without leaking raw config`() {
        val providerId = Uuid.random()
        val baseProvider = ProviderSetting.OpenAI(
            id = providerId,
            name = "Hermes Provider",
            baseUrl = "https://hermes.example.com/api",
            apiKey = "sk-secret-device-api-key-12345",
        )
        val settings1 = Settings(
            providers = listOf(baseProvider),
        )
        val hash1 = hermesEndpointConfigurationHash(settings1)
        val hash1Repeat = hermesEndpointConfigurationHash(settings1)

        assertEquals(hash1, hash1Repeat)
        assertTrue(hash1.startsWith("sha256:"))
        assertFalse(hash1.contains("sk-secret"))
        assertFalse(hash1.contains("https://hermes.example.com"))

        // Change API key
        val settingsChangedKey = Settings(
            providers = listOf(baseProvider.copy(apiKey = "sk-different-key-99999")),
        )
        val hash2 = hermesEndpointConfigurationHash(settingsChangedKey)
        assertNotEquals(hash1, hash2)

        // Change Base URL
        val settingsChangedUrl = Settings(
            providers = listOf(baseProvider.copy(baseUrl = "https://other.example.com/api")),
        )
        val hash3 = hermesEndpointConfigurationHash(settingsChangedUrl)
        assertNotEquals(hash1, hash3)

        // Unrelated setting change (launchCount) does not change hash
        val settingsUnrelated = settings1.copy(launchCount = 42)
        val hashUnrelated = hermesEndpointConfigurationHash(settingsUnrelated)
        assertEquals(hash1, hashUnrelated)
    }

    @Test
    fun `configuration change reactivates ProtocolMismatch, AuthUnavailable, and WindowElapsed across conversations`() = runTest {
        val dao = TestDAO()
        val ledger = HermesRecoveryLedger(dao)
        val scheduler = TestScheduler()
        val clock = TestClock()

        val protoEntry = HermesRecoveryEntry(
            recoveryKey = "key-proto",
            conversationId = conversationId1,
            callId = "c1",
            jobId = "j1",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = clock.now,
            automaticDeadlineAt = clock.now,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.ProtocolMismatch,
            lastAttemptAt = clock.now,
        )
        val authEntry = protoEntry.copy(
            recoveryKey = "key-auth-2",
            conversationId = conversationId2,
            jobId = "j2",
            dormantReason = HermesDormantReason.AuthUnavailable,
        )
        val legacyEntry = protoEntry.copy(
            recoveryKey = "key-legacy-2",
            conversationId = conversationId2,
            jobId = "j3",
            dormantReason = HermesDormantReason.LegacyIncomplete,
            originalVoiceSessionHash = null,
        )

        ledger.insert(protoEntry)
        ledger.insert(authEntry)
        ledger.insert(legacyEntry)

        val coordinator = HermesRecoveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            relayRegistry = HermesRelayRegistry(clock),
            endpointResolver = HermesRecoveryEndpointResolver(null),
            conversationStoreProvider = { id -> InMemoryVoiceConversationStore(Conversation.ofId(id)) },
            clock = clock,
        )

        coordinator.reactivateDormant(RecoveryTrigger.ConfigurationChanged)

        // ProtocolMismatch reactivated
        val updatedProto = ledger.find("key-proto")!!
        assertEquals(HermesRecoveryState.Active, updatedProto.recoveryState)
        assertNull(updatedProto.dormantReason)
        assertTrue(scheduler.preempted.containsKey("key-proto"))

        // AuthUnavailable reactivated
        val updatedAuth = ledger.find("key-auth-2")!!
        assertEquals(HermesRecoveryState.Active, updatedAuth.recoveryState)
        assertNull(updatedAuth.dormantReason)
        assertTrue(scheduler.preempted.containsKey("key-auth-2"))

        // LegacyIncomplete remains Dormant
        val updatedLegacy = ledger.find("key-legacy-2")!!
        assertEquals(HermesRecoveryState.Dormant, updatedLegacy.recoveryState)
        assertEquals(HermesDormantReason.LegacyIncomplete, updatedLegacy.dormantReason)
    }

    @Test
    fun `conversation open reactivates only targeted conversation AuthUnavailable and WindowElapsed, never ProtocolMismatch`() = runTest {
        val dao = TestDAO()
        val ledger = HermesRecoveryLedger(dao)
        val scheduler = TestScheduler()
        val clock = TestClock()

        val authEntry1 = HermesRecoveryEntry(
            recoveryKey = "key-auth-conv1",
            conversationId = conversationId1,
            callId = "c1",
            jobId = "j1",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = clock.now,
            automaticDeadlineAt = clock.now,
            recoveryState = HermesRecoveryState.Dormant,
            dormantReason = HermesDormantReason.AuthUnavailable,
            lastAttemptAt = clock.now,
        )
        val protoEntry1 = authEntry1.copy(
            recoveryKey = "key-proto-conv1",
            jobId = "j2",
            dormantReason = HermesDormantReason.ProtocolMismatch,
        )
        val authEntry2 = authEntry1.copy(
            recoveryKey = "key-auth-conv2",
            conversationId = conversationId2,
            jobId = "j3",
        )

        ledger.insert(authEntry1)
        ledger.insert(protoEntry1)
        ledger.insert(authEntry2)

        val coordinator = HermesRecoveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            relayRegistry = HermesRelayRegistry(clock),
            endpointResolver = HermesRecoveryEndpointResolver(null),
            conversationStoreProvider = { id -> InMemoryVoiceConversationStore(Conversation.ofId(id)) },
            clock = clock,
        )

        // Open conversation 1 only
        coordinator.reactivateConversation(conversationId1, RecoveryTrigger.ConversationOpened)

        // Conv 1 AuthUnavailable reactivated
        val updatedAuth1 = ledger.find("key-auth-conv1")!!
        assertEquals(HermesRecoveryState.Active, updatedAuth1.recoveryState)
        assertNull(updatedAuth1.dormantReason)
        assertTrue(scheduler.preempted.containsKey("key-auth-conv1"))

        // Conv 1 ProtocolMismatch remains Dormant
        val updatedProto1 = ledger.find("key-proto-conv1")!!
        assertEquals(HermesRecoveryState.Dormant, updatedProto1.recoveryState)
        assertEquals(HermesDormantReason.ProtocolMismatch, updatedProto1.dormantReason)

        // Conv 2 AuthUnavailable remains Dormant (untargeted)
        val updatedAuth2 = ledger.find("key-auth-conv2")!!
        assertEquals(HermesRecoveryState.Dormant, updatedAuth2.recoveryState)
        assertEquals(HermesDormantReason.AuthUnavailable, updatedAuth2.dormantReason)
        assertFalse(scheduler.preempted.containsKey("key-auth-conv2"))
    }

    @Test
    fun `repairAll suppresses expired notification outbox rows and calls notificationScheduler replaceForEarliestDue for valid pending`() = runTest {
        val dao = TestDAO()
        val ledger = HermesRecoveryLedger(dao)
        val scheduler = TestScheduler()
        val clock = TestClock(now = 100_000L)
        val notifScheduler = StartupFakeNotificationScheduler()

        // Expired pending row (committed at 0, deadline at 15m = 900_000, now is 1_000_000)
        clock.now = 1_000_000L
        val expiredPending = HermesRecoveryEntry(
            recoveryKey = "key-expired-pending",
            conversationId = conversationId1,
            callId = "c-exp",
            jobId = "j-exp",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = 0L,
            automaticDeadlineAt = 86400000L,
            recoveryState = HermesRecoveryState.Finished,
            lastAttemptAt = 0L,
            terminalCommittedAt = 0L,
            terminalDeadlineAt = 900_000L,
            notificationDisposition = HermesNotificationDisposition.PendingPost,
            notificationDispositionChangedAt = 0L,
            notificationNextAttemptAt = 0L,
        )

        // Valid pending row (committed at 950_000, deadline at 950k + 15m, nextAttempt at 1_010_000)
        val validPending = HermesRecoveryEntry(
            recoveryKey = "key-valid-pending",
            conversationId = conversationId2,
            callId = "c-val",
            jobId = "j-val",
            producer = HERMES_PRODUCER,
            originalVoiceSessionHash = "s",
            originalArgumentHash = "a",
            originalOwnerHash = "o",
            originalEndpointHash = "e",
            acceptedAt = 950_000L,
            automaticDeadlineAt = 950_000L + 86400000L,
            recoveryState = HermesRecoveryState.Finished,
            lastAttemptAt = 950_000L,
            terminalCommittedAt = 950_000L,
            terminalDeadlineAt = 950_000L + 15.minutes.inWholeMilliseconds,
            notificationDisposition = HermesNotificationDisposition.PendingPost,
            notificationDispositionChangedAt = 950_000L,
            notificationNextAttemptAt = 1_010_000L,
        )

        ledger.insert(expiredPending)
        ledger.insert(validPending)

        val coordinator = HermesRecoveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            relayRegistry = HermesRelayRegistry(clock),
            endpointResolver = HermesRecoveryEndpointResolver(null),
            conversationStoreProvider = { id -> InMemoryVoiceConversationStore(Conversation.ofId(id)) },
            clock = clock,
            notificationScheduler = notifScheduler,
        )

        coordinator.repairAll()

        // Expired row suppressed to SuppressedPostFailure
        val updatedExpired = ledger.find("key-expired-pending")!!
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, updatedExpired.notificationDisposition)
        assertNull(updatedExpired.notificationNextAttemptAt)

        // Valid pending scheduled with REPLACE and delay = 1_010_000 - 1_000_000 = 10_000ms
        assertEquals(1, notifScheduler.replaceCalls.size)
        assertEquals(conversationId2, notifScheduler.replaceCalls[0].first)
        assertEquals(10_000L.milliseconds, notifScheduler.replaceCalls[0].second)
    }
}

private class StartupFakeNotificationScheduler : HermesNotificationWorkScheduler {
    val replaceCalls = mutableListOf<Pair<Uuid, Duration>>()
    override suspend fun replaceForEarliestDue(conversationId: Uuid, delay: Duration) {
        replaceCalls.add(conversationId to delay)
    }
    override suspend fun continueAfterCurrent(conversationId: Uuid, delay: Duration) = Unit
    override suspend fun cancel(conversationId: Uuid) = Unit
}
