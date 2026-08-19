package me.rerere.rikkahub.voiceagent.notification

import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import me.rerere.rikkahub.voiceagent.recovery.recoverySha256
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.uuid.Uuid

internal const val INPUT_CONVERSATION_ID = "conversationId"

internal data class HermesNotificationWorkSpec(
    val conversationId: Uuid,
    val delay: Duration,
)

internal fun interface HermesNotificationWorkRequestFactory {
    fun create(spec: HermesNotificationWorkSpec): OneTimeWorkRequest
}

internal fun hermesNotificationWorkName(conversationId: Uuid): String =
    "hermes-notification:${recoverySha256(conversationId.toString())}"

internal inline fun <reified W : ListenableWorker> hermesNotificationRequest(
    spec: HermesNotificationWorkSpec,
): OneTimeWorkRequest = OneTimeWorkRequestBuilder<W>()
    .setInputData(workDataOf(INPUT_CONVERSATION_ID to spec.conversationId.toString()))
    .setInitialDelay(spec.delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .build()

internal interface HermesNotificationWorkScheduler {
    suspend fun replaceForEarliestDue(conversationId: Uuid, delay: Duration = Duration.ZERO)
    suspend fun continueAfterCurrent(conversationId: Uuid, delay: Duration)
    suspend fun cancel(conversationId: Uuid)
}

internal class WorkManagerHermesNotificationWorkScheduler(
    private val workManager: WorkManager,
    private val requestFactory: HermesNotificationWorkRequestFactory,
) : HermesNotificationWorkScheduler {

    override suspend fun replaceForEarliestDue(conversationId: Uuid, delay: Duration) {
        val name = hermesNotificationWorkName(conversationId)
        val request = requestFactory.create(HermesNotificationWorkSpec(conversationId, delay))
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request).await()
    }

    override suspend fun continueAfterCurrent(conversationId: Uuid, delay: Duration) {
        val name = hermesNotificationWorkName(conversationId)
        val request = requestFactory.create(HermesNotificationWorkSpec(conversationId, delay))
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }

    override suspend fun cancel(conversationId: Uuid) {
        val name = hermesNotificationWorkName(conversationId)
        workManager.cancelUniqueWork(name).await()
    }
}
