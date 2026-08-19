package me.rerere.rikkahub.voiceagent.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryEntry
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryState
import me.rerere.rikkahub.voiceagent.recovery.RecoveryClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class HermesNotificationDeliveryCoordinatorTest {

    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val conversationId2 = Uuid.parse("00000000-0000-0000-0000-000000000002")

    private val fakeDao = FakeNotificationRecoveryDAODouble()
    private val ledger = HermesRecoveryLedger(fakeDao)
    private val fakeScheduler = FakeNotificationWorkScheduler()
    private val fakePoster = FakeNotificationPoster()
    private val fakeAdmission = FakeNotificationAdmission()
    private val clock = FakeRecoveryClock(100_000L)

    private val coordinator = HermesNotificationDeliveryCoordinator(
        ledger = ledger,
        scheduler = fakeScheduler,
        poster = fakePoster,
        admission = fakeAdmission,
        clock = clock,
    )

    private fun createPendingEntry(
        recoveryKey: String,
        convId: Uuid = conversationId,
        jobId: String = "job-$recoveryKey",
        committedAt: Long = 100_000L,
        deadlineAt: Long = committedAt + 15.minutes.inWholeMilliseconds,
        nextAttemptAt: Long = committedAt,
        attemptCount: Int = 0,
    ): HermesRecoveryEntry = HermesRecoveryEntry(
        recoveryKey = recoveryKey,
        conversationId = convId,
        callId = "call-$recoveryKey",
        jobId = jobId,
        producer = HERMES_PRODUCER,
        originalVoiceSessionHash = "s-hash",
        originalArgumentHash = "a-hash",
        originalOwnerHash = "o-hash",
        originalEndpointHash = "e-hash",
        acceptedAt = committedAt,
        automaticDeadlineAt = committedAt + 86400000L,
        recoveryState = HermesRecoveryState.Finished,
        lastAttemptAt = committedAt,
        terminalCommittedAt = committedAt,
        terminalDeadlineAt = deadlineAt,
        notificationDisposition = HermesNotificationDisposition.PendingPost,
        notificationDispositionChangedAt = committedAt,
        notificationNextAttemptAt = nextAttemptAt,
        notificationAttemptCount = attemptCount,
    )

    @Test
    fun `multiple due rows coalesce to one post and all become Posted`() = runTest {
        val entry1 = createPendingEntry(recoveryKey = "key-1", nextAttemptAt = 100_000L)
        val entry2 = createPendingEntry(recoveryKey = "key-2", nextAttemptAt = 100_000L)
        val entry3 = createPendingEntry(recoveryKey = "key-3", nextAttemptAt = 100_000L)
        ledger.insert(entry1)
        ledger.insert(entry2)
        ledger.insert(entry3)

        clock.currentEpoch = 100_000L
        coordinator.deliver(conversationId)

        // Poster called exactly once with conversationId
        assertEquals(listOf(conversationId), fakePoster.postedConversations)

        // All entries transitioned to Posted
        val updated1 = ledger.find("key-1")!!
        val updated2 = ledger.find("key-2")!!
        val updated3 = ledger.find("key-3")!!

        assertEquals(HermesNotificationDisposition.Posted, updated1.notificationDisposition)
        assertEquals(HermesNotificationDisposition.Posted, updated2.notificationDisposition)
        assertEquals(HermesNotificationDisposition.Posted, updated3.notificationDisposition)

        assertEquals(100_000L, updated1.notificationDispositionChangedAt)
        assertEquals(100_000L, updated2.notificationDispositionChangedAt)
        assertEquals(100_000L, updated3.notificationDispositionChangedAt)

        assertNull(updated1.notificationNextAttemptAt)
        assertNull(updated2.notificationNextAttemptAt)
        assertNull(updated3.notificationNextAttemptAt)

        // No pending continuation work scheduled because no more pending rows
        assertTrue(fakeScheduler.continueCalls.isEmpty())
    }

    @Test
    fun `a later job has an independent budget`() = runTest {
        // Receipt 1 committed at T=100_000
        val entry1 = createPendingEntry(
            recoveryKey = "rec-1",
            committedAt = 100_000L,
            deadlineAt = 100_000L + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = 100_000L,
            attemptCount = 0,
        )
        ledger.insert(entry1)

        // First attempt fails at T=100_000
        fakePoster.shouldFail = true
        coordinator.deliver(conversationId)

        val afterFail1 = ledger.find("rec-1")!!
        assertEquals(1, afterFail1.notificationAttemptCount)
        assertEquals(100_000L + 1.minutes.inWholeMilliseconds, afterFail1.notificationNextAttemptAt)

        // Second attempt fails at T=160_000 (100_000 + 1m)
        clock.currentEpoch = 160_000L
        coordinator.deliver(conversationId)

        val afterFail2 = ledger.find("rec-1")!!
        assertEquals(2, afterFail2.notificationAttemptCount)
        assertEquals(100_000L + 5.minutes.inWholeMilliseconds, afterFail2.notificationNextAttemptAt)

        // At T=200_000, Receipt 2 is committed with its own fresh budget
        val entry2 = createPendingEntry(
            recoveryKey = "rec-2",
            committedAt = 200_000L,
            deadlineAt = 200_000L + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = 200_000L,
            attemptCount = 0,
        )
        ledger.insert(entry2)

        // Now deliver at T=200_000. Receipt 2 is due (nextAttempt=200k), Receipt 1 is not due yet (nextAttempt=400k)
        // Poster succeeds now
        fakePoster.shouldFail = false
        fakeScheduler.continueCalls.clear()
        clock.currentEpoch = 200_000L
        coordinator.deliver(conversationId)

        // Receipt 2 is posted
        val rec2Updated = ledger.find("rec-2")!!
        assertEquals(HermesNotificationDisposition.Posted, rec2Updated.notificationDisposition)

        // Receipt 1 is still PendingPost with attemptCount=2 and nextAttemptAt=400_000
        val rec1AfterRec2 = ledger.find("rec-1")!!
        assertEquals(HermesNotificationDisposition.PendingPost, rec1AfterRec2.notificationDisposition)
        assertEquals(2, rec1AfterRec2.notificationAttemptCount)
        assertEquals(400_000L, rec1AfterRec2.notificationNextAttemptAt)

        // Continuation scheduled for Receipt 1
        assertEquals(1, fakeScheduler.continueCalls.size)
        val continuation = fakeScheduler.continueCalls.last()
        assertEquals(conversationId, continuation.conversationId)
        assertEquals((400_000L - 200_000L).milliseconds, continuation.delay)

        // At T=400_000, Receipt 1 fails its 3rd attempt
        fakePoster.shouldFail = true
        clock.currentEpoch = 400_000L
        coordinator.deliver(conversationId)

        val rec1Final = ledger.find("rec-1")!!
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, rec1Final.notificationDisposition)
        assertEquals(3, rec1Final.notificationAttemptCount)
    }

    @Test
    fun `first and second failure schedule absolute terminal plus 1m and terminal plus 5m`() = runTest {
        val committedTime = 100_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-cadence",
            committedAt = committedTime,
            deadlineAt = committedTime + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = committedTime,
            attemptCount = 0,
        )
        ledger.insert(entry)

        fakePoster.shouldFail = true

        // Worker runs at T=105_000 (woke 5s late)
        clock.currentEpoch = 105_000L
        coordinator.deliver(conversationId)

        val afterFirstFailure = ledger.find("rec-cadence")!!
        assertEquals(1, afterFirstFailure.notificationAttemptCount)
        // Absolute terminal + 1m = 100_000 + 60_000 = 160_000 (NOT 105_000 + 60_000)
        assertEquals(160_000L, afterFirstFailure.notificationNextAttemptAt)

        // Continuation delay must be 160_000 - 105_000 = 55_000 ms
        assertEquals(1, fakeScheduler.continueCalls.size)
        assertEquals(55.seconds, fakeScheduler.continueCalls[0].delay)

        // Second run at T=160_000
        clock.currentEpoch = 160_000L
        coordinator.deliver(conversationId)

        val afterSecondFailure = ledger.find("rec-cadence")!!
        assertEquals(2, afterSecondFailure.notificationAttemptCount)
        // Absolute terminal + 5m = 100_000 + 300_000 = 400_000 (NOT 160_000 + 300_000)
        assertEquals(400_000L, afterSecondFailure.notificationNextAttemptAt)

        // Continuation delay must be 400_000 - 160_000 = 240_000 ms
        assertEquals(2, fakeScheduler.continueCalls.size)
        assertEquals(240.seconds, fakeScheduler.continueCalls[1].delay)
    }

    @Test
    fun `third failure suppresses immediately`() = runTest {
        val committedTime = 100_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-3rd-fail",
            committedAt = committedTime,
            deadlineAt = committedTime + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = 400_000L,
            attemptCount = 2,
        )
        ledger.insert(entry)

        fakePoster.shouldFail = true

        // Third attempt at T=400_000
        clock.currentEpoch = 400_000L
        coordinator.deliver(conversationId)

        val afterThirdFailure = ledger.find("rec-3rd-fail")!!
        assertEquals(3, afterThirdFailure.notificationAttemptCount)
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, afterThirdFailure.notificationDisposition)
        assertEquals(400_000L, afterThirdFailure.notificationDispositionChangedAt)
        assertNull(afterThirdFailure.notificationNextAttemptAt)

        // No continuation scheduled
        assertTrue(fakeScheduler.continueCalls.isEmpty())
    }

    @Test
    fun `first execution at exactly deadline suppresses before posting`() = runTest {
        val committedTime = 100_000L
        val deadline = committedTime + 15.minutes.inWholeMilliseconds // 1_000_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-deadline",
            committedAt = committedTime,
            deadlineAt = deadline,
            nextAttemptAt = committedTime,
            attemptCount = 0,
        )
        ledger.insert(entry)

        // Execution at exactly deadline
        clock.currentEpoch = deadline
        coordinator.deliver(conversationId)

        // Poster was NEVER called
        assertTrue(fakePoster.postedConversations.isEmpty())

        // Entry is permanently suppressed
        val updated = ledger.find("rec-deadline")!!
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, updated.notificationDisposition)
        assertEquals(deadline, updated.notificationDispositionChangedAt)
        assertNull(updated.notificationNextAttemptAt)
        assertTrue(fakeScheduler.continueCalls.isEmpty())
    }

    @Test
    fun `foreground loss before retry permanently suppresses`() = runTest {
        val committedTime = 100_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-fg-suppress",
            committedAt = committedTime,
            deadlineAt = committedTime + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = 160_000L,
            attemptCount = 1,
        )
        ledger.insert(entry)

        // App becomes foreground before delivery
        fakeAdmission.disposition = HermesNotificationDisposition.SuppressedForeground

        clock.currentEpoch = 160_000L
        coordinator.deliver(conversationId)

        // Poster was NEVER called
        assertTrue(fakePoster.postedConversations.isEmpty())

        val updated = ledger.find("rec-fg-suppress")!!
        assertEquals(HermesNotificationDisposition.SuppressedForeground, updated.notificationDisposition)
        assertEquals(160_000L, updated.notificationDispositionChangedAt)
        assertNull(updated.notificationNextAttemptAt)
        assertTrue(fakeScheduler.continueCalls.isEmpty())
    }

    @Test
    fun `permission loss before retry permanently suppresses`() = runTest {
        val committedTime = 100_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-perm-suppress",
            committedAt = committedTime,
            deadlineAt = committedTime + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = 160_000L,
            attemptCount = 1,
        )
        ledger.insert(entry)

        // Permission revoked before delivery
        fakeAdmission.disposition = HermesNotificationDisposition.SuppressedPermission

        clock.currentEpoch = 160_000L
        coordinator.deliver(conversationId)

        assertTrue(fakePoster.postedConversations.isEmpty())

        val updated = ledger.find("rec-perm-suppress")!!
        assertEquals(HermesNotificationDisposition.SuppressedPermission, updated.notificationDisposition)
        assertEquals(160_000L, updated.notificationDispositionChangedAt)
        assertNull(updated.notificationNextAttemptAt)
        assertTrue(fakeScheduler.continueCalls.isEmpty())
    }

    @Test
    fun `cancellation propagates without increment`() = runTest {
        val committedTime = 100_000L
        val entry = createPendingEntry(
            recoveryKey = "rec-cancel-prop",
            committedAt = committedTime,
            deadlineAt = committedTime + 15.minutes.inWholeMilliseconds,
            nextAttemptAt = committedTime,
            attemptCount = 0,
        )
        ledger.insert(entry)

        fakePoster.cancellationException = CancellationException("Worker scope cancelled")

        clock.currentEpoch = 100_000L
        try {
            coordinator.deliver(conversationId)
            fail("Expected CancellationException to propagate")
        } catch (expected: CancellationException) {
            assertEquals("Worker scope cancelled", expected.message)
        }

        // Ledger entry must NOT have incremented attempt count or changed disposition
        val unchanged = ledger.find("rec-cancel-prop")!!
        assertEquals(HermesNotificationDisposition.PendingPost, unchanged.notificationDisposition)
        assertEquals(0, unchanged.notificationAttemptCount)
        assertEquals(committedTime, unchanged.notificationNextAttemptAt)
    }

    @Test
    fun `crash-shaped duplicate post updates the same conversation ID and marks rows Posted`() = runTest {
        val entry = createPendingEntry(
            recoveryKey = "rec-dup-post",
            committedAt = 100_000L,
            nextAttemptAt = 100_000L,
            attemptCount = 0,
        )
        ledger.insert(entry)

        // First delivery invocation posts successfully
        coordinator.deliver(conversationId)
        assertEquals(listOf(conversationId), fakePoster.postedConversations)

        // Entry is now Posted
        assertEquals(HermesNotificationDisposition.Posted, ledger.find("rec-dup-post")!!.notificationDisposition)

        // Subsequent delivery invocation for same conversation finds no pending rows
        fakePoster.postedConversations.clear()
        coordinator.deliver(conversationId)
        assertTrue(fakePoster.postedConversations.isEmpty())
    }

    @Test
    fun `Posted schedules no work`() = runTest {
        val entry = createPendingEntry(
            recoveryKey = "rec-already-posted",
            committedAt = 100_000L,
        ).copy(
            notificationDisposition = HermesNotificationDisposition.Posted,
            notificationNextAttemptAt = null,
        )
        ledger.insert(entry)

        coordinator.deliver(conversationId)

        assertTrue(fakePoster.postedConversations.isEmpty())
        assertTrue(fakeScheduler.continueCalls.isEmpty())
        assertTrue(fakeScheduler.replaceCalls.isEmpty())
    }
}

