package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentCallOrchestratorStressTest {
    @Test
    fun `deterministic reducer sequences preserve ownership and complete every waiter`() {
        val seeds = listOf(1L, 7L, 42L, 20260719L)

        seeds.forEach { seed ->
            StressHarness(seed).run()
        }
    }
}

private class StressHarness(seed: Long) {
    private val random = Random(seed)
    private val requests = List(3) { orchestratorRequest("stress-$seed-$it") }
    private val startReplies = mutableListOf<CompletableDeferred<VoiceAgentCallStartResult>>()
    private val endReplies = mutableListOf<CompletableDeferred<VoiceAgentCallEndResult>>()
    private val cancellationReplies = mutableListOf<CompletableDeferred<Throwable?>>()
    private val ownedCleanups = mutableListOf<StressCleanupOperation>()
    private val coverage = mutableSetOf<String>()
    private var state: VoiceAgentCallState = VoiceAgentCallState.Idle
    private var generatedEvents = 0

    fun run() {
        exerciseRequiredVariants()
        while (generatedEvents < EVENTS_PER_SEED) {
            randomEvent()
        }
        assertEquals(EVENTS_PER_SEED, generatedEvents)
        assertRequiredCoverage()
        drainToTerminalState()
        assertTrue(state is VoiceAgentCallState.Idle)
        assertTrue("all start replies must terminate", startReplies.all { it.isCompleted })
        assertTrue("all end replies must terminate", endReplies.all { it.isCompleted })
        assertTrue("all cancellation replies must terminate", cancellationReplies.all { it.isCompleted })
        assertTrue("every acquired fake resource must be cleaned", ownedCleanups.all { it.cleaned })
    }

