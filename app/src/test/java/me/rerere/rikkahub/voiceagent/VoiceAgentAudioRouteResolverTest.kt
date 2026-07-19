package me.rerere.rikkahub.voiceagent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentAudioRouteResolverTest {
    @Test
    fun `active attempt selects Telecom`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val telecomCall = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { id ->
            attempt = id
            registry.activate(id, telecomCall)
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        lease.retire()
        assertEquals(1, telecomCall.disconnectCalls)
    }

    @Test
    fun `registration failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
            registry,
            100,
        ).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_register_failed", lease.metadata.failure?.diagnosticName)
        lease.retire()
    }

    @Test
    fun `fallback retirement cannot affect a newer Telecom attempt`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(registerResult = Result.failure(IllegalStateException("denied"))),
            registry,
            100,
        ).resolve()
        val newerAttempt = registry.beginAttempt()
        val newerCall = ResolverFakeCall()
        assertTrue(registry.activate(newerAttempt, newerCall))

        lease.retire()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals(0, newerCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(newerAttempt))
        registry.acknowledgeOutcome(newerAttempt)
        registry.retireOwnedAttempt(newerAttempt)
    }

    @Test
    fun `placement failure selects direct fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()

        val lease = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(startResult = Result.failure(IllegalStateException("rejected"))),
            registry,
            100,
        ).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_start_failed", lease.metadata.failure?.diagnosticName)
    }

    @Test
    fun `throwing previous-call supersession returns contained fallback and consumes replacement`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val previous = registry.beginAttempt()
        val previousCall = ThrowingResolverCall()
        registry.activate(previous, previousCall)
        registry.awaitOutcome(previous)
        val gateway = FakeTelecomGateway()

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals(
            VoiceAgentTelecomFailure(
                diagnosticName = "telecom_supersession_cleanup_failed",
                detail = "framework retirement failed",
            ),
            lease.metadata.failure,
        )
        assertEquals(0, gateway.registerCalls)
        assertEquals(0, gateway.startCalls)
        assertEquals(1, previousCall.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(previous))
        assertFalse(registry.isOwnedAttemptActive(VoiceAgentTelecomAttemptId(previous.value + 1)))
    }

    @Test
    fun `ConnectionService rejection is preserved`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val gateway = FakeTelecomGateway(onStart = { id ->
            registry.fail(id, VoiceAgentTelecomFailure("telecom_outgoing_failed", "framework rejected"))
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 100).resolve()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_outgoing_failed", lease.metadata.failure?.diagnosticName)
        assertEquals("framework rejected", lease.metadata.failure?.detail)
    }

    @Test
    fun `timeout selects fallback and disconnects late connection`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { attempt = it })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 1).resolve()
        val late = ResolverFakeCall()
        val accepted = registry.activate(requireNotNull(attempt), late)

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_connection_timeout", lease.metadata.failure?.diagnosticName)
        assertEquals(false, accepted)
        assertEquals(1, late.disconnectCalls)
    }

    @Test
    fun `active attempt at timeout boundary retains Telecom ownership`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val telecomCall = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val gateway = FakeTelecomGateway(onStart = { id ->
            attempt = id
            registry.activate(id, telecomCall)
        })

        val lease = VoiceAgentAudioRouteResolver(gateway, registry, 0).resolve()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        lease.retire()
        assertEquals(1, telecomCall.disconnectCalls)
    }

    @Test
    fun `completed outcome at timeout publication boundary selects one owner and is consumed`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val timeout = BoundaryOutcomeTimeout()
        val resolver = VoiceAgentAudioRouteResolver(
            gateway = FakeTelecomGateway(onStart = { attempt = it }),
            registry = registry,
            timeoutMs = 1_000,
            outcomeTimeout = timeout,
        )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
        timeout.observationStarted.await()

        assertTrue(registry.activate(requireNotNull(attempt), call))
        runCurrent()
        assertEquals(VoiceAgentTelecomOutcome.Active, timeout.observedOutcome.await())
        assertFalse(resolution.isCompleted)

        timeout.returnTimeout.complete(Unit)
        runCurrent()
        val lease = resolution.await()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(null, lease.metadata.failure)
        assertTrue(registry.isOwnedAttemptActive(requireNotNull(attempt)))
        assertEquals(0, call.disconnectCalls)
        lease.retire()
        assertEquals(1, call.disconnectCalls)
    }

    @Test
    fun `caller cancellation retires pending attempt before rethrowing`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        var attempt: VoiceAgentTelecomAttemptId? = null
        val cancellation = CancellationException("caller cancelled")
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = {
                attempt = it
                throw cancellation
            }),
            registry,
            1_000,
        )

        val thrown = runCatching { resolver.resolve() }.exceptionOrNull()
        val late = ResolverFakeCall()
        val accepted = registry.activate(requireNotNull(attempt), late)

        assertSame(cancellation, thrown)
        assertFalse(accepted)
        assertEquals(1, late.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }

    @Test
    fun `retirement error does not replace caller cancellation`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ThrowingResolverCall()
        val cancellation = CancellationException("caller cancelled after activation")
        var attempt: VoiceAgentTelecomAttemptId? = null
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { id ->
                attempt = id
                registry.activate(id, call)
                throw cancellation
            }),
            registry,
            1_000,
        )

        val thrown = runCatching { resolver.resolve() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf("framework retirement failed"), cancellation.suppressed.map { it.message })
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }

    @Test
    fun `canceled active resolution keeps exact dirty cleanup ahead of replacement gateway work`() = runBlocking {
        val firstFailure = IllegalStateException("first pre-lease cleanup failed")
        val secondFailure = IllegalArgumentException("second pre-lease cleanup failed")
        val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val registry = VoiceAgentTelecomCallRegistry()
        val oldCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailure,
            onCleanup = { call ->
                events += "old-cleanup-$call"
                if (call == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(1, TimeUnit.SECONDS)) {
                        "predecessor cleanup retry was not released"
                    }
                }
            },
        )
        val cancellation = CancellationException("caller cancelled after activation")
        val initialGateway = RecordingTelecomGateway(events) { attempt ->
            assertTrue(registry.activate(attempt, oldCall))
            throw cancellation
        }

        val thrown = runCatching {
            VoiceAgentAudioRouteResolver(initialGateway, registry, 1_000).resolve()
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, cancellation.suppressed.size)
        assertSame(firstFailure, cancellation.suppressed.single())
        assertEquals(1, oldCall.disconnectCalls.get())
        events.clear()

        cleanupFailure.set(secondFailure)
        val replacementCall = ResolverFakeCall()
        val replacementGateway = RecordingTelecomGateway(events) { attempt ->
            events += "replacement-active"
            assertTrue(registry.activate(attempt, replacementCall))
        }
        val retryResolver = VoiceAgentAudioRouteResolver(replacementGateway, registry, 1_000)
        val secondRetryStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit<Throwable?> {
                runCatching { runBlocking { retryResolver.resolve() } }.exceptionOrNull()
            }
            check(retryEntered.await(1, TimeUnit.SECONDS)) {
                "next resolution did not retry predecessor cleanup"
            }
            val joiner = executor.submit<Throwable?> {
                secondRetryStarted.countDown()
                runCatching { runBlocking { retryResolver.resolve() } }.exceptionOrNull()
            }
            check(secondRetryStarted.await(1, TimeUnit.SECONDS)) {
                "joining resolution did not start"
            }
            assertTrue(
                runCatching { owner.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            assertTrue(
                runCatching { joiner.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            assertEquals(0, replacementGateway.registerCalls)
            assertEquals(0, replacementGateway.startCalls)
            assertEquals(0, replacementCall.disconnectCalls)

            releaseRetry.countDown()
            assertSame(secondFailure, owner.get(1, TimeUnit.SECONDS))
            assertSame(secondFailure, joiner.get(1, TimeUnit.SECONDS))
            assertEquals(2, oldCall.disconnectCalls.get())
            assertEquals(listOf("old-cleanup-2"), events)

            events.clear()
            cleanupFailure.set(null)
            val lease = retryResolver.resolve()

            assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
            assertEquals(3, oldCall.disconnectCalls.get())
            assertEquals(
                listOf("old-cleanup-3", "register", "start", "replacement-active"),
                events,
            )
            assertEquals(0, replacementCall.disconnectCalls)
            lease.retire()
            assertEquals(1, replacementCall.disconnectCalls)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `framework failure during activation stays dirty until exact cleanup precedes replacement`() = runBlocking {
        val firstFailure = IllegalStateException("activation cleanup failed")
        val secondFailure = IllegalArgumentException("activation cleanup retry failed")
        val cleanupFailure = AtomicReference<Throwable?>(firstFailure)
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val registry = VoiceAgentTelecomCallRegistry()
        val oldCall = CallbackFaithfulResolverCall(
            registry = registry,
            cleanupFailure = cleanupFailure,
            onCleanup = { call -> events += "old-cleanup-$call" },
        )
        val accepted = AtomicBoolean(true)
        val activationFailure = AtomicReference<Throwable>()
        var activation: Thread? = null
        var attempt: VoiceAgentTelecomAttemptId? = null
        val firstGateway = RecordingTelecomGateway(events) { id ->
            attempt = id
            activation = thread {
                runCatching {
                    accepted.set(
                        registry.activate(id, oldCall) {
                            activationEntered.countDown()
                            check(releaseActivation.await(5, TimeUnit.SECONDS)) {
                                "activation callback was not released"
                            }
                            events += "activation-returned"
                        },
                    )
                }.onFailure(activationFailure::set)
            }
            check(activationEntered.await(1, TimeUnit.SECONDS)) {
                "activation callback did not start"
            }
        }
        val resolver = VoiceAgentAudioRouteResolver(firstGateway, registry, 1_000)
        val resolution = async(Dispatchers.Default) {
            runCatching { resolver.resolve() }.exceptionOrNull()
        }
        check(activationEntered.await(1, TimeUnit.SECONDS)) {
            "activation callback did not start"
        }
        val dirtyOutcome = async(Dispatchers.Default) {
            registry.observeOutcome(requireNotNull(attempt))
        }

        val frameworkFailure = runCatching { oldCall.disconnectFromApp() }.exceptionOrNull()
        val outcome = withTimeoutOrNull(1_000) { dirtyOutcome.await() }
        val resolutionFailure = withTimeoutOrNull(1_000) { resolution.await() }

        assertSame(firstFailure, frameworkFailure)
        assertTrue(outcome is VoiceAgentTelecomOutcome.CleanupFailed)
        val cleanupFailed = outcome as VoiceAgentTelecomOutcome.CleanupFailed
        assertEquals("telecom_connection_disconnected", cleanupFailed.failure.diagnosticName)
        assertSame(firstFailure, cleanupFailed.cleanupError)
        assertSame(firstFailure, resolutionFailure)
        assertEquals(1, oldCall.disconnectCalls.get())
        assertEquals(1, firstGateway.registerCalls)
        assertEquals(1, firstGateway.startCalls)

        releaseActivation.countDown()
        activation?.join(1_000)
        assertFalse(checkNotNull(activation).isAlive)
        activationFailure.get()?.let { throw AssertionError("activation failed", it) }
        assertFalse(accepted.get())
        assertEquals(1, oldCall.disconnectCalls.get())

        events.clear()
        cleanupFailure.set(secondFailure)
        val replacementCall = ResolverFakeCall()
        val replacementGateway = RecordingTelecomGateway(events) { id ->
            events += "replacement-active"
            assertTrue(registry.activate(id, replacementCall))
        }
        val retryResolver = VoiceAgentAudioRouteResolver(replacementGateway, registry, 1_000)

        val retryFailure = runCatching { retryResolver.resolve() }.exceptionOrNull()

        assertSame(secondFailure, retryFailure)
        assertEquals(2, oldCall.disconnectCalls.get())
        assertEquals(0, replacementGateway.registerCalls)
        assertEquals(0, replacementGateway.startCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        assertEquals(listOf("old-cleanup-2"), events)

        events.clear()
        cleanupFailure.set(null)
        val lease = retryResolver.resolve()

        assertEquals(VoiceAudioRouteOwner.Telecom, lease.metadata.owner)
        assertEquals(3, oldCall.disconnectCalls.get())
        assertEquals(
            listOf("old-cleanup-3", "register", "start", "replacement-active"),
            events,
        )
        assertEquals(0, replacementCall.disconnectCalls)
        lease.retire()
        assertEquals(1, replacementCall.disconnectCalls)
    }

    @Test
    fun `caller cancellation waits for blocked activation retirement before rethrowing`() = runTest {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        var activation: Thread? = null
        var attempt: VoiceAgentTelecomAttemptId? = null
        val resolver = VoiceAgentAudioRouteResolver(
            FakeTelecomGateway(onStart = { id ->
                attempt = id
                activation = thread {
                    accepted.set(
                        registry.activate(id, call) {
                            callbackEntered.countDown()
                            releaseCallback.await()
                        },
                    )
                }
                assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
            }),
            registry,
            1_000,
        )
        val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
        val cancellation = CancellationException("caller cancelled during activation")

        resolution.cancel(cancellation)
        runCurrent()
        try {
            assertFalse(resolution.isCompleted)
            assertEquals(0, call.disconnectCalls)
        } finally {
            releaseCallback.countDown()
            activation?.join()
        }
        runCurrent()
        val thrown = runCatching { resolution.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(requireNotNull(attempt)))
    }

    @Test
    fun `timeout waits for in-progress activation retirement before fallback`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = ResolverFakeCall()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val events = Collections.synchronizedList(mutableListOf<String>())
        var activation: Thread? = null
        val gateway = FakeTelecomGateway(onStart = { attempt ->
            activation = thread {
                accepted.set(
                    registry.activate(attempt, call) {
                        callbackEntered.countDown()
                        releaseCallback.await()
                        events += "setActive"
                    },
                )
            }
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))
        })

        val resolution = async(start = CoroutineStart.UNDISPATCHED) {
            VoiceAgentAudioRouteResolver(
                gateway = gateway,
                registry = registry,
                timeoutMs = 1_000,
                outcomeTimeout = ImmediateOutcomeTimeout,
            ).resolve().also {
                events += "fallback"
            }
        }

        try {
            assertFalse(resolution.isCompleted)
            assertEquals(0, call.disconnectCalls)
        } finally {
            releaseCallback.countDown()
            activation?.join()
        }
        val lease = resolution.await()

        assertEquals(VoiceAudioRouteOwner.DirectFallback, lease.metadata.owner)
        assertEquals("telecom_connection_timeout", lease.metadata.failure?.diagnosticName)
        assertFalse(accepted.get())
        assertEquals(1, call.disconnectCalls)
        assertEquals(listOf("setActive", "fallback"), events)
    }
}

private class FakeTelecomGateway(
    private val registerResult: Result<Unit> = Result.success(Unit),
    private val startResult: Result<Unit> = Result.success(Unit),
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit = {},
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        return registerResult
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
        onStart(attemptId)
        return startResult
    }
}

