package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.voiceagent.RouteOwnedManagedVoiceCallSession
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupOperation
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentUiState
import me.rerere.rikkahub.voiceagent.VoiceAudioStatus
import me.rerere.rikkahub.voiceagent.VoiceDiagnosticLine
import me.rerere.rikkahub.voiceagent.VoiceSessionStatus
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
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
) : RouteOwnedManagedVoiceCallSession {
    private val mutableState = MutableStateFlow(VoiceAgentUiState())
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val microphoneStateLock = Any()
    private val ready = CompletableDeferred<Unit>()
    private val roomConnected = CompletableDeferred<Unit>()
    private val microphoneCommands = Channel<Unit>(capacity = Channel.CONFLATED)
    private var desiredMicrophoneEnabled = true
    private var wasReady = false
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var microphoneJob: Job? = null

    init {
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readyTimeoutMillis > 0) { "readyTimeoutMillis must be positive" }
    }

    override val state: StateFlow<VoiceAgentUiState> = mutableState.asStateFlow()
    override val routeMetadata = routeLease.metadata
    override val isRouteUsable: Boolean
        get() = routeLease.isUsable
    override val cleanupOperation: VoiceAgentCleanupOperation = LiveKitCleanupOperation(
        routeLease = routeLease,
        requestClose = { requestCloseForCleanup() },
        connectionJob = { connectionJob },
        eventJob = { eventJob },
        microphoneJob = { microphoneJob },
        rpcMethods = rpcMethods.keys,
        room = room,
    )

    override fun start() {
        synchronized(lifecycleLock) {
            if (!started.compareAndSet(false, true) || closed.get()) return
            rpcMethods.forEach(room::registerRpcMethod)
            eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                room.events.collect(::handleRoomEvent)
            }
            if (closed.get()) return
            microphoneJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                publishMicrophoneCommands()
            }
            connectRoom()
        }
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
        synchronized(microphoneStateLock) {
            if (closed.get()) return
            desiredMicrophoneEnabled = !value
            mutableState.update { state ->
                state.copy(audio = if (value) VoiceAudioStatus.Muted else VoiceAudioStatus.Listening)
            }
            microphoneCommands.trySend(Unit)
        }
    }

    override fun reconnect() {
        if (!started.get() || closed.get()) return
        appendDiagnostic("livekit_native_reconnect_owned", "automatic")
    }

    override fun recordDiagnostic(name: String, detail: String) {
        appendDiagnostic(name, detail)
    }

    private fun connectRoom() {
        connectionJob = scope.launch {
            mutableState.update { it.copy(session = VoiceSessionStatus.ConnectingGemini) }
            try {
                withTimeout(connectTimeoutMillis) {
                    room.connect(details.livekitUrl, details.participantToken)
                }
                roomConnected.complete(Unit)
                microphoneCommands.trySend(Unit)
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

    private suspend fun publishMicrophoneCommands() {
        roomConnected.await()
        for (ignored in microphoneCommands) {
            while (true) {
                val requested = synchronized(microphoneStateLock) { desiredMicrophoneEnabled }
                try {
                    room.setMicrophoneEnabled(requested)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    appendDiagnostic("livekit_microphone_failed", error::class.simpleName ?: "unknown")
                    break
                }
                while (microphoneCommands.tryReceive().isSuccess) {
                    // Requests are represented by desiredMicrophoneEnabled; discard stale wakeups.
                }
                if (requested == synchronized(microphoneStateLock) { desiredMicrophoneEnabled }) break
            }
        }
    }

    private fun handleRoomEvent(event: LiveKitRoomEvent) {
        if (closed.get()) return
        when (event) {
            LiveKitRoomEvent.Connected -> {
                if (wasReady) {
                    mutableState.update { it.copy(session = VoiceSessionStatus.Connected) }
                }
            }
            LiveKitRoomEvent.Reconnecting -> {
                mutableState.update { it.copy(session = VoiceSessionStatus.Reconnecting) }
            }
            LiveKitRoomEvent.Reconnected -> {
                mutableState.update {
                    it.copy(
                        session = if (wasReady) VoiceSessionStatus.Connected else VoiceSessionStatus.ConnectingGemini,
                    )
                }
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
        mutableState.update { it.copy(session = VoiceSessionStatus.Connected, error = null) }
    }

    private fun failExperimental(message: String) {
        if (!requestCloseForCleanup()) return
        mutableState.update {
            it.copy(
                session = VoiceSessionStatus.Error(message),
                error = message,
            )
        }
        scope.launch {
            when (val result = cleanupOperation.run(VoiceAgentCleanupMode.Immediate)) {
                VoiceAgentCleanupResult.Completed -> appendDiagnostic("livekit_call_ended", "experimental_failure")
                is VoiceAgentCleanupResult.Failed -> appendDiagnostic(
                    "livekit_cleanup_failed",
                    result.error::class.simpleName ?: "unknown",
                )
            }
        }
    }

    private fun appendDiagnostic(name: String, detail: String) {
        mutableState.update { state ->
            state.copy(
                diagnostics = state.diagnostics + VoiceDiagnosticLine(
                    name = name,
                    detail = detail,
                    at = Instant.now().toString(),
                ),
            )
        }
    }

    private fun requestCloseForCleanup(): Boolean {
        return synchronized(lifecycleLock) {
            synchronized(microphoneStateLock) {
                closed.compareAndSet(false, true)
            }
        }
    }

    private companion object {
        const val DEFAULT_LIVEKIT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_LIVEKIT_READY_TIMEOUT_MS = 30_000L
    }
}

private class LiveKitCleanupOperation(
    private val routeLease: VoiceAgentRouteLease,
    private val requestClose: () -> Unit,
    private val connectionJob: () -> Job?,
    private val eventJob: () -> Job?,
    private val microphoneJob: () -> Job?,
    rpcMethods: Set<String>,
    private val room: LiveKitRoomFacade,
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()

    private val lock = Any()
    private var state: LiveKitCleanupAttemptState = LiveKitCleanupAttemptState.Ready
    private var routeCompleted = false
    private var connectionJobCompleted = false
    private var eventJobCompleted = false
    private var microphoneJobCompleted = false
    private val pendingRpcMethods = rpcMethods.toMutableSet()
    private var disconnectCompleted = false
    private var closeCompleted = false

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        val decision = synchronized(lock) {
            when (val current = state) {
                LiveKitCleanupAttemptState.Completed -> LiveKitCleanupAttemptDecision.Completed
                LiveKitCleanupAttemptState.Ready -> {
                    val completion = CompletableDeferred<LiveKitCleanupAttemptOutcome>()
                    state = LiveKitCleanupAttemptState.Running(completion)
                    LiveKitCleanupAttemptDecision.Execute(completion)
                }
                is LiveKitCleanupAttemptState.Running -> LiveKitCleanupAttemptDecision.Join(current.completion)
            }
        }
        return when (decision) {
            LiveKitCleanupAttemptDecision.Completed -> VoiceAgentCleanupResult.Completed
            is LiveKitCleanupAttemptDecision.Join -> decision.completion.await().deliver()
            is LiveKitCleanupAttemptDecision.Execute -> executeAndPublish(decision.completion).deliver()
        }
    }

    private suspend fun executeAndPublish(
        completion: CompletableDeferred<LiveKitCleanupAttemptOutcome>,
    ): LiveKitCleanupAttemptOutcome {
        val outcome = try {
            executeAttempt()
        } catch (cancellation: CancellationException) {
            LiveKitCleanupAttemptOutcome.Cancelled(cancellation.canonicalLiveKitCleanupCancellation())
        } catch (error: Throwable) {
            LiveKitCleanupAttemptOutcome.Returned(VoiceAgentCleanupResult.Failed(error))
        }
        synchronized(lock) {
            check((state as? LiveKitCleanupAttemptState.Running)?.completion === completion) {
                "LiveKit cleanup attempt ownership changed before publication"
            }
            state = if (hasUnfinishedStages()) {
                LiveKitCleanupAttemptState.Ready
            } else {
                LiveKitCleanupAttemptState.Completed
            }
            check(completion.complete(outcome)) { "LiveKit cleanup attempt was already completed" }
        }
        return outcome
    }

    private suspend fun executeAttempt(): LiveKitCleanupAttemptOutcome {
        val failures = LiveKitCleanupFailures()
        failures.captureCallerCancellation()
        try {
            withContext(NonCancellable) {
                requestClose()
                retireRoute(failures)
                connectionJobCompleted = cleanJob(connectionJob(), connectionJobCompleted, failures)
                eventJobCompleted = cleanJob(eventJob(), eventJobCompleted, failures)
                microphoneJobCompleted = cleanJob(microphoneJob(), microphoneJobCompleted, failures)
                unregisterRpcMethods(failures)
                disconnectRoom(failures)
                closeRoom(failures)
            }
        } catch (cancellation: CancellationException) {
            failures.add(cancellation)
        }
        return failures.outcome()
    }

    private fun retireRoute(failures: LiveKitCleanupFailures) {
        if (routeCompleted) return
        try {
            routeLease.retire()
            routeCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private suspend fun cleanJob(
        job: Job?,
        completed: Boolean,
        failures: LiveKitCleanupFailures,
    ): Boolean {
        if (completed) return true
        return try {
            job?.cancel()
            job?.join()
            true
        } catch (error: Throwable) {
            failures.add(error)
            false
        }
    }

    private fun unregisterRpcMethods(failures: LiveKitCleanupFailures) {
        if (!jobsCompleted()) return
        pendingRpcMethods.toList().forEach { method ->
            try {
                room.unregisterRpcMethod(method)
                pendingRpcMethods.remove(method)
            } catch (error: Throwable) {
                failures.add(error)
            }
        }
    }

    private fun disconnectRoom(failures: LiveKitCleanupFailures) {
        if (disconnectCompleted || !jobsCompleted() || pendingRpcMethods.isNotEmpty()) return
        try {
            room.disconnect()
            disconnectCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun closeRoom(failures: LiveKitCleanupFailures) {
        if (closeCompleted || !disconnectCompleted) return
        try {
            room.close()
            closeCompleted = true
        } catch (error: Throwable) {
            failures.add(error)
        }
    }

    private fun jobsCompleted(): Boolean =
        connectionJobCompleted && eventJobCompleted && microphoneJobCompleted

    private fun hasUnfinishedStages(): Boolean =
        !routeCompleted ||
            !jobsCompleted() ||
            pendingRpcMethods.isNotEmpty() ||
            !disconnectCompleted ||
            !closeCompleted
}

private sealed interface LiveKitCleanupAttemptState {
    data object Ready : LiveKitCleanupAttemptState
    data class Running(
        val completion: CompletableDeferred<LiveKitCleanupAttemptOutcome>,
    ) : LiveKitCleanupAttemptState
    data object Completed : LiveKitCleanupAttemptState
}

private sealed interface LiveKitCleanupAttemptDecision {
    data object Completed : LiveKitCleanupAttemptDecision
    data class Execute(
        val completion: CompletableDeferred<LiveKitCleanupAttemptOutcome>,
    ) : LiveKitCleanupAttemptDecision
    data class Join(
        val completion: CompletableDeferred<LiveKitCleanupAttemptOutcome>,
    ) : LiveKitCleanupAttemptDecision
}

private sealed interface LiveKitCleanupAttemptOutcome {
    data class Returned(val result: VoiceAgentCleanupResult) : LiveKitCleanupAttemptOutcome
    data class Cancelled(val error: CancellationException) : LiveKitCleanupAttemptOutcome
}

private class LiveKitCleanupFailures {
    private val failures = mutableListOf<Throwable>()
    private var cancellation: CancellationException? = null

    suspend fun captureCallerCancellation() {
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            add(error)
        }
    }

    fun add(error: Throwable) {
        if (error is CancellationException) {
            val canonical = error.canonicalLiveKitCleanupCancellation()
            val current = cancellation
            if (current == null) {
                cancellation = canonical
            } else if (current !== canonical && canonical !in failures) {
                failures += canonical
            }
        } else if (error !in failures) {
            failures += error
        }
    }

    suspend fun outcome(): LiveKitCleanupAttemptOutcome {
        captureCallerCancellation()
        cancellation?.let { canonical ->
            failures.forEach { failure ->
                if (failure !== canonical && failure !in canonical.suppressed) {
                    canonical.addSuppressed(failure)
                }
            }
            return LiveKitCleanupAttemptOutcome.Cancelled(canonical)
        }
        val primary = failures.firstOrNull() ?: return LiveKitCleanupAttemptOutcome.Returned(
            VoiceAgentCleanupResult.Completed,
        )
        failures.drop(1).forEach { failure ->
            if (failure !== primary && failure !in primary.suppressed) {
                primary.addSuppressed(failure)
            }
        }
        return LiveKitCleanupAttemptOutcome.Returned(VoiceAgentCleanupResult.Failed(primary))
    }
}

private fun LiveKitCleanupAttemptOutcome.deliver(): VoiceAgentCleanupResult = when (this) {
    is LiveKitCleanupAttemptOutcome.Returned -> result
    is LiveKitCleanupAttemptOutcome.Cancelled -> throw error
}

private fun CancellationException.canonicalLiveKitCleanupCancellation(): CancellationException {
    var canonical = this
    val visited = Collections.newSetFromMap(
        IdentityHashMap<CancellationException, Boolean>(),
    )
    visited += canonical
    while (true) {
        val original = canonical.cause as? CancellationException ?: return canonical
        if (original.message != canonical.message || !visited.add(original)) return canonical
        canonical = original
    }
}

@Serializable
private data class LiveKitReadyMessage(
    val version: Int,
    val voiceSessionId: String,
    val kind: String,
    val observedAt: String,
)
