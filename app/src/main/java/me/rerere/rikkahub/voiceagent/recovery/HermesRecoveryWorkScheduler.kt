package me.rerere.rikkahub.voiceagent.recovery

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
import kotlin.time.Duration

internal interface HermesRecoveryWorkScheduler {
    suspend fun ensure(recoveryKey: String, delay: Duration)
    suspend fun preempt(recoveryKey: String, delay: Duration = Duration.ZERO)
    suspend fun continueAfterCurrent(recoveryKey: String, delay: Duration)
    suspend fun cancel(recoveryKey: String)
}

internal interface RecoveryClock {
    fun epochMillis(): Long
    fun elapsedRealtimeMillis(): Long
}

internal object SystemRecoveryClock : RecoveryClock {
    override fun epochMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

internal class WorkManagerHermesRecoveryWorkScheduler(
    private val workManager: WorkManager,
    private val requestFactory: HermesRecoveryWorkRequestFactory,
) : HermesRecoveryWorkScheduler {

    override suspend fun ensure(recoveryKey: String, delay: Duration) {
        val name = recoveryWorkName(recoveryKey)
        val request = requestFactory.create(HermesRecoveryWorkSpec(recoveryKey, delay))
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).await()
    }

    override suspend fun preempt(recoveryKey: String, delay: Duration) {
        val name = recoveryWorkName(recoveryKey)
        val request = requestFactory.create(HermesRecoveryWorkSpec(recoveryKey, delay))
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request).await()
    }

    override suspend fun continueAfterCurrent(recoveryKey: String, delay: Duration) {
        val name = recoveryWorkName(recoveryKey)
        val request = requestFactory.create(HermesRecoveryWorkSpec(recoveryKey, delay))
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
    }

    override suspend fun cancel(recoveryKey: String) {
        val name = recoveryWorkName(recoveryKey)
        workManager.cancelUniqueWork(name).await()
    }
}
