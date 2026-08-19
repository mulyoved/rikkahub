package me.rerere.rikkahub.voiceagent.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.uuid.Uuid

internal class HermesNotificationWorker(
    context: Context,
    workerParams: WorkerParameters,
    private val coordinator: HermesNotificationDeliveryCoordinator,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val conversationIdStr = inputData.getString(INPUT_CONVERSATION_ID) ?: return Result.failure()
        val conversationId = try {
            Uuid.parse(conversationIdStr)
        } catch (e: Exception) {
            return Result.failure()
        }
        coordinator.deliver(conversationId)
        return Result.success()
    }
}
