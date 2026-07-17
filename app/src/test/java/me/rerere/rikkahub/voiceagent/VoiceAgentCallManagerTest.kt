package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerTest {
    @Test
    fun `manager exposes idle state before start`() {
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(FakeManagedVoiceCallSession()))

        assertEquals(VoiceSessionStatus.Idle, manager.state.value.session)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `start transfers lease to one active session and exposes exact metadata`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val failure = VoiceAgentTelecomFailure("fallback", "exact failure")
        val installedLiveLease = DirectFallbackVoiceAgentRouteLease(failure)

        val started = manager.start(conversationId, config, installedLiveLease, this)
        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        yield()

        assertEquals(VoiceAgentManagerStartResult.Started(installedLiveLease.metadata), started)
        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(listOf(CreatedCall(conversationId, config, VoiceAudioRouteOwner.DirectFallback)), factory.created)
        assertEquals(
            VoiceAgentRouteMatchResult.Existing(
                VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback, failure),
            ),
            manager.matchingRoute(conversationId, config),
        )
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `duplicate race retires unused incoming lease exactly once`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installedLiveLease = CountingTelecomLease()
        val raceRejectedLease = CountingTelecomLease()
        manager.start(conversationId, config, installedLiveLease.lease, this)

        val started = manager.start(conversationId, config, raceRejectedLease.lease, this)

        assertEquals(VoiceAgentManagerStartResult.Existing(installedLiveLease.lease.metadata), started)
        assertEquals(1, raceRejectedLease.retireCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(0, session.endCalls)
    }

    @Test
    fun `replacement ends previous route-owned session before factory consumes incoming lease`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(first, second)
        val manager = VoiceAgentCallManager(factory)
        val previousSessionLease = CountingTelecomLease()
        val installedLiveLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), previousSessionLease.lease, this)

        val nextConversation = Uuid.random()
        manager.start(nextConversation, fakeLaunchConfig(), installedLiveLease.lease, this)

        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, first.endCalls)
        assertEquals(0, installedLiveLease.retireCalls)
        assertEquals(1, second.startCalls)
        assertEquals(nextConversation, manager.activeConversationId.value)
    }

    @Test
    fun `previous end and incoming retirement failure preserve primary error and clear aggregate`() = runTest {
        val endFailure = IllegalStateException("previous end failed")
        val retirementFailure = IllegalArgumentException("incoming retirement failed")
        val first = FakeManagedVoiceCallSession(endFailure = endFailure)
        val factory = FakeVoiceAgentCallFactory(first, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val previousSessionLease = CountingTelecomLease()
        val incomingLease = CountingTelecomLease(disconnectFailure = retirementFailure)
        manager.start(Uuid.random(), fakeLaunchConfig(), previousSessionLease.lease, this)

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeLaunchConfig(), incomingLease.lease, this)
        }.exceptionOrNull()

        assertSame(endFailure, thrown)
        assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        assertEquals(1, previousSessionLease.retireCalls)
        assertEquals(1, incomingLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `matching start suspends without blocking manager then reuses published call`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installed = CountingTelecomLease()
        val duplicate = CountingTelecomLease()

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }

        manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
        assertFalse(waiter.isCompleted)
        releaseFactory.countDown()

        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
        assertTrue(waiter.await() is VoiceAgentManagerStartResult.Existing)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)
        assertEquals(1, duplicate.retireCalls)
    }

    @Test
    fun `matching route suspends then returns exact published route`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installed = CountingTelecomLease()

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) {
            manager.matchingRoute(conversationId, config)
        }

        assertFalse(waiter.isCompleted)
        releaseFactory.countDown()

        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
        assertEquals(VoiceAgentRouteMatchResult.Existing(installed.lease.metadata), waiter.await())
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)
    }

    @Test
    fun `cancelled matching waiter retires exact lease and preserves cancellation`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installed = CountingTelecomLease()
        val retirementFailure = IllegalStateException("waiter retirement failed")
        val duplicate = CountingTelecomLease(disconnectFailure = retirementFailure)

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        waiter.cancel(cancellation)
        val thrown = runCatching { waiter.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(listOf(retirementFailure), thrown?.suppressed?.toList())
        assertEquals(1, duplicate.retireCalls)
        assertFalse(owner.isCompleted)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)

        releaseFactory.countDown()
        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
    }

    @Test
    fun `superseded unconsumed lease retirement failure stays primary and is attempted once`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val firstSession = BlockingEndManagedVoiceCallSession(releaseEnd)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(firstSession, installedSession))
        val activeLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), activeLease.lease, this)
        val retirementFailure = NonCopyableCleanupException(Any(), "stale lease retirement failed")
        val staleLease = CountingTelecomLease(disconnectFailure = retirementFailure)
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(firstSession.endEntered.await(1, TimeUnit.SECONDS))
        val replacement = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(Uuid.random(), fakeLaunchConfig(), installedLease.lease, this@runTest)
        }

        releaseEnd.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertSame(retirementFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, staleLease.retireCalls)
        assertTrue(replacement.await() is VoiceAgentManagerStartResult.Started)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `superseded created session close failure stays primary and is attempted once`() = runTest {
        val releaseCreate = CountDownLatch(1)
        val closeFailure = NonCopyableCleanupException(Any(), "stale created session close failed")
        val staleSession = CloseFailingManagedVoiceCallSession(closeFailure)
        val installedSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstCreateVoiceAgentCallFactory(
            releaseFirstCreate = releaseCreate,
            firstSession = staleSession,
            secondSession = installedSession,
        )
        val manager = VoiceAgentCallManager(factory)
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))

        val replacement = manager.start(Uuid.random(), fakeLaunchConfig(), installedLease.lease, this)
        releaseCreate.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertTrue(replacement is VoiceAgentManagerStartResult.Started)
        assertSame(closeFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(0, staleSession.startCalls)
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `superseded started session close failure stays primary and is attempted once`() = runTest {
        val releaseStart = CountDownLatch(1)
        val closeFailure = NonCopyableCleanupException(Any(), "stale session close failed")
        val staleSession = BlockingStartManagedVoiceCallSession(releaseStart, closeFailure)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, installedSession))
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()

        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(staleSession.startEntered.await(1, TimeUnit.SECONDS))

        val replacement = manager.start(Uuid.random(), fakeLaunchConfig(), installedLease.lease, this)
        releaseStart.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertTrue(replacement is VoiceAgentManagerStartResult.Started)
        assertSame(closeFailure, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `synchronous session start failure clears aggregate and closes exact route once`() = runTest {
        val startFailure = IllegalStateException("session start failed")
        val cleanupFailure = IllegalArgumentException("route cleanup failed")
        val session = FakeManagedVoiceCallSession(
            initialState = VoiceAgentUiState(session = VoiceSessionStatus.Error("stale in-flight state")),
            startFailure = startFailure,
        )
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val lease = CountingTelecomLease(disconnectFailure = cleanupFailure)
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val thrown = try {
            runCatching {
                manager.start(conversationId, config, lease.lease, collectorScope)
            }.exceptionOrNull()
        } finally {
            collectorScope.cancel()
        }

        assertSame(startFailure, thrown)
        assertEquals(listOf(cleanupFailure), thrown?.suppressed?.toList())
        assertEquals(1, lease.retireCalls)
        assertEquals(1, session.closeNowCalls)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
        assertEquals(VoiceAgentUiState(), manager.state.value)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `factory failure consumes lease and manager does not retire it twice`() = runTest {
        val creationFailure = IllegalStateException("factory failed")
        val factoryFailureLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(ConsumingFailingVoiceAgentCallFactory(creationFailure))

        val thrown = runCatching {
            manager.start(Uuid.random(), fakeLaunchConfig(), factoryFailureLease.lease, this)
        }.exceptionOrNull()

        assertSame(creationFailure, thrown)
        assertEquals(1, factoryFailureLease.retireCalls)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `exact route usability controls active session preservation`() = runTest {
        val lease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(FakeManagedVoiceCallSession()))
        val conversationId = Uuid.random()
        manager.start(conversationId, fakeLaunchConfig(), lease.lease, this)

        assertTrue(manager.canPreserveActiveSession(conversationId))
        lease.lease.retire()
        assertEquals(false, manager.canPreserveActiveSession(conversationId))
        assertEquals(false, manager.canPreserveActiveSession(Uuid.random()))
    }

    @Test
    fun `end clears aggregate and retires installed lease once`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val lease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        manager.start(Uuid.random(), fakeLaunchConfig(), lease.lease, this)

        manager.end()

        assertEquals(1, lease.retireCalls)
        assertEquals(1, session.endCalls)
        assertEquals(null, manager.activeConversationId.value)
    }

    @Test
    fun `detached end drain retires only detached lease and leaves replacement live`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val firstLease = CountingTelecomLease()
        val secondLease = CountingTelecomLease()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(first, second))
        manager.start(Uuid.random(), fakeLaunchConfig(), firstLease.lease, this)
        val detached = manager.detachForEndAndDrain()
        val secondConversation = Uuid.random()
        manager.start(secondConversation, fakeLaunchConfig(), secondLease.lease, this)

        detached?.endAndDrain()

        assertEquals(1, firstLease.retireCalls)
        assertEquals(0, secondLease.retireCalls)
        assertEquals(1, first.endAndDrainCalls)
        assertEquals(0, second.endAndDrainCalls)
        assertEquals(secondConversation, manager.activeConversationId.value)
    }
}

