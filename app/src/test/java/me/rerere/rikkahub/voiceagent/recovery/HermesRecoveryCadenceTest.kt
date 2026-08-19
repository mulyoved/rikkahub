package me.rerere.rikkahub.voiceagent.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HermesRecoveryCadenceTest {

    @Test
    fun `cadence boundaries map to exact specified delays`() {
        // 9:59.999 -> 30s (Tier 1: < 10m)
        val age9m59s999ms = 9.minutes + 59.seconds + 999.milliseconds
        assertEquals(30.seconds, HermesRecoveryCadence.nextDelay(age9m59s999ms))

        // 10:00 -> 5m (Tier 2: >= 10m and < 1h)
        val age10m = 10.minutes
        assertEquals(5.minutes, HermesRecoveryCadence.nextDelay(age10m))

        // 59:59.999 -> 5m (Tier 2: >= 10m and < 1h)
        val age59m59s999ms = 59.minutes + 59.seconds + 999.milliseconds
        assertEquals(5.minutes, HermesRecoveryCadence.nextDelay(age59m59s999ms))

        // 1:00:00 -> 30m (Tier 3: >= 1h and < 24h)
        val age1h = 1.hours
        assertEquals(30.minutes, HermesRecoveryCadence.nextDelay(age1h))

        // 24:00:00 -> null (Window elapsed)
        val age24h = 24.hours
        assertNull(HermesRecoveryCadence.nextDelay(age24h))
    }

    @Test
    fun `edge cases and intermediate points map correctly`() {
        // 0s -> 30s
        assertEquals(30.seconds, HermesRecoveryCadence.nextDelay(Duration.ZERO))

        // Negative age (clock drift) -> 30s
        assertEquals(30.seconds, HermesRecoveryCadence.nextDelay((-5).seconds))

        // 23:59:59.999 -> 30m
        val age23h59m59s999ms = 23.hours + 59.minutes + 59.seconds + 999.milliseconds
        assertEquals(30.minutes, HermesRecoveryCadence.nextDelay(age23h59m59s999ms))

        // 24:00:00.001 -> null
        val age24h1ms = 24.hours + 1.milliseconds
        assertNull(HermesRecoveryCadence.nextDelay(age24h1ms))

        // 48h -> null
        assertEquals(null, HermesRecoveryCadence.nextDelay(48.hours))
    }

    @Test
    fun `overloaded epoch calculation behaves identically`() {
        val acceptedAt = 1_000_000L

        // Tier 1
        assertEquals(30.seconds, HermesRecoveryCadence.nextDelay(acceptedAt, acceptedAt + 599_999L))
        // Tier 2 boundary
        assertEquals(5.minutes, HermesRecoveryCadence.nextDelay(acceptedAt, acceptedAt + 600_000L))
        // Tier 2 upper
        assertEquals(5.minutes, HermesRecoveryCadence.nextDelay(acceptedAt, acceptedAt + 3_599_999L))
        // Tier 3 boundary
        assertEquals(30.minutes, HermesRecoveryCadence.nextDelay(acceptedAt, acceptedAt + 3_600_000L))
        // 24h boundary
        assertNull(HermesRecoveryCadence.nextDelay(acceptedAt, acceptedAt + 86_400_000L))
    }

    @Test
    fun `stepping through cadences produces exactly 76 scheduled active polls`() {
        var age = Duration.ZERO
        var tier1Count = 0
        var tier2Count = 0
        var tier3Count = 0
        var totalCount = 0

        while (true) {
            val delay = HermesRecoveryCadence.nextDelay(age) ?: break
            when (delay) {
                30.seconds -> {
                    assertTrue("Tier 1 poll must be before 10m boundary", age < 10.minutes)
                    tier1Count++
                }
                5.minutes -> {
                    assertTrue("Tier 2 poll must be between 10m and 1h", age >= 10.minutes && age < 1.hours)
                    tier2Count++
                }
                30.minutes -> {
                    assertTrue("Tier 3 poll must be between 1h and 24h", age >= 1.hours && age < 24.hours)
                    tier3Count++
                }
                else -> throw AssertionError("Unexpected delay: $delay")
            }
            totalCount++
            age += delay
        }

        assertEquals("Tier 1 must have exactly 20 polls", 20, tier1Count)
        assertEquals("Tier 2 must have exactly 10 polls", 10, tier2Count)
        assertEquals("Tier 3 must have exactly 46 polls", 46, tier3Count)
        assertEquals("Total scheduled active polls across tiers must be exactly 76 (20 + 10 + 46)", 76, totalCount)
        assertEquals("Final age at termination must be exactly 24 hours", 24.hours, age)
    }
}
