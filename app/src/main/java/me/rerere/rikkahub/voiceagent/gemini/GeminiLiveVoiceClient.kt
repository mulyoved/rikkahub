package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonObject

interface GeminiLiveVoiceClient {
    suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    )

    fun sendAudio(base64Pcm16: String)

    fun sendToolResponse(callId: String, answer: String)

    fun close()
}

interface GeminiSocket {
    fun open(
        url: String,
        token: String,
        onMessage: (String) -> Unit,
        onClosed: (Int, String) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun send(text: String): Boolean

    fun close()
}

class TestableGeminiLiveVoiceClient(
    private val socket: GeminiSocket,
    private val codec: GeminiLiveCodec = GeminiLiveCodec(),
) : GeminiLiveVoiceClient {
    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        socket.open(
            url = websocketUrl,
            token = token,
            onMessage = { message ->
                onEvent(codec.parseServerMessage(message))
            },
            onClosed = { code, reason ->
                onEvent(
                    GeminiLiveEvent.Error(
                        message = "WebSocket closed: $code $reason",
                        raw = "",
                    )
                )
            },
            onFailure = { error ->
                onEvent(
                    GeminiLiveEvent.Error(
                        message = error.message ?: error.javaClass.simpleName,
                        raw = "",
                    )
                )
            },
        )
        socket.send(
            codec.setupMessage(
                providerModel = providerModel,
                liveConnectConfig = liveConnectConfig,
                systemInstruction = systemInstruction,
            )
        )
        if (contextTurns.isNotEmpty()) {
            socket.send(codec.clientContentMessage(contextTurns))
        }
    }

    override fun sendAudio(base64Pcm16: String) {
        socket.send(codec.realtimeAudioMessage(base64Pcm16))
    }

    override fun sendToolResponse(callId: String, answer: String) {
        socket.send(codec.toolResponseMessage(callId = callId, answer = answer))
    }

    override fun close() {
        socket.close()
    }
}
