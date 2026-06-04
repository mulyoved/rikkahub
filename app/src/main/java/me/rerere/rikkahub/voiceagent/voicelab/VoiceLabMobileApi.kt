package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val DEFAULT_HTTP_CLIENT by lazy { OkHttpClient.Builder().build() }
private val DEV_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")

class VoiceLabMobileApi(
    private val baseUrl: String,
    private val credentials: VoiceLabMobileCredentials,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    private val json: Json = JsonInstant,
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val parsedBaseUrl = normalizedBaseUrl.toHttpUrl()

    init {
        require(parsedBaseUrl.isHttps || parsedBaseUrl.host in DEV_HTTP_HOSTS) {
            "Voice Lab baseUrl must use HTTPS unless it targets a local development host"
        }
    }

    suspend fun createSession(modelId: String): MobileVoiceSessionResponse =
        postJson(
            path = "/api/mobile/voice/session",
            body = MobileVoiceSessionRequest(modelId = modelId),
        )

    suspend fun askHermes(
        callId: String,
        prompt: String,
        profileId: String? = null,
    ): MobileHermesResponse =
        postJson(
            path = "/api/mobile/hermes",
            body = MobileHermesRequest(callId = callId, prompt = prompt, profileId = profileId),
        )

    private suspend inline fun <reified Req, reified Res> postJson(path: String, body: Req): Res =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(normalizedBaseUrl + path)
                .addHeader("Authorization", "Bearer ${credentials.hermesProfileApiKey}")
                .apply {
                    credentials.cloudflareClientId?.let { addHeader("CF-Access-Client-Id", it) }
                    credentials.cloudflareClientSecret?.let { addHeader("CF-Access-Client-Secret", it) }
                }
                .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException("Voice Lab request failed ${response.code}: $responseText")
                }
                json.decodeFromString<Res>(responseText)
            }
        }
}
