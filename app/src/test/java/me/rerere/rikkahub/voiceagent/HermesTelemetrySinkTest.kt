package me.rerere.rikkahub.voiceagent

import me.rerere.rikkahub.voiceagent.hermes.HermesJobCompletion
import me.rerere.rikkahub.voiceagent.hermes.HermesJobFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesPollFailure
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueEvent
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTelemetrySinkTest {

    private val diagnostics = VoiceDiagnostics()
    private val requestHashes = mutableListOf<String>()
    private val responseHashes = mutableListOf<String>()
    private val toolFailures = mutableListOf<String>()
    private val queueEventLogs = mutableListOf<String>()
    private val artifacts = mutableListOf<Pair<VoiceE2EArtifact, String>>()

    private fun sink(
        expectedHash: String? = null,
        writeArtifact: (VoiceE2EArtifact, String) -> Unit = { artifact, content -> artifacts += artifact to content },
        logQueueEvent: (String) -> Unit = { queueEventLogs += it },
        logRequestHash: (String) -> Unit = { requestHashes += it },
        logResponseHash: (String) -> Unit = { responseHashes += it },
    ) = HermesTelemetrySink(
        diagnostics = diagnostics,
        hermesResponseExpectedHash = expectedHash,
        logHermesRequestHash = logRequestHash,
        logHermesResponseHash = logResponseHash,
        logHermesToolFailure = { toolFailures += it },
        logHermesQueueEvent = logQueueEvent,
        artifactSink = VoiceE2EArtifactSink(
            diagnostics = diagnostics,
            writeVoiceE2EArtifact = writeArtifact,
        ),
    )

    private fun diagnosticNames(): List<String> = diagnostics.events.value.map { it.name }

    @Test
    fun `writeQueueEvent logs the detail line and writes the JSON artifact`() {
        val event = HermesQueueEvent(type = "job_created", callId = "c1", jobId = "j1", status = "queued")
        sink().writeQueueEvent(event)
        assertEquals(listOf("type=job_created callId=c1 jobId=j1 status=queued sent=n/a"), queueEventLogs)
        assertEquals(listOf(VoiceE2EArtifact.HermesEvents to event.toJson()), artifacts)
    }

    @Test
    fun `writeQueueEvent survives a throwing log sink and records the failure diagnostic`() {
        sink(logQueueEvent = { error("logcat down") })
            .writeQueueEvent(HermesQueueEvent(type = "t", callId = "c", jobId = "j"))
        assertTrue("hermes_queue_event_log_failed" in diagnosticNames())
        assertEquals(1, artifacts.size) // artifact still written
    }

    @Test
    fun `writeQueueEvent with a throwing artifact writer records the failure diagnostic with the event callId`() {
        sink(writeArtifact = { _, _ -> error("disk full") })
            .writeQueueEvent(HermesQueueEvent(type = "t", callId = "c9", jobId = "j"))
        val event = diagnostics.events.value.single { it.name == "voice_e2e_artifact_write_failed" }
        assertTrue(event.detail.contains("callId=c9"))
        assertEquals(listOf("type=t callId=c9 jobId=j status=none sent=n/a"), queueEventLogs)
    }

    @Test
    fun `recordJobCompletion records the response hash diagnostic and answer artifact`() {
        sink().recordJobCompletion(
            HermesJobCompletion(callId = "c1", jobId = "j1", answer = "answer", elapsedMs = 5L, serverElapsedMs = null)
        )
        assertEquals(1, responseHashes.size)
        assertTrue("hermes_tool_response_hash" in diagnosticNames())
        assertEquals(VoiceE2EArtifact.HermesAnswer, artifacts.single().first)
        assertEquals("answer", artifacts.single().second)
    }

    @Test
    fun `recordJobFailure sanitizes the message for the e2e log`() {
        sink().recordJobFailure(
            HermesJobFailure(callId = "c1", jobId = "j1", message = "Hermes Voice request failed 500: detail", elapsedMs = 2L)
        )
        assertEquals(
            listOf("callId=c1, jobId=j1, elapsedMs=2, message=Hermes Voice request failed 500"),
            toolFailures,
        )
    }

    @Test
    fun `recordPollFailure records the diagnostic`() {
        sink().recordPollFailure(HermesPollFailure(callId = "c1", jobId = "j1", attempt = 3, message = "m"))
        assertTrue("hermes_job_poll_failed" in diagnosticNames())
    }

    @Test
    fun `recordRequestHash logs and diagnoses`() {
        sink().recordRequestHash(callId = "c1", prompt = "p")
        assertEquals(1, requestHashes.size)
        assertTrue("hermes_tool_request_hash" in diagnosticNames())
    }

    @Test
    fun `recordRequestHash survives a throwing request-hash logger`() {
        sink(logRequestHash = { error("logcat down") })
            .recordRequestHash(callId = "c1", prompt = "p")
        val failureEvent = diagnostics.events.value.single { it.name == "hermes_tool_request_hash_log_failed" }
        assertTrue(failureEvent.detail.contains("callId=c1"))
        assertTrue("hermes_tool_request_hash" in diagnosticNames())
    }

    @Test
    fun `recordJobCompletion survives a throwing response-hash logger`() {
        sink(logResponseHash = { error("logcat down") })
            .recordJobCompletion(
                HermesJobCompletion(callId = "c1", jobId = "j1", answer = "a", elapsedMs = 1L, serverElapsedMs = null)
            )
        val failureEvent = diagnostics.events.value.single { it.name == "hermes_tool_response_hash_log_failed" }
        assertTrue(failureEvent.detail.contains("callId=c1"))
        assertEquals(VoiceE2EArtifact.HermesAnswer, artifacts.single().first)
        assertEquals("a", artifacts.single().second)
    }

    @Test
    fun `owner check evaluation emits only match missing or mismatch`() {
        assertEquals(HermesOwnerCheck.Match, HermesOwnerCheck.evaluate("owner-123", "owner-123"))
        assertEquals("match", HermesOwnerCheck.Match.wireName)

        assertEquals(HermesOwnerCheck.Missing, HermesOwnerCheck.evaluate("owner-123", null))
        assertEquals(HermesOwnerCheck.Missing, HermesOwnerCheck.evaluate(null, "owner-123"))
        assertEquals(HermesOwnerCheck.Missing, HermesOwnerCheck.evaluate("", "owner-123"))
        assertEquals(HermesOwnerCheck.Missing, HermesOwnerCheck.evaluate("owner-123", "   "))
        assertEquals("missing", HermesOwnerCheck.Missing.wireName)

        assertEquals(HermesOwnerCheck.Mismatch, HermesOwnerCheck.evaluate("owner-123", "owner-456"))
        assertEquals("mismatch", HermesOwnerCheck.Mismatch.wireName)
    }

    @Test
    fun `recovery telemetry event builders redact sentinels and serialize only categorical wire fields`() {
        val promptSentinel = "PROMPT_SECRET_SENTINEL_1111"
        val answerSentinel = "ANSWER_SECRET_SENTINEL_2222"
        val errorSentinel = "ERROR_SECRET_SENTINEL_3333"
        val accessTokenSentinel = "ACCESS_TOKEN_SECRET_SENTINEL_4444"
        val ownerHashSentinel = "OWNER_HASH_SECRET_SENTINEL_5555"
        val correlationSentinel = "CORRELATION_SECRET_SENTINEL_6666"
        val routeSentinel = "https://secret.endpoint.domain.com/secret/path/7777"
        val rawConversationId = "conv-raw-id-123"
        val rawCallId = "call-raw-id-456"
        val rawJobId = "job-raw-id-789"
        val rawSessionId = "session-raw-id-012"
        val rawRecoveryKey = "recovery-key-raw-345"

        val sentinels = listOf(
            promptSentinel,
            answerSentinel,
            errorSentinel,
            accessTokenSentinel,
            ownerHashSentinel,
            correlationSentinel,
            routeSentinel,
            rawConversationId,
            rawCallId,
            rawJobId,
            rawSessionId,
            rawRecoveryKey,
        )

        val regEvent = HermesRecoveryTelemetryEvent.RegistrationRepair.create(
            kind = "registration",
            conversationId = rawConversationId,
            callId = rawCallId,
            jobId = rawJobId,
            trigger = "CallEnded",
            outcome = "registered",
        )
        val repairEvent = HermesRecoveryTelemetryEvent.RegistrationRepair.create(
            kind = "repair",
            conversationId = rawConversationId,
            callId = rawCallId,
            jobId = rawJobId,
            trigger = "StartupRepair",
            outcome = "repaired",
        )
        val relayAcquireEvent = HermesRecoveryTelemetryEvent.RelayAction.create(
            action = "acquire",
            conversationId = rawConversationId,
            callId = rawCallId,
            jobId = rawJobId,
            voiceSessionId = rawSessionId,
        )
        val relayReleaseEvent = HermesRecoveryTelemetryEvent.RelayAction.create(
            action = "release",
            conversationId = rawConversationId,
            callId = rawCallId,
            jobId = rawJobId,
            voiceSessionId = rawSessionId,
        )
        val relayTakeoverEvent = HermesRecoveryTelemetryEvent.RelayAction.create(
            action = "takeover",
            conversationId = rawConversationId,
            callId = rawCallId,
            jobId = rawJobId,
            voiceSessionId = rawSessionId,
        )
        val pollOpEvent = HermesRecoveryTelemetryEvent.OperationClass.create(
            operation = "poll",
            callId = rawCallId,
            jobId = rawJobId,
            classification = "active",
            httpStatus = 200,
        )
        val cancelOpEvent = HermesRecoveryTelemetryEvent.OperationClass.create(
            operation = "cancel",
            callId = rawCallId,
            jobId = rawJobId,
            classification = "cancelled",
            httpStatus = 200,
        )
        val dormantEvent = HermesRecoveryTelemetryEvent.DormantReason.create(
            conversationId = rawConversationId,
            jobId = rawJobId,
            recoveryKey = rawRecoveryKey,
            reason = "auth_unavailable",
        )
        val snapshotDecisionMatch = HermesRecoveryTelemetryEvent.SnapshotDecision.create(
            callId = rawCallId,
            jobId = rawJobId,
            decision = "valid",
            ownerCheck = HermesOwnerCheck.evaluate("expected-owner", "expected-owner"),
            status = "succeeded",
        )
        val snapshotDecisionMissing = HermesRecoveryTelemetryEvent.SnapshotDecision.create(
            callId = rawCallId,
            jobId = rawJobId,
            decision = "dormant",
            ownerCheck = HermesOwnerCheck.evaluate("expected-owner", null),
            status = "expired",
        )
        val snapshotDecisionMismatch = HermesRecoveryTelemetryEvent.SnapshotDecision.create(
            callId = rawCallId,
            jobId = rawJobId,
            decision = "dormant",
            ownerCheck = HermesOwnerCheck.evaluate("expected-owner", "other-owner"),
            status = "failed",
        )
        val terminalCommitEvent = HermesRecoveryTelemetryEvent.TerminalCommit.create(
            conversationId = rawConversationId,
            jobId = rawJobId,
            status = "succeeded",
            disposition = "suppressed_not_enabled",
        )

        val events: List<HermesRecoveryTelemetryEvent> = listOf(
            regEvent,
            repairEvent,
            relayAcquireEvent,
            relayReleaseEvent,
            relayTakeoverEvent,
            pollOpEvent,
            cancelOpEvent,
            dormantEvent,
            snapshotDecisionMatch,
            snapshotDecisionMissing,
            snapshotDecisionMismatch,
            terminalCommitEvent,
        )

        val s = sink()
        for (event in events) {
            val json = event.toJson()
            val logDetail = event.toLogDetail()

            // Verify none of the raw sentinels appear in json or log detail
            for (sentinel in sentinels) {
                assertTrue("Sentinel $sentinel must not appear in JSON: $json", !json.contains(sentinel))
                assertTrue("Sentinel $sentinel must not appear in logDetail: $logDetail", !logDetail.contains(sentinel))
            }

            // Write through sink and verify no throw and recorded
            s.writeRecoveryEvent(event)
        }

        // Verify owner check strings in snapshot decision events
        assertTrue(snapshotDecisionMatch.toJson().contains("\"ownerCheck\":\"match\""))
        assertTrue(snapshotDecisionMissing.toJson().contains("\"ownerCheck\":\"missing\""))
        assertTrue(snapshotDecisionMismatch.toJson().contains("\"ownerCheck\":\"mismatch\""))
        assertTrue(snapshotDecisionMatch.toLogDetail().contains("ownerCheck=match"))
        assertTrue(snapshotDecisionMissing.toLogDetail().contains("ownerCheck=missing"))
        assertTrue(snapshotDecisionMismatch.toLogDetail().contains("ownerCheck=mismatch"))
    }

    @Test
    fun `notification telemetry events redact sentinels and serialize categorical admission, post, retry, suppression, tap, and dismiss`() {
        val promptSentinel = "PROMPT_SECRET_SENTINEL_1111"
        val answerSentinel = "ANSWER_SECRET_SENTINEL_2222"
        val errorSentinel = "ERROR_SECRET_SENTINEL_3333"
        val accessTokenSentinel = "ACCESS_TOKEN_SECRET_SENTINEL_4444"
        val ownerHashSentinel = "OWNER_HASH_SECRET_SENTINEL_5555"
        val correlationSentinel = "CORRELATION_SECRET_SENTINEL_6666"
        val routeSentinel = "https://secret.endpoint.domain.com/secret/path/7777"
        val rawConversationId = "conv-raw-id-999"

        val sentinels = listOf(
            promptSentinel,
            answerSentinel,
            errorSentinel,
            accessTokenSentinel,
            ownerHashSentinel,
            correlationSentinel,
            routeSentinel,
            rawConversationId,
        )

        val admissionEvent = HermesRecoveryTelemetryEvent.NotificationAdmission.create(
            conversationId = rawConversationId,
            disposition = "pending_post",
            context = "recovery",
        )
        val postEvent = HermesRecoveryTelemetryEvent.NotificationPost.create(
            conversationId = rawConversationId,
            attemptOrdinal = 1,
        )
        val retryEvent = HermesRecoveryTelemetryEvent.NotificationRetry.create(
            conversationId = rawConversationId,
            attemptOrdinal = 1,
            nextAttemptDelayMs = 60_000L,
        )
        val suppressionEvent = HermesRecoveryTelemetryEvent.NotificationSuppression.create(
            conversationId = rawConversationId,
            reason = "post_failure_exhausted",
        )
        val tapEvent = HermesRecoveryTelemetryEvent.NotificationTap.create(
            conversationId = rawConversationId,
        )
        val dismissEvent = HermesRecoveryTelemetryEvent.NotificationDismiss.create(
            conversationId = rawConversationId,
        )

        val notificationEvents: List<HermesRecoveryTelemetryEvent> = listOf(
            admissionEvent,
            postEvent,
            retryEvent,
            suppressionEvent,
            tapEvent,
            dismissEvent,
        )

        val s = sink()
        for (event in notificationEvents) {
            val json = event.toJson()
            val logDetail = event.toLogDetail()

            // Verify none of the raw sentinels appear in json or log detail
            for (sentinel in sentinels) {
                assertTrue("Sentinel $sentinel must not appear in JSON: $json", !json.contains(sentinel))
                assertTrue("Sentinel $sentinel must not appear in logDetail: $logDetail", !logDetail.contains(sentinel))
            }

            s.writeRecoveryEvent(event)
        }

        // Verify categorical json serialization
        assertTrue(admissionEvent.toJson().contains("\"type\":\"notification_admission\""))
        assertTrue(admissionEvent.toJson().contains("\"disposition\":\"pending_post\""))
        assertTrue(admissionEvent.toJson().contains("\"context\":\"recovery\""))

        assertTrue(postEvent.toJson().contains("\"type\":\"notification_post\""))
        assertTrue(postEvent.toJson().contains("\"attemptOrdinal\":1"))

        assertTrue(retryEvent.toJson().contains("\"type\":\"notification_retry\""))
        assertTrue(retryEvent.toJson().contains("\"attemptOrdinal\":1"))
        assertTrue(retryEvent.toJson().contains("\"nextAttemptDelayMs\":60000"))

        assertTrue(suppressionEvent.toJson().contains("\"type\":\"notification_suppression\""))
        assertTrue(suppressionEvent.toJson().contains("\"reason\":\"post_failure_exhausted\""))

        assertTrue(tapEvent.toJson().contains("\"type\":\"notification_tap\""))

        assertTrue(dismissEvent.toJson().contains("\"type\":\"notification_dismiss\""))

        // Verify log details
        assertTrue(admissionEvent.toLogDetail().contains("type=notification_admission"))
        assertTrue(admissionEvent.toLogDetail().contains("disposition=pending_post"))
        assertTrue(admissionEvent.toLogDetail().contains("context=recovery"))
        assertTrue(postEvent.toLogDetail().contains("type=notification_post"))
        assertTrue(postEvent.toLogDetail().contains("attemptOrdinal=1"))
        assertTrue(retryEvent.toLogDetail().contains("type=notification_retry"))
        assertTrue(retryEvent.toLogDetail().contains("attemptOrdinal=1"))
        assertTrue(retryEvent.toLogDetail().contains("nextAttemptDelayMs=60000"))
        assertTrue(suppressionEvent.toLogDetail().contains("type=notification_suppression"))
        assertTrue(suppressionEvent.toLogDetail().contains("reason=post_failure_exhausted"))
        assertTrue(tapEvent.toLogDetail().contains("type=notification_tap"))
        assertTrue(dismissEvent.toLogDetail().contains("type=notification_dismiss"))

        // Verify diagnostics recorded
        val names = diagnosticNames()
        assertTrue("hermes_recovery_notification_admission" in names)
        assertTrue("hermes_recovery_notification_post" in names)
        assertTrue("hermes_recovery_notification_retry" in names)
        assertTrue("hermes_recovery_notification_suppression" in names)
        assertTrue("hermes_recovery_notification_tap" in names)
        assertTrue("hermes_recovery_notification_dismiss" in names)
    }
}
