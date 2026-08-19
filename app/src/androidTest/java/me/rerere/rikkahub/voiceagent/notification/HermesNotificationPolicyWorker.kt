package me.rerere.rikkahub.voiceagent.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HermesNotificationPolicyWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = Result.success()
}
