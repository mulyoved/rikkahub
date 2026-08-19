package me.rerere.rikkahub.voiceagent.recovery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HermesRecoveryPolicyWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = Result.success()
}
