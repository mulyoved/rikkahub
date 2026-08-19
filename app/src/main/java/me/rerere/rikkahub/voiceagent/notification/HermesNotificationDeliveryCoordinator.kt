package me.rerere.rikkahub.voiceagent.notification

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryEntry
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.RecoveryClock
import me.rerere.rikkahub.voiceagent.recovery.SystemRecoveryClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal fun interface HermesNotificationPoster {
    suspend fun post(conversationId: Uuid)
}

internal class HermesNotificationDeliveryCoordinator(
    private val ledger: HermesRecoveryLedger,
    private val scheduler: HermesNotificationWorkScheduler,
    private val poster: HermesNotificationPoster,
    private val admission: HermesNotificationAdmission = HermesNotificationAdmission { _, _ ->
        HermesNotificationDisposition.SuppressedNotEnabled
    },
    private val clock: RecoveryClock = SystemRecoveryClock,
) {

    suspend fun deliver(conversationId: Uuid) {
        val now = clock.epochMillis()

        // 1. Load all pending rows for this conversation
        val pendingRows = ledger.pendingNotifications(conversationId)
        if (pendingRows.isEmpty()) return

        // 2. Suppress expired rows: now >= terminalDeadlineAt
        val unexpiredRows = mutableListOf<HermesRecoveryEntry>()
        for (row in pendingRows) {
            val deadline = row.terminalDeadlineAt
                ?: (row.terminalCommittedAt?.let { it + 15.minutes.inWholeMilliseconds })
                ?: (row.acceptedAt + 15.minutes.inWholeMilliseconds)
            if (now >= deadline) {
                val expiredEntry = row.copy(
                    notificationDisposition = HermesNotificationDisposition.SuppressedPostFailure,
                    notificationDispositionChangedAt = now,
                    notificationNextAttemptAt = null,
                )
                ledger.update(expiredEntry)
            } else {
                unexpiredRows.add(row)
            }
        }

        if (unexpiredRows.isEmpty()) return

        // 3. Select due rows: nextAttemptAt <= now
        val dueRows = unexpiredRows.filter { (it.notificationNextAttemptAt ?: 0L) <= now }
        val futureRows = unexpiredRows.filter { (it.notificationNextAttemptAt ?: 0L) > now }

        if (dueRows.isEmpty()) {
            val earliestNext = futureRows.mapNotNull { it.notificationNextAttemptAt }.minOrNull()
            if (earliestNext != null) {
                val delay = maxOf(0L, earliestNext - now).milliseconds
                scheduler.continueAfterCurrent(conversationId, delay)
            }
            return
        }

        // 4. Immediately before calling post(...), re-check:
        val freshNow = clock.epochMillis()
        val stillValidDueRows = mutableListOf<HermesRecoveryEntry>()
        for (row in dueRows) {
            val deadline = row.terminalDeadlineAt
                ?: (row.terminalCommittedAt?.let { it + 15.minutes.inWholeMilliseconds })
                ?: (row.acceptedAt + 15.minutes.inWholeMilliseconds)
            if (freshNow >= deadline) {
                val expiredEntry = row.copy(
                    notificationDisposition = HermesNotificationDisposition.SuppressedPostFailure,
                    notificationDispositionChangedAt = freshNow,
                    notificationNextAttemptAt = null,
                )
                ledger.update(expiredEntry)
            } else {
                stillValidDueRows.add(row)
            }
        }

        if (stillValidDueRows.isEmpty()) {
            val earliestNext = futureRows.mapNotNull { it.notificationNextAttemptAt }.minOrNull()
            if (earliestNext != null) {
                val delay = maxOf(0L, earliestNext - freshNow).milliseconds
                scheduler.continueAfterCurrent(conversationId, delay)
            }
            return
        }

        // Admission re-check: foreground, permission/channel availability, in-call, admission enabled
        val admissionDisposition = admission.decide(conversationId, TerminalObservationContext.Recovery)
        if (admissionDisposition != HermesNotificationDisposition.PendingPost) {
            for (row in stillValidDueRows) {
                val suppressedEntry = row.copy(
                    notificationDisposition = admissionDisposition,
                    notificationDispositionChangedAt = freshNow,
                    notificationNextAttemptAt = null,
                )
                ledger.update(suppressedEntry)
            }
            val earliestNext = futureRows.mapNotNull { it.notificationNextAttemptAt }.minOrNull()
            if (earliestNext != null) {
                val delay = maxOf(0L, earliestNext - freshNow).milliseconds
                scheduler.continueAfterCurrent(conversationId, delay)
            }
            return
        }

        // 5. Post notification
        try {
            poster.post(conversationId)

            val successNow = clock.epochMillis()
            for (row in stillValidDueRows) {
                val postedEntry = row.copy(
                    notificationDisposition = HermesNotificationDisposition.Posted,
                    notificationDispositionChangedAt = successNow,
                    notificationNextAttemptAt = null,
                )
                ledger.update(postedEntry)
            }

            val earliestNext = futureRows.mapNotNull { it.notificationNextAttemptAt }.minOrNull()
            if (earliestNext != null) {
                val delay = maxOf(0L, earliestNext - successNow).milliseconds
                scheduler.continueAfterCurrent(conversationId, delay)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failureNow = clock.epochMillis()
            val allRemainingPending = mutableListOf<HermesRecoveryEntry>()
            allRemainingPending.addAll(futureRows)

            for (row in stillValidDueRows) {
                val nextAttemptCount = row.notificationAttemptCount + 1
                val terminalTime = row.terminalCommittedAt ?: row.acceptedAt
                val deadline = row.terminalDeadlineAt ?: (terminalTime + 15.minutes.inWholeMilliseconds)

                if (nextAttemptCount >= 3) {
                    val failedEntry = row.copy(
                        notificationAttemptCount = nextAttemptCount,
                        notificationDisposition = HermesNotificationDisposition.SuppressedPostFailure,
                        notificationDispositionChangedAt = failureNow,
                        notificationNextAttemptAt = null,
                    )
                    ledger.update(failedEntry)
                } else {
                    val nextAttemptDelay = when (nextAttemptCount) {
                        1 -> 1.minutes.inWholeMilliseconds
                        2 -> 5.minutes.inWholeMilliseconds
                        else -> 5.minutes.inWholeMilliseconds
                    }
                    val nextAttemptAt = terminalTime + nextAttemptDelay
                    if (nextAttemptAt >= deadline) {
                        val failedEntry = row.copy(
                            notificationAttemptCount = nextAttemptCount,
                            notificationDisposition = HermesNotificationDisposition.SuppressedPostFailure,
                            notificationDispositionChangedAt = failureNow,
                            notificationNextAttemptAt = null,
                        )
                        ledger.update(failedEntry)
                    } else {
                        val retryEntry = row.copy(
                            notificationAttemptCount = nextAttemptCount,
                            notificationNextAttemptAt = nextAttemptAt,
                            notificationDispositionChangedAt = failureNow,
                        )
                        ledger.update(retryEntry)
                        allRemainingPending.add(retryEntry)
                    }
                }
            }

            val earliestNext = allRemainingPending.mapNotNull { it.notificationNextAttemptAt }.minOrNull()
            if (earliestNext != null) {
                val delay = maxOf(0L, earliestNext - failureNow).milliseconds
                scheduler.continueAfterCurrent(conversationId, delay)
            }
        }
    }
}
