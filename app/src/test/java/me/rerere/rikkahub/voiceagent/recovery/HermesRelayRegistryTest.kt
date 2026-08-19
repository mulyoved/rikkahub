package me.rerere.rikkahub.voiceagent.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HermesRelayRegistryTest {

    private class FakeRecoveryClock(
        var currentEpochMillis: Long = 1_000_000L,
        var currentElapsedRealtimeMillis: Long = 10_000L,
    ) : RecoveryClock {
        override fun epochMillis(): Long = currentEpochMillis
        override fun elapsedRealtimeMillis(): Long = currentElapsedRealtimeMillis
    }

    @Test
    fun `acquire extends lease to elapsedRealtime plus 30s`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-1")

        assertTrue(registry.isLeaseActive("key-1"))
        assertEquals(30.seconds, registry.remainingLease("key-1"))

        // Advance 10s -> 20s remaining
        clock.currentElapsedRealtimeMillis = 20_000L
        assertTrue(registry.isLeaseActive("key-1"))
        assertEquals(20.seconds, registry.remainingLease("key-1"))
    }

    @Test
    fun `renew extends lease to elapsedRealtime plus 30s from renewal time`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-1")

        // Advance 25s -> only 5s remaining
        clock.currentElapsedRealtimeMillis = 35_000L
        assertEquals(5.seconds, registry.remainingLease("key-1"))

        // Renew at 35_000L -> expires at 65_000L
        registry.renew("key-1")

        assertEquals(30.seconds, registry.remainingLease("key-1"))

        // Advance 15s to 50_000L -> 15s remaining
        clock.currentElapsedRealtimeMillis = 50_000L
        assertEquals(15.seconds, registry.remainingLease("key-1"))
    }

    @Test
    fun `call-end invalidation is immediate`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-1")
        registry.acquire("key-2")

        assertTrue(registry.isLeaseActive("key-1"))
        assertTrue(registry.isLeaseActive("key-2"))

        // Single key invalidation
        registry.invalidate("key-1")
        assertFalse(registry.isLeaseActive("key-1"))
        assertNull(registry.remainingLease("key-1"))
        assertTrue(registry.isLeaseActive("key-2"))

        // Invalidate all
        registry.invalidateAll()
        assertFalse(registry.isLeaseActive("key-2"))
        assertNull(registry.remainingLease("key-2"))
    }

    @Test
    fun `process recreation has no lease`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val process1Registry = HermesRelayRegistry(clock)
        process1Registry.acquire("key-1")
        assertTrue(process1Registry.isLeaseActive("key-1"))

        // Simulate process death / restart: new instance with the same clock
        val process2Registry = HermesRelayRegistry(clock)
        assertFalse(process2Registry.isLeaseActive("key-1"))
        assertNull(process2Registry.remainingLease("key-1"))
    }

    @Test
    fun `delayed observation returns exact remaining delay without using retry or backoff`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-1")

        // Advance by arbitrary 12,345 ms
        clock.currentElapsedRealtimeMillis = 22_345L
        val remaining = registry.remainingLease("key-1")
        assertNotNull(remaining)
        assertEquals((30_000L - 12_345L).milliseconds, remaining)
        assertEquals(17_655.milliseconds, remaining)

        // Advance exactly to 30s elapsed (40_000L) -> lease is expired
        clock.currentElapsedRealtimeMillis = 40_000L
        assertNull(registry.remainingLease("key-1"))
        assertFalse(registry.isLeaseActive("key-1"))

        // Advance past 30s
        clock.currentElapsedRealtimeMillis = 40_001L
        assertNull(registry.remainingLease("key-1"))
        assertFalse(registry.isLeaseActive("key-1"))
    }

    @Test
    fun `custom duration lease works properly`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-custom", duration = 10.seconds)
        assertEquals(10.seconds, registry.remainingLease("key-custom"))

        clock.currentElapsedRealtimeMillis = 16_000L
        assertEquals(4.seconds, registry.remainingLease("key-custom"))

        clock.currentElapsedRealtimeMillis = 20_001L
        assertNull(registry.remainingLease("key-custom"))
    }

    @Test
    fun `multiple keys operate independently`() {
        val clock = FakeRecoveryClock(currentElapsedRealtimeMillis = 10_000L)
        val registry = HermesRelayRegistry(clock)

        registry.acquire("key-A")
        clock.currentElapsedRealtimeMillis = 20_000L
        registry.acquire("key-B")

        assertEquals(20.seconds, registry.remainingLease("key-A"))
        assertEquals(30.seconds, registry.remainingLease("key-B"))

        clock.currentElapsedRealtimeMillis = 40_000L
        assertNull(registry.remainingLease("key-A"))
        assertEquals(10.seconds, registry.remainingLease("key-B"))
    }
}
