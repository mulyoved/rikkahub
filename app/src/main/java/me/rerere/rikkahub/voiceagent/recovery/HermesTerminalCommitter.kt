package me.rerere.rikkahub.voiceagent.recovery

import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.ValidatedHermesRecoverySnapshot
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus

internal class HermesTerminalCommitter(
    private val ledger: HermesRecoveryLedger,
    private val clock: RecoveryClock = SystemRecoveryClock,
) {
    suspend fun commitTerminal(
        queueStore: HermesQueueStore,
        entry: HermesRecoveryEntry,
        snapshot: ValidatedHermesRecoverySnapshot,
    ): HermesQueuePersistenceResult {
        val now = clock.epochMillis()
        val updatedEntry = entry.copy(
            recoveryState = HermesRecoveryState.Finished,
            dormantReason = null,
            terminalCommittedAt = now,
            terminalDeadlineAt = null,
            notificationDisposition = HermesNotificationDisposition.SuppressedNotEnabled,
            notificationDispositionChangedAt = now,
            cancelRequestedAt = null,
            lastAttemptAt = now,
        )

        return queueStore.persistValidatedRecoverySnapshot(
            snapshot = snapshot,
            commit = { persistenceResult ->
                if (persistenceResult != HermesQueuePersistenceResult.Conflict &&
                    persistenceResult != HermesQueuePersistenceResult.Stale
                ) {
                    ledger.update(updatedEntry)
                }
            },
        )
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
    ): HermesQueuePersistenceResult {
        val now = clock.epochMillis()
        val updatedEntry = entry.copy(
            recoveryState = HermesRecoveryState.Finished,
            dormantReason = null,
            terminalCommittedAt = now,
            terminalDeadlineAt = null,
            notificationDisposition = HermesNotificationDisposition.SuppressedNotEnabled,
            notificationDispositionChangedAt = now,
            cancelRequestedAt = null,
            lastAttemptAt = now,
        )

        return queueStore.persistLiveKitTerminal(
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
    }
}
