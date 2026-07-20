package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

internal interface VoiceAgentCallServiceController {
    val activeConversationId: StateFlow<Uuid?>
    val lifecycle: StateFlow<VoiceAgentCallLifecycle>
    val state: StateFlow<VoiceAgentUiState>

    suspend fun start(request: VoiceAgentCallRequest): VoiceAgentCallStartResult
    suspend fun end(): VoiceAgentCallEndResult
    fun closeNow()
    fun updateCallStatus(status: VoiceCallStatus)
}

internal class VoiceAgentCallOrchestrator(
    private val factory: VoiceAgentCallFactory,
    private val resolveRoute: suspend () -> VoiceAgentRouteLease,
    private val appScope: CoroutineScope,
    private val endDrainTimeoutMillis: Long = VOICE_AGENT_END_DRAIN_TIMEOUT_MS,
) : VoiceAgentCallServiceController {
    private val lock = Any()
    private var callState: VoiceAgentCallState = VoiceAgentCallState.Idle
    private val _activeConversationId = MutableStateFlow<Uuid?>(null)
    private val _lifecycle = MutableStateFlow<VoiceAgentCallLifecycle>(VoiceAgentCallLifecycle.Idle)
    private val _state = MutableStateFlow(VoiceAgentUiState())

    override val activeConversationId: StateFlow<Uuid?> = _activeConversationId.asStateFlow()
    override val lifecycle: StateFlow<VoiceAgentCallLifecycle> = _lifecycle.asStateFlow()
    override val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    init {
        require(endDrainTimeoutMillis > 0) { "endDrainTimeoutMillis must be positive" }
    }

    override suspend fun start(request: VoiceAgentCallRequest): VoiceAgentCallStartResult {
        val reply = CompletableDeferred<VoiceAgentCallStartResult>()
        dispatch(
            VoiceAgentCallEvent.StartRequested(
                PendingVoiceAgentStart(
                    token = Any(),
                    request = request,
                    replies = listOf(reply),
                ),
            ),
        )
        return try {
            reply.await()
        } catch (cancellation: CancellationException) {
            val canonical = cancellation.canonicalVoiceAgentCancellation()
            val completion = CompletableDeferred<Throwable?>()
            dispatch(
                VoiceAgentCallEvent.StartCancelled(
                    reply = reply,
                    cancellation = PendingVoiceAgentCancellation(canonical, completion),
                ),
            )
            val cleanupFailure = withContext(NonCancellable) { completion.await() }
            cleanupFailure?.let(canonical::addVoiceAgentSuppressedDistinct)
            throw canonical
        }
    }

    override suspend fun end(): VoiceAgentCallEndResult {
        val reply = CompletableDeferred<VoiceAgentCallEndResult>()
        dispatch(VoiceAgentCallEvent.EndRequested(reply))
        return reply.await()
    }

    override fun closeNow() {
        dispatch(VoiceAgentCallEvent.CloseNowRequested)
    }

    fun interrupt() {
        activeCallSnapshot()?.session?.interrupt()
    }

    fun setMuted(value: Boolean) {
        activeCallSnapshot()?.session?.setMuted(value)
    }

    fun reconnect() {
        activeCallSnapshot()?.session?.reconnect()
    }

    override fun updateCallStatus(status: VoiceCallStatus) {
        val call = activeCallSnapshot() ?: return
        applyCallStatus(call, status)
    }

    fun recordDiagnostic(name: String, detail: String) {
        activeCallSnapshot()?.session?.recordDiagnostic(name, detail)
    }

    private fun dispatch(event: VoiceAgentCallEvent) {
        val execution = synchronized(lock) {
            reduceAndDrainAdmissionsLocked(event)
        }
        execution.effects.forEach(::runEffect)
        execution.collectorToStart?.start()
    }

    private fun reduceAndDrainAdmissionsLocked(event: VoiceAgentCallEvent): EventExecution {
        var transition = reduceVoiceAgentCallState(callState, event)
        publishTransitionLocked(transition.state)
        val pendingEffects = ArrayDeque(transition.effects)
        val externalEffects = mutableListOf<VoiceAgentCallEffect>()
        while (pendingEffects.isNotEmpty()) {
            when (val effect = pendingEffects.removeFirst()) {
                is VoiceAgentCallEffect.AdmitStart -> {
                    val operation = createUnstartedOperationLocked(effect.pending)
                    transition = reduceVoiceAgentCallState(
                        callState,
                        VoiceAgentCallEvent.StartAdmitted(effect.pending.token, operation),
                    )
                    publishTransitionLocked(transition.state)
                    transition.effects.reversed().forEach(pendingEffects::addFirst)
                }
                else -> externalEffects += effect
            }
        }
        val collector = (event as? VoiceAgentCallEvent.StartFinished)
            ?.outcome
            ?.let { it as? VoiceAgentStartOutcome.Ready }
            ?.call
            ?.takeIf { ready ->
                (callState as? VoiceAgentCallState.Active)?.call === ready
            }
            ?.collector
        return EventExecution(externalEffects, collector)
    }

    private fun createUnstartedOperationLocked(pending: PendingVoiceAgentStart): VoiceAgentStartOperation {
        val callJob = SupervisorJob(appScope.coroutineContext[Job])
        val callScope = CoroutineScope(appScope.coroutineContext.withJob(callJob))
        return voiceAgentStartOperation(
            request = pending.request,
            callScope = callScope,
            callJob = callJob,
            factory = factory,
            resolveRoute = resolveRoute,
            onFinished = { operation, outcome ->
                dispatch(VoiceAgentCallEvent.StartFinished(operation, outcome))
            },
            onSessionState = { call, state, routeUsable ->
                dispatch(VoiceAgentCallEvent.SessionStateChanged(call, state, routeUsable))
            },
        )
    }

    private fun publishTransitionLocked(next: VoiceAgentCallState) {
        val previous = callState
        callState = next
        _activeConversationId.value = next.activeConversationId
        _lifecycle.value = next.lifecycle
        when {
            next is VoiceAgentCallState.Active &&
                (previous as? VoiceAgentCallState.Active)?.call?.token !== next.call.token -> {
                _state.value = next.sessionState
            }
            previous is VoiceAgentCallState.Active && next !is VoiceAgentCallState.Active -> {
                _state.value = VoiceAgentUiState()
            }
        }
    }

    private fun runEffect(effect: VoiceAgentCallEffect) {
        when (effect) {
            is VoiceAgentCallEffect.AdmitStart -> error("Admissions must be drained under the orchestrator lock")
            is VoiceAgentCallEffect.LaunchStart -> effect.operation.start()
            is VoiceAgentCallEffect.CancelStart -> effect.operation.cancel()
            is VoiceAgentCallEffect.RunCleanup -> appScope.launch {
                val result = try {
                    effect.cleanup.run(effect.mode)
                } catch (error: Throwable) {
                    VoiceAgentCleanupResult.Failed(error)
                }
                dispatch(VoiceAgentCallEvent.CleanupFinished(effect.cleanup, result))
            }
            is VoiceAgentCallEffect.CompleteStarts -> effect.replies.forEach { it.complete(effect.result) }
            is VoiceAgentCallEffect.CompleteEnds -> effect.replies.forEach { it.complete(effect.result) }
            is VoiceAgentCallEffect.CompleteCancellations -> effect.cancellations.forEach {
                it.completion.complete(effect.cleanupFailure)
            }
            is VoiceAgentCallEffect.Reconnect -> withCurrentActive(effect.call) { it.session.reconnect() }
            is VoiceAgentCallEffect.ApplySessionState -> applySessionState(effect.call, effect.state)
            is VoiceAgentCallEffect.RecordDiagnostic -> withCurrentActive(effect.call) {
                it.session.recordDiagnostic(effect.name, effect.detail)
            }
            is VoiceAgentCallEffect.ApplyCallStatus -> applyCallStatus(effect.call, effect.status)
        }
    }

    private fun activeCallSnapshot(): ActiveVoiceAgentCall? = synchronized(lock) {
        (callState as? VoiceAgentCallState.Active)?.call
    }

    private fun withCurrentActive(call: ActiveVoiceAgentCall, block: (ActiveVoiceAgentCall) -> Unit) {
        val current = synchronized(lock) {
            (callState as? VoiceAgentCallState.Active)?.call?.takeIf { it.token === call.token }
        }
        current?.let(block)
    }

    private fun applySessionState(call: ActiveVoiceAgentCall, value: VoiceAgentUiState) {
        synchronized(lock) {
            val current = (callState as? VoiceAgentCallState.Active)?.call
            if (current?.token === call.token) _state.value = value
        }
    }

    private fun applyCallStatus(call: ActiveVoiceAgentCall, status: VoiceCallStatus) {
        synchronized(lock) {
            val current = (callState as? VoiceAgentCallState.Active)?.call
            if (current?.token === call.token) _state.value = _state.value.copy(call = status)
        }
    }

    private data class EventExecution(
        val effects: List<VoiceAgentCallEffect>,
        val collectorToStart: Job?,
    )
}

private fun CoroutineContext.withJob(job: Job): CoroutineContext = minusKey(Job) + job
