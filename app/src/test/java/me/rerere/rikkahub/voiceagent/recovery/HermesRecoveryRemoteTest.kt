package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpTransport
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRecoveryRemoteTest {
    @Test
    fun `poll sends GET request to recovery jobs endpoint and captures owner hash`() = runBlocking {
        var seenRequest: Request? = null
        val transport = transportFor { request ->
            seenRequest = request
            responseFor(
                request = request,
                code = 200,
                headers = mapOf("X-Hermes-Owner-Hash" to "sha256:owner_123"),
                body = """
                {
                  "jobId":"job_123",
                  "callId":"call_1",
                  "status":"running",
                  "acceptedAt":"2026-08-18T12:00:00Z"
                }
                """.trimIndent(),
            )
        }
        val api = HermesVoiceApi(
            baseUrl = "https://voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "dev-key"),
            transport = transport,
        )
        val remote = HermesRecoveryRemote(api)

        val response = remote.poll("job_123")

        val req = requireNotNull(seenRequest)
        assertEquals("GET", req.method)
        assertEquals("/api/mobile/hermes/jobs/job_123", req.url.encodedPath)
        assertEquals("Bearer dev-key", req.header("Authorization"))
        assertEquals(200, response.statusCode)
        assertEquals("sha256:owner_123", response.ownerHash)
        assertNotNull(response.payload)
        assertEquals("job_123", response.payload?.get("jobId")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `cancel sends DELETE request to recovery jobs endpoint and captures owner hash`() = runBlocking {
        var seenRequest: Request? = null
        val transport = transportFor { request ->
            seenRequest = request
            responseFor(
                request = request,
                code = 200,
                headers = mapOf("X-Hermes-Owner-Hash" to "sha256:owner_cancel"),
                body = """
                {
                  "jobId":"job_123",
                  "status":"canceled"
                }
                """.trimIndent(),
            )
        }
        val api = HermesVoiceApi(
            baseUrl = "https://voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "dev-key"),
            transport = transport,
        )
        val remote = HermesRecoveryRemote(api)

        val response = remote.cancel("job_123")

        val req = requireNotNull(seenRequest)
        assertEquals("DELETE", req.method)
        assertEquals("/api/mobile/hermes/jobs/job_123", req.url.encodedPath)
        assertEquals(200, response.statusCode)
        assertEquals("sha256:owner_cancel", response.ownerHash)
        assertNotNull(response.payload)
    }

    @Test
    fun `malformed JSON in response produces payload null without throwing`() = runBlocking {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 200,
                headers = mapOf("X-Hermes-Owner-Hash" to "sha256:owner_abc"),
                body = "<html><body>Not JSON</body></html>",
            )
        }
        val api = HermesVoiceApi(
            baseUrl = "https://voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "dev-key"),
            transport = transport,
        )
        val remote = HermesRecoveryRemote(api)

        val response = remote.poll("job_bad_json")

        assertEquals(200, response.statusCode)
        assertEquals("sha256:owner_abc", response.ownerHash)
        assertNull(response.payload)
    }

    @Test
    fun `non-2xx responses return raw status and payload without throwing`() = runBlocking {
        val transport = transportFor { request ->
            responseFor(
                request = request,
                code = 404,
                headers = mapOf("X-Hermes-Owner-Hash" to "sha256:owner_404"),
                body = """
                {
                  "jobId":"job_missing",
                  "status":"expired",
                  "failure":{
                    "kind":"expired",
                    "safeMessage":"Hermes job not found",
                    "safeSummary":"Hermes job not found",
                    "retryable":false,
                    "source":"hermes_voice"
                  }
                }
                """.trimIndent(),
            )
        }
        val api = HermesVoiceApi(
            baseUrl = "https://voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "dev-key"),
            transport = transport,
        )
        val remote = HermesRecoveryRemote(api)

        val response = remote.poll("job_missing")

        assertEquals(404, response.statusCode)
        assertEquals("sha256:owner_404", response.ownerHash)
        assertNotNull(response.payload)
    }

    @Test
    fun `parseHermesRecoverySnapshot preserves unknown rawStatus without mapping to Failed`() {
        val payload = buildJsonObject {
            put("jobId", "job_unknown_status")
            put("callId", "call_unk")
            put("status", "in_limbo")
            put("acceptedAt", "2026-08-18T12:00:00Z")
        }

        val snapshot = parseHermesRecoverySnapshot(payload)

        assertNotNull(snapshot)
        assertEquals("job_unknown_status", snapshot?.jobId)
        assertEquals("call_unk", snapshot?.callId)
        assertEquals("in_limbo", snapshot?.rawStatus)
        assertEquals("2026-08-18T12:00:00Z", snapshot?.acceptedAt)
        assertNull(snapshot?.failure)
    }

    @Test
    fun `parseHermesRecoverySnapshot parses all five structured failure fields`() {
        val payload = buildJsonObject {
            put("jobId", "job_failed")
            put("status", "failed")
            putJsonObject("failure") {
                put("kind", "hermes_failed")
                put("safeMessage", "Hermes provider failed")
                put("safeSummary", "Safe summary of failure")
                put("retryable", true)
                put("source", "hermes")
            }
        }

        val snapshot = parseHermesRecoverySnapshot(payload)

        assertNotNull(snapshot)
        assertEquals("job_failed", snapshot?.jobId)
        assertEquals("failed", snapshot?.rawStatus)
        val failure = requireNotNull(snapshot?.failure)
        assertEquals("hermes_failed", failure.kind)
        assertEquals("Hermes provider failed", failure.safeMessage)
        assertEquals("Safe summary of failure", failure.safeSummary)
        assertTrue(failure.retryable)
        assertEquals("hermes", failure.source)
    }

    @Test
    fun `parseHermesRecoverySnapshot rejects present but malformed failure`() {
        // Missing safeMessage
        val missingMessage = buildJsonObject {
            put("jobId", "j1")
            put("status", "failed")
            putJsonObject("failure") {
                put("kind", "hermes_failed")
                put("safeSummary", "Summary")
                put("retryable", false)
                put("source", "hermes")
            }
        }
        assertNull(parseHermesRecoverySnapshot(missingMessage))

        // retryable is a String instead of Boolean
        val stringRetryable = buildJsonObject {
            put("jobId", "j1")
            put("status", "failed")
            putJsonObject("failure") {
                put("kind", "hermes_failed")
                put("safeMessage", "Msg")
                put("safeSummary", "Summary")
                put("retryable", "true")
                put("source", "hermes")
            }
        }
        assertNull(parseHermesRecoverySnapshot(stringRetryable))

        // failure is a string instead of object
        val primitiveFailure = buildJsonObject {
            put("jobId", "j1")
            put("status", "failed")
            put("failure", "server error")
        }
        assertNull(parseHermesRecoverySnapshot(primitiveFailure))

        // missing kind
        val missingKind = buildJsonObject {
            put("jobId", "j1")
            put("status", "failed")
            putJsonObject("failure") {
                put("safeMessage", "Msg")
                put("safeSummary", "Summary")
                put("retryable", false)
                put("source", "hermes")
            }
        }
        assertNull(parseHermesRecoverySnapshot(missingKind))

        // missing source
        val missingSource = buildJsonObject {
            put("jobId", "j1")
            put("status", "failed")
            putJsonObject("failure") {
                put("kind", "hermes_failed")
                put("safeMessage", "Msg")
                put("safeSummary", "Summary")
                put("retryable", false)
            }
        }
        assertNull(parseHermesRecoverySnapshot(missingSource))
    }

    @Test
    fun `parseHermesRecoverySnapshot parses complete correlation`() {
        val payload = buildJsonObject {
            put("jobId", "job_corr")
            put("status", "running")
            putJsonObject("correlation") {
                put("ownerHash", "sha256:owner_hash")
                put("conversationId", "conv_123")
                put("voiceSessionId", "session_456")
                put("traceId", "trace_789")
                put("argumentHash", "sha256:arg_hash")
            }
        }

        val snapshot = parseHermesRecoverySnapshot(payload)

        assertNotNull(snapshot)
        val corr = requireNotNull(snapshot?.correlation)
        assertEquals("sha256:owner_hash", corr.ownerHash)
        assertEquals("conv_123", corr.conversationId)
        assertEquals("session_456", corr.voiceSessionId)
        assertEquals("trace_789", corr.traceId)
        assertEquals("sha256:arg_hash", corr.argumentHash)
    }

    @Test
    fun `parseHermesRecoverySnapshot rejects malformed correlation`() {
        // Missing argumentHash
        val missingArg = buildJsonObject {
            put("jobId", "j1")
            put("status", "running")
            putJsonObject("correlation") {
                put("ownerHash", "sha256:owner")
                put("conversationId", "conv_1")
                put("voiceSessionId", "session_1")
                put("traceId", "trace_1")
            }
        }
        assertNull(parseHermesRecoverySnapshot(missingArg))

        // correlation is a string primitive
        val primitiveCorr = buildJsonObject {
            put("jobId", "j1")
            put("status", "running")
            put("correlation", "correlation_string")
        }
        assertNull(parseHermesRecoverySnapshot(primitiveCorr))
    }

    @Test
    fun `parseHermesRecoverySnapshot parses answer and resultHash`() {
        val payload = buildJsonObject {
            put("jobId", "job_success")
            put("callId", "call_1")
            put("status", "succeeded")
            put("answer", "Here is your answer")
            put("resultHash", "sha256:result_hash_123")
        }

        val snapshot = parseHermesRecoverySnapshot(payload)

        assertNotNull(snapshot)
        assertEquals("Here is your answer", snapshot?.answer)
        assertEquals("sha256:result_hash_123", snapshot?.resultHash)
    }

    @Test
    fun `parseHermesRecoverySnapshot rejects missing jobId or status`() {
        val missingJobId = buildJsonObject {
            put("status", "succeeded")
        }
        assertNull(parseHermesRecoverySnapshot(missingJobId))

        val missingStatus = buildJsonObject {
            put("jobId", "job_1")
        }
        assertNull(parseHermesRecoverySnapshot(missingStatus))
    }

    @Test
    fun `redaction in HermesRecoveryHttpResponse toString`() {
        val response = HermesRecoveryHttpResponse(
            statusCode = 200,
            ownerHash = "sha256:secret_owner_proof",
            payload = buildJsonObject {
                put("answer", "super_secret_answer")
                put("token", "secret_token")
            },
        )
        val rendered = response.toString()

        assertFalse(rendered.contains("secret_owner_proof"))
        assertFalse(rendered.contains("super_secret_answer"))
        assertFalse(rendered.contains("secret_token"))
    }

    @Test
    fun `redaction in HermesRecoverySnapshot toString`() {
        val snapshot = HermesRecoverySnapshot(
            jobId = "job_public_1",
            callId = "call_public_1",
            rawStatus = "succeeded",
            acceptedAt = "2026-08-18T12:00:00Z",
            answer = "super_private_answer_content",
            resultHash = "sha256:result_hash",
            failure = HermesRecoveryFailure(
                kind = "hermes_failed",
                safeMessage = "super_private_failure_message",
                safeSummary = "super_private_summary",
                retryable = false,
                source = "hermes",
            ),
            correlation = HermesRecoveryCorrelation(
                ownerHash = "secret_owner_hash",
                conversationId = "secret_conversation_id",
                voiceSessionId = "secret_session_id",
                traceId = "secret_trace_id",
                argumentHash = "secret_argument_hash",
            ),
        )
        val rendered = snapshot.toString()

        assertFalse(rendered.contains("super_private_answer_content"))
        assertFalse(rendered.contains("super_private_failure_message"))
        assertFalse(rendered.contains("super_private_summary"))
        assertFalse(rendered.contains("secret_owner_hash"))
        assertFalse(rendered.contains("secret_conversation_id"))
        assertFalse(rendered.contains("secret_session_id"))
        assertFalse(rendered.contains("secret_trace_id"))
        assertFalse(rendered.contains("secret_argument_hash"))
    }

    @Test
    fun `redaction in HermesRecoveryFailure and HermesRecoveryCorrelation toString`() {
        val failure = HermesRecoveryFailure(
            kind = "hermes_failed",
            safeMessage = "private error details",
            safeSummary = "private summary details",
            retryable = false,
            source = "hermes",
        )
        val failureString = failure.toString()
        assertFalse(failureString.contains("private error details"))
        assertFalse(failureString.contains("private summary details"))

        val correlation = HermesRecoveryCorrelation(
            ownerHash = "secret_owner_value",
            conversationId = "secret_conv_value",
            voiceSessionId = "secret_session_value",
            traceId = "secret_trace_value",
            argumentHash = "secret_arg_value",
        )
        val correlationString = correlation.toString()
        assertFalse(correlationString.contains("secret_owner_value"))
        assertFalse(correlationString.contains("secret_conv_value"))
        assertFalse(correlationString.contains("secret_session_value"))
        assertFalse(correlationString.contains("secret_trace_value"))
        assertFalse(correlationString.contains("secret_arg_value"))
    }
}

private fun transportFor(handler: (Request) -> Response): HermesVoiceHttpTransport =
    object : HermesVoiceHttpTransport {
        override suspend fun execute(request: Request): Response = handler(request)
    }

private fun responseFor(
    request: Request,
    code: Int = 200,
    message: String = "OK",
    headers: Map<String, String> = emptyMap(),
    body: String,
): Response {
    val builder = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(body.toResponseBody())
    headers.forEach { (name, value) -> builder.addHeader(name, value) }
    return builder.build()
}
