package me.rerere.rikkahub.voiceagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.voiceagent.hermes.HermesJobCompletion
import me.rerere.rikkahub.voiceagent.hermes.HermesJobFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesPollFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueEvent
import me.rerere.rikkahub.voiceagent.telemetry.HermesTelemetryLogSanitizer
import me.rerere.rikkahub.voiceagent.telemetry.HermesToolResponseHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import java.security.MessageDigest

/**
 * Result of comparing an incoming/persisted owner hash against the expected owner identity.
 * Emits strictly categorical values: [Match], [Missing], or [Mismatch].
 * Raw owner identifiers and secret hashes are never serialized to logs or telemetry.
 */
enum class HermesOwnerCheck(val wireName: String) {
    Match("match"),
    Missing("missing"),
    Mismatch("mismatch");

    companion object {
        fun evaluate(expectedOwnerHash: String?, actualOwnerHash: String?): HermesOwnerCheck {
            val expected = expectedOwnerHash?.trim()
            val actual = actualOwnerHash?.trim()
            return when {
                expected.isNullOrBlank() || actual.isNullOrBlank() -> Missing
                expected == actual -> Match
                else -> Mismatch
            }
        }
    }
}

/**
 * Redacted, categorical recovery telemetry events.
 *
 * Operational Notes:
 * 1. Force-Stop Relaunch: Android force-stop terminates active recovery work and suppresses
 *    scheduled WorkManager tasks until the app is explicitly relaunched by the user. On next
 *    launch, [me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryCoordinator.repairAll]
 *    runs startup repair to discover and reconcile all pending recovery ledger entries.
 * 2. WorkManager Best-Effort Timing: Recovery delays (e.g., 30 seconds, 5 minutes, 30 minutes)
 *    represent minimum scheduling targets, not rigid real-time guarantees. Device battery state,
 *    Doze mode, and system background policies govern actual invocation timing.
 * 3. Server Restart / Eviction Tombstone: Server restarts or memory evictions generate expired
 *    tombstones or missing job responses. When authenticated owner proofs match, the reconciler
 *    maps these to [me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus.Expired], or to
 *    [me.rerere.rikkahub.voiceagent.recovery.HermesDormantReason.AuthUnavailable] if owner proof is
 *    missing/mismatched, strictly preserving task truth without fabricating false outcomes.
 * 4. Manual Explicit Retry: Explicit user-initiated retries bypass scheduled backoff delays,
 *    re-evaluating current credentials and triggering an immediate single-operation poll or cancel.
 */
sealed class HermesRecoveryTelemetryEvent {
    abstract val eventType: String
    abstract fun toJson(): String
    abstract fun toLogDetail(): String

    data class RegistrationRepair(
        val kind: String,
        val conversationHash: String,
        val callHash: String,
        val jobHash: String,
        val trigger: String,
        val outcome: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "registration_repair"

        companion object {
            fun create(
                kind: String,
                conversationId: String,
                callId: String,
                jobId: String,
                trigger: String,
                outcome: String,
            ): RegistrationRepair = RegistrationRepair(
                kind = kind,
                conversationHash = hashIdentifier(conversationId),
                callHash = hashIdentifier(callId),
                jobHash = hashIdentifier(jobId),
                trigger = trigger,
                outcome = outcome,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "registration_repair")
            put("kind", kind)
            put("conversationHash", conversationHash)
            put("callHash", callHash)
            put("jobHash", jobHash)
            put("trigger", trigger)
            put("outcome", outcome)
        }.toString()

        override fun toLogDetail(): String =
            "type=registration_repair kind=$kind conversationHash=$conversationHash callHash=$callHash jobHash=$jobHash trigger=$trigger outcome=$outcome"
    }

    data class RelayAction(
        val action: String,
        val conversationHash: String,
        val callHash: String,
        val jobHash: String,
        val sessionHash: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "relay_action"

        companion object {
            fun create(
                action: String,
                conversationId: String,
                callId: String,
                jobId: String,
                voiceSessionId: String,
            ): RelayAction = RelayAction(
                action = action,
                conversationHash = hashIdentifier(conversationId),
                callHash = hashIdentifier(callId),
                jobHash = hashIdentifier(jobId),
                sessionHash = hashIdentifier(voiceSessionId),
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "relay_action")
            put("action", action)
            put("conversationHash", conversationHash)
            put("callHash", callHash)
            put("jobHash", jobHash)
            put("sessionHash", sessionHash)
        }.toString()

        override fun toLogDetail(): String =
            "type=relay_action action=$action conversationHash=$conversationHash callHash=$callHash jobHash=$jobHash sessionHash=$sessionHash"
    }

    data class OperationClass(
        val operation: String,
        val callHash: String,
        val jobHash: String,
        val classification: String,
        val httpStatus: Int? = null,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "operation_class"

        companion object {
            fun create(
                operation: String,
                callId: String,
                jobId: String,
                classification: String,
                httpStatus: Int? = null,
            ): OperationClass = OperationClass(
                operation = operation,
                callHash = hashIdentifier(callId),
                jobHash = hashIdentifier(jobId),
                classification = classification,
                httpStatus = httpStatus,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "operation_class")
            put("operation", operation)
            put("callHash", callHash)
            put("jobHash", jobHash)
            put("classification", classification)
            httpStatus?.let { put("httpStatus", it) }
        }.toString()

        override fun toLogDetail(): String =
            "type=operation_class operation=$operation callHash=$callHash jobHash=$jobHash classification=$classification httpStatus=${httpStatus ?: "none"}"
    }

