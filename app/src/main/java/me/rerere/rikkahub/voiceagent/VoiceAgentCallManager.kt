package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal sealed interface VoiceAgentManagerStartResult {
    data class Started(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentManagerStartResult
    data object Superseded : VoiceAgentManagerStartResult
}

internal enum class VoiceAgentStartupResolution {
    Published,
    Failed,
    Superseded,
}

internal sealed interface VoiceAgentRouteMatchResult {
    data object NoMatch : VoiceAgentRouteMatchResult
    data class Existing(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
    data class Superseded(val route: VoiceAgentRouteMetadata) : VoiceAgentRouteMatchResult
}

class VoiceAgentCallManager(
    private val factory: VoiceAgentCallFactory,
) {
    private sealed interface CallSlot {
        data object Idle : CallSlot

        data class Starting(
            val token: Any,
            val conversationId: Uuid,
            val launchConfig: VoiceAgentLaunchConfig,
            val route: VoiceAgentRouteMetadata,
            val resolution: CompletableDeferred<VoiceAgentStartupResolution>,
            val predecessorCleanup: CompletableDeferred<Result<Unit>>?,
        ) : CallSlot

        data class Active(
            val token: Any,
            val conversationId: Uuid,
            val launchConfig: VoiceAgentLaunchConfig,
            val route: VoiceAgentRouteMetadata,
            val session: RouteOwnedManagedVoiceCallSession,
            val stateCollectionJob: Job?,
        ) : CallSlot
    }

    private sealed interface StartDecision {
        data class Own(
            val reservation: CallSlot.Starting,
            val predecessor: CallSlot.Active?,
            val displaced: CallSlot.Starting?,
        ) : StartDecision

        data class Await(val reservation: CallSlot.Starting) : StartDecision
        data class Reuse(val route: VoiceAgentRouteMetadata) : StartDecision
        data object RetrySuperseded : StartDecision
    }

    private val lock = Any()
    private val _state = MutableStateFlow(VoiceAgentUiState())
    private val _activeConversationId = MutableStateFlow<Uuid?>(null)
    private var slot: CallSlot = CallSlot.Idle
    private var callStatus: VoiceCallStatus = VoiceCallStatus.Idle

    val activeConversationId: StateFlow<Uuid?> = _activeConversationId.asStateFlow()
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    internal suspend fun start(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): VoiceAgentManagerStartResult {
        var retryingFailedMatch = false
        while (true) {
            val decision = synchronized(lock) {
                decideStartLocked(
                    conversationId = conversationId,
                    config = config,
                    route = routeLease.metadata,
                    retryingFailedMatch = retryingFailedMatch,
                )
            }
            when (decision) {
                is StartDecision.Reuse -> {
                    routeLease.retire()
                    return VoiceAgentManagerStartResult.Existing(decision.route)
                }

                is StartDecision.RetrySuperseded -> {
                    routeLease.retire()
                    return VoiceAgentManagerStartResult.Superseded
                }

                is StartDecision.Await -> {
                    val resolution = try {
                        decision.reservation.resolution.await()
                    } catch (cancellation: CancellationException) {
                        runCatching(routeLease::retire)
                            .exceptionOrNull()
                            ?.let(cancellation::addSuppressed)
                        throw cancellation
                    }
                    when (resolution) {
                        VoiceAgentStartupResolution.Published -> {
                            routeLease.retire()
                            return VoiceAgentManagerStartResult.Existing(decision.reservation.route)
                        }

                        VoiceAgentStartupResolution.Superseded -> {
                            routeLease.retire()
                            return VoiceAgentManagerStartResult.Superseded
                        }

                        VoiceAgentStartupResolution.Failed -> retryingFailedMatch = true
                    }
                }

                is StartDecision.Own -> {
                    decision.displaced?.resolution?.complete(VoiceAgentStartupResolution.Superseded)
                    return runReservationOwner(
                        reservation = decision.reservation,
                        routeLease = routeLease,
                        scope = scope,
                        predecessor = decision.predecessor,
                    )
                }
            }
        }
    }

    internal suspend fun matchingRoute(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
    ): VoiceAgentRouteMatchResult {
        var awaitedRoute: VoiceAgentRouteMetadata? = null
        while (true) {
            val matching = synchronized(lock) {
                when (val current = slot) {
                    CallSlot.Idle -> null
                    is CallSlot.Active -> current.takeIf {
                        it.conversationId == conversationId && it.launchConfig == config
                    }
                    is CallSlot.Starting -> current.takeIf {
                        it.conversationId == conversationId && it.launchConfig == config
                    }
                }
            }
            when (matching) {
                CallSlot.Idle -> return VoiceAgentRouteMatchResult.NoMatch
                null -> return awaitedRoute?.let(VoiceAgentRouteMatchResult::Superseded)
                    ?: VoiceAgentRouteMatchResult.NoMatch
                is CallSlot.Active -> return VoiceAgentRouteMatchResult.Existing(matching.route)
                is CallSlot.Starting -> {
                    awaitedRoute = matching.route
                    when (matching.resolution.await()) {
                        VoiceAgentStartupResolution.Published -> {
                            return VoiceAgentRouteMatchResult.Existing(matching.route)
                        }

                        VoiceAgentStartupResolution.Superseded -> {
                            return VoiceAgentRouteMatchResult.Superseded(matching.route)
                        }

                        VoiceAgentStartupResolution.Failed -> {
                            val afterFailure = synchronized(lock) { slot }
                            when (afterFailure) {
                                CallSlot.Idle -> return VoiceAgentRouteMatchResult.NoMatch
                                is CallSlot.Active -> if (
                                    afterFailure.conversationId != conversationId ||
                                    afterFailure.launchConfig != config
                                ) {
                                    return VoiceAgentRouteMatchResult.Superseded(matching.route)
                                }
                                is CallSlot.Starting -> if (
                                    afterFailure.conversationId != conversationId ||
                                    afterFailure.launchConfig != config
                                ) {
                                    return VoiceAgentRouteMatchResult.Superseded(matching.route)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun canPreserveActiveSession(conversationId: Uuid): Boolean = synchronized(lock) {
        (slot as? CallSlot.Active)
            ?.takeIf { it.conversationId == conversationId }
            ?.session
            ?.isRouteUsable == true
    }

    fun interrupt() = activeSessionSnapshot()?.interrupt()
    fun setMuted(value: Boolean) = activeSessionSnapshot()?.setMuted(value)
    fun reconnect() = activeSessionSnapshot()?.reconnect()

    fun updateCallStatus(status: VoiceCallStatus) {
        synchronized(lock) {
            callStatus = status
            _state.value = _state.value.copy(call = status)
        }
    }

    fun recordDiagnostic(name: String, detail: String) =
        activeSessionSnapshot()?.recordDiagnostic(name = name, detail = detail)

    fun end() {
        val active = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            detachActiveLocked()
        }
        active?.stateCollectionJob?.cancel()
        active?.session?.end()
    }

    fun detachForEndAndDrain(): RouteOwnedManagedVoiceCallSession? {
        val active = synchronized(lock) {
            callStatus = VoiceCallStatus.Ending
            _state.value = _state.value.copy(call = VoiceCallStatus.Ending)
            detachActiveLocked()
        }
        active?.stateCollectionJob?.cancel()
        return active?.session
    }

    fun closeNow() {
        val active = synchronized(lock) {
            callStatus = VoiceCallStatus.Ended
            _state.value = VoiceAgentUiState(call = VoiceCallStatus.Ended)
            detachActiveLocked()
        }
        active?.stateCollectionJob?.cancel()
        active?.session?.closeNow()
    }

    private fun decideStartLocked(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        route: VoiceAgentRouteMetadata,
        retryingFailedMatch: Boolean,
    ): StartDecision = when (val current = slot) {
        CallSlot.Idle -> StartDecision.Own(
            reservation = installReservationLocked(conversationId, config, route, predecessorCleanup = null),
            predecessor = null,
            displaced = null,
        )

        is CallSlot.Active -> if (current.conversationId == conversationId && current.launchConfig == config) {
            StartDecision.Reuse(current.route)
        } else if (retryingFailedMatch) {
            StartDecision.RetrySuperseded
        } else {
            val cleanup = CompletableDeferred<Result<Unit>>()
            StartDecision.Own(
                reservation = installReservationLocked(conversationId, config, route, cleanup),
                predecessor = current,
                displaced = null,
            )
        }

        is CallSlot.Starting -> if (current.conversationId == conversationId && current.launchConfig == config) {
            StartDecision.Await(current)
        } else if (retryingFailedMatch) {
            StartDecision.RetrySuperseded
        } else {
            StartDecision.Own(
                reservation = installReservationLocked(
                    conversationId,
                    config,
                    route,
                    current.predecessorCleanup,
                ),
                predecessor = null,
                displaced = current,
            )
        }
    }

    private fun installReservationLocked(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        route: VoiceAgentRouteMetadata,
        predecessorCleanup: CompletableDeferred<Result<Unit>>?,
    ) = CallSlot.Starting(
        token = Any(),
        conversationId = conversationId,
        launchConfig = config,
        route = route,
        resolution = CompletableDeferred(),
        predecessorCleanup = predecessorCleanup,
    ).also {
        slot = it
        _activeConversationId.value = null
    }

    private suspend fun runReservationOwner(
        reservation: CallSlot.Starting,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        predecessor: CallSlot.Active?,
    ): VoiceAgentManagerStartResult {
        var factoryOwnsLease = false
        var routeLeaseCleanupAttempted = false
        var createdSession: RouteOwnedManagedVoiceCallSession? = null
        var createdSessionCleanupAttempted = false
        try {
            if (predecessor != null) {
                val cleanupResult = runCatching {
                    predecessor.stateCollectionJob?.cancel()
                    predecessor.session.end()
                }
                checkNotNull(reservation.predecessorCleanup).complete(cleanupResult)
            }
            reservation.predecessorCleanup?.await()?.getOrThrow()
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                routeLeaseCleanupAttempted = true
                routeLease.retire()
                return VoiceAgentManagerStartResult.Superseded
            }

            factoryOwnsLease = true
            val session = factory.create(
                conversationId = reservation.conversationId,
                config = reservation.launchConfig,
                routeLease = routeLease,
                scope = scope,
            )
            createdSession = session
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }

            session.start()
            currentCoroutineContext().ensureActive()
            if (!owns(reservation)) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }

            val active = CallSlot.Active(
                token = reservation.token,
                conversationId = reservation.conversationId,
                launchConfig = reservation.launchConfig,
                route = session.routeMetadata,
                session = session,
                stateCollectionJob = null,
            )
            val published = synchronized(lock) {
                if (slot === reservation) {
                    slot = active
                    _activeConversationId.value = reservation.conversationId
                    _state.value = session.state.value.copy(call = callStatus)
                    true
                } else {
                    false
                }
            }
            if (!published) {
                createdSessionCleanupAttempted = true
                session.closeNow()
                return VoiceAgentManagerStartResult.Superseded
            }

            val collector = scope.launch {
                session.state.collect { sessionState ->
                    synchronized(lock) {
                        val current = slot
                        if (current is CallSlot.Active && current.token === reservation.token) {
                            _state.value = sessionState.copy(call = callStatus)
                        }
                    }
                }
            }
            val attached = synchronized(lock) {
                val current = slot
                if (current === active) {
                    slot = active.copy(stateCollectionJob = collector)
                    true
                } else {
                    false
                }
            }
            if (!attached) collector.cancel()
            reservation.resolution.complete(VoiceAgentStartupResolution.Published)
            return VoiceAgentManagerStartResult.Started(session.routeMetadata)
        } catch (failure: Throwable) {
            val wasOwner = synchronized(lock) {
                val current = slot
                if (current === reservation ||
                    current is CallSlot.Active && current.token === reservation.token
                ) {
                    slot = CallSlot.Idle
                    _activeConversationId.value = null
                    _state.value = VoiceAgentUiState()
                    true
                } else {
                    false
                }
            }
            if (wasOwner) reservation.resolution.complete(VoiceAgentStartupResolution.Failed)
            val cleanupFailure = when {
                factoryOwnsLease && createdSession != null && !createdSessionCleanupAttempted -> {
                    createdSessionCleanupAttempted = true
                    runCatching(createdSession::closeNow).exceptionOrNull()
                }
                !factoryOwnsLease && !routeLeaseCleanupAttempted -> {
                    routeLeaseCleanupAttempted = true
                    runCatching(routeLease::retire).exceptionOrNull()
                }
                else -> null
            }
            cleanupFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun owns(reservation: CallSlot.Starting): Boolean = synchronized(lock) {
        slot === reservation
    }

    private fun activeSessionSnapshot(): RouteOwnedManagedVoiceCallSession? = synchronized(lock) {
        (slot as? CallSlot.Active)?.session
    }

    private fun detachActiveLocked(): CallSlot.Active? = (slot as? CallSlot.Active)?.also {
        slot = CallSlot.Idle
        _activeConversationId.value = null
    }
}
