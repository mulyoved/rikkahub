package me.rerere.rikkahub.voiceagent.voicelab

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val DEFAULT_HTTP_CLIENT by lazy { OkHttpClient.Builder().build() }
private val DEV_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2", "10.0.3.2")
private const val ERROR_BODY_PREVIEW_LIMIT = 4096

internal fun interface VoiceLabHttpTransport {
    suspend fun execute(request: Request): Response
}

private class OkHttpVoiceLabTransport(
    private val client: OkHttpClient,
) : VoiceLabHttpTransport {
    override suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isCancelled) {
                            response.close()
                        } else {
                            continuation.resume(response) { _, resource, _ -> resource.close() }
                        }
                    }
                }
            )
        }
}

class VoiceLabMobileApi internal constructor(
    private val baseUrl: String,
    private val credentials: VoiceLabMobileCredentials,
    private val transport: VoiceLabHttpTransport,
    private val json: Json = JsonInstant,
) {
    constructor(
        baseUrl: String,
        credentials: VoiceLabMobileCredentials,
        json: Json = JsonInstant,
    ) : this(
        baseUrl = baseUrl,
        credentials = credentials,
        transport = OkHttpVoiceLabTransport(DEFAULT_HTTP_CLIENT),
        json = json,
    )

    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val parsedBaseUrl = normalizedBaseUrl.toHttpUrl()

    init {
        require(credentials.hermesProfileApiKey.isNotBlank()) {
            "Voice Lab Hermes profile API key must not be blank"
        }
        require(
            credentials.cloudflareClientId == null &&
                credentials.cloudflareClientSecret == null ||
                !credentials.cloudflareClientId.isNullOrBlank() &&
                !credentials.cloudflareClientSecret.isNullOrBlank()
        ) {
            "Voice Lab Cloudflare Access credentials must be provided together or omitted together"
        }
        require(parsedBaseUrl.isHttps || parsedBaseUrl.host in DEV_HTTP_HOSTS) {
            "Voice Lab baseUrl must use HTTPS unless it targets a local development host"
        }
        require(parsedBaseUrl.query == null && parsedBaseUrl.fragment == null) {
            "Voice Lab baseUrl must not include a query or fragment"
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
            transport.execute(request).use { response ->
                val responseText = response.body.string()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Voice Lab request failed ${response.code}: ${responseText.toErrorPreview()}"
                    )
                }
                json.decodeFromString<Res>(responseText)
            }
        }
}

private fun String.toErrorPreview(): String =
    if (length <= ERROR_BODY_PREVIEW_LIMIT) {
        this
    } else {
        take(ERROR_BODY_PREVIEW_LIMIT) + "... [truncated]"
    }