private class FakeRecoveryClock(var currentEpoch: Long) : RecoveryClock {
    override fun epochMillis(): Long = currentEpoch
    override fun elapsedRealtimeMillis(): Long = currentEpoch
}

private class FakeNotificationPoster : HermesNotificationPoster {
    val postedConversations = mutableListOf<Uuid>()
    var shouldFail = false
    var cancellationException: CancellationException? = null

    override suspend fun post(conversationId: Uuid) {
        cancellationException?.let { throw it }
        if (shouldFail) {
            throw IllegalStateException("Simulated post failure")
        }
        postedConversations.add(conversationId)
    }
}

private class FakeNotificationAdmission(
    var disposition: HermesNotificationDisposition = HermesNotificationDisposition.PendingPost,
) : HermesNotificationAdmission {
    override fun decide(
        conversationId: Uuid,
        observation: TerminalObservationContext,
    ): HermesNotificationDisposition = disposition
}

private class FakeNotificationWorkScheduler : HermesNotificationWorkScheduler {
    data class WorkCall(val conversationId: Uuid, val delay: Duration)

    val replaceCalls = mutableListOf<WorkCall>()
    val continueCalls = mutableListOf<WorkCall>()
    val cancelCalls = mutableListOf<Uuid>()

    override suspend fun replaceForEarliestDue(conversationId: Uuid, delay: Duration) {
        replaceCalls.add(WorkCall(conversationId, delay))
    }

    override suspend fun continueAfterCurrent(conversationId: Uuid, delay: Duration) {
        continueCalls.add(WorkCall(conversationId, delay))
    }

    override suspend fun cancel(conversationId: Uuid) {
        cancelCalls.add(conversationId)
    }
}

private class FakeNotificationRecoveryDAODouble : HermesRecoveryDAO {
    private val storage = mutableMapOf<String, HermesRecoveryEntity>()

    override suspend fun find(key: String): HermesRecoveryEntity? = storage[key]

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

    override suspend fun markPostedSeen(conversationId: String, changedAt: Long): Int = 0

    override suspend fun deleteOrphans(): Int = 0
}
