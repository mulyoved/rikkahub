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

    fun sendToolResponse(callId: String, answer: String): Boolean

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
    private val lock = Any()
    private val lifecycleLock = Any()
    private var nextGeneration = 0L
    private var sessionState: SessionState? = null

    override suspend fun connect(
        token: String,
        websocketUrl: String,
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
        contextTurns: List<GeminiContentTurn>,
        onEvent: (GeminiLiveEvent) -> Unit,
    ) {
        val setupMessage = codec.setupMessage(
            providerModel = providerModel,
            liveConnectConfig = liveConnectConfig,
            systemInstruction = systemInstruction,
        )
        val pendingContext = contextTurns
            .takeIf { it.isNotEmpty() }
            ?.let {
                PendingMessage(
                    text = codec.clientContentMessage(it),
                    errorMessage = "Failed to send Gemini context message",
                )
            }
        val setupError = synchronized(lifecycleLock) {
            val generation = synchronized(lock) {
                sessionState?.closed = true
                val newGeneration = nextGeneration + 1
                nextGeneration = newGeneration
                sessionState = SessionState(
                    generation = newGeneration,
                    onEvent = onEvent,
                    setupComplete = false,
                    flushingSetupComplete = false,
                    pendingContext = pendingContext,
                    pendingOutboundMessages = mutableListOf(),
                    closed = false,
                )
                newGeneration
            }
            socket.open(
                url = websocketUrl,
                token = token,
                onMessage = { message ->
                    handleMessage(generation = generation, message = message)
                },
                onClosed = { code, reason ->
                    terminateCurrentSessionWithError(
                        generation = generation,
                        GeminiLiveEvent.Error(
                            message = "WebSocket closed: $code $reason",
                            raw = "",
                        ),
                    )
                },
                onFailure = { error ->
                    terminateCurrentSessionWithError(
                        generation = generation,
                        GeminiLiveEvent.Error(
                            message = error.message ?: error.javaClass.simpleName,
                            raw = "",
                        ),
                    )
                },
            )
            when (val result = sendOrGetPendingError(
                generation = generation,
                text = setupMessage,
                errorMessage = "Failed to send Gemini setup message",
            )) {
                is SendResult.Failed -> result.error
                SendResult.Sent,
                SendResult.Stale,
                    -> null
            }
        }
        setupError?.emitIfCurrent()
    }

    override fun sendAudio(base64Pcm16: String) {
        sendPostSetupMessage(
            text = codec.realtimeAudioMessage(base64Pcm16),
            errorMessage = "Failed to send Gemini audio message",
            queueBeforeSetup = true,
        )
    }

    override fun sendToolResponse(callId: String, answer: String): Boolean {
        return sendPostSetupMessage(
            text = codec.toolResponseMessage(callId = callId, answer = answer),
            errorMessage = "Failed to send Gemini tool response message",
            queueBeforeSetup = false,
        )
    }

    override fun close() {
        synchronized(lifecycleLock) {
            synchronized(lock) {
                sessionState?.let { state ->
                    state.closed = true
                    state.pendingContext = null
                    state.pendingOutboundMessages.clear()
                }
                sessionState = null
            }
            socket.close()
        }
    }

    private fun handleMessage(generation: Long, message: String) {
        val event = codec.parseServerMessage(message)
        if (event == GeminiLiveEvent.SetupComplete) {
            handleSetupComplete(generation = generation, event = event)
            return
        }

        val onEvent = synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?.onEvent
        }
        onEvent?.invoke(event)
    }

    private fun handleSetupComplete(generation: Long, event: GeminiLiveEvent) {
        val batch: List<PendingMessage>
        val onEvent: (GeminiLiveEvent) -> Unit
        synchronized(lock) {
            val state = sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return
            state.setupComplete = true
            state.flushingSetupComplete = true
            batch = buildList {
                state.pendingContext?.let(::add)
                addAll(state.pendingOutboundMessages)
            }
            state.pendingContext = null
            state.pendingOutboundMessages.clear()
            onEvent = state.onEvent
        }

        onEvent(event)
        flushSetupCompleteMessages(generation = generation, messages = batch)
    }

    private fun flushSetupCompleteMessages(generation: Long, messages: List<PendingMessage>) {
        var batch = messages
        while (true) {
            batch.forEach { message ->
                sendOrEmitError(
                    generation = generation,
                    text = message.text,
                    errorMessage = message.errorMessage,
                )
            }
            batch = synchronized(lock) {
                val state = sessionState
                    ?.takeIf { it.generation == generation && !it.closed }
                    ?: return
                if (state.pendingOutboundMessages.isEmpty()) {
                    state.flushingSetupComplete = false
                    return
                }
                state.pendingOutboundMessages.toList().also {
                    state.pendingOutboundMessages.clear()
                }
            }
        }
    }

    private fun sendPostSetupMessage(
        text: String,
        errorMessage: String,
        queueBeforeSetup: Boolean,
    ): Boolean {
        val generation = synchronized(lock) {
            val state = sessionState?.takeUnless { it.closed } ?: return false
            if (!state.setupComplete || state.flushingSetupComplete) {
                if (!queueBeforeSetup) {
                    return false
                }
                state.pendingOutboundMessages += PendingMessage(
                    text = text,
                    errorMessage = errorMessage,
                )
                return true
            }
            state.generation
        }
        return sendOrEmitError(
            generation = generation,
            text = text,
            errorMessage = errorMessage,
        )
    }

    private fun sendOrEmitError(
        generation: Long,
        text: String,
        errorMessage: String,
    ): Boolean {
        return when (val result = sendOrGetPendingError(
            generation = generation,
            text = text,
            errorMessage = errorMessage,
        )) {
            is SendResult.Failed -> {
                result.error.emitIfCurrent()
                false
            }
            SendResult.Sent -> true
            SendResult.Stale -> false
        }
    }

    private fun sendOrGetPendingError(
        generation: Long,
        text: String,
        errorMessage: String,
    ): SendResult {
        val sendFailed = synchronized(lock) {
            val state = sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return SendResult.Stale
            if (socket.send(text)) {
                false
            } else {
                true
            }
        }
        return if (sendFailed) {
            SendResult.Failed(PendingError(generation = generation, message = errorMessage))
        } else {
            SendResult.Sent
        }
    }

    private fun emitIfCurrent(generation: Long, event: GeminiLiveEvent) {
        val onEvent = synchronized(lock) {
            sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?.onEvent
        }
        onEvent?.invoke(event)
    }

    private fun terminateCurrentSessionWithError(generation: Long, event: GeminiLiveEvent.Error) {
        val onEvent = synchronized(lock) {
            val state = sessionState
                ?.takeIf { it.generation == generation && !it.closed }
                ?: return
            state.closed = true
            state.pendingContext = null
            state.pendingOutboundMessages.clear()
            sessionState = null
            state.onEvent
        }
        onEvent(event)
    }

    private data class SessionState(
        val generation: Long,
        val onEvent: (GeminiLiveEvent) -> Unit,
        var setupComplete: Boolean,
        var flushingSetupComplete: Boolean,
        var pendingContext: PendingMessage?,
        val pendingOutboundMessages: MutableList<PendingMessage>,
        var closed: Boolean,
    )

    private data class PendingMessage(
        val text: String,
        val errorMessage: String,
    )

    private data class PendingError(
        val generation: Long,
        val message: String,
    )

    private sealed interface SendResult {
        data object Sent : SendResult
        data object Stale : SendResult
        data class Failed(val error: PendingError) : SendResult
    }

    private fun PendingError.emitIfCurrent() {
        emitIfCurrent(
            generation = generation,
            GeminiLiveEvent.Error(
                message = message,
                raw = "",
            ),
        )
    }
}
