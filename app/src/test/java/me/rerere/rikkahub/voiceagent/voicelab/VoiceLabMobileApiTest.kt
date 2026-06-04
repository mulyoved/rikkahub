package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLabMobileApiTest {
    @Test
    fun `createSession sends mobile credentials and parses response`() = runBlocking {
        var seenRequest: Request? = null
        var seenBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenRequest = chain.request()
                seenBody = chain.request().body.bodyToUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "token":"tok",
                          "modelId":"gemini-flash",
                          "providerModel":"gemini-3.1-flash-live-preview",
                          "apiVersion":"v1alpha",
                          "websocketUrl":"wss://example.test/live",
                          "inputSampleRate":16000,
                          "outputSampleRate":24000,
                          "liveConnectConfig":{"responseModalities":["AUDIO"],"inputAudioTranscription":{},"outputAudioTranscription":{},"tools":[]}
                        }
                        """.trimIndent().toResponseBody()
                    )
                    .build()
            }
            .build()

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(
                hermesProfileApiKey = "profile-api-key",
                cloudflareClientId = "cf-id",
                cloudflareClientSecret = "cf-secret",
            ),
            httpClient = client,
        )

        val session = api.createSession("gemini-flash")

        val request = requireNotNull(seenRequest)
        assertEquals("POST", request.method)
        assertEquals("/api/mobile/voice/session", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        assertEquals("Bearer profile-api-key", request.header("Authorization"))
        assertEquals("cf-id", request.header("CF-Access-Client-Id"))
        assertEquals("cf-secret", request.header("CF-Access-Client-Secret"))
        assertTrue(seenBody.contains("\"modelId\":\"gemini-flash\""))
        assertEquals("tok", session.token)
        assertEquals(16000, session.inputSampleRate)
    }

    @Test
    fun `askHermes omits default profileId and parses response`() = runBlocking {
        var seenRequest: Request? = null
        var seenBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenRequest = chain.request()
                seenBody = chain.request().body.bodyToUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "callId":"call-1",
                          "answer":"done",
                          "model":"ms-agent",
                          "profileId":"default",
                          "profileLabel":"Default"
                        }
                        """.trimIndent().toResponseBody()
                    )
                    .build()
            }
            .build()

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test/base",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            httpClient = client,
        )

        val response = api.askHermes(callId = "call-1", prompt = "status")

        val request = requireNotNull(seenRequest)
        assertEquals("/base/api/mobile/hermes", request.url.encodedPath)
        assertEquals("Bearer profile-api-key", request.header("Authorization"))
        assertNull(request.header("CF-Access-Client-Id"))
        assertNull(request.header("CF-Access-Client-Secret"))
        assertTrue(seenBody.contains("\"callId\":\"call-1\""))
        assertTrue(seenBody.contains("\"prompt\":\"status\""))
        assertFalse(seenBody.contains("profileId"))
        assertEquals("done", response.answer)
        assertEquals("default", response.profileId)
    }

    @Test
    fun `askHermes sends explicit profileId`() = runBlocking {
        var seenBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenBody = chain.request().body.bodyToUtf8()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """
                        {
                          "callId":"call-1",
                          "answer":"done",
                          "model":"ms-agent",
                          "profileId":"research",
                          "profileLabel":"Research"
                        }
                        """.trimIndent().toResponseBody()
                    )
                    .build()
            }
            .build()

        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            httpClient = client,
        )

        api.askHermes(callId = "call-1", prompt = "status", profileId = "research")

        assertTrue(seenBody.contains("\"profileId\":\"research\""))
    }

    @Test
    fun `non successful responses include status and body`() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("""{"error":"down"}""".toResponseBody())
                    .build()
            }
            .build()
        val api = VoiceLabMobileApi(
            baseUrl = "https://voice-lab.example.test",
            credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key"),
            httpClient = client,
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { api.createSession("gemini-flash") }
        }

        assertTrue(error.message.orEmpty().contains("503"))
        assertTrue(error.message.orEmpty().contains("down"))
    }

    @Test
    fun `baseUrl must be https unless it is a local development host`() {
        val credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-api-key")

        assertThrows(IllegalArgumentException::class.java) {
            VoiceLabMobileApi(baseUrl = "http://voice-lab.example.test", credentials = credentials)
        }

        VoiceLabMobileApi(baseUrl = "http://127.0.0.1:8787", credentials = credentials)
        VoiceLabMobileApi(baseUrl = "http://10.0.2.2:8787", credentials = credentials)
    }
}

private fun okhttp3.RequestBody?.bodyToUtf8(): String {
    if (this == null) return ""
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
