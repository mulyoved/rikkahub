package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.voiceagent.ManagedVoiceCallSession
import me.rerere.rikkahub.voiceagent.RouteOwnedManagedVoiceCallSession
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupOperation
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentUiState
import me.rerere.rikkahub.voiceagent.VoiceAudioStatus
import me.rerere.rikkahub.voiceagent.VoiceDiagnosticLine
import me.rerere.rikkahub.voiceagent.VoiceSessionStatus
import me.rerere.rikkahub.voiceagent.voiceAgentSessionCleanupOperation
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal const val LIVEKIT_READY_TOPIC = "voice.ready.v1"
internal const val LIVEKIT_INTERRUPT_RPC = "voice.interrupt"

internal class LiveKitVoiceCallSession(
    private val details: LiveKitSessionDetails,
    private val room: LiveKitRoomFacade,
    private val routeLease: VoiceAgentRouteLease,
    private val scope: CoroutineScope,
    private val rpcMethods: Map<String, suspend (LiveKitRpcInvocation) -> String> = emptyMap(),
    private val connectTimeoutMillis: Long = DEFAULT_LIVEKIT_CONNECT_TIMEOUT_MS,
    private val readyTimeoutMillis: Long = DEFAULT_LIVEKIT_READY_TIMEOUT_MS,
    private val json: Json = Json,
) : RouteOwnedManagedVoiceCallSession, ManagedVoiceCallSession {
    private val mutableState = MutableStateFlow(VoiceAgentUiState())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val ready = CompletableDeferred<Unit>()
    private var wasReady = false
    private var eventJob: Job? = null
    private var connectionJob: Job? = null

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readyTimeoutMillis > 0) { "readyTimeoutMillis must be positive" }
    }

    override val state: StateFlow<VoiceAgentUiState> = mutableState.asStateFlow()
    override val routeMetadata = routeLease.metadata
    override val isRouteUsable: Boolean
        get() = routeLease.isUsable
    override val cleanupOperation: VoiceAgentCleanupOperation = voiceAgentSessionCleanupOperation(
        delegate = this,
        routeLease = routeLease,
        endDrainTimeoutMillis = DEFAULT_LIVEKIT_END_DRAIN_TIMEOUT_MS,
    )

    override fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        rpcMethods.forEach(room::registerRpcMethod)
        eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            room.events.collect(::handleRoomEvent)
        }
        connectRoom(reconnecting = false)
    }

    override fun interrupt() {
        if (closed.get()) return
        scope.launch {
            runCatching {
                room.performRpc(
                    destination = details.agentParticipantIdentity,
                    method = LIVEKIT_INTERRUPT_RPC,
                    payload = "",
                )
            }.onFailure { error ->
                if (error !is CancellationException) {
                    appendDiagnostic("livekit_interrupt_failed", error::class.simpleName ?: "unknown")
                }
            }
        }
    }

    override fun setMuted(value: Boolean) {
        if (closed.get()) return
        scope.launch {
            runCatching { room.setMicrophoneEnabled(!value) }
                .onSuccess { changed ->
                    if (changed) {
                        mutableState.value = mutableState.value.copy(
                            audio = if (value) VoiceAudioStatus.Muted else VoiceAudioStatus.Listening,
                        )
                    }
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        appendDiagnostic("livekit_microphone_failed", error::class.simpleName ?: "unknown")
                    }
                }
        }
    }

    override fun reconnect() {
        if (!started.get() || closed.get()) return
        connectionJob?.cancel()
        mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Reconnecting)
        connectRoom(reconnecting = true)
    }

    override fun recordDiagnostic(name: String, detail: String) {
        appendDiagnostic(name, detail)
    }

    override fun end() {
        closeSession()
    }

    override suspend fun endAndDrain() {
        closeSession()
    }

    override fun closeNow() {
        closeSession()
    }

    private fun connectRoom(reconnecting: Boolean) {
        connectionJob = scope.launch {
            if (!reconnecting) {
                mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.ConnectingGemini)
            }
            try {
                withTimeout(connectTimeoutMillis) {
                    room.connect(details.livekitUrl, details.participantToken)
                }
                room.setMicrophoneEnabled(mutableState.value.audio != VoiceAudioStatus.Muted)
                withTimeout(readyTimeoutMillis) { ready.await() }
            } catch (timeout: TimeoutCancellationException) {
                failExperimental("LiveKit experimental voice connection timed out")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                failExperimental("LiveKit experimental voice connection failed")
            }
        }
    }

    private fun handleRoomEvent(event: LiveKitRoomEvent) {
        if (closed.get()) return
        when (event) {
            LiveKitRoomEvent.Connected -> {
                if (wasReady) {
                    mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Connected)
                }
            }
            LiveKitRoomEvent.Reconnecting -> {
                mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Reconnecting)
            }
            LiveKitRoomEvent.Reconnected -> {
                mutableState.value = mutableState.value.copy(
                    session = if (wasReady) VoiceSessionStatus.Connected else VoiceSessionStatus.ConnectingGemini,
                )
            }
            is LiveKitRoomEvent.Data -> handleReady(event)
            is LiveKitRoomEvent.Failed -> failExperimental("LiveKit experimental voice connection failed")
            is LiveKitRoomEvent.Disconnected -> failExperimental("LiveKit experimental voice call disconnected")
            is LiveKitRoomEvent.ParticipantDisconnected -> {
                if (event.participantIdentity == details.agentParticipantIdentity) {
                    failExperimental("LiveKit experimental voice agent disconnected")
                }
            }
        }
    }

    private fun handleReady(event: LiveKitRoomEvent.Data) {
        if (event.participantIdentity != details.agentParticipantIdentity || event.topic != LIVEKIT_READY_TOPIC) return
        val message = runCatching { json.decodeFromString<LiveKitReadyMessage>(event.payload) }.getOrNull() ?: return
        if (
            message.version != 1 ||
            message.voiceSessionId != details.voiceSessionId ||
            message.kind != "ready"
        ) return
        wasReady = true
        ready.complete(Unit)
        mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Connected, error = null)
    }

    private fun failExperimental(message: String) {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(
            session = VoiceSessionStatus.Error(message),
            error = message,
        )
        closeRoomResources()
    }

    private fun closeSession() {
        if (closed.get()) return
        mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Ending)
        closeRoomResources()
        mutableState.value = mutableState.value.copy(session = VoiceSessionStatus.Ended)
    }

    private fun closeRoomResources() {
        if (!closed.compareAndSet(false, true)) return
        connectionJob?.cancel()
        eventJob?.cancel()
        rpcMethods.keys.forEach { method -> runCatching { room.unregisterRpcMethod(method) } }
        runCatching(room::disconnect)
        runCatching(room::close)
    }

    private fun appendDiagnostic(name: String, detail: String) {
        mutableState.value = mutableState.value.copy(
            diagnostics = mutableState.value.diagnostics + VoiceDiagnosticLine(
                name = name,
                detail = detail,
                at = Instant.now().toString(),
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIVEKIT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LIVEKIT_READY_TIMEOUT_MS = 30_000L
        const val DEFAULT_LIVEKIT_END_DRAIN_TIMEOUT_MS = 5_000L
    }
}

@Serializable
private data class LiveKitReadyMessage(
    val version: Int,
    val voiceSessionId: String,
    val kind: String,
    val observedAt: String,
)
