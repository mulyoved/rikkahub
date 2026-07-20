package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal fun voiceAgentStartOperation(
    request: VoiceAgentCallRequest,
    callScope: CoroutineScope,
    callJob: CompletableJob,
    factory: VoiceAgentCallFactory,
    resolveRoute: suspend () -> VoiceAgentRouteLease,
    onFinished: (VoiceAgentStartOperation, VoiceAgentStartOutcome) -> Unit,
    onSessionState: (ActiveVoiceAgentCall, VoiceAgentUiState, Boolean) -> Unit,
): VoiceAgentStartOperation = DefaultVoiceAgentStartOperation(
    request = request,
    callScope = callScope,
    callJob = callJob,
    factory = factory,
    resolveRoute = resolveRoute,
    onFinished = onFinished,
    onSessionState = onSessionState,
)

private class DefaultVoiceAgentStartOperation(
    override val request: VoiceAgentCallRequest,
    private val callScope: CoroutineScope,
    private val callJob: CompletableJob,
    private val factory: VoiceAgentCallFactory,
    private val resolveRoute: suspend () -> VoiceAgentRouteLease,
    private val onFinished: (VoiceAgentStartOperation, VoiceAgentStartOutcome) -> Unit,
    private val onSessionState: (ActiveVoiceAgentCall, VoiceAgentUiState, Boolean) -> Unit,
) : VoiceAgentStartOperation {
    override val token: Any = Any()
    private val phaseLock = Any()
    private var currentPhase: VoiceAgentStartPhase = VoiceAgentStartPhase.PreparingRoute(
        request = request,
        callScope = callScope,
        callJob = callJob,
    )
    private val startupCleanup = StartupCleanupOperation(callJob)
    private val worker = callScope.launch(start = CoroutineStart.LAZY) {
        val outcome = runStartup()
        onFinished(this@DefaultVoiceAgentStartOperation, outcome)
        if (outcome !is VoiceAgentStartOutcome.Ready) {
            callJob.complete()
        }
    }.also(startupCleanup::attachWorker)

    override val phase: VoiceAgentStartPhase
        get() = synchronized(phaseLock) { currentPhase }

    override val cleanup: VoiceAgentCleanupOperation
        get() = startupCleanup

    override fun start() {
        worker.start()
    }

    override fun cancel() {
        startupCleanup.cancelWorker()
    }

    private suspend fun runStartup(): VoiceAgentStartOutcome {
        return try {
            val routeLease = resolveRoute()
            startupCleanup.installDelegate(voiceAgentRouteCleanupOperation(routeLease))
            updatePhase(VoiceAgentStartPhase.CreatingSession(request, callScope, callJob))
            yield()
            currentCoroutineContext().ensureActive()
            when (val creation = factory.createOwned(request, routeLease, callScope)) {
                is VoiceAgentSessionCreationResult.Created -> startSession(creation.session)
                is VoiceAgentSessionCreationResult.FailedClean -> {
                    startupCleanup.clearDelegate()
                    VoiceAgentStartOutcome.FailedClean(creation.error)
                }
                is VoiceAgentSessionCreationResult.FailedDirty -> {
                    startupCleanup.installDelegate(creation.cleanup)
                    VoiceAgentStartOutcome.FailedDirty(creation.error, startupCleanup)
                }
            }
        } catch (cancellation: CancellationException) {
            if (!startupCleanup.wasCleanupRequested()) {
                val canonical = cancellation.canonicalStartupCancellation()
                val cleanupFailure = cleanCurrentResourceAfterCancellation()
                cleanupFailure?.let(canonical::addSuppressedDistinct)
            }
            VoiceAgentStartOutcome.Cancelled
        } catch (error: Throwable) {
            finishFailure(error)
        }
    }

    private suspend fun startSession(session: RouteOwnedManagedVoiceCallSession): VoiceAgentStartOutcome {
        startupCleanup.installDelegate(session.cleanupOperation)
        updatePhase(VoiceAgentStartPhase.StartingSession(request, callScope, callJob, session))
        return try {
            yield()
            currentCoroutineContext().ensureActive()
            session.start()
            yield()
            val initialState = session.state.value
            lateinit var call: ActiveVoiceAgentCall
            val collector = callScope.launch(start = CoroutineStart.LAZY) {
                session.state.collect { state ->
                    onSessionState(call, state, session.isRouteUsable)
                }
            }
            val activeCleanup = activeVoiceAgentCallCleanupOperation(
                collector = collector,
                callJob = callJob,
                sessionCleanup = session.cleanupOperation,
            )
            startupCleanup.installDelegate(activeCleanup)
            call = ActiveVoiceAgentCall(
                token = token,
                request = request,
                route = session.routeMetadata,
                session = session,
                callScope = callScope,
                callJob = callJob,
                collector = collector,
                cleanup = startupCleanup,
            )
            yield()
            currentCoroutineContext().ensureActive()
            VoiceAgentStartOutcome.Ready(call, initialState)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            finishFailure(error)
        }
    }

    private suspend fun finishFailure(error: Throwable): VoiceAgentStartOutcome {
        val cleanup = when (val target = startupCleanup.currentTarget()) {
            StartupCleanupTarget.None -> return VoiceAgentStartOutcome.FailedClean(error)
            is StartupCleanupTarget.Owned -> target.cleanup
        }
        return when (val result = cleanup.run(VoiceAgentCleanupMode.Immediate)) {
            VoiceAgentCleanupResult.Completed -> {
                startupCleanup.clearDelegate(cleanup)
                VoiceAgentStartOutcome.FailedClean(error)
            }
            is VoiceAgentCleanupResult.Failed -> {
                error.addSuppressedDistinct(result.error)
                VoiceAgentStartOutcome.FailedDirty(error, startupCleanup)
            }
        }
    }

    private suspend fun cleanCurrentResourceAfterCancellation(): Throwable? = withContext(NonCancellable) {
        val cleanup = when (val target = startupCleanup.currentTarget()) {
            StartupCleanupTarget.None -> return@withContext null
            is StartupCleanupTarget.Owned -> target.cleanup
        }
        when (val result = cleanup.run(VoiceAgentCleanupMode.Immediate)) {
            VoiceAgentCleanupResult.Completed -> {
                startupCleanup.clearDelegate(cleanup)
                null
            }
            is VoiceAgentCleanupResult.Failed -> result.error
        }
    }

    private fun updatePhase(value: VoiceAgentStartPhase) {
        synchronized(phaseLock) {
            currentPhase = value
        }
    }
}

