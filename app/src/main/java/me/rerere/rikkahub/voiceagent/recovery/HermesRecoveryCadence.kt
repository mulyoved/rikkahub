package me.rerere.rikkahub.voiceagent.recovery

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal object HermesRecoveryCadence {
    val TIER_1_INTERVAL: Duration = 30.seconds
    val TIER_1_BOUNDARY: Duration = 10.minutes

    val TIER_2_INTERVAL: Duration = 5.minutes
    val TIER_2_BOUNDARY: Duration = 1.hours

    val TIER_3_INTERVAL: Duration = 30.minutes
    val TIER_3_BOUNDARY: Duration = 24.hours

    fun nextDelay(age: Duration): Duration? {
        if (age < Duration.ZERO) return TIER_1_INTERVAL
        return when {
            age < TIER_1_BOUNDARY -> TIER_1_INTERVAL
            age < TIER_2_BOUNDARY -> TIER_2_INTERVAL
            age < TIER_3_BOUNDARY -> TIER_3_INTERVAL
            else -> null
        }
    }

    fun nextDelay(acceptedAtEpochMillis: Long, currentEpochMillis: Long): Duration? {
        val ageMillis = (currentEpochMillis - acceptedAtEpochMillis).coerceAtLeast(0)
        return nextDelay(ageMillis.milliseconds)
    }
}