private class FakeManagedVoiceCallSession(
    initialState: VoiceAgentUiState = VoiceAgentUiState(),
    private val startFailure: Throwable? = null,
    private val endFailure: Throwable? = null,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(initialState)
    var startCalls = 0
    var reconnectCalls = 0
    var endCalls = 0
    var endAndDrainCalls = 0
    var closeNowCalls = 0
    val diagnostics = mutableListOf<Pair<String, String>>()

    override fun start() { startCalls += 1; startFailure?.let { throw it } }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() { reconnectCalls += 1 }
    override fun recordDiagnostic(name: String, detail: String) { diagnostics += name to detail }
    override fun end() { endCalls += 1; endFailure?.let { throw it } }
    override suspend fun endAndDrain() { endAndDrainCalls += 1 }
    override fun closeNow() { closeNowCalls += 1 }
}

private class BlockingEndManagedVoiceCallSession(
    private val releaseEnd: CountDownLatch,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val endEntered = CountDownLatch(1)

    override fun start() = Unit
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() {
        endEntered.countDown()
        check(releaseEnd.await(1, TimeUnit.SECONDS)) { "timed out waiting to release session end" }
    }
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

private class BlockingStartManagedVoiceCallSession(
    private val releaseStart: CountDownLatch,
    private val closeFailure: Throwable,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    val startEntered = CountDownLatch(1)
    var closeNowCalls = 0

    override fun start() {
        startEntered.countDown()
        check(releaseStart.await(1, TimeUnit.SECONDS)) { "timed out waiting to release session start" }
    }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeNowCalls += 1
        throw closeFailure
    }
}

