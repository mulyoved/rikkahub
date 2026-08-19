package me.rerere.rikkahub.voiceagent.recovery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class HermesRecoveryWorkerTest {

    @Test
    fun `doWork returns failure when recoveryKey is missing`() = runTest {
        val coordinator = mockk<HermesRecoveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf()

        val worker = HermesRecoveryWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork maps RecoveryOutcome Success to Result success`() = runTest {
        val coordinator = mockk<HermesRecoveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val recoveryKey = "rec-key-success"
        every { params.inputData } returns workDataOf(INPUT_RECOVERY_KEY to recoveryKey)
        coEvery { coordinator.reconcile(recoveryKey, RecoveryTrigger.Scheduled) } returns RecoveryOutcome.Success

        val worker = HermesRecoveryWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork maps RecoveryOutcome Retry to Result retry`() = runTest {
        val coordinator = mockk<HermesRecoveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        val recoveryKey = "rec-key-retry"
        every { params.inputData } returns workDataOf(INPUT_RECOVERY_KEY to recoveryKey)
        coEvery { coordinator.reconcile(recoveryKey, RecoveryTrigger.Scheduled) } returns RecoveryOutcome.Retry

        val worker = HermesRecoveryWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `production request factory binds to HermesRecoveryWorker with exact attributes`() {
        val factory = HermesRecoveryWorkRequestFactory { spec ->
            hermesRecoveryRequest<HermesRecoveryWorker>(spec)
        }
        val spec = HermesRecoveryWorkSpec(
            recoveryKey = "test-prod-key",
            delay = 30.seconds,
        )
        val request = factory.create(spec)
        val workSpec = request.workSpec

        assertEquals("test-prod-key", workSpec.input.getString(INPUT_RECOVERY_KEY))
        assertEquals(30_000L, workSpec.initialDelay)
        assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, workSpec.backoffPolicy)
        assertEquals(TimeUnit.SECONDS.toMillis(30), workSpec.backoffDelayDuration)
        assertEquals(HermesRecoveryWorker::class.java.name, workSpec.workerClassName)
    }
}