    data class DormantReason(
        val conversationHash: String,
        val jobHash: String,
        val recoveryKeyHash: String,
        val reason: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "dormant_reason"

        companion object {
            fun create(
                conversationId: String,
                jobId: String,
                recoveryKey: String,
                reason: String,
            ): DormantReason = DormantReason(
                conversationHash = hashIdentifier(conversationId),
                jobHash = hashIdentifier(jobId),
                recoveryKeyHash = hashIdentifier(recoveryKey),
                reason = reason,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "dormant_reason")
            put("conversationHash", conversationHash)
            put("jobHash", jobHash)
            put("recoveryKeyHash", recoveryKeyHash)
            put("reason", reason)
        }.toString()

        override fun toLogDetail(): String =
            "type=dormant_reason conversationHash=$conversationHash jobHash=$jobHash recoveryKeyHash=$recoveryKeyHash reason=$reason"
    }

    data class SnapshotDecision(
        val callHash: String,
        val jobHash: String,
        val decision: String,
        val ownerCheck: HermesOwnerCheck,
        val status: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "snapshot_decision"

        companion object {
            fun create(
                callId: String,
                jobId: String,
                decision: String,
                ownerCheck: HermesOwnerCheck,
                status: String,
            ): SnapshotDecision = SnapshotDecision(
                callHash = hashIdentifier(callId),
                jobHash = hashIdentifier(jobId),
                decision = decision,
                ownerCheck = ownerCheck,
                status = status,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "snapshot_decision")
            put("callHash", callHash)
            put("jobHash", jobHash)
            put("decision", decision)
            put("ownerCheck", ownerCheck.wireName)
            put("status", status)
        }.toString()

        override fun toLogDetail(): String =
            "type=snapshot_decision callHash=$callHash jobHash=$jobHash decision=$decision ownerCheck=${ownerCheck.wireName} status=$status"
    }

    data class TerminalCommit(
        val conversationHash: String,
        val jobHash: String,
        val status: String,
        val disposition: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "terminal_commit"

        companion object {
            fun create(
                conversationId: String,
                jobId: String,
                status: String,
                disposition: String,
            ): TerminalCommit = TerminalCommit(
                conversationHash = hashIdentifier(conversationId),
                jobHash = hashIdentifier(jobId),
                status = status,
                disposition = disposition,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "terminal_commit")
            put("conversationHash", conversationHash)
            put("jobHash", jobHash)
            put("status", status)
            put("disposition", disposition)
        }.toString()

        override fun toLogDetail(): String =
            "type=terminal_commit conversationHash=$conversationHash jobHash=$jobHash status=$status disposition=$disposition"
    }

