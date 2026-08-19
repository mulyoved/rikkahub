package me.rerere.rikkahub.voiceagent.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

internal class HermesRecoveryWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val coordinator: HermesRecoveryCoordinator,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(INPUT_RECOVERY_KEY) ?: return Result.failure()
        return when (coordinator.reconcile(key, RecoveryTrigger.Scheduled)) {
            RecoveryOutcome.Success -> Result.success()
            RecoveryOutcome.Retry -> Result.retry()
        }
    }
}
