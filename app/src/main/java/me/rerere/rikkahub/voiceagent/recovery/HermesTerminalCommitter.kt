package me.rerere.rikkahub.voiceagent.recovery

import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.ValidatedHermesRecoverySnapshot
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.notification.HermesNotificationAdmission
import me.rerere.rikkahub.voiceagent.notification.HermesNotificationWorkScheduler
import me.rerere.rikkahub.voiceagent.notification.TerminalObservationContext
import kotlin.time.Duration.Companion.minutes

internal class HermesTerminalCommitter(
    private val ledger: HermesRecoveryLedger,
    private val admission: HermesNotificationAdmission = HermesNotificationAdmission { _, _ ->
        HermesNotificationDisposition.SuppressedNotEnabled
    },
    private val clock: RecoveryClock = SystemRecoveryClock,
    private val notificationScheduler: HermesNotificationWorkScheduler? = null,
) {
    constructor(
        ledger: HermesRecoveryLedger,
        clock: RecoveryClock,
    ) : this(
        ledger = ledger,
        admission = HermesNotificationAdmission { _, _ ->
            HermesNotificationDisposition.SuppressedNotEnabled
        },
        clock = clock,
        notificationScheduler = null,
    )

    suspend fun commitTerminal(
        queueStore: HermesQueueStore,
        entry: HermesRecoveryEntry,
        snapshot: ValidatedHermesRecoverySnapshot,
        observation: TerminalObservationContext = TerminalObservationContext.Recovery,
    ): HermesQueuePersistenceResult {
        val now = clock.epochMillis()
        val updatedEntry = prepareTerminalEntry(entry, now, observation)

        val result = queueStore.persistValidatedRecoverySnapshot(
            snapshot = snapshot,
            commit = { persistenceResult ->
                if (persistenceResult != HermesQueuePersistenceResult.Conflict &&
                    persistenceResult != HermesQueuePersistenceResult.Stale
                ) {
                    ledger.update(updatedEntry)
                }
            },
        )

        if (result != HermesQueuePersistenceResult.Conflict &&
            result != HermesQueuePersistenceResult.Stale
        ) {
            if (updatedEntry.notificationDisposition == HermesNotificationDisposition.PendingPost) {
                try {
                    notificationScheduler?.replaceForEarliestDue(entry.conversationId)
                } catch (e: Exception) {
                    // scheduling failure leaves durable pending truth for startup repair
                }
            }
        }

        return result
    }

    suspend fun commitLiveKitTerminal(
        queueStore: HermesQueueStore,
        entry: HermesRecoveryEntry,
        callId: String,
        status: VoiceToolRecordStatus,
        jobId: String,
        originatingUserTurnId: String,
        requestHash: String,
        argumentHash: String,
        resultHash: String?,
        producer: String,
        observation: TerminalObservationContext = TerminalObservationContext.ConnectedRelay,
    ): HermesQueuePersistenceResult {
        val now = clock.epochMillis()
        val updatedEntry = prepareTerminalEntry(entry, now, observation)

        val result = queueStore.persistLiveKitTerminal(
            callId = callId,
            status = status,
            jobId = jobId,
            originatingUserTurnId = originatingUserTurnId,
            requestHash = requestHash,
            argumentHash = argumentHash,
            resultHash = resultHash,
            producer = producer,
            commit = { persistenceResult ->
                if (persistenceResult != HermesQueuePersistenceResult.Conflict &&
                    persistenceResult != HermesQueuePersistenceResult.Stale
                ) {
                    ledger.update(updatedEntry)
                }
            },
        )

        if (result != HermesQueuePersistenceResult.Conflict &&
            result != HermesQueuePersistenceResult.Stale
        ) {
            if (updatedEntry.notificationDisposition == HermesNotificationDisposition.PendingPost) {
                try {
                    notificationScheduler?.replaceForEarliestDue(entry.conversationId)
                } catch (e: Exception) {
                    // scheduling failure leaves durable pending truth for startup repair
                }
            }
        }

        return result
    }

    private fun prepareTerminalEntry(
        entry: HermesRecoveryEntry,
        now: Long,
        observation: TerminalObservationContext,
    ): HermesRecoveryEntry {
        val disposition: HermesNotificationDisposition
        val dispositionChangedAt: Long
        val terminalDeadlineAt: Long?
        val nextAttemptAt: Long?
        val attemptCount: Int

        if (entry.notificationDisposition != HermesNotificationDisposition.Undecided) {
            disposition = entry.notificationDisposition
            dispositionChangedAt = entry.notificationDispositionChangedAt
            terminalDeadlineAt = entry.terminalDeadlineAt
            nextAttemptAt = entry.notificationNextAttemptAt
            attemptCount = entry.notificationAttemptCount
        } else {
            disposition = admission.decide(entry.conversationId, observation)
            dispositionChangedAt = now
            if (disposition == HermesNotificationDisposition.PendingPost) {
                terminalDeadlineAt = now + 15.minutes.inWholeMilliseconds
                nextAttemptAt = now
                attemptCount = 0
            } else {
                terminalDeadlineAt = null
                nextAttemptAt = null
                attemptCount = 0
            }
        }

        return entry.copy(
            recoveryState = HermesRecoveryState.Finished,
            dormantReason = null,
            terminalCommittedAt = entry.terminalCommittedAt ?: now,
            terminalDeadlineAt = terminalDeadlineAt,
            notificationDisposition = disposition,
            notificationDispositionChangedAt = dispositionChangedAt,
            notificationNextAttemptAt = nextAttemptAt,
            notificationAttemptCount = attemptCount,
            cancelRequestedAt = null,
            lastAttemptAt = now,
        )
    }
}
