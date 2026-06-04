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
    private var webSocket: WebSocket? = null

    override fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val request = Request.Builder()
            .url(geminiLiveUrlWithAccessToken(url = url, token = token))
            .build()
        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    onMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    this@OkHttpGeminiSocket.webSocket = null
                    onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    this@OkHttpGeminiSocket.webSocket = null
                    onFailure(t)
                }
            },
        )
    }

    override fun send(text: String): Boolean = webSocket?.send(text) == true

    override fun close() {
        webSocket?.close(1000, null)
        webSocket = null
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
