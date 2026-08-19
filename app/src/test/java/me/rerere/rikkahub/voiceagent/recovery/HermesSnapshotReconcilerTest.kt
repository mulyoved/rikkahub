package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.livekit.voiceSha256
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesSnapshotReconcilerTest {

    private val sampleConversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val endpointHash = "endpoint-binding-hash-123"
    private val ownerHash = "owner-hash-abc"
    private val voiceSessionId = "lvs_session_1"
    private val voiceSessionHash = voiceSha256(voiceSessionId)
    private val argumentHash = "sha256:argument_hash_xyz"
    private val callId = "call-1"
    private val jobId = "job-1"

    private val defaultEntry = HermesRecoveryEntry(
        recoveryKey = "test-recovery-key",
        conversationId = sampleConversationId,
        callId = callId,
        jobId = jobId,
        producer = HERMES_PRODUCER,
        originalVoiceSessionHash = voiceSessionHash,
        originalArgumentHash = argumentHash,
        originalOwnerHash = ownerHash,
        originalEndpointHash = endpointHash,
        acceptedAt = 1000L,
        automaticDeadlineAt = 1000L + 86400000L,
        recoveryState = HermesRecoveryState.Active,
        lastAttemptAt = 1000L,
    )

    private val reconciler = HermesSnapshotReconciler()

    private fun correlationJson(
        cOwnerHash: String = ownerHash,
        cConversationId: String = sampleConversationId.toString(),
        cVoiceSessionId: String = voiceSessionId,
        cTraceId: String = "trace-1",
        cArgumentHash: String = argumentHash,
    ) = buildJsonObject {
        put("ownerHash", cOwnerHash)
        put("conversationId", cConversationId)
        put("voiceSessionId", cVoiceSessionId)
        put("traceId", cTraceId)
        put("argumentHash", cArgumentHash)
    }

    private fun failureJson(
        kind: String = "timeout",
        safeMessage: String = "Execution timed out",
        safeSummary: String = "Timeout",
        retryable: Boolean = false,
        source: String = "hermes",
    ) = buildJsonObject {
        put("kind", kind)
        put("safeMessage", safeMessage)
        put("safeSummary", safeSummary)
        put("retryable", retryable)
        put("source", source)
    }

    // --- Endpoint & Owner Binding Checks ---

    @Test
    fun `endpoint binding mismatch returns Dormant AuthUnavailable`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = "different-endpoint-hash",
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable),
            result,
        )
    }

    @Test
    fun `absent owner header returns Dormant AuthUnavailable`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = null,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable),
            result,
        )
    }

    @Test
    fun `blank owner header returns Dormant AuthUnavailable`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = "   ",
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable),
            result,
        )
    }

    @Test
    fun `mismatched owner header returns Dormant AuthUnavailable`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = "different-owner-hash",
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable),
            result,
        )
    }

    // --- Identity & Correlation Mismatches ---

    @Test
    fun `exact job ID mismatch returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", "wrong-job-id")
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `call ID mismatch returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", "wrong-call-id")
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `producer mismatch in entry returns Dormant ProtocolMismatch`() {
        val nonHermesEntry = defaultEntry.copy(producer = "custom-agent")
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = nonHermesEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `correlation conversation ID mismatch returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson(cConversationId = Uuid.random().toString()))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `correlation voice session mismatch returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson(cVoiceSessionId = "other-session"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `correlation argument hash mismatch returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson(cArgumentHash = "sha256:different_arg"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `correlation owner hash mismatch with response header returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson(cOwnerHash = "different-owner"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    // --- Status and Payload Shape Validation ---

    @Test
    fun `unknown raw status returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "paused")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `succeeded status without answer returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("resultHash", "sha256:some_hash")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `succeeded status with blank answer returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", "   ")
            put("resultHash", "sha256:some_hash")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `succeeded status without result hash returns Dormant ProtocolMismatch`() {
        val answer = "Result of 2+2 is 4"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `succeeded status with wrong result hash returns Dormant ProtocolMismatch`() {
        val answer = "Result of 2+2 is 4"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("resultHash", "sha256:0000000000000000000000000000000000000000000000000000000000000000")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `succeeded status with failure object returns Dormant ProtocolMismatch`() {
        val answer = "Result of 2+2 is 4"
        val expectedHash = sha256Hex(answer)
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("resultHash", "sha256:$expectedHash")
            put("failure", failureJson())
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `failed status without failure object returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "failed")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `failed status with answer or resultHash returns Dormant ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "failed")
            put("answer", "should not be here")
            put("failure", failureJson())
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    // --- Valid Snapshots ---

    @Test
    fun `valid queued status returns Valid with Queued status`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "queued")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Queued, valid.status)
        assertNull(valid.answer)
        assertNull(valid.error)
        assertNull(valid.resultHash)
    }

    @Test
    fun `valid running status returns Valid with Running status`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "running")
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Running, valid.status)
        assertNull(valid.answer)
        assertNull(valid.error)
        assertNull(valid.resultHash)
    }

    @Test
    fun `valid succeeded status returns Valid with Complete status and exact answer and resultHash`() {
        val answer = "Computed result is 42"
        val expectedHash = "sha256:" + sha256Hex(answer)
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "succeeded")
            put("answer", answer)
            put("resultHash", expectedHash)
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Complete, valid.status)
        assertEquals(answer, valid.answer)
        assertEquals(expectedHash, valid.resultHash)
        assertNull(valid.error)
    }

    @Test
    fun `valid failed status returns Valid with Failed status and safeMessage in error`() {
        val safeMsg = "Tool crashed with safe failure"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "failed")
            put("failure", failureJson(kind = "execution_failed", safeMessage = safeMsg))
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Failed, valid.status)
        assertEquals(safeMsg, valid.error)
        assertNull(valid.answer)
        assertNull(valid.resultHash)
    }

    @Test
    fun `valid expired status returns Valid with Expired status and safeMessage in error`() {
        val safeMsg = "Job was not completed in time"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "expired")
            put("failure", failureJson(kind = "expired", safeMessage = safeMsg))
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Expired, valid.status)
        assertEquals(safeMsg, valid.error)
        assertNull(valid.answer)
        assertNull(valid.resultHash)
    }

    @Test
    fun `valid canceled status returns Valid with Canceled status and safeMessage in error`() {
        val safeMsg = "Job was canceled by user"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", callId)
            put("status", "canceled")
            put("failure", failureJson(kind = "canceled", safeMessage = safeMsg))
            put("correlation", correlationJson())
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Canceled, valid.status)
        assertEquals(safeMsg, valid.error)
        assertNull(valid.answer)
        assertNull(valid.resultHash)
    }

    // --- Expired Tombstone Tests ---

    @Test
    fun `valid expired tombstone produces Valid Expired snapshot`() {
        val safeMsg = "Job expired and evicted"
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("status", "expired")
            put("failure", failureJson(kind = "expired", safeMessage = safeMsg))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertTrue(result is SnapshotReconciliation.Valid)
        val valid = (result as SnapshotReconciliation.Valid).snapshot
        assertEquals(jobId, valid.jobId)
        assertEquals(callId, valid.callId)
        assertEquals(HermesQueueStatus.Expired, valid.status)
        assertEquals(safeMsg, valid.error)
        assertNull(valid.answer)
        assertNull(valid.resultHash)
    }

    @Test
    fun `tombstone with unexpected callId is ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("callId", "unexpected-call")
            put("status", "expired")
            put("failure", failureJson(kind = "expired"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `tombstone with non-expired status is ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("status", "failed")
            put("failure", failureJson(kind = "expired"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `tombstone with non-expired failure kind is ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("status", "expired")
            put("failure", failureJson(kind = "internal_error"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `tombstone with answer or resultHash is ProtocolMismatch`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("status", "expired")
            put("answer", "some answer")
            put("failure", failureJson(kind = "expired"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = ownerHash,
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.ProtocolMismatch),
            result,
        )
    }

    @Test
    fun `tombstone with mismatched owner returns Dormant AuthUnavailable`() {
        val payload = buildJsonObject {
            put("jobId", jobId)
            put("status", "expired")
            put("failure", failureJson(kind = "expired"))
        }
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = "wrong-owner",
            payload = payload,
        )

        val result = reconciler.reconcile(
            currentEndpointBindingHash = endpointHash,
            entry = defaultEntry,
            response = response,
        )

        assertEquals(
            SnapshotReconciliation.Dormant(HermesDormantReason.AuthUnavailable),
            result,
        )
    }
}
