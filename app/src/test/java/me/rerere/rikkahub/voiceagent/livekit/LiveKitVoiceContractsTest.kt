package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpTransport
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceContractsTest {
    @Test
    fun `create LiveKit session uses exact mobile request and parses response`() = runBlocking {
        var seenRequest: Request? = null
        val api = HermesVoiceApi(
            baseUrl = "https://hermes-voice.example.test/base",
            credentials = HermesVoiceCredentials(deviceApiKey = "device-api-key"),
            transport = transportFor { request ->
                seenRequest = request
                responseFor(
                    request,
                    """
                    {
                      "livekitUrl":"wss://project.livekit.cloud",
                      "participantToken":"participant-secret-token",
                      "roomName":"rikka-0123456789abcdef0123456789abcdef",
                      "voiceSessionId":"lvs_0123456789abcdef0123456789abcdef",
                      "mobileParticipantIdentity":"mobile-lvs_0123456789abcdef0123456789abcdef",
                      "agentParticipantIdentity":"agent-lvs_0123456789abcdef0123456789abcdef",
                      "dispatchId":"AD_123",
                      "expiresAt":"2026-07-20T02:00:00Z"
                    }
                    """.trimIndent(),
                )
            },
        )

        val result = api.createLiveKitSession(
            conversationId = "018f0000-0000-7000-8000-000000000001",
            traceId = "trace-1",
        )

        val request = requireNotNull(seenRequest)
        assertEquals("POST", request.method)
        assertEquals("/base/api/mobile/livekit/session", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        assertEquals("Bearer device-api-key", request.header("Authorization"))
        assertEquals(setOf("Authorization"), request.headers.names())
        assertEquals(
            "{\"conversationId\":\"018f0000-0000-7000-8000-000000000001\",\"traceId\":\"trace-1\"}",
            request.body.bodyToUtf8(),
        )
        assertEquals("wss://project.livekit.cloud", result.livekitUrl)
        assertEquals("participant-secret-token", result.participantToken)
        assertEquals("rikka-0123456789abcdef0123456789abcdef", result.roomName)
        assertEquals("lvs_0123456789abcdef0123456789abcdef", result.voiceSessionId)
        assertEquals("mobile-lvs_0123456789abcdef0123456789abcdef", result.mobileParticipantIdentity)
        assertEquals("agent-lvs_0123456789abcdef0123456789abcdef", result.agentParticipantIdentity)
        assertEquals("AD_123", result.dispatchId)
        assertEquals("2026-07-20T02:00:00Z", result.expiresAt)
    }

    @Test
    fun `LiveKit session details redact URL and participant token`() {
        val details = validDetails()

        val rendered = details.toString()

        assertFalse(rendered.contains(details.livekitUrl))
        assertFalse(rendered.contains(details.participantToken))
        assertTrue(rendered.contains("livekitUrl=[redacted]"))
        assertTrue(rendered.contains("participantToken=[redacted]"))
        assertTrue(rendered.contains(details.voiceSessionId))
    }

    @Test
    fun `LiveKit session request and response reject invalid identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveKitSessionRequest(conversationId = "conversation/id", traceId = "trace-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validDetails().copy(dispatchId = "dispatch id")
        }
    }

    @Test
    fun `LiveKit session details require a secure websocket URL`() {
        listOf(
            "https://project.livekit.cloud",
            "ws://project.livekit.cloud",
            "project.livekit.cloud",
            "wss://user:secret@project.livekit.cloud",
            "wss://project.livekit.cloud/room",
            "wss://project.livekit.cloud?region=test",
            "wss://project.livekit.cloud#fragment",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                validDetails().copy(livekitUrl = url)
            }
        }
    }

    @Test
    fun `invalid LiveKit response is rejected without revealing URL or token`() {
        val api = HermesVoiceApi(
            baseUrl = "https://hermes-voice.example.test",
            credentials = HermesVoiceCredentials(deviceApiKey = "device-api-key"),
            transport = transportFor { request ->
                responseFor(
                    request,
                    """
                    {
                      "livekitUrl":"wss://private-project.livekit.cloud",
                      "participantToken":"participant-secret-token",
                      "roomName":"invalid room",
                      "voiceSessionId":"lvs_valid",
                      "mobileParticipantIdentity":"mobile-lvs_valid",
                      "agentParticipantIdentity":"agent-lvs_valid",
                      "dispatchId":"AD_123",
                      "expiresAt":"2026-07-20T02:00:00Z"
                    }
                    """.trimIndent(),
                )
            },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createLiveKitSession("conversation-1", "trace-1") }
        }

        assertFalse(error.message.orEmpty().contains("private-project"))
        assertFalse(error.message.orEmpty().contains("participant-secret-token"))
        assertTrue(error.message.orEmpty().contains("[redacted]"))
    }

    private fun validDetails() = LiveKitSessionDetails(
        livekitUrl = "wss://project.livekit.cloud",
        participantToken = "participant-secret-token",
        roomName = "rikka-0123456789abcdef0123456789abcdef",
        voiceSessionId = "lvs_0123456789abcdef0123456789abcdef",
        mobileParticipantIdentity = "mobile-lvs_0123456789abcdef0123456789abcdef",
        agentParticipantIdentity = "agent-lvs_0123456789abcdef0123456789abcdef",
        dispatchId = "AD_123",
        expiresAt = "2026-07-20T02:00:00Z",
    )
}

private fun transportFor(handler: (Request) -> Response): HermesVoiceHttpTransport =
    HermesVoiceHttpTransport(handler)

private fun responseFor(request: Request, body: String): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody())
        .build()

private fun okhttp3.RequestBody?.bodyToUtf8(): String {
    if (this == null) return ""
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
