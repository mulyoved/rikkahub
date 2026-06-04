package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OkHttpGeminiLiveVoiceClient(
    httpClient: OkHttpClient,
    codec: GeminiLiveCodec = GeminiLiveCodec(),
) : GeminiLiveVoiceClient {
    private val delegate = TestableGeminiLiveVoiceClient(
        socket = OkHttpGeminiSocket(httpClient),
        codec = codec,
    )

    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        delegate.connect(
            token = token,
            websocketUrl = websocketUrl,
            providerModel = providerModel,
            liveConnectConfig = liveConnectConfig,
            systemInstruction = systemInstruction,
            contextTurns = contextTurns,
            onEvent = onEvent,
        )
    }

    override fun sendAudio(base64Pcm16: String) {
        delegate.sendAudio(base64Pcm16)
    }

    override fun sendToolResponse(callId: String, answer: String) {
        delegate.sendToolResponse(callId = callId, answer = answer)
    }

    override fun close() {
        delegate.close()
    }
}

class OkHttpGeminiSocket(
    private val httpClient: OkHttpClient,
) : GeminiSocket {
    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var generation = 0L
    private var closed = true

    override fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val openGeneration: Long
        val previousWebSocket = synchronized(lock) {
            generation += 1
            openGeneration = generation
            closed = false
            val current = webSocket
            webSocket = null
            current
        }
        previousWebSocket?.close(1000, "replaced")

        val request = Request.Builder()
            .url(geminiLiveUrlWithAccessToken(url = url, token = token))
            .build()
        val newWebSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isCurrent(webSocket, openGeneration)) {
                        onMessage(text)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    val notifyClosed = clearIfCurrent(webSocket, openGeneration)
                    webSocket.close(code, reason)
                    if (notifyClosed) {
                        onClosed(code, reason)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (clearIfCurrent(webSocket, openGeneration)) {
                        onClosed(code, reason)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (clearIfCurrent(webSocket, openGeneration)) {
                        onFailure(t)
                    }
                }
            },
        )
        val closeNewWebSocket = synchronized(lock) {
            if (generation == openGeneration && !closed) {
                webSocket = newWebSocket
                false
            } else {
                true
            }
        }
        if (closeNewWebSocket) {
            newWebSocket.close(1000, "replaced")
        }
    }

    override fun send(text: String): Boolean = synchronized(lock) {
        webSocket
    }?.send(text) == true

    override fun close() {
        val current = synchronized(lock) {
            generation += 1
            closed = true
            val current = webSocket
            webSocket = null
            current
        }
        current?.close(1000, null)
    }

    private fun isCurrent(candidate: WebSocket, candidateGeneration: Long): Boolean = synchronized(lock) {
        !closed && generation == candidateGeneration && webSocket === candidate
    }

    private fun clearIfCurrent(candidate: WebSocket, candidateGeneration: Long): Boolean = synchronized(lock) {
        if (!closed && generation == candidateGeneration && webSocket === candidate) {
            webSocket = null
            true
        } else {
            false
        }
    }
}

internal fun geminiLiveUrlWithAccessToken(url: String, token: String): HttpUrl {
    return Request.Builder()
        .url(url)
        .build()
        .url
        .newBuilder()
        .setQueryParameter("access_token", token)
        .build()
}
