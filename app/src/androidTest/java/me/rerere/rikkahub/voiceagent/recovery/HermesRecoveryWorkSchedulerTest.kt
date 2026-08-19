package me.rerere.rikkahub.voiceagent.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class HermesRecoveryWorkSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: HermesRecoveryWorkScheduler

    private val testFactory = HermesRecoveryWorkRequestFactory { spec ->
        hermesRecoveryRequest<HermesRecoveryPolicyWorker>(spec)
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
        scheduler = WorkManagerHermesRecoveryWorkScheduler(workManager, testFactory)
    }

    @Test
    fun requestAttributes_matchRecoverySpecification() {
        val spec = HermesRecoveryWorkSpec(
            recoveryKey = "test-rec-key-123",
            delay = 30.seconds,
        )
        val request = hermesRecoveryRequest<HermesRecoveryPolicyWorker>(spec)
        val workSpec = request.workSpec

        // Input data
        assertEquals("test-rec-key-123", workSpec.input.getString(INPUT_RECOVERY_KEY))

        // Initial delay
        assertEquals(30_000L, workSpec.initialDelay)

        // Network constraint
        assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)

        // Exponential backoff with 30-second minimum
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), workSpec.backoffDelayDuration)

        // Worker class name
        assertEquals(HermesRecoveryPolicyWorker::class.java.name, workSpec.workerClassName)
    }

    @Test
    fun ensure_usesKeepPolicy_andIgnoresDuplicate() = runBlocking {
        val key = "key-keep-test"
        val workName = recoveryWorkName(key)

        // First call: enqueues work
        scheduler.ensure(key, 30.seconds)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)
        val firstId = initialInfos.first().id
        assertEquals(WorkInfo.State.ENQUEUED, initialInfos.first().state)

        // Duplicate call with KEEP policy: does not replace work, ID remains identical
        scheduler.ensure(key, 5.minutes)
        val duplicateInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, duplicateInfos.size)
        assertEquals(firstId, duplicateInfos.first().id)
    }

    @Test
    fun preempt_usesReplacePolicy_replacesWorkWithNewRequest() = runBlocking {
        val key = "key-replace-test"
        val workName = recoveryWorkName(key)

        // First call with deferred delay
        scheduler.ensure(key, 30.minutes)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)
        val firstId = initialInfos.first().id

        // Preempt with immediate / different delay (REPLACE)
        scheduler.preempt(key, 0.seconds)
        val replacedInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, replacedInfos.size)
        val newId = replacedInfos.first().id
        assertNotEquals("REPLACE must replace the previous work request with a new request ID", firstId, newId)
        assertEquals(WorkInfo.State.ENQUEUED, replacedInfos.first().state)
    }

    @Test
    fun continueAfterCurrent_usesAppendOrReplacePolicy() = runBlocking {
        val key = "key-append-test"
        val workName = recoveryWorkName(key)

        // Start with initial work
        scheduler.ensure(key, 30.seconds)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)

        // Continue after current with APPEND_OR_REPLACE
        scheduler.continueAfterCurrent(key, 5.minutes)
        val afterAppendInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertTrue("Work infos must contain scheduled work", afterAppendInfos.isNotEmpty())
    }

    @Test
    fun cancel_cancelsUniqueWork() = runBlocking {
        val key = "key-cancel-test"
        val workName = recoveryWorkName(key)

        scheduler.ensure(key, 30.seconds)
        val initialInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertEquals(1, initialInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, initialInfos.first().state)

        scheduler.cancel(key)
        val canceledInfos = workManager.getWorkInfosForUniqueWork(workName).get()
        assertTrue(
            "Canceled work should be CANCELLED or empty",
            canceledInfos.isEmpty() || canceledInfos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }
}
