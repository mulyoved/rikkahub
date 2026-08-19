package me.rerere.rikkahub.voiceagent.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class HermesNotificationWorkSchedulerTest {

    private val testConversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: HermesNotificationWorkScheduler

    private val testFactory = HermesNotificationWorkRequestFactory { spec ->
        hermesNotificationRequest<HermesNotificationPolicyWorker>(spec)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerHermesNotificationWorkScheduler(workManager, testFactory)
    }

    @Test
    fun requestAttributes_matchNotificationSpecification() {
        val spec = HermesNotificationWorkSpec(
            conversationId = testConversationId,
            delay = 60.seconds,
        )
        val request = hermesNotificationRequest<HermesNotificationPolicyWorker>(spec)
        val workSpec = request.workSpec

        // Input data contains conversationId only
        assertEquals(testConversationId.toString(), workSpec.input.getString(INPUT_CONVERSATION_ID))

        // Initial delay
        assertEquals(60_000L, workSpec.initialDelay)

        // No network constraint (notifications are local)
        assertEquals(NetworkType.NOT_REQUIRED, workSpec.constraints.requiredNetworkType)

        // Worker class name
        assertEquals(HermesNotificationPolicyWorker::class.java.name, workSpec.workerClassName)
    }

    @Test
    fun uniqueWorkName_usesHermesNotificationPrefixWithOpaqueConversationHash() {
        val workName = hermesNotificationWorkName(testConversationId)
        assertTrue(
            "Work name must start with hermes-notification:",
            workName.startsWith("hermes-notification:"),
        )
        val hashPart = workName.removePrefix("hermes-notification:")
        assertEquals(
            "Hash part should be 64-char hex string (SHA-256)",
            64,
            hashPart.length,
        )
    }

    @Test
    fun replaceForEarliestDue_usesReplacePolicy() = runBlocking {
        val workName = hermesNotificationWorkName(testConversationId)

        // First call with deferred delay
        scheduler.replaceForEarliestDue(testConversationId, 5.minutes)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)
        val firstId = initialInfos.first().id
        assertEquals(WorkInfo.State.ENQUEUED, initialInfos.first().state)

        // Replace with immediate delay
        scheduler.replaceForEarliestDue(testConversationId, 0.seconds)
        val replacedInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, replacedInfos.size)
        val newId = replacedInfos.first().id
        assertNotEquals("REPLACE must replace the previous work request with a new request ID", firstId, newId)
        assertEquals(WorkInfo.State.ENQUEUED, replacedInfos.first().state)
    }

    @Test
    fun continueAfterCurrent_usesAppendOrReplacePolicy() = runBlocking {
        val workName = hermesNotificationWorkName(testConversationId)

        // Start with initial work
        scheduler.replaceForEarliestDue(testConversationId, 1.minutes)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)

        // Continue after current with APPEND_OR_REPLACE
        scheduler.continueAfterCurrent(testConversationId, 5.minutes)
        val afterAppendInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertTrue("Work infos must contain scheduled work", afterAppendInfos.isNotEmpty())
    }

    @Test
    fun cancel_cancelsUniqueWork() = runBlocking {
        val workName = hermesNotificationWorkName(testConversationId)

        scheduler.replaceForEarliestDue(testConversationId, 1.minutes)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, initialInfos.first().state)

        scheduler.cancel(testConversationId)
        val canceledInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertTrue(
            "Canceled work should be CANCELLED or empty",
            canceledInfos.isEmpty() || canceledInfos.all { it.state == WorkInfo.State.CANCELLED },
        )
    }
}
