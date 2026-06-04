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
    private var currentOnEvent: ((GeminiLiveEvent) -> Unit)? = null

    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        currentOnEvent = onEvent
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
        sendOrEmitError(
            text = codec.setupMessage(
                providerModel = providerModel,
                liveConnectConfig = liveConnectConfig,
                systemInstruction = systemInstruction,
            ),
            errorMessage = "Failed to send Gemini setup message",
            onEvent = onEvent,
        )
        if (contextTurns.isNotEmpty()) {
            sendOrEmitError(
                text = codec.clientContentMessage(contextTurns),
                errorMessage = "Failed to send Gemini context message",
                onEvent = onEvent,
            )
        }
    }

    override fun sendAudio(base64Pcm16: String) {
        sendOrEmitError(
            text = codec.realtimeAudioMessage(base64Pcm16),
            errorMessage = "Failed to send Gemini audio message",
            onEvent = currentOnEvent,
        )
    }

    override fun sendToolResponse(callId: String, answer: String) {
        sendOrEmitError(
            text = codec.toolResponseMessage(callId = callId, answer = answer),
            errorMessage = "Failed to send Gemini tool response message",
            onEvent = currentOnEvent,
        )
    }

    override fun close() {
        currentOnEvent = null
        socket.close()
    }

    private fun sendOrEmitError(
        text: String,
        errorMessage: String,
        onEvent: ((GeminiLiveEvent) -> Unit)?,
    ) {
        if (!socket.send(text)) {
            onEvent?.invoke(
                GeminiLiveEvent.Error(
                    message = errorMessage,
                    raw = "",
                )
            )
        }
    }
}
