package me.rerere.rikkahub.voiceagent.recovery

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal const val INPUT_RECOVERY_KEY = "recoveryKey"

internal data class HermesRecoveryWorkSpec(
    val recoveryKey: String,
    val delay: Duration,
)

internal fun interface HermesRecoveryWorkRequestFactory {
    fun create(spec: HermesRecoveryWorkSpec): OneTimeWorkRequest
}

internal fun recoveryWorkName(recoveryKey: String) = "hermes-recovery:$recoveryKey"

internal inline fun <reified W : ListenableWorker> hermesRecoveryRequest(
    spec: HermesRecoveryWorkSpec,
): OneTimeWorkRequest = OneTimeWorkRequestBuilder<W>()
    .setInputData(workDataOf(INPUT_RECOVERY_KEY to spec.recoveryKey))
    .setInitialDelay(spec.delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
    .build()