    private fun exerciseRequiredVariants() {
        requestStart(requests[0], "different")
        requestStart(requests[0], "same")
        admit(current = true)
        cancelStart(current = false)
        cancelStart(current = true)
        cancelStart(current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)

        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.Ready, current = true)
        session(SessionKind.Connected, current = true)
        session(SessionKind.ErrorUsable, current = true)
        session(SessionKind.Connected, current = false)
        requestStart(requests[0], "same")
        session(SessionKind.Ended, current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)

        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.FailedClean, current = true)
        requestStart(requests[0], "different")
        admit(current = true)
        finish(StartOutcomeKind.FailedDirty, current = true)
        endCall()
        closeNow()
        cleanup(VoiceAgentCleanupResult.Failed(IllegalStateException("cleanup failed")), current = true)
        requestStart(requests[1], "different")
        cleanup(VoiceAgentCleanupResult.Completed, current = true)
        admit(current = true)
        finish(StartOutcomeKind.Cancelled, current = true)

        StartOutcomeKind.entries.forEach { finish(it, current = false) }
        admit(current = false)
        cleanup(VoiceAgentCleanupResult.Completed, current = false)
        closeNow()

        requestStart(requests[2], "different")
        admit(current = true)
        finish(StartOutcomeKind.Ready, current = true)
        session(SessionKind.ErrorUnusable, current = true)
        cleanup(VoiceAgentCleanupResult.Completed, current = true)
    }

    private fun randomEvent() {
        when (random.nextInt(8)) {
            0 -> randomStartRequest()
            1 -> admit(current = random.nextBoolean())
            2 -> cancelStart(current = random.nextBoolean())
            3 -> endCall()
            4 -> closeNow()
            5 -> finish(StartOutcomeKind.entries.random(random), current = random.nextBoolean())
            6 -> cleanup(randomCleanupResult(), current = random.nextBoolean())
            else -> session(SessionKind.entries.random(random), current = random.nextBoolean())
        }
    }

    private fun randomStartRequest() {
        val currentRequest = state.desiredRequest()
        val useSame = currentRequest != null && random.nextBoolean()
        val request = if (useSame) {
            currentRequest
        } else {
            requests.filterNot { it == currentRequest }.random(random)
        }
        requestStart(request, if (useSame) "same" else "different")
    }

    private fun requestStart(request: VoiceAgentCallRequest, variant: String) {
        val reply = CompletableDeferred<VoiceAgentCallStartResult>()
        startReplies += reply
        coverage += "StartRequested"
        coverage += "StartRequested:$variant"
        dispatch(
            VoiceAgentCallEvent.StartRequested(
                PendingVoiceAgentStart(Any(), request, listOf(reply)),
            ),
        )
    }

    private fun admit(current: Boolean) {
        val admitting = state as? VoiceAgentCallState.Starting.Admitting
        val isCurrent = current && admitting != null
        val request = admitting?.pending?.request ?: requests.random(random)
        val operation = newOperation(request)
        coverage += "StartAdmitted"
        coverage += "StartAdmitted:${if (isCurrent) "current" else "stale"}"
        dispatch(
            VoiceAgentCallEvent.StartAdmitted(
                pendingToken = if (isCurrent) admitting.pending.token else Any(),
                operation = operation,
            ),
            stale = !isCurrent,
        )
    }

    private fun cancelStart(current: Boolean) {
        val ownedReply = state.startWaiters().firstOrNull { !it.isCompleted }
        val isCurrent = current && ownedReply != null
        val reply = if (isCurrent) checkNotNull(ownedReply) else CompletableDeferred()
        val error = CancellationException("stress cancellation")
        if (isCurrent) reply.cancel(error)
        val completion = CompletableDeferred<Throwable?>()
        cancellationReplies += completion
        coverage += "StartCancelled"
        coverage += "StartCancelled:${if (isCurrent) "current" else "stale"}"
        dispatch(
            VoiceAgentCallEvent.StartCancelled(
                reply = reply,
                cancellation = PendingVoiceAgentCancellation(error, completion),
            ),
            stale = !isCurrent,
        )
    }

    private fun endCall() {
        val reply = CompletableDeferred<VoiceAgentCallEndResult>()
        endReplies += reply
        coverage += "EndRequested"
        dispatch(VoiceAgentCallEvent.EndRequested(reply))
    }

    private fun closeNow() {
        coverage += "CloseNowRequested"
        dispatch(VoiceAgentCallEvent.CloseNowRequested)
    }

    private fun finish(kind: StartOutcomeKind, current: Boolean) {
        val running = state as? VoiceAgentCallState.Starting.Running
        val isCurrent = current && running != null
        val operation = if (isCurrent) running.operation else newOperation(requests.random(random))
        val outcome = outcome(kind, operation)
        if (isCurrent && (kind == StartOutcomeKind.FailedClean || kind == StartOutcomeKind.Cancelled)) {
            (operation.cleanup as StressCleanupOperation).cleaned = true
        }
        coverage += "StartFinished"
        coverage += "StartFinished:${if (isCurrent) "current" else "stale"}"
        coverage += "StartOutcome:$kind"
        dispatch(VoiceAgentCallEvent.StartFinished(operation, outcome), stale = !isCurrent)
    }

    private fun outcome(
        kind: StartOutcomeKind,
        operation: VoiceAgentStartOperation,
    ): VoiceAgentStartOutcome = when (kind) {
        StartOutcomeKind.Ready -> VoiceAgentStartOutcome.Ready(
            activeCall(operation.request, operation.cleanup),
            VoiceAgentUiState(session = VoiceSessionStatus.Connected),
        )
        StartOutcomeKind.FailedClean -> VoiceAgentStartOutcome.FailedClean(
            IllegalStateException("clean startup failure"),
        )
        StartOutcomeKind.FailedDirty -> VoiceAgentStartOutcome.FailedDirty(
            IllegalStateException("dirty startup failure"),
            operation.cleanup,
        )
        StartOutcomeKind.Cancelled -> VoiceAgentStartOutcome.Cancelled
    }

    private fun cleanup(result: VoiceAgentCleanupResult, current: Boolean) {
        val stopping = state as? VoiceAgentCallState.Stopping
        val isCurrent = current && stopping != null
        val cleanup = if (isCurrent) stopping.cleanup else newCleanup()
        if (isCurrent && result == VoiceAgentCleanupResult.Completed) {
            (cleanup as StressCleanupOperation).cleaned = true
        }
        coverage += "CleanupFinished"
        coverage += "CleanupFinished:${if (isCurrent) "current" else "stale"}"
        coverage += when (result) {
            VoiceAgentCleanupResult.Completed -> "CleanupResult:Completed"
            is VoiceAgentCleanupResult.Failed -> "CleanupResult:Failed"
        }
        dispatch(VoiceAgentCallEvent.CleanupFinished(cleanup, result), stale = !isCurrent)
    }

    private fun session(kind: SessionKind, current: Boolean) {
        val active = state as? VoiceAgentCallState.Active
        val isCurrent = current && active != null
        val call = if (isCurrent) active.call else activeCall(requests.random(random), newCleanup())
        val routeUsable = kind != SessionKind.ErrorUnusable
        val sessionState = VoiceAgentUiState(
            session = when (kind) {
                SessionKind.Connected -> VoiceSessionStatus.Connected
                SessionKind.ErrorUsable,
                SessionKind.ErrorUnusable,
                -> VoiceSessionStatus.Error("stress session failure")
                SessionKind.Ended -> VoiceSessionStatus.Ended
            },
        )
        coverage += "SessionStateChanged"
        coverage += "SessionStateChanged:${if (isCurrent) "current" else "stale"}"
        coverage += "Session:$kind"
        dispatch(
            VoiceAgentCallEvent.SessionStateChanged(call, sessionState, routeUsable),
            stale = !isCurrent,
        )
    }

    private fun dispatch(event: VoiceAgentCallEvent, stale: Boolean = false, generated: Boolean = true) {
        val before = state
        val beforeCleanup = before.cleanupOwner()
        val transition = reduceVoiceAgentCallState(before, event)
        if (stale) {
            assertSame("stale identity changed state for $event", before, transition.state)
        }
        state = transition.state
        (state.cleanupOwner() as? StressCleanupOperation)?.let { cleanup ->
            if (ownedCleanups.none { it === cleanup }) ownedCleanups += cleanup
        }
        assertOwnerInvariant()
        val afterCleanup = state.cleanupOwner()
        if (beforeCleanup != null && afterCleanup != null) {
            assertSame("cleanup identity changed without release", beforeCleanup, afterCleanup)
        }
        applyCompletionEffects(transition.effects)
        if (generated) generatedEvents += 1
    }

    private fun applyCompletionEffects(effects: List<VoiceAgentCallEffect>) {
        effects.forEach { effect ->
            when (effect) {
                is VoiceAgentCallEffect.CompleteStarts -> effect.replies.forEach { it.complete(effect.result) }
                is VoiceAgentCallEffect.CompleteEnds -> effect.replies.forEach { it.complete(effect.result) }
                is VoiceAgentCallEffect.CompleteCancellations -> {
                    effect.cancellations.forEach { it.completion.complete(effect.cleanupFailure) }
                }
                is VoiceAgentCallEffect.RunCleanup -> {
                    assertNotNull(effect.cleanup.token)
                    if (effect.cleanup !== state.cleanupOwner()) {
                        (effect.cleanup as? StressCleanupOperation)?.cleaned = true
                    }
                }
                is VoiceAgentCallEffect.AdmitStart,
                is VoiceAgentCallEffect.ApplyCallStatus,
                is VoiceAgentCallEffect.ApplySessionState,
                is VoiceAgentCallEffect.CancelStart,
                is VoiceAgentCallEffect.LaunchStart,
                is VoiceAgentCallEffect.Reconnect,
                is VoiceAgentCallEffect.RecordDiagnostic,
                -> Unit
            }
        }
    }

    private fun assertOwnerInvariant() {
        val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        state.cleanupOwner()?.let { owners += it.token }
        assertTrue("state published more than one resource owner", owners.size <= 1)
        val active = state as? VoiceAgentCallState.Active ?: return
        assertNotNull(active.call.route)
        assertNotNull(active.call.session)
        assertNotNull(active.call.callScope)
        assertNotNull(active.call.callJob)
        assertNotNull(active.call.collector)
        assertNotNull(active.call.cleanup)
        assertSame(active.call.callJob, active.call.callScope.coroutineContext[Job])
        assertEquals(active.call.route, active.call.session.routeMetadata)
        assertSame(active.call.cleanup, active.call.session.cleanupOperation)
    }

    private fun drainToTerminalState() {
        repeat(MAX_DRAIN_EVENTS) {
            when (state) {
                VoiceAgentCallState.Idle -> return
                is VoiceAgentCallState.Starting.Admitting -> admitForDrain()
                is VoiceAgentCallState.Starting.Running -> finishForDrain()
                is VoiceAgentCallState.Active -> endForDrain()
                is VoiceAgentCallState.Stopping -> cleanupForDrain()
                is VoiceAgentCallState.CleanupFailed -> closeForDrain()
            }
        }
        throw AssertionError("stress sequence did not reach Idle: $state")
    }

    private fun admitForDrain() {
        val admitting = state as VoiceAgentCallState.Starting.Admitting
        dispatch(
            VoiceAgentCallEvent.StartAdmitted(
                admitting.pending.token,
                newOperation(admitting.pending.request),
            ),
            generated = false,
        )
    }

    private fun finishForDrain() {
        val running = state as VoiceAgentCallState.Starting.Running
        (running.operation.cleanup as StressCleanupOperation).cleaned = true
        dispatch(
            VoiceAgentCallEvent.StartFinished(
                running.operation,
                VoiceAgentStartOutcome.FailedClean(IllegalStateException("terminal drain")),
            ),
            generated = false,
        )
    }

    private fun endForDrain() {
        val reply = CompletableDeferred<VoiceAgentCallEndResult>()
        endReplies += reply
        dispatch(VoiceAgentCallEvent.EndRequested(reply), generated = false)
    }

    private fun cleanupForDrain() {
        val stopping = state as VoiceAgentCallState.Stopping
        (stopping.cleanup as StressCleanupOperation).cleaned = true
        dispatch(
            VoiceAgentCallEvent.CleanupFinished(stopping.cleanup, VoiceAgentCleanupResult.Completed),
            generated = false,
        )
    }

    private fun closeForDrain() {
        dispatch(VoiceAgentCallEvent.CloseNowRequested, generated = false)
    }

    private fun newOperation(request: VoiceAgentCallRequest): StressStartOperation =
        StressStartOperation(request, newCleanup())

    private fun newCleanup(): StressCleanupOperation = StressCleanupOperation()

    private fun assertRequiredCoverage() {
        val required = buildSet {
            addAll(EVENT_NAMES)
            addAll(IDENTITY_EVENTS.flatMap { event -> listOf("$event:current", "$event:stale") })
            addAll(listOf("StartRequested:same", "StartRequested:different"))
            addAll(StartOutcomeKind.entries.map { "StartOutcome:$it" })
            addAll(listOf("CleanupResult:Completed", "CleanupResult:Failed"))
            addAll(SessionKind.entries.map { "Session:$it" })
        }
        assertEquals("missing deterministic stress coverage", emptySet<String>(), required - coverage)
    }

    private fun randomCleanupResult(): VoiceAgentCleanupResult = if (random.nextBoolean()) {
        VoiceAgentCleanupResult.Completed
    } else {
        VoiceAgentCleanupResult.Failed(IllegalStateException("random cleanup failure"))
    }
}