private class CloseFailingManagedVoiceCallSession(
    private val closeFailure: Throwable,
) : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var closeNowCalls = 0

    override fun start() { startCalls += 1 }
    override fun interrupt() = Unit
    override fun setMuted(value: Boolean) = Unit
    override fun reconnect() = Unit
    override fun recordDiagnostic(name: String, detail: String) = Unit
    override fun end() = Unit
    override suspend fun endAndDrain() = Unit
    override fun closeNow() {
        closeNowCalls += 1
        throw closeFailure
    }
}

private class BlockingFirstCreateVoiceAgentCallFactory(
    private val releaseFirstCreate: CountDownLatch,
    private val firstSession: ManagedVoiceCallSession,
    private val secondSession: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val firstCreateEntered = CountDownLatch(1)
    private val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        val callIndex = createdCalls.getAndIncrement()
        val session = when (callIndex) {
            0 -> {
                firstCreateEntered.countDown()
                check(releaseFirstCreate.await(1, TimeUnit.SECONDS)) {
                    "timed out waiting to release first call factory invocation"
                }
                firstSession
            }
            1 -> secondSession
            else -> error("unexpected factory invocation $callIndex")
        }
        return RouteOwnedVoiceCallSession(session, routeLease)
    }
}

private class BlockingFirstVoiceAgentCallFactory(
    private val releaseFactory: CountDownLatch,
) : VoiceAgentCallFactory {
    val factoryEntered = CountDownLatch(1)
    val createdCalls = AtomicInteger()
    val session = FakeManagedVoiceCallSession()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        createdCalls.incrementAndGet()
        factoryEntered.countDown()
        check(releaseFactory.await(1, TimeUnit.SECONDS)) { "timed out waiting to release call factory" }
        return RouteOwnedVoiceCallSession(session, routeLease)
    }
}

private class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<CreatedCall>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        created += CreatedCall(conversationId, config, routeLease.metadata.owner)
        return RouteOwnedVoiceCallSession(sessions[nextSession++], routeLease)
    }
}

private class ConsumingFailingVoiceAgentCallFactory(
    private val failure: Throwable,
) : VoiceAgentCallFactory {
    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        routeLease.retire()
        throw failure
    }
}

private data class CreatedCall(
    val conversationId: Uuid,
    val config: VoiceAgentLaunchConfig,
    val routeOwner: VoiceAudioRouteOwner,
)

private fun fakeLaunchConfig(voiceModelId: String = "gemini-flash") = VoiceAgentLaunchConfig(
    hermesVoiceBaseUrl = "https://voice.test",
    credentials = HermesVoiceCredentials(deviceApiKey = "profile-key"),
    voiceModelId = voiceModelId,
    assistantName = "Hermes",
    assistantPrompt = "system",
)

private class CanonicalCancellationException(
    @Suppress("unused") private val identityMarker: Any,
) : CancellationException("cancel matching waiter")

private class NonCopyableCleanupException(
    @Suppress("unused") private val identityMarker: Any,
    message: String,
) : IllegalStateException(message)

private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try { testScope.block() } finally { testScope.cancel() }
}
