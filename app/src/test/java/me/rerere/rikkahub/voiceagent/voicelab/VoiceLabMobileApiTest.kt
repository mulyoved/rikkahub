package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLabMobileApiTest {
    @Test
    fun `createSession sends mobile credentials and parses response`() = runBlocking {
        var seenAuthorization = ""
        var seenBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                seenAuthorization = chain.request().header("Authorization").orEmpty()
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

        assertEquals("Bearer profile-api-key", seenAuthorization)
        assertTrue(seenBody.contains("gemini-flash"))
        assertEquals("tok", session.token)
        assertEquals(16000, session.inputSampleRate)
    }
}

private fun okhttp3.RequestBody?.bodyToUtf8(): String {
    if (this == null) return ""
    val buffer = okio.Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
