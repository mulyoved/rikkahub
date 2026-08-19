package me.rerere.rikkahub.voiceagent.recovery

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal open class HermesRelayRegistry(
    private val clock: RecoveryClock = SystemRecoveryClock,
    private val leaseDuration: Duration = DEFAULT_LEASE_DURATION,
) {
    companion object {
        val DEFAULT_LEASE_DURATION: Duration = 30.seconds
    }

    private val leases = mutableMapOf<String, Long>()
    private val lock = Any()

    open fun acquire(recoveryKey: String, duration: Duration = leaseDuration) {
        val expiry = clock.elapsedRealtimeMillis() + duration.inWholeMilliseconds
        synchronized(lock) {
            leases[recoveryKey] = expiry
        }
    }

    open fun renew(recoveryKey: String, duration: Duration = leaseDuration) {
        acquire(recoveryKey, duration)
    }

    open fun remainingLease(recoveryKey: String): Duration? {
        val now = clock.elapsedRealtimeMillis()
        val expiry = synchronized(lock) {
            leases[recoveryKey]
        } ?: return null

        val remainingMillis = expiry - now
        return if (remainingMillis > 0) {
            remainingMillis.milliseconds
        } else {
            synchronized(lock) {
                leases.remove(recoveryKey)
            }
            null
        }
    }

    open fun isLeaseActive(recoveryKey: String): Boolean {
        return remainingLease(recoveryKey) != null
    }

    open fun invalidate(recoveryKey: String) {
        synchronized(lock) {
            leases.remove(recoveryKey)
        }
    }

    open fun invalidateAll() {
        synchronized(lock) {
            leases.clear()
        }
    }
}
