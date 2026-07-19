package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAgentRouteLeaseTest {
    @Test
    fun `Telecom lease exposes metadata and retires its exact attempt once`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.claimRouteLease(attempt)

        lease.retire()
        lease.retire()

        assertEquals(VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom), lease.metadata)
        assertEquals(1, call.disconnectCalls)
        assertFalse(registry.isOwnedAttemptActive(attempt))
    }

    @Test
    fun `failed exact Telecom retirement can retry same connection`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(firstFailure)
        val retryEntered = CountDownLatch(1)
        val releaseRetry = CountDownLatch(1)
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls += 1
                if (disconnectCalls == 2) {
                    retryEntered.countDown()
                    check(releaseRetry.await(1, TimeUnit.SECONDS)) {
                        "retry disconnect was not released"
                    }
                }
                retirementFailure.get()?.let { throw it }
            }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.claimRouteLease(attempt)

        assertSame(firstFailure, runCatching(lease::retire).exceptionOrNull())
        assertEquals(1, call.disconnectCalls)
        assertFalse(lease.isUsable)

        assertSame(
            firstFailure,
            runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
        )
        retirementFailure.set(null)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val retry = executor.submit { lease.retire() }
            check(retryEntered.await(1, TimeUnit.SECONDS)) {
                "retry disconnect did not start"
            }

            assertFalse(lease.isUsable)
            releaseRetry.countDown()
            retry.get(1, TimeUnit.SECONDS)
            assertEquals(2, call.disconnectCalls)
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementCall = RecordingTelecomCall()
            assertTrue(registry.activate(replacementAttempt, replacementCall))
            assertEquals(0, replacementCall.disconnectCalls)

            lease.retire()
            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)
        } finally {
            releaseRetry.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `lease joins concurrent supersession failure before retrying exact connection`() {
        val firstFailure = IllegalStateException("first retirement failed")
        val retirementFailure = AtomicReference<Throwable?>(firstFailure)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val leaseRetirementStarted = CountDownLatch(1)
        val leaseRetirementThread = AtomicReference<Thread>()
        val leaseRetirementInterrupted = AtomicBoolean()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = object : VoiceAgentTelecomCall {
            var disconnectCalls = 0

            override fun disconnectFromApp() {
                disconnectCalls += 1
                disconnectEntered.countDown()
                check(releaseDisconnect.await(1, TimeUnit.SECONDS)) {
                    "supersession disconnect was not released"
                }
                retirementFailure.get()?.let { throw it }
            }
        }
        assertTrue(registry.activate(attempt, call))
        val lease = registry.claimRouteLease(attempt)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val supersession = executor.submit<Throwable?> {
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull()
            }
            check(disconnectEntered.await(1, TimeUnit.SECONDS)) {
                "supersession disconnect did not start"
            }
            val leaseRetirement = executor.submit<Throwable?> {
                leaseRetirementThread.set(Thread.currentThread())
                leaseRetirementStarted.countDown()
                val failure = runCatching(lease::retire).exceptionOrNull()
                leaseRetirementInterrupted.set(Thread.currentThread().isInterrupted)
                failure
            }
            check(leaseRetirementStarted.await(1, TimeUnit.SECONDS)) {
                "lease retirement did not start"
            }

            assertTrue(
                "lease retirement did not join the supersession disconnect",
                runCatching { leaseRetirement.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            leaseRetirementThread.get().interrupt()
            assertTrue(
                "interrupted lease retirement returned before the supersession result",
                runCatching { leaseRetirement.get(100, TimeUnit.MILLISECONDS) }
                    .exceptionOrNull() is TimeoutException,
            )
            releaseDisconnect.countDown()

            val supersessionFailure = supersession.get(1, TimeUnit.SECONDS)
            assertSame(firstFailure, supersessionFailure)
            assertSame(firstFailure, leaseRetirement.get(1, TimeUnit.SECONDS))
            assertEquals(1, call.disconnectCalls)
            assertFalse(lease.isUsable)
            assertTrue(leaseRetirementInterrupted.get())

            retirementFailure.set(null)
            lease.retire()
            val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
            val replacementCall = RecordingTelecomCall()
            assertTrue(registry.activate(replacementAttempt, replacementCall))

            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)

            lease.retire()
            assertEquals(2, call.disconnectCalls)
            assertEquals(0, replacementCall.disconnectCalls)
        } finally {
            releaseDisconnect.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale Telecom lease retirement leaves replacement attempt untouched`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val ownedAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val ownedCall = RecordingTelecomCall()
        assertTrue(registry.activate(ownedAttempt, ownedCall))
        val lease = registry.claimRouteLease(ownedAttempt)
        val replacementAttempt = registry.beginAttempt().requireAllocatedAttemptId()
        val replacementCall = RecordingTelecomCall()
        assertTrue(registry.activate(replacementAttempt, replacementCall))
        assertEquals(1, ownedCall.disconnectCalls)

        lease.retire()

        assertEquals(1, ownedCall.disconnectCalls)
        assertEquals(0, replacementCall.disconnectCalls)
        assertTrue(registry.isOwnedAttemptActive(replacementAttempt))
    }

    @Test
    fun `Telecom lease usability follows external connection retirement`() {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall()
        assertTrue(registry.activate(attempt, call))
        val lease = registry.claimRouteLease(attempt)

        assertTrue(lease.isUsable)

        registry.retiring(call)

        assertFalse(lease.isUsable)
    }

    @Test
    fun `direct fallback lease exposes failure and retirement is a no-op`() {
        val failure = VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable")
        val lease = DirectFallbackVoiceAgentRouteLease(failure)

        lease.retire()
        lease.retire()

        assertEquals(
            VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure),
            lease.metadata,
        )
        assertTrue(lease.isUsable)
    }

    @Test
    fun `owned session exposes route state and directly delegates nonterminal operations`() {
        val delegate = RecordingManagedSession()
        val lease = DirectFallbackVoiceAgentRouteLease(
            VoiceAgentTelecomFailure("telecom_unavailable", "Telecom unavailable"),
        )
        val owned = RouteOwnedVoiceCallSession(delegate, lease)

        owned.start()
        owned.interrupt()
        owned.setMuted(true)
        owned.reconnect()
        owned.recordDiagnostic("route", "ready")

        assertSame(delegate.state, owned.state)
        assertSame(lease.metadata, owned.routeMetadata)
        assertEquals(lease.isUsable, owned.isRouteUsable)
        assertEquals(
            listOf(
                "session-start",
                "session-interrupt",
                "session-muted-true",
                "session-reconnect",
                "session-route-ready",
            ),
            delegate.events,
        )
    }

    @Test
    fun `end retires route before ending session`() {
        val events = mutableListOf<String>()
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events),
            routeLease = activeTelecomLease(events),
        )

        owned.end()

        assertEquals(listOf("route-retire", "session-end"), events)
    }

    @Test
    fun `end preserves route failure and suppresses delegate failure`() {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val sessionFailure = IllegalArgumentException("session end failed")
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events, endFailure = sessionFailure),
            routeLease = activeTelecomLease(events, routeFailure),
        )

        val thrown = runCatching { owned.end() }.exceptionOrNull()

        assertSame(routeFailure, thrown)
        assertEquals(listOf(sessionFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("route-retire", "session-end"), events)
    }

    @Test
    fun `endAndDrain preserves route failure and suppresses delegate failure`() = runBlocking {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val sessionFailure = IllegalArgumentException("session drain failed")
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events, drainFailure = sessionFailure),
            routeLease = activeTelecomLease(events, routeFailure),
        )

        val thrown = runCatching { owned.endAndDrain() }.exceptionOrNull()

        assertSame(routeFailure, thrown)
        assertEquals(listOf(sessionFailure), thrown?.suppressed?.toList())
        assertEquals(listOf("route-retire", "session-end-and-drain"), events)
    }

    @Test
    fun `timed end returns without closing after normal delegate drain`() = runTest {
        val delegate = RecordingManagedSession()
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("fallback", "fallback"),
            ),
        )

        owned.endAndDrainWithin(timeoutMillis = 100)

        assertEquals(0, delegate.closeNowCalls)
        assertEquals(listOf("session-end-and-drain"), delegate.events)
    }

    @Test
    fun `timed end throws route failure without closing after normal delegate drain`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val delegate = RecordingManagedSession(events = events)
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = activeTelecomLease(events, routeFailure),
        )

        val thrown = captureFailure {
            owned.endAndDrainWithin(timeoutMillis = 100)
        }

        assertSame(routeFailure, thrown)
        assertTrue(thrown?.suppressed?.isEmpty() == true)
        assertEquals(0, delegate.closeNowCalls)
        assertEquals(listOf("route-retire", "session-end-and-drain"), events)
    }

    @Test
    fun `timed end throws drain failure and closes once after successful route retirement`() = runTest {
        val drainFailure = UnsupportedOperationException("session drain failed")
        val delegate = RecordingManagedSession(drainFailure = drainFailure)
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("fallback", "fallback"),
            ),
        )

        val thrown = runCatching {
            owned.endAndDrainWithin(timeoutMillis = 100)
        }.exceptionOrNull()

        assertEquals(drainFailure::class, thrown?.javaClass?.kotlin)
        assertEquals(drainFailure.message, thrown?.message)
        assertSame(drainFailure, thrown?.cause)
        assertTrue(thrown?.suppressed?.isEmpty() == true)
        assertEquals(1, delegate.closeNowCalls)
        assertEquals(
            listOf("session-end-and-drain", "session-close-now"),
            delegate.events,
        )
    }

    @Test
    fun `timed end suppresses close failure behind drain failure after successful route retirement`() = runTest {
        val drainFailure = UnsupportedOperationException("session drain failed")
        val closeFailure = IllegalArgumentException("session close failed")
        val delegate = RecordingManagedSession(
            drainFailure = drainFailure,
            closeFailure = closeFailure,
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("fallback", "fallback"),
            ),
        )

        val thrown = captureFailure {
            owned.endAndDrainWithin(timeoutMillis = 100)
        }

        assertEquals(drainFailure::class, thrown?.javaClass?.kotlin)
        assertEquals(drainFailure.message, thrown?.message)
        assertSame(drainFailure, thrown?.cause)
        assertEquals(listOf(closeFailure), thrown?.suppressed?.toList())
        assertEquals(1, delegate.closeNowCalls)
        assertEquals(
            listOf("session-end-and-drain", "session-close-now"),
            delegate.events,
        )
    }

    @Test
    fun `timed end closes exact delegate and throws route drain close failure order`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val drainFailure = UnsupportedOperationException("session drain failed")
        val closeFailure = IllegalArgumentException("session close failed")
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall(events, routeFailure)
        assertTrue(registry.activate(attempt, call))
        val delegate = RecordingManagedSession(
            events = events,
            drainFailure = drainFailure,
            closeFailure = closeFailure,
            onDrain = {
                assertSame(
                    routeFailure,
                    runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
                )
            },
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = registry.claimRouteLease(attempt),
        )
        val thrown = captureFailure {
            owned.endAndDrainWithin(timeoutMillis = 1_000)
        }
        assertSame(routeFailure, thrown)
        assertEquals(2, thrown?.suppressed?.size)
        val recoveredDrainFailure = thrown?.suppressed?.get(0)
        assertEquals(drainFailure::class, recoveredDrainFailure?.javaClass?.kotlin)
        assertEquals(drainFailure.message, recoveredDrainFailure?.message)
        assertSame(drainFailure, recoveredDrainFailure?.cause)
        assertSame(closeFailure, thrown?.suppressed?.get(1))
        assertEquals(1, call.disconnectCalls)
        assertEquals(1, delegate.closeNowCalls)
        assertEquals(
            listOf("route-retire", "session-end-and-drain", "session-close-now"),
            events,
        )
    }

    @Test
    fun `timed end throws timeout and closes once after successful route retirement`() = runTest {
        val neverCompletes = CompletableDeferred<Unit>()
        val delegate = RecordingManagedSession(onDrain = { neverCompletes.await() })
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("fallback", "fallback"),
            ),
        )
        try {
            val thrown = captureFailure {
                owned.endAndDrainWithin(timeoutMillis = 100)
            }
            assertTrue(thrown is VoiceAgentEndDrainTimeoutException)
            assertTrue(thrown?.suppressed?.isEmpty() == true)
            assertEquals(1, delegate.closeNowCalls)
            assertEquals(
                listOf("session-end-and-drain", "session-close-now"),
                delegate.events,
            )
        } finally {
            neverCompletes.complete(Unit)
        }
    }

    @Test
    fun `timed end suppresses close failure behind timeout after successful route retirement`() = runTest {
        val closeFailure = IllegalArgumentException("session close failed")
        val neverCompletes = CompletableDeferred<Unit>()
        val delegate = RecordingManagedSession(
            closeFailure = closeFailure,
            onDrain = { neverCompletes.await() },
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = DirectFallbackVoiceAgentRouteLease(
                VoiceAgentTelecomFailure("fallback", "fallback"),
            ),
        )
        try {
            val thrown = captureFailure {
                owned.endAndDrainWithin(timeoutMillis = 100)
            }
            assertTrue(thrown is VoiceAgentEndDrainTimeoutException)
            assertEquals(listOf(closeFailure), thrown?.suppressed?.toList())
            assertEquals(1, delegate.closeNowCalls)
            assertEquals(
                listOf("session-end-and-drain", "session-close-now"),
                delegate.events,
            )
        } finally {
            neverCompletes.complete(Unit)
        }
    }

    @Test
    fun `caller cancellation closes detached delegate once and remains cancellation`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val closeFailure = IllegalArgumentException("session close failed")
        val callerCancellation = CancellationException("caller cancelled")
        val drainStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall(events, routeFailure)
        assertTrue(registry.activate(attempt, call))
        val delegate = RecordingManagedSession(
            events = events,
            closeFailure = closeFailure,
            onDrain = {
                drainStarted.complete(Unit)
                neverCompletes.await()
            },
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = registry.claimRouteLease(attempt),
        )
        val outcome = async { owned.endAndDrainWithin(timeoutMillis = 1_000) }
        try {
            drainStarted.await()
            assertSame(
                routeFailure,
                runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
            )
            outcome.cancel(callerCancellation)

            val thrown = runCatching { outcome.await() }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertEquals(callerCancellation.message, thrown?.message)
            assertSame(callerCancellation, thrown?.cause)
            assertTrue(outcome.isCancelled)
            val cleanupAggregate = callerCancellation.suppressed.single()
            assertSame(routeFailure, cleanupAggregate)
            assertEquals(listOf(closeFailure), cleanupAggregate.suppressed.toList())
            assertEquals(1, call.disconnectCalls)
            assertEquals(1, delegate.closeNowCalls)
            assertEquals(
                listOf("route-retire", "session-end-and-drain", "session-close-now"),
                events,
            )
        } finally {
            neverCompletes.complete(Unit)
            outcome.cancel()
        }
    }

    @Test
    fun `caller cancellation cause cycle terminates and aggregates cleanup once`() {
        val routeFailure = IllegalStateException("route retirement failed")
        val closeFailure = IllegalArgumentException("session close failed")
        val cycleEntry = CancellationException("caller cancelled")
        val cycleDeepest = CancellationException("caller cancelled")
        cycleEntry.initCause(cycleDeepest)
        cycleDeepest.initCause(cycleEntry)
        val delegate = RecordingManagedSession(
            closeFailure = closeFailure,
            drainFailure = cycleEntry,
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = activeTelecomLease(mutableListOf(), routeFailure),
        )
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "voice-cancellation-cycle-test").apply { isDaemon = true }
        }

        val thrown = try {
            executor.submit<Throwable?> {
                runBlocking {
                    runCatching { owned.endAndDrainWithin(timeoutMillis = 1_000) }.exceptionOrNull()
                }
            }.get(1, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertSame(cycleDeepest, thrown)
        assertTrue(cycleEntry.suppressed.isEmpty())
        assertEquals(listOf(routeFailure), cycleDeepest.suppressed.toList())
        assertEquals(listOf(closeFailure), routeFailure.suppressed.toList())
        assertEquals(1, delegate.closeNowCalls)
    }

    @Test
    fun `timed end throws canonical route timeout close failure and leaves replacement untouched`() = runTest {
        val events = mutableListOf<String>()
        val routeFailure = IllegalStateException("route retirement failed")
        val closeFailure = IllegalArgumentException("session close failed")
        val neverCompletes = CompletableDeferred<Unit>()
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        val call = RecordingTelecomCall(events, routeFailure)
        assertTrue(registry.activate(attempt, call))
        val delegate = RecordingManagedSession(
            events = events,
            closeFailure = closeFailure,
            onDrain = {
                assertSame(
                    routeFailure,
                    runCatching { registry.beginAttempt().requireAllocatedAttemptId() }.exceptionOrNull(),
                )
                neverCompletes.await()
            },
        )
        val owned = RouteOwnedVoiceCallSession(
            delegate = delegate,
            routeLease = registry.claimRouteLease(attempt),
        )
        try {
            val thrown = captureFailure {
                owned.endAndDrainWithin(timeoutMillis = 100)
            }
            assertSame(routeFailure, thrown)
            assertEquals(2, thrown?.suppressed?.size)
            assertTrue(thrown?.suppressed?.get(0) is VoiceAgentEndDrainTimeoutException)
            assertSame(closeFailure, thrown?.suppressed?.get(1))
            assertEquals(1, call.disconnectCalls)
            assertEquals(1, delegate.closeNowCalls)
            assertEquals(
                listOf("route-retire", "session-end-and-drain", "session-close-now"),
                events,
            )
        } finally {
            neverCompletes.complete(Unit)
        }
    }

    @Test
    fun `closeNow retires route before closing session`() {
        val events = mutableListOf<String>()
        val owned = RouteOwnedVoiceCallSession(
            delegate = RecordingManagedSession(events = events),
            routeLease = activeTelecomLease(events),
        )

        owned.closeNow()

        assertEquals(listOf("route-retire", "session-close-now"), events)
    }

    private fun activeTelecomLease(
        events: MutableList<String>,
        retirementFailure: Throwable? = null,
    ): VoiceAgentRouteLease {
        val registry = VoiceAgentTelecomCallRegistry()
        val attempt = registry.beginAttempt().requireAllocatedAttemptId()
        assertTrue(
            registry.activate(
                attempt,
                RecordingTelecomCall(events, retirementFailure),
            ),
        )
        return registry.claimRouteLease(attempt)
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable? = supervisorScope {
        val outcome = async { block() }
        outcome.join()
        outcome.getCompletionExceptionOrNull()
    }
}

private class RecordingTelecomCall(
    private val events: MutableList<String>? = null,
    private val retirementFailure: AtomicReference<Throwable?>,
) : VoiceAgentTelecomCall {
    constructor(
        events: MutableList<String>? = null,
        retirementFailure: Throwable? = null,
    ) : this(events, AtomicReference(retirementFailure))

    var disconnectCalls = 0

    override fun disconnectFromApp() {
        disconnectCalls += 1
        events?.add("route-retire")
        retirementFailure.get()?.let { throw it }
    }
}

private class RecordingManagedSession(
    val events: MutableList<String> = mutableListOf(),
    private val endFailure: Throwable? = null,
    private val drainFailure: Throwable? = null,
    private val closeFailure: Throwable? = null,
    private val onDrain: suspend () -> Unit = {},
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var closeNowCalls = 0

    override fun start() {
        events += "session-start"
    }

    override fun interrupt() {
        events += "session-interrupt"
    }

    override fun setMuted(value: Boolean) {
        events += "session-muted-$value"
    }

    override fun reconnect() {
        events += "session-reconnect"
    }

    override fun recordDiagnostic(name: String, detail: String) {
        events += "session-$name-$detail"
    }

    override fun end() {
        events += "session-end"
        endFailure?.let { throw it }
    }

    override suspend fun endAndDrain() {
        events += "session-end-and-drain"
        onDrain()
        drainFailure?.let { throw it }
    }

    override fun closeNow() {
        closeNowCalls += 1
        events += "session-close-now"
        closeFailure?.let { throw it }
    }
}