private enum class StartOutcomeKind {
    Ready,
    FailedClean,
    FailedDirty,
    Cancelled,
}

private enum class SessionKind {
    Connected,
    ErrorUsable,
    ErrorUnusable,
    Ended,
}

private class StressStartOperation(
    override val request: VoiceAgentCallRequest,
    override val cleanup: VoiceAgentCleanupOperation,
) : VoiceAgentStartOperation {
    override val token: Any = Any()
    private val callJob = completedStressJob()
    override val phase: VoiceAgentStartPhase = VoiceAgentStartPhase.PreparingRoute(
        request = request,
        callScope = CoroutineScope(callJob),
        callJob = callJob,
    )

    override fun start() = Unit

    override fun cancel() = Unit
}

private class StressCleanupOperation : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    var cleaned = false

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult =
        VoiceAgentCleanupResult.Completed
}

private class StressRouteOwnedSession(
    override val cleanupOperation: VoiceAgentCleanupOperation,
) : RouteOwnedManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState(session = VoiceSessionStatus.Connected))
    override val routeMetadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom)
    override val isRouteUsable = true

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
}

private fun activeCall(
    request: VoiceAgentCallRequest,
    cleanup: VoiceAgentCleanupOperation,
): ActiveVoiceAgentCall {
    val session = StressRouteOwnedSession(cleanup)
    val callJob = completedStressJob()
    return ActiveVoiceAgentCall(
        token = Any(),
        request = request,
        route = session.routeMetadata,
        session = session,
        callScope = CoroutineScope(callJob),
        callJob = callJob,
        collector = completedStressJob(),
        cleanup = cleanup,
    )
}

