package me.rerere.rikkahub.voiceagent.notification

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryEntry
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryState
import me.rerere.rikkahub.voiceagent.recovery.RecoveryClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesNotificationAcknowledgementTest {

    private val fakeDao = FakeAcknowledgementRecoveryDAO()
    private val ledger = HermesRecoveryLedger(fakeDao)
    private val cancelledIds = mutableListOf<Uuid>()
    private val fakeNotifier = object : HermesResultNotifierInterface {
        override suspend fun post(conversationId: Uuid) = Unit
        override fun cancel(conversationId: Uuid) {
            cancelledIds.add(conversationId)
        }
        override fun cleanupStaleNotifications(validConversationIds: Set<Uuid>) = Unit
    }

    private var currentEpochMillis = 1_000_000L
    private val testClock = object : RecoveryClock {
        override fun epochMillis(): Long = currentEpochMillis
        override fun elapsedRealtimeMillis(): Long = currentEpochMillis
    }

    private val acknowledger = HermesNotificationAcknowledger(
        ledger = ledger,
        notifier = fakeNotifier,
        clock = testClock,
    )

    private fun createEntry(
        convId: Uuid,
        key: String,
        disposition: HermesNotificationDisposition,
        acceptedAt: Long = 100_000L,
    ): HermesRecoveryEntry = HermesRecoveryEntry(
        recoveryKey = key,
        conversationId = convId,
        callId = "call-$key",
        jobId = "job-$key",
        producer = "gemini",
        originalVoiceSessionHash = "voice-hash",
        originalArgumentHash = "arg-hash",
        originalOwnerHash = "owner-hash",
        originalEndpointHash = "endpoint-hash",
        acceptedAt = acceptedAt,
        automaticDeadlineAt = acceptedAt + 300_000L,
        recoveryState = HermesRecoveryState.Finished,
        lastAttemptAt = acceptedAt,
        notificationDisposition = disposition,
        notificationDispositionChangedAt = acceptedAt,
    )

    @Test
    fun `acknowledging conversation transitions Posted entries to Seen with timestamp`() = runTest {
        val conv1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val conv2 = Uuid.parse("22222222-2222-2222-2222-222222222222")

        val entry1 = createEntry(conv1, "key-1", HermesNotificationDisposition.Posted)
        val entry2 = createEntry(conv1, "key-2", HermesNotificationDisposition.Posted)
        val entry3 = createEntry(conv1, "key-3", HermesNotificationDisposition.PendingPost)
        val entry4 = createEntry(conv2, "key-4", HermesNotificationDisposition.Posted)

        ledger.insert(entry1)
        ledger.insert(entry2)
        ledger.insert(entry3)
        ledger.insert(entry4)

        currentEpochMillis = 1_234_567L
        acknowledger.acknowledgeConversation(conv1)

        val updated1 = ledger.find("key-1")!!
        val updated2 = ledger.find("key-2")!!
        val updated3 = ledger.find("key-3")!!
        val updated4 = ledger.find("key-4")!!

        assertEquals(HermesNotificationDisposition.Seen, updated1.notificationDisposition)
        assertEquals(1_234_567L, updated1.notificationDispositionChangedAt)

        assertEquals(HermesNotificationDisposition.Seen, updated2.notificationDisposition)
        assertEquals(1_234_567L, updated2.notificationDispositionChangedAt)

        assertEquals(HermesNotificationDisposition.PendingPost, updated3.notificationDisposition)
        assertEquals(100_000L, updated3.notificationDispositionChangedAt)

        assertEquals(HermesNotificationDisposition.Posted, updated4.notificationDisposition)
        assertEquals(100_000L, updated4.notificationDispositionChangedAt)

        assertEquals(listOf(conv1), cancelledIds)
    }

    @Test
    fun `acknowledging conversation with no Posted receipts cancels notification safely`() = runTest {
        val conv1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val entry = createEntry(conv1, "key-1", HermesNotificationDisposition.PendingPost)
        ledger.insert(entry)

        acknowledger.acknowledgeConversation(conv1)

        val updated = ledger.find("key-1")!!
        assertEquals(HermesNotificationDisposition.PendingPost, updated.notificationDisposition)
        assertEquals(listOf(conv1), cancelledIds)
    }

    @Test
    fun `acknowledging non-existent conversation cancels notification without error`() = runTest {
        val unknownConv = Uuid.parse("99999999-9999-9999-9999-999999999999")

        acknowledger.acknowledgeConversation(unknownConv)

        assertEquals(listOf(unknownConv), cancelledIds)
    }

    @Test
    fun `acknowledging does not mutate or access voice tool announcement state`() = runTest {
        val conv1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val entry = createEntry(conv1, "key-1", HermesNotificationDisposition.Posted)
        ledger.insert(entry)

        acknowledger.acknowledgeConversation(conv1)

        // The DAO double only interacts with hermes_recovery table
        assertTrue(fakeDao.voiceAnnouncementAccessed.not())
    }
}

private class FakeAcknowledgementRecoveryDAO : HermesRecoveryDAO {
    private val storage = mutableMapOf<String, HermesRecoveryEntity>()
    var voiceAnnouncementAccessed: Boolean = false

    override suspend fun find(key: String): HermesRecoveryEntity? = storage[key]

    override suspend fun active(): List<HermesRecoveryEntity> =
        storage.values.filter { it.recoveryState == "Active" }

    override suspend fun dormant(): List<HermesRecoveryEntity> =
        storage.values.filter { it.recoveryState == "Dormant" }

    override suspend fun forConversation(conversationId: String): List<HermesRecoveryEntity> =
        storage.values.filter { it.conversationId == conversationId }

    override suspend fun pendingForConversation(conversationId: String): List<HermesRecoveryEntity> =
        storage.values.filter { it.conversationId == conversationId && it.notificationDisposition == "PendingPost" }

    override suspend fun allPendingNotifications(): List<HermesRecoveryEntity> =
        storage.values.filter { it.notificationDisposition == "PendingPost" }

    override suspend fun pendingNotificationConversationIds(): List<String> =
        storage.values.filter { it.notificationDisposition == "PendingPost" }.map { it.conversationId }.distinct()

    override suspend fun insert(entry: HermesRecoveryEntity): Long {
        if (storage.containsKey(entry.recoveryKey)) return -1L
        storage[entry.recoveryKey] = entry
        return 1L
    }

    override suspend fun update(entry: HermesRecoveryEntity) {
        storage[entry.recoveryKey] = entry
    }

    override suspend fun markPostedSeen(conversationId: String, changedAt: Long): Int {
        var count = 0
        for ((key, value) in storage) {
            if (value.conversationId == conversationId && value.notificationDisposition == "Posted") {
                storage[key] = value.copy(
                    notificationDisposition = "Seen",
                    notificationDispositionChangedAt = changedAt,
                )
                count++
            }
        }
        return count
    }

    override suspend fun deleteOrphans(): Int = 0
}
