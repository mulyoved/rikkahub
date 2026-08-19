package me.rerere.rikkahub.voiceagent.notification

import android.app.Notification
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryEntry
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryState
import me.rerere.rikkahub.voiceagent.recovery.RecoveryClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.unloadKoinModules
import org.koin.dsl.module
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Sole failure-control entry point for Hermes notification delivery under injected post failures.
 *
 * Requirements:
 * - Test-only Koin override installs [CountdownFailingPostGate(3)] and a mutable fake [RecoveryClock].
 * - The gate throws before notify(...) for exactly three matching conversation posts and records only attempt ordinals.
 * - Seeds one PendingPost receipt, advances fake clock to absolute terminal+1m and terminal+5m due times,
 *   and uses WorkManager's [TestDriver] to release each initial delay.
 * - Asserts attempt counts 1, 2, 3, exact durable next-attempt timestamps after first two failures,
 *   terminal SuppressedPostFailure after the third, and zero active notification records for the tag/ID.
 * - A second receipt advances directly to now >= deadline (15m), releases the worker, and proves
 *   the post gate was never entered and notify(...) was never called.
 */
@RunWith(AndroidJUnit4::class)
class HermesNotificationFailureAutomationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var ledger: HermesRecoveryLedger
    private lateinit var workManager: WorkManager
    private lateinit var testDriver: TestDriver
    private lateinit var scheduler: HermesNotificationWorkScheduler
    private lateinit var coordinator: HermesNotificationDeliveryCoordinator
    private lateinit var notifier: HermesResultNotifier

    private val countdownGate = CountdownFailingPostGate(3)
    private val mutableClock = MutableRecoveryClock(1_000_000L)
    private val postedNotifications = mutableMapOf<Pair<String, Int>, Notification>()

    private val testModule = module {
        single<HermesNotificationPostGate> { countdownGate }
        single<RecoveryClock> { mutableClock }
        single<HermesNotificationAdmission> {
            HermesNotificationAdmission { _, _ -> HermesNotificationDisposition.PendingPost }
        }
        single<HermesNotificationPoster> { notifier }
        single {
            HermesNotificationDeliveryCoordinator(
                ledger = ledger,
                scheduler = scheduler,
                poster = notifier,
                admission = get(),
                clock = mutableClock,
            )
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 1. Initialize in-memory test database
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledger = HermesRecoveryLedger(database.hermesRecoveryDao())

        // 2. Initialize WorkManager test driver
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!

        val testFactory = HermesNotificationWorkRequestFactory { spec ->
            hermesNotificationRequest<HermesNotificationPolicyWorker>(spec)
        }
        scheduler = WorkManagerHermesNotificationWorkScheduler(workManager, testFactory)

        // 3. Initialize notifier with countdown failing gate
        notifier = HermesResultNotifier(
            context = context,
            postGate = countdownGate,
            notificationBuilder = { id -> buildHermesResultNotification(context, id) },
            notifyAction = { tag, id, notification ->
                postedNotifications[tag to id] = notification
            },
            cancelAction = { tag, id ->
                postedNotifications.remove(tag to id)
            },
            activeNotificationsProvider = { null },
        )

        // 4. Initialize delivery coordinator with always-admitting admission and mutable fake clock
        coordinator = HermesNotificationDeliveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            poster = notifier,
            admission = { _, _ -> HermesNotificationDisposition.PendingPost },
            clock = mutableClock,
        )

        // 5. Install test-only Koin override
        runCatching { loadKoinModules(testModule) }
    }

    @After
    fun tearDown() {
        runCatching { unloadKoinModules(testModule) }
        database.close()
    }

    @Test
    fun injectedPostFailures_retrySchedule_andThirdFailureSuppression() = runBlocking {
        val testConvId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val t0 = 1_000_000L
        mutableClock.currentTime = t0
        val deadline = t0 + 15.minutes.inWholeMilliseconds

        // Insert parent conversation for foreign-key integrity
        database.conversationDao().insert(
            ConversationEntity(
                id = testConvId.toString(),
                assistantId = Uuid.random().toString(),
                title = "Test Failure Automation Conversation",
                nodes = "[]",
                createAt = t0,
                updateAt = t0,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )

        // Seed initial PendingPost receipt
        val initialEntry = HermesRecoveryEntry(
            recoveryKey = "rec-fail-test-1",
            conversationId = testConvId,
            callId = "call-fail-1",
            jobId = "job-fail-1",
            producer = "hermes",
            originalVoiceSessionHash = "session-hash-1",
            originalArgumentHash = "arg-hash-1",
            originalOwnerHash = "owner-hash-1",
            originalEndpointHash = "endpoint-hash-1",
            acceptedAt = t0,
            automaticDeadlineAt = t0 + 30.minutes.inWholeMilliseconds,
            recoveryState = HermesRecoveryState.Finished,
            terminalCommittedAt = t0,
            terminalDeadlineAt = deadline,
            notificationDisposition = HermesNotificationDisposition.PendingPost,
            notificationDispositionChangedAt = t0,
            notificationNextAttemptAt = t0,
            notificationAttemptCount = 0,
            lastAttemptAt = t0,
        )
        ledger.insert(initialEntry)

        val workName = hermesNotificationWorkName(testConvId)

        // === Attempt 1 (Immediate at t0) ===
        coordinator.deliver(testConvId)

        val afterAttempt1 = ledger.find("rec-fail-test-1")
        assertNotNull(afterAttempt1)
        assertEquals(1, afterAttempt1!!.notificationAttemptCount)
        assertEquals(HermesNotificationDisposition.PendingPost, afterAttempt1.notificationDisposition)
        val expectedNext1 = t0 + 1.minutes.inWholeMilliseconds // terminal + 1m
        assertEquals(expectedNext1, afterAttempt1.notificationNextAttemptAt)
        // Zero active notification records for tag/ID
        assertNull(postedNotifications[testConvId.toString() to HERMES_RESULT_NOTIFICATION_ID])
        assertEquals(listOf(1), countdownGate.attempts)

        // Verify WorkManager scheduled next attempt with delay
        val workInfos1 = workManager.getWorkInfosForUniqueWork(workName).get()
        assertFalse(workInfos1.isEmpty())
        val workId1 = workInfos1.first().id

        // === Attempt 2 (Advance clock to terminal + 1m and release delay) ===
        mutableClock.currentTime = expectedNext1
        testDriver.setInitialDelayMet(workId1)
        coordinator.deliver(testConvId)

        val afterAttempt2 = ledger.find("rec-fail-test-1")
        assertNotNull(afterAttempt2)
        assertEquals(2, afterAttempt2!!.notificationAttemptCount)
        assertEquals(HermesNotificationDisposition.PendingPost, afterAttempt2.notificationDisposition)
        val expectedNext2 = t0 + 5.minutes.inWholeMilliseconds // terminal + 5m
        assertEquals(expectedNext2, afterAttempt2.notificationNextAttemptAt)
        // Zero active notification records for tag/ID
        assertNull(postedNotifications[testConvId.toString() to HERMES_RESULT_NOTIFICATION_ID])
        assertEquals(listOf(1, 2), countdownGate.attempts)

        val workInfos2 = workManager.getWorkInfosForUniqueWork(workName).get()
        assertFalse(workInfos2.isEmpty())
        val workId2 = workInfos2.first().id

        // === Attempt 3 (Advance clock to terminal + 5m and release delay) ===
        mutableClock.currentTime = expectedNext2
        testDriver.setInitialDelayMet(workId2)
        coordinator.deliver(testConvId)

        val afterAttempt3 = ledger.find("rec-fail-test-1")
        assertNotNull(afterAttempt3)
        assertEquals(3, afterAttempt3!!.notificationAttemptCount)
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, afterAttempt3.notificationDisposition)
        assertNull(afterAttempt3.notificationNextAttemptAt)
        // Zero active notification records for tag/ID
        assertNull(postedNotifications[testConvId.toString() to HERMES_RESULT_NOTIFICATION_ID])
        assertEquals(listOf(1, 2, 3), countdownGate.attempts)
    }

    @Test
    fun delayedExecutionAfterDeadline_suppressesBeforePost_withoutEnteringPostGate() = runBlocking {
        val testConvId2 = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val t1 = 5_000_000L
        val deadline = t1 + 15.minutes.inWholeMilliseconds

        // Insert parent conversation
        database.conversationDao().insert(
            ConversationEntity(
                id = testConvId2.toString(),
                assistantId = Uuid.random().toString(),
                title = "Test Deadline Suppression Conversation",
                nodes = "[]",
                createAt = t1,
                updateAt = t1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )

        // Seed second receipt
        val receipt2 = HermesRecoveryEntry(
            recoveryKey = "rec-fail-test-2",
            conversationId = testConvId2,
            callId = "call-fail-2",
            jobId = "job-fail-2",
            producer = "hermes",
            originalVoiceSessionHash = "session-hash-2",
            originalArgumentHash = "arg-hash-2",
            originalOwnerHash = "owner-hash-2",
            originalEndpointHash = "endpoint-hash-2",
            acceptedAt = t1,
            automaticDeadlineAt = t1 + 30.minutes.inWholeMilliseconds,
            recoveryState = HermesRecoveryState.Finished,
            terminalCommittedAt = t1,
            terminalDeadlineAt = deadline,
            notificationDisposition = HermesNotificationDisposition.PendingPost,
            notificationDispositionChangedAt = t1,
            notificationNextAttemptAt = t1,
            notificationAttemptCount = 0,
            lastAttemptAt = t1,
        )
        ledger.insert(receipt2)

        val previousAttemptsCount = countdownGate.attempts.size

        // Advance clock directly to now >= deadline (15m elapsed)
        mutableClock.currentTime = deadline + 1000L

        // Release worker / invoke coordinator delivery
        coordinator.deliver(testConvId2)

        val afterDeadlineDelivery = ledger.find("rec-fail-test-2")
        assertNotNull(afterDeadlineDelivery)
        assertEquals(HermesNotificationDisposition.SuppressedPostFailure, afterDeadlineDelivery!!.notificationDisposition)
        assertNull(afterDeadlineDelivery.notificationNextAttemptAt)

        // Assert post gate was NEVER entered and notify(...) was NEVER called
        assertEquals(
            "Post gate must not be entered for delivery at/after deadline",
            previousAttemptsCount,
            countdownGate.attempts.size,
        )
        assertNull(postedNotifications[testConvId2.toString() to HERMES_RESULT_NOTIFICATION_ID])
    }
}

class CountdownFailingPostGate(
    private var countdown: Int = 3,
) : HermesNotificationPostGate {
    val attempts = mutableListOf<Int>()

    override fun beforePost(conversationId: Uuid) {
        val ordinal = attempts.size + 1
        attempts.add(ordinal)
        if (countdown > 0) {
            countdown--
            throw IllegalStateException("CountdownFailingPostGate injected failure for attempt $ordinal")
        }
    }
}

class MutableRecoveryClock(
    var currentTime: Long = 0L,
) : RecoveryClock {
    override fun epochMillis(): Long = currentTime
    override fun elapsedRealtimeMillis(): Long = currentTime
}