    data class NotificationAdmission(
        val conversationHash: String,
        val disposition: String,
        val context: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_admission"

        companion object {
            fun create(
                conversationId: String,
                disposition: String,
                context: String,
            ): NotificationAdmission = NotificationAdmission(
                conversationHash = hashIdentifier(conversationId),
                disposition = disposition,
                context = context,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_admission")
            put("conversationHash", conversationHash)
            put("disposition", disposition)
            put("context", context)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_admission conversationHash=$conversationHash disposition=$disposition context=$context"
    }

    data class NotificationPost(
        val conversationHash: String,
        val attemptOrdinal: Int,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_post"

        companion object {
            fun create(
                conversationId: String,
                attemptOrdinal: Int,
            ): NotificationPost = NotificationPost(
                conversationHash = hashIdentifier(conversationId),
                attemptOrdinal = attemptOrdinal,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_post")
            put("conversationHash", conversationHash)
            put("attemptOrdinal", attemptOrdinal)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_post conversationHash=$conversationHash attemptOrdinal=$attemptOrdinal"
    }

    data class NotificationRetry(
        val conversationHash: String,
        val attemptOrdinal: Int,
        val nextAttemptDelayMs: Long,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_retry"

        companion object {
            fun create(
                conversationId: String,
                attemptOrdinal: Int,
                nextAttemptDelayMs: Long,
            ): NotificationRetry = NotificationRetry(
                conversationHash = hashIdentifier(conversationId),
                attemptOrdinal = attemptOrdinal,
                nextAttemptDelayMs = nextAttemptDelayMs,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_retry")
            put("conversationHash", conversationHash)
            put("attemptOrdinal", attemptOrdinal)
            put("nextAttemptDelayMs", nextAttemptDelayMs)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_retry conversationHash=$conversationHash attemptOrdinal=$attemptOrdinal nextAttemptDelayMs=$nextAttemptDelayMs"
    }

    data class NotificationSuppression(
        val conversationHash: String,
        val reason: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_suppression"

        companion object {
            fun create(
                conversationId: String,
                reason: String,
            ): NotificationSuppression = NotificationSuppression(
                conversationHash = hashIdentifier(conversationId),
                reason = reason,
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_suppression")
            put("conversationHash", conversationHash)
            put("reason", reason)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_suppression conversationHash=$conversationHash reason=$reason"
    }

    data class NotificationTap(
        val conversationHash: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_tap"

        companion object {
            fun create(
                conversationId: String,
            ): NotificationTap = NotificationTap(
                conversationHash = hashIdentifier(conversationId),
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_tap")
            put("conversationHash", conversationHash)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_tap conversationHash=$conversationHash"
    }

    data class NotificationDismiss(
        val conversationHash: String,
    ) : HermesRecoveryTelemetryEvent() {
        override val eventType: String get() = "notification_dismiss"

        companion object {
            fun create(
                conversationId: String,
            ): NotificationDismiss = NotificationDismiss(
                conversationHash = hashIdentifier(conversationId),
            )
        }

        override fun toJson(): String = buildJsonObject {
            put("type", "notification_dismiss")
            put("conversationHash", conversationHash)
        }.toString()

        override fun toLogDetail(): String =
            "type=notification_dismiss conversationHash=$conversationHash"
    }
}

private fun hashIdentifier(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * E2E artifact and hash/log sinks for Hermes job telemetry. Pure sinks: no
 * coordinator state, every entry point is safe against throwing log/artifact
 * destinations. The single consumer for [HermesQueueEvent] and [HermesRecoveryTelemetryEvent]
 * from both producers.
 */
class HermesTelemetrySink(
    private val diagnostics: VoiceDiagnostics,
    private val hermesResponseExpectedHash: String?,
    private val logHermesRequestHash: (String) -> Unit,
    private val logHermesResponseHash: (String) -> Unit,
    private val logHermesToolFailure: (String) -> Unit,
    private val logHermesQueueEvent: (String) -> Unit,
    private val artifactSink: VoiceE2EArtifactSink,
) {
    fun recordJobCompletion(completion: HermesJobCompletion) {
        recordResponseHash(
            callId = completion.callId,
            answer = completion.answer,
            expectedHash = hermesResponseExpectedHash,
            elapsedMs = completion.elapsedMs,
            serverElapsedMs = completion.serverElapsedMs,
        )
    }

    fun recordJobFailure(failure: HermesJobFailure) {
        val jobDetail = failure.jobId?.let { ", jobId=$it" }.orEmpty()
        val e2eDetail = "callId=${failure.callId}$jobDetail, elapsedMs=${failure.elapsedMs}, " +
            "message=${HermesTelemetryLogSanitizer.failureMessage(failure.message)}"
        runCatching {
            logHermesToolFailure(e2eDetail)
        }
    }

    fun recordPollFailure(failure: HermesPollFailure) {
        diagnostics.record(
            "hermes_job_poll_failed",
            "callId=${failure.callId}, jobId=${failure.jobId}, attempt=${failure.attempt}, message=${failure.message}",
        )
    }

    fun recordRequestHash(callId: String, prompt: String) {
        val detail = HermesToolResponseHash.requestDiagnosticDetail(callId = callId, prompt = prompt)
        diagnostics.record("hermes_tool_request_hash", detail)
        runCatching {
            logHermesRequestHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_request_hash_log_failed", "callId=$callId, message=$message")
        }
    }

    fun writeQueueEvent(event: HermesQueueEvent) {
        logQueueEventSafely(event.toLogDetail())
        artifactSink.writeArtifactSafely(
            artifact = VoiceE2EArtifact.HermesEvents,
            content = event.toJson(),
            callId = event.callId,
        )
    }

    fun writeRecoveryEvent(event: HermesRecoveryTelemetryEvent) {
        val detail = event.toLogDetail()
        logQueueEventSafely(detail)
        diagnostics.record("hermes_recovery_${event.eventType}", detail)
        artifactSink.writeArtifactSafely(
            artifact = VoiceE2EArtifact.HermesEvents,
            content = event.toJson(),
        )
    }

    private fun recordResponseHash(
        callId: String,
        answer: String,
        expectedHash: String?,
        elapsedMs: Long,
        serverElapsedMs: Long?,
    ) {
        val detail = HermesToolResponseHash.diagnosticDetail(
            callId = callId,
            answer = answer,
            expectedSha256 = expectedHash?.takeIf { it.isNotBlank() },
            elapsedMs = elapsedMs,
            serverElapsedMs = serverElapsedMs,
        )
        diagnostics.record("hermes_tool_response_hash", detail)
        runCatching {
            logHermesResponseHash(detail)
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_response_hash_log_failed", "callId=$callId, message=$message")
        }
        artifactSink.writeArtifactSafely(artifact = VoiceE2EArtifact.HermesAnswer, content = answer, callId = callId)
    }

    private fun logQueueEventSafely(detail: String) {
        runCatching {
            logHermesQueueEvent(detail)
        }.onFailure { error ->
            diagnostics.record(
                "hermes_queue_event_log_failed",
                error.message ?: error.javaClass.simpleName,
            )
        }
    }
}