private class ResolverFakeCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
    }
}

private class ThrowingResolverCall : VoiceAgentTelecomCall {
    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        error("framework retirement failed")
    }
}

private class CallbackFaithfulResolverCall(
    private val registry: VoiceAgentTelecomCallRegistry,
    private val cleanupFailure: AtomicReference<Throwable?>,
    private val onCleanup: (Int) -> Unit = {},
) : VoiceAgentTelecomCall {
    val disconnectCalls = AtomicInteger()

    override fun disconnectFromApp() {
        val call = disconnectCalls.incrementAndGet()
        registry.retiring(this)
        val cleanupResult = runCatching {
            onCleanup(call)
            cleanupFailure.get()?.let { throw it }
            Unit
        }
        registry.retired(this, cleanupResult)
        cleanupResult.getOrThrow()
    }
}

private class RecordingTelecomGateway(
    private val events: MutableList<String>,
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit,
) : VoiceAgentTelecomGateway {
    var registerCalls = 0
    var startCalls = 0

    override fun register(): Result<Unit> {
        registerCalls += 1
        events += "register"
        return Result.success(Unit)
    }

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        startCalls += 1
        events += "start"
        onStart(attemptId)
        return Result.success(Unit)
    }
}

private class BoundaryOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    val observationStarted = CompletableDeferred<Unit>()
    val observedOutcome = CompletableDeferred<VoiceAgentTelecomOutcome>()
    val returnTimeout = CompletableDeferred<Unit>()

    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? {
        observationStarted.complete(Unit)
        observedOutcome.complete(observe())
        returnTimeout.await()
        return null
    }
}

private object ImmediateOutcomeTimeout : VoiceAgentTelecomOutcomeTimeout {
    override suspend fun awaitOutcome(
        timeoutMs: Long,
        observe: suspend () -> VoiceAgentTelecomOutcome,
    ): VoiceAgentTelecomOutcome? = null
}