private fun completedStressJob(): Job = CompletableDeferred<Unit>().apply { complete(Unit) }

private fun VoiceAgentCallState.desiredRequest(): VoiceAgentCallRequest? = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Stopping.ForEnd,
    is VoiceAgentCallState.CleanupFailed,
    -> null
    is VoiceAgentCallState.Starting -> pending.request
    is VoiceAgentCallState.Active -> call.request
    is VoiceAgentCallState.Stopping.ForReplacement -> pending.request
}

private fun VoiceAgentCallState.startWaiters(): List<CompletableDeferred<VoiceAgentCallStartResult>> = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Active,
    is VoiceAgentCallState.CleanupFailed,
    -> emptyList()
    is VoiceAgentCallState.Starting -> pending.replies
    is VoiceAgentCallState.Stopping.ForEnd -> supersededStarts
    is VoiceAgentCallState.Stopping.ForReplacement -> supersededStarts + pending.replies
}

private fun VoiceAgentCallState.cleanupOwner(): VoiceAgentCleanupOperation? = when (this) {
    VoiceAgentCallState.Idle,
    is VoiceAgentCallState.Starting.Admitting,
    -> null
    is VoiceAgentCallState.Starting.Running -> operation.cleanup
    is VoiceAgentCallState.Active -> call.cleanup
    is VoiceAgentCallState.Stopping -> cleanup
    is VoiceAgentCallState.CleanupFailed -> cleanup
}

private const val EVENTS_PER_SEED = 250
private const val MAX_DRAIN_EVENTS = 16
private val EVENT_NAMES = setOf(
    "StartRequested",
    "StartAdmitted",
    "StartCancelled",
    "EndRequested",
    "CloseNowRequested",
    "StartFinished",
    "CleanupFinished",
    "SessionStateChanged",
)
private val IDENTITY_EVENTS = setOf(
    "StartAdmitted",
    "StartCancelled",
    "StartFinished",
    "CleanupFinished",
    "SessionStateChanged",
)