private sealed interface StartupCleanupAttempt {
    data object Ready : StartupCleanupAttempt
    data class Running(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupAttempt
    data object Completed : StartupCleanupAttempt
}

private sealed interface StartupCleanupDecision {
    data object Completed : StartupCleanupDecision
    data class Execute(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupDecision
    data class Join(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupDecision
}

private sealed interface StartupCleanupTarget {
    data object None : StartupCleanupTarget
    data class Owned(val cleanup: VoiceAgentCleanupOperation) : StartupCleanupTarget
}

private enum class StartupCallJobProgress {
    Pending,
    Completed,
}

private enum class StartupCancellationState {
    Running,
    CleanupRequested,
}

private class StartupCleanupOperation(
    private val callJob: Job,
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    private val lock = Any()
    private lateinit var worker: Job
    private var target: StartupCleanupTarget = StartupCleanupTarget.None
    private var attempt: StartupCleanupAttempt = StartupCleanupAttempt.Ready
    private var callJobProgress = StartupCallJobProgress.Pending
    private var cancellationState = StartupCancellationState.Running

    fun attachWorker(value: Job) {
        synchronized(lock) {
            worker = value
        }
    }

    fun installDelegate(value: VoiceAgentCleanupOperation) {
        synchronized(lock) {
            target = StartupCleanupTarget.Owned(value)
        }
    }

    fun currentTarget(): StartupCleanupTarget = synchronized(lock) { target }

    fun cancelWorker() {
        synchronized(lock) {
            cancellationState = StartupCancellationState.CleanupRequested
        }
        worker.cancel()
    }

    fun wasCleanupRequested(): Boolean = synchronized(lock) {
        cancellationState == StartupCancellationState.CleanupRequested
    }

    fun clearDelegate(expected: VoiceAgentCleanupOperation? = null) {
        synchronized(lock) {
            val current = target as? StartupCleanupTarget.Owned
            if (expected == null || current?.cleanup === expected) target = StartupCleanupTarget.None
        }
    }

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        val decision = synchronized(lock) {
            when (val current = attempt) {
                StartupCleanupAttempt.Completed -> StartupCleanupDecision.Completed
                StartupCleanupAttempt.Ready -> CompletableDeferred<VoiceAgentCleanupResult>().also {
                    attempt = StartupCleanupAttempt.Running(it)
                }.let(StartupCleanupDecision::Execute)
                is StartupCleanupAttempt.Running -> StartupCleanupDecision.Join(current.completion)
            }
        }
        return when (decision) {
            StartupCleanupDecision.Completed -> VoiceAgentCleanupResult.Completed
            is StartupCleanupDecision.Join -> decision.completion.await()
            is StartupCleanupDecision.Execute -> executeAndPublish(mode, decision.completion)
        }
    }

    private suspend fun executeAndPublish(
        mode: VoiceAgentCleanupMode,
        completion: CompletableDeferred<VoiceAgentCleanupResult>,
    ): VoiceAgentCleanupResult {
        cancelWorker()
        val result = executeAttempt(mode)
        synchronized(lock) {
            if ((attempt as? StartupCleanupAttempt.Running)?.completion === completion) {
                attempt = if (
                    target == StartupCleanupTarget.None &&
                    callJobProgress == StartupCallJobProgress.Completed
                ) {
                    StartupCleanupAttempt.Completed
                } else {
                    StartupCleanupAttempt.Ready
                }
                completion.complete(result)
            }
        }
        return result
    }

    private suspend fun executeAttempt(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        var failure: Throwable? = null
        worker.cancel()
        try {
            worker.join()
        } catch (error: Throwable) {
            failure = failure.appendStartupFailure(error)
        }
        val current = currentTarget()
        if (current is StartupCleanupTarget.Owned) {
            try {
                when (val result = current.cleanup.run(mode)) {
                    VoiceAgentCleanupResult.Completed -> clearDelegate(current.cleanup)
                    is VoiceAgentCleanupResult.Failed -> failure = failure.appendStartupFailure(result.error)
                }
            } catch (error: Throwable) {
                failure = failure.appendStartupFailure(error)
            }
        }
        if (callJobProgress == StartupCallJobProgress.Pending) {
            try {
                callJob.cancel()
                callJob.join()
                callJobProgress = StartupCallJobProgress.Completed
            } catch (error: Throwable) {
                failure = failure.appendStartupFailure(error)
            }
        }
        return failure?.let(VoiceAgentCleanupResult::Failed) ?: VoiceAgentCleanupResult.Completed
    }
}

private fun Throwable?.appendStartupFailure(error: Throwable): Throwable = when {
    this == null -> error
    this !== error && error !in suppressed -> apply { addSuppressed(error) }
    else -> this
}

private fun Throwable.addSuppressedDistinct(error: Throwable) {
    if (error !== this && error !in suppressed) addSuppressed(error)
}

private fun CancellationException.canonicalStartupCancellation(): CancellationException {
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
