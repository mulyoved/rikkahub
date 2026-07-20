package me.rerere.rikkahub.voiceagent

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
        startupCleanup.releaseLocalCleanupAfterPublication()
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
            val canonical = cancellation.canonicalVoiceAgentCancellation()
            finishFailure(canonical)
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
            val routeMetadata = session.routeMetadata
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
                route = routeMetadata,
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
        return when (val claim = startupCleanup.claimLocalCleanup()) {
            StartupLocalCleanupClaim.Clean -> VoiceAgentStartOutcome.FailedClean(error)
            StartupLocalCleanupClaim.External -> VoiceAgentStartOutcome.Cancelled
            is StartupLocalCleanupClaim.Execute -> finishClaimedFailure(error, claim)
        }
    }

    private suspend fun finishClaimedFailure(
        error: Throwable,
        claim: StartupLocalCleanupClaim.Execute,
    ): VoiceAgentStartOutcome {
        val result = withContext(NonCancellable) {
            try {
                claim.cleanup.run(VoiceAgentCleanupMode.Immediate)
            } catch (cleanupError: Throwable) {
                VoiceAgentCleanupResult.Failed(cleanupError)
            }
        }
        if (startupCleanup.completeLocalCleanup(claim, result)) {
            return VoiceAgentStartOutcome.Cancelled
        }
        return when (result) {
            VoiceAgentCleanupResult.Completed -> VoiceAgentStartOutcome.FailedClean(error)
            is VoiceAgentCleanupResult.Failed -> {
                error.addVoiceAgentSuppressedDistinct(result.error)
                VoiceAgentStartOutcome.FailedDirty(error, startupCleanup)
            }
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
    data class Local(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupAttempt
    data class Running(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupAttempt
    data object Completed : StartupCleanupAttempt
}

private sealed interface StartupCleanupDecision {
    data object Completed : StartupCleanupDecision
    data class Execute(
        val completion: CompletableDeferred<VoiceAgentCleanupResult>,
        val localCompletion: CompletableDeferred<VoiceAgentCleanupResult>?,
    ) : StartupCleanupDecision
    data class Join(val completion: CompletableDeferred<VoiceAgentCleanupResult>) : StartupCleanupDecision
}

private sealed interface StartupLocalCleanupClaim {
    data object Clean : StartupLocalCleanupClaim
    data object External : StartupLocalCleanupClaim

    data class Execute(
        val cleanup: VoiceAgentCleanupOperation,
        val completion: CompletableDeferred<VoiceAgentCleanupResult>,
    ) : StartupLocalCleanupClaim
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

    fun clearDelegate(expected: VoiceAgentCleanupOperation? = null) {
        synchronized(lock) {
            val current = target as? StartupCleanupTarget.Owned
            if (expected == null || current?.cleanup === expected) target = StartupCleanupTarget.None
        }
    }

    fun claimLocalCleanup(): StartupLocalCleanupClaim = synchronized(lock) {
        if (
            cancellationState == StartupCancellationState.CleanupRequested ||
            attempt != StartupCleanupAttempt.Ready
        ) {
            return@synchronized StartupLocalCleanupClaim.External
        }
        val cleanup = when (val current = target) {
            StartupCleanupTarget.None -> return@synchronized StartupLocalCleanupClaim.Clean
            is StartupCleanupTarget.Owned -> current.cleanup
        }
        val completion = CompletableDeferred<VoiceAgentCleanupResult>()
        attempt = StartupCleanupAttempt.Local(completion)
        StartupLocalCleanupClaim.Execute(cleanup, completion)
    }

    fun completeLocalCleanup(
        claim: StartupLocalCleanupClaim.Execute,
        result: VoiceAgentCleanupResult,
    ): Boolean = synchronized(lock) {
        if (result == VoiceAgentCleanupResult.Completed) {
            val current = target as? StartupCleanupTarget.Owned
            if (current?.cleanup === claim.cleanup) target = StartupCleanupTarget.None
        }
        check(claim.completion.complete(result)) { "Local startup cleanup was already completed" }
        cancellationState == StartupCancellationState.CleanupRequested ||
            (attempt as? StartupCleanupAttempt.Local)?.completion !== claim.completion
    }

    fun releaseLocalCleanupAfterPublication() {
        synchronized(lock) {
            val local = attempt as? StartupCleanupAttempt.Local ?: return
            if (
                local.completion.isCompleted &&
                cancellationState == StartupCancellationState.Running
            ) {
                attempt = StartupCleanupAttempt.Ready
            }
        }
    }

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        val decision = synchronized(lock) {
            when (val current = attempt) {
                StartupCleanupAttempt.Completed -> StartupCleanupDecision.Completed
                StartupCleanupAttempt.Ready -> CompletableDeferred<VoiceAgentCleanupResult>().also {
                    attempt = StartupCleanupAttempt.Running(it)
                }.let { StartupCleanupDecision.Execute(it, null) }
                is StartupCleanupAttempt.Local -> CompletableDeferred<VoiceAgentCleanupResult>().also {
                    attempt = StartupCleanupAttempt.Running(it)
                }.let { externalCompletion ->
                    val joinsLocalAttempt =
                        cancellationState == StartupCancellationState.CleanupRequested ||
                            !current.completion.isCompleted
                    StartupCleanupDecision.Execute(
                        completion = externalCompletion,
                        localCompletion = current.completion.takeIf { joinsLocalAttempt },
                    )
                }
                is StartupCleanupAttempt.Running -> StartupCleanupDecision.Join(current.completion)
            }
        }
        worker.cancel()
        return when (decision) {
            StartupCleanupDecision.Completed -> VoiceAgentCleanupResult.Completed
            is StartupCleanupDecision.Join -> decision.completion.await()
            is StartupCleanupDecision.Execute -> executeAndPublish(
                mode = mode,
                completion = decision.completion,
                localCompletion = decision.localCompletion,
            )
        }
    }

    private suspend fun executeAndPublish(
        mode: VoiceAgentCleanupMode,
        completion: CompletableDeferred<VoiceAgentCleanupResult>,
        localCompletion: CompletableDeferred<VoiceAgentCleanupResult>?,
    ): VoiceAgentCleanupResult {
        val localResult = localCompletion?.await()
        val result = executeAttempt(mode, localResult)
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

    private suspend fun executeAttempt(
        mode: VoiceAgentCleanupMode,
        localResult: VoiceAgentCleanupResult?,
    ): VoiceAgentCleanupResult {
        var failure = (localResult as? VoiceAgentCleanupResult.Failed)?.error
        worker.cancel()
        try {
            worker.join()
        } catch (error: Throwable) {
            failure = failure.appendStartupFailure(error)
        }
        val current = currentTarget()
        if (localResult == null && current is StartupCleanupTarget.Owned) {
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
        return when {
            failure == null -> VoiceAgentCleanupResult.Completed
            localResult is VoiceAgentCleanupResult.Failed && failure === localResult.error -> localResult
            else -> VoiceAgentCleanupResult.Failed(failure)
        }
    }
}

private fun Throwable?.appendStartupFailure(error: Throwable): Throwable = when {
    this == null -> error
    this !== error && error !in suppressed -> apply { addSuppressed(error) }
    else -> this
}
