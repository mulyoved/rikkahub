package me.rerere.rikkahub.voiceagent.recovery

import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.ValidatedHermesRecoverySnapshot
import me.rerere.rikkahub.voiceagent.livekit.voiceSha256
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex

internal sealed interface SnapshotReconciliation {
    data class Valid(val snapshot: ValidatedHermesRecoverySnapshot) : SnapshotReconciliation
    data class Dormant(val reason: HermesDormantReason) : SnapshotReconciliation
}

internal class HermesSnapshotReconciler {
    fun reconcile(
        currentEndpointBindingHash: String,
        entry: HermesRecoveryEntry,
        response: HermesRecoveryHttpResponse,
    ): SnapshotReconciliation {
        // 1. Current endpoint binding check
        if (currentEndpointBindingHash != entry.originalEndpointHash) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable)
        }

        // 2. X-Hermes-Owner-Hash check
        val responseOwnerHash = response.ownerHash?.trim()
        if (responseOwnerHash.isNullOrBlank() || responseOwnerHash != entry.originalOwnerHash) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable)
        }

        // 3. HTTP status code check: only 2xx bodies may be parsed
        if (response.statusCode !in 200..299) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // 4. Parse payload
        val snapshot = parseHermesRecoverySnapshot(response.payload)
            ?: return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)

        // 5. Job ID match
        if (snapshot.jobId != entry.jobId) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // 6. Check for Tombstone vs Normal Snapshot
        val isTombstoneCandidate = snapshot.correlation == null && snapshot.callId == null

        if (isTombstoneCandidate) {
            if (snapshot.rawStatus != "expired") {
                return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
            }
            val failure = snapshot.failure
                ?: return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
            if (failure.kind != "expired" || failure.safeMessage.isBlank()) {
                return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
            }
            if (snapshot.answer != null || snapshot.resultHash != null) {
                return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
            }

            return SnapshotReconciliation.Valid(
                ValidatedHermesRecoverySnapshot(
                    jobId = entry.jobId,
                    callId = entry.callId,
                    status = HermesQueueStatus.Expired,
                    answer = null,
                    error = failure.safeMessage,
                    resultHash = null,
                ),
            )
        }

        // Normal snapshot validation:
        if (entry.producer != HERMES_PRODUCER) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        if (snapshot.callId != entry.callId) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        val correlation = snapshot.correlation
            ?: return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)

        // Correlation owner check
        if (correlation.ownerHash != responseOwnerHash || correlation.ownerHash != entry.originalOwnerHash) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // Correlation conversation check
        if (correlation.conversationId != entry.conversationId.toString()) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // Correlation voice session check
        if (!matchesVoiceSession(correlation.voiceSessionId, entry.originalVoiceSessionHash)) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // Correlation argument hash check
        if (correlation.argumentHash != entry.originalArgumentHash) {
            return SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }

        // Status mapping and validation
        return when (snapshot.rawStatus) {
            "accepted", "queued" -> {
                if (snapshot.answer != null || snapshot.resultHash != null || snapshot.failure != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Queued,
                            answer = null,
                            error = null,
                            resultHash = null,
                        ),
                    )
                }
            }

            "running" -> {
                if (snapshot.answer != null || snapshot.resultHash != null || snapshot.failure != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Running,
                            answer = null,
                            error = null,
                            resultHash = null,
                        ),
                    )
                }
            }

            "succeeded" -> {
                val answer = snapshot.answer
                val resultHash = snapshot.resultHash
                if (answer.isNullOrBlank() || resultHash == null || snapshot.failure != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else if (!matchesResultHash(answer, resultHash)) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Complete,
                            answer = answer,
                            error = null,
                            resultHash = resultHash,
                        ),
                    )
                }
            }

            "failed" -> {
                val failure = snapshot.failure
                if (failure == null || snapshot.answer != null || snapshot.resultHash != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Failed,
                            answer = null,
                            error = failure.safeMessage,
                            resultHash = null,
                        ),
                    )
                }
            }

            "expired" -> {
                val failure = snapshot.failure
                if (failure == null || failure.kind != "expired" || snapshot.answer != null || snapshot.resultHash != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Expired,
                            answer = null,
                            error = failure.safeMessage,
                            resultHash = null,
                        ),
                    )
                }
            }

            "canceled" -> {
                val failure = snapshot.failure
                if (failure == null || failure.kind != "canceled" || snapshot.answer != null || snapshot.resultHash != null) {
                    SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
                } else {
                    SnapshotReconciliation.Valid(
                        ValidatedHermesRecoverySnapshot(
                            jobId = entry.jobId,
                            callId = entry.callId,
                            status = HermesQueueStatus.Canceled,
                            answer = null,
                            error = failure.safeMessage,
                            resultHash = null,
                        ),
                    )
                }
            }

            else -> SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch)
        }
    }

    private fun matchesResultHash(answer: String, resultHash: String): Boolean {
        val calculatedHex = sha256Hex(answer)
        val expectedHex = resultHash.removePrefix("sha256:").trim()
        return calculatedHex.equals(expectedHex, ignoreCase = true)
    }

    private fun matchesVoiceSession(correlationVoiceSessionId: String, originalVoiceSessionHash: String?): Boolean {
        if (originalVoiceSessionHash == null) return false
        return correlationVoiceSessionId == originalVoiceSessionHash ||
            voiceSha256(correlationVoiceSessionId) == originalVoiceSessionHash ||
            recoverySha256(correlationVoiceSessionId) == originalVoiceSessionHash
    }
}
