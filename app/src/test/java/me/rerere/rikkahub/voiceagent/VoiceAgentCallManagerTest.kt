package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
    fun `matching route failure chain ending idle remains retryable`() = runTest {
        repeat(50) { attempt ->
            val releaseFirstFactory = CountDownLatch(1)
            val creationFailure = IllegalStateException("factory failure $attempt")
            val factory = BlockingFirstThenFailingVoiceAgentCallFactory(
                releaseFirstFactory = releaseFirstFactory,
                failure = creationFailure,
            )
            val manager = VoiceAgentCallManager(factory)
            val conversationId = Uuid.random()
            val config = fakeLaunchConfig()
            val ownerLease = CountingTelecomLease()
            val retryLeases = List(8) { CountingTelecomLease() }

            val owner = async(Dispatchers.Default) {
                runCatching { manager.start(conversationId, config, ownerLease.lease, this@runTest) }
            }
            assertTrue(factory.firstFactoryEntered.await(1, TimeUnit.SECONDS))
            val retryOwners = retryLeases.take(4).map { retryLease ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { manager.start(conversationId, config, retryLease.lease, this@runTest) }
                }
            }.toMutableList()
            val matching = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.matchingRoute(conversationId, config)
            }
            retryOwners += retryLeases.drop(4).map { retryLease ->
                async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    runCatching { manager.start(conversationId, config, retryLease.lease, this@runTest) }
                }
            }

            releaseFirstFactory.countDown()

            assertEquals(
                "failure-chain attempt $attempt",
                VoiceAgentRouteMatchResult.NoMatch,
                matching.await(),
            )
            assertSame(creationFailure, owner.await().exceptionOrNull())
            retryOwners.forEach { retryOwner ->
                assertSame(creationFailure, retryOwner.await().exceptionOrNull())
            }
            assertEquals(1, ownerLease.retireCalls)
            retryLeases.forEach { retryLease -> assertEquals(1, retryLease.retireCalls) }
            assertEquals(1 + retryLeases.size, factory.createdCalls.get())
            assertEquals(null, manager.activeConversationId.value)
            assertEquals(VoiceAgentUiState(), manager.state.value)
        }
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
    fun `cancelled matching waiter ignores self suppression from exact retirement failure`() = runTest {
        val releaseFactory = CountDownLatch(1)
        val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
        val manager = VoiceAgentCallManager(factory)
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val installed = CountingTelecomLease()
        val cancellation = CanonicalCancellationException(Any())
        val duplicate = CountingTelecomLease(disconnectFailure = cancellation)

        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, installed.lease, this@runTest)
        }
        assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, duplicate.lease, this@runTest)
        }

        waiter.cancel(cancellation)
        val thrown = runCatching { waiter.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(emptyList<Throwable>(), thrown?.suppressed?.toList())
        assertEquals(1, duplicate.retireCalls)
        assertFalse(owner.isCompleted)
        assertEquals(1, factory.createdCalls.get())
        assertEquals(0, installed.retireCalls)

        releaseFactory.countDown()
        assertTrue(owner.await() is VoiceAgentManagerStartResult.Started)
    }

    @Test
    fun `end invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.End, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.End, this)
    }

    @Test
    fun `detach for drain invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.DetachForEndAndDrain, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.DetachForEndAndDrain, this)
    }

    @Test
    fun `close now invalidates starting before and after factory transfer`() = runTest {
        assertTerminalInvalidatesBlockedFactory(StartingTerminalAction.CloseNow, this)
        assertTerminalInvalidatesBlockedSessionStart(StartingTerminalAction.CloseNow, this)
    }

    @Test
    fun `cancelled reservation owner before factory transfer preserves cancellation and idle`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd)
        val factory = FakeVoiceAgentCallFactory(predecessor, FakeManagedVoiceCallSession())
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), predecessorLease.lease, this)
        val ownerLease = CountingTelecomLease()
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, ownerLease.lease, this@runTest)
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val cancellation = CanonicalCancellationException(Any())

        owner.cancel(cancellation)
        releaseEnd.countDown()
        val thrown = runCatching { owner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, predecessorLease.retireCalls)
        assertEquals(1, ownerLease.retireCalls)
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
        assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
    }

    @Test
    fun `cancelled reservation owner after factory creation completes failed for waiter and closes exact session`() = runTest {
        val releaseStart = CountDownLatch(1)
        val cancelledSession = BlockingStartManagedVoiceCallSession(releaseStart)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(cancelledSession, installedSession))
        val conversationId = Uuid.random()
        val config = fakeLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val owner = async(Dispatchers.Default) {
            manager.start(conversationId, config, ownerLease.lease, this@runTest)
        }
        assertTrue(cancelledSession.startEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(conversationId, config, waiterLease.lease, this@runTest)
        }
        val cancellation = CanonicalCancellationException(Any())

        owner.cancel(cancellation)
        releaseStart.countDown()
        val thrown = runCatching { owner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, cancelledSession.closeNowCalls)
        assertEquals(1, ownerLease.retireCalls)
        assertEquals(VoiceAgentManagerStartResult.Started(waiterLease.lease.metadata), waiter.await())
        assertEquals(0, waiterLease.retireCalls)
        assertEquals(1, installedSession.startCalls)
        assertEquals(conversationId, manager.activeConversationId.value)
    }

    @Test
    fun `cancelled superseded owner closes exact session without clearing newer active slot`() = runTest {
        val releaseStart = CountDownLatch(1)
        val staleSession = BlockingStartManagedVoiceCallSession(releaseStart)
        val installedSession = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, installedSession))
        val staleLease = CountingTelecomLease()
        val installedLease = CountingTelecomLease()
        val staleOwner = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeLaunchConfig(), staleLease.lease, this@runTest)
        }
        assertTrue(staleSession.startEntered.await(1, TimeUnit.SECONDS))
        val installedConversation = Uuid.random()

        assertEquals(
            VoiceAgentManagerStartResult.Started(installedLease.lease.metadata),
            manager.start(installedConversation, fakeLaunchConfig(), installedLease.lease, this),
        )
        val cancellation = CanonicalCancellationException(Any())
        staleOwner.cancel(cancellation)
        releaseStart.countDown()
        val thrown = runCatching { staleOwner.await() }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, staleSession.closeNowCalls)
        assertEquals(1, staleLease.retireCalls)
        assertEquals(0, installedLease.retireCalls)
        assertEquals(installedConversation, manager.activeConversationId.value)
        assertEquals(1, installedSession.startCalls)
    }

    @Test
    fun `failed matching retry is terminally superseded by newer active slot`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val creationFailure = NonCopyableCleanupException(Any(), "first factory failed")
        val installedSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstFailingVoiceAgentCallFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            subsequentSessions = arrayOf(installedSession),
        )
        val manager = VoiceAgentCallManager(factory)
        val failedConversation = Uuid.random()
        val failedConfig = fakeLaunchConfig()
        val failedOwnerLease = CountingTelecomLease()
        val retryLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(failedConversation, failedConfig, failedOwnerLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        val retryGate = BlockedRetryDispatcher()
        try {
            val retry = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.start(failedConversation, failedConfig, retryLease.lease, this@runTest)
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val installedConversation = Uuid.random()
            val installedLease = CountingTelecomLease()
            assertEquals(
                VoiceAgentManagerStartResult.Started(installedLease.lease.metadata),
                manager.start(installedConversation, fakeLaunchConfig(), installedLease.lease, this),
            )

            retryGate.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, retry.await())
            assertEquals(1, failedOwnerLease.retireCalls)
            assertEquals(1, retryLease.retireCalls)
            assertEquals(0, installedLease.retireCalls)
            assertEquals(installedConversation, manager.activeConversationId.value)
        } finally {
            retryGate.close()
        }
    }

    @Test
    fun `failed matching start and route match are terminally superseded by newer starting slot`() = runTest {
        val releaseFailure = CountDownLatch(1)
        val releaseNewerCreate = CountDownLatch(1)
        val creationFailure = NonCopyableCleanupException(Any(), "first factory failed")
        val newerSession = FakeManagedVoiceCallSession()
        val factory = BlockingFirstFailingVoiceAgentCallFactory(
            releaseFirstFailure = releaseFailure,
            firstFailure = creationFailure,
            releaseSecondCreate = releaseNewerCreate,
            subsequentSessions = arrayOf(newerSession),
        )
        val manager = VoiceAgentCallManager(factory)
        val failedConversation = Uuid.random()
        val failedConfig = fakeLaunchConfig()
        val failedOwnerLease = CountingTelecomLease()
        val retryLease = CountingTelecomLease()
        val failedOwner = async(Dispatchers.Default) {
            manager.start(failedConversation, failedConfig, failedOwnerLease.lease, this@runTest)
        }
        assertTrue(factory.firstCreateEntered.await(1, TimeUnit.SECONDS))
        val retryGate = BlockedRetryDispatcher()
        try {
            val retry = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.start(failedConversation, failedConfig, retryLease.lease, this@runTest)
            }
            val matchingRoute = async(retryGate.dispatcher, start = CoroutineStart.UNDISPATCHED) {
                manager.matchingRoute(failedConversation, failedConfig)
            }

            releaseFailure.countDown()
            assertSame(creationFailure, runCatching { failedOwner.await() }.exceptionOrNull())
            val newerLease = CountingTelecomLease()
            val newerOwner = async(Dispatchers.Default) {
                manager.start(Uuid.random(), fakeLaunchConfig(), newerLease.lease, this@runTest)
            }
            assertTrue(factory.secondCreateEntered.await(1, TimeUnit.SECONDS))

            retryGate.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, retry.await())
            assertEquals(
                VoiceAgentRouteMatchResult.Superseded(failedOwnerLease.lease.metadata),
                matchingRoute.await(),
            )
            assertEquals(1, retryLease.retireCalls)
            assertFalse(newerOwner.isCompleted)

            releaseNewerCreate.countDown()
            assertTrue(newerOwner.await() is VoiceAgentManagerStartResult.Started)
            assertEquals(0, newerLease.retireCalls)
        } finally {
            releaseNewerCreate.countDown()
            retryGate.close()
        }
    }

    @Test
    fun `superseded unconsumed lease retirement failure stays primary and is attempted once`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val firstSession = BlockingEndManagedVoiceCallSession(releaseEnd)
        val installedSession = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(firstSession, installedSession)
        val manager = VoiceAgentCallManager(factory)
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

        assertFalse(replacement.isCompleted)
        assertEquals(1, factory.created.size)

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
    fun `failed predecessor cleanup is replayed identically to every inheriting reservation`() = runTest {
        val releaseEnd = CountDownLatch(1)
        val endFailure = NonCopyableCleanupException(Any(), "predecessor end failed")
        val predecessor = BlockingEndManagedVoiceCallSession(releaseEnd, endFailure)
        val factory = FakeVoiceAgentCallFactory(
            predecessor,
            FakeManagedVoiceCallSession(),
            FakeManagedVoiceCallSession(),
            FakeManagedVoiceCallSession(),
        )
        val manager = VoiceAgentCallManager(factory)
        val predecessorLease = CountingTelecomLease()
        manager.start(Uuid.random(), fakeLaunchConfig(), predecessorLease.lease, this)
        val inheritingLeases = List(3) { CountingTelecomLease() }
        val first = async(Dispatchers.Default) {
            manager.start(Uuid.random(), fakeLaunchConfig("first"), inheritingLeases[0].lease, this@runTest)
        }
        assertTrue(predecessor.endEntered.await(1, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(Uuid.random(), fakeLaunchConfig("second"), inheritingLeases[1].lease, this@runTest)
        }
        val third = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            manager.start(Uuid.random(), fakeLaunchConfig("third"), inheritingLeases[2].lease, this@runTest)
        }

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertFalse(third.isCompleted)
        assertEquals(1, factory.created.size)

        releaseEnd.countDown()
        listOf(first, second, third).forEach { owner ->
            assertSame(endFailure, runCatching { owner.await() }.exceptionOrNull())
        }
        assertEquals(1, predecessorLease.retireCalls)
        inheritingLeases.forEach { assertEquals(1, it.retireCalls) }
        assertEquals(1, factory.created.size)
        assertEquals(null, manager.activeConversationId.value)
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

private enum class StartingTerminalAction {
    End,
    DetachForEndAndDrain,
    CloseNow,
}

private suspend fun assertTerminalInvalidatesBlockedFactory(
    action: StartingTerminalAction,
    scope: CoroutineScope,
) {
    val releaseFactory = CountDownLatch(1)
    val factory = BlockingFirstVoiceAgentCallFactory(releaseFactory)
    val manager = VoiceAgentCallManager(factory)
    val conversationId = Uuid.random()
    val config = fakeLaunchConfig()
    val lease = CountingTelecomLease()
    val waiterLease = CountingTelecomLease()
    val owner = scope.async(Dispatchers.Default) {
        manager.start(conversationId, config, lease.lease, scope)
    }
    assertTrue(factory.factoryEntered.await(1, TimeUnit.SECONDS))
    val waiter = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(conversationId, config, waiterLease.lease, scope)
    }

    invokeTerminal(action, manager)
    assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
    releaseFactory.countDown()

    assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())
    assertEquals(1, lease.retireCalls)
    assertEquals(1, waiterLease.retireCalls)
    assertEquals(0, factory.session.startCalls)
    assertEquals(1, factory.session.closeNowCalls)
    assertEquals(null, manager.activeConversationId.value)
    assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
}

private suspend fun assertTerminalInvalidatesBlockedSessionStart(
    action: StartingTerminalAction,
    scope: CoroutineScope,
) {
    val releaseStart = CountDownLatch(1)
    val session = BlockingStartManagedVoiceCallSession(releaseStart)
    val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
    val conversationId = Uuid.random()
    val config = fakeLaunchConfig()
    val lease = CountingTelecomLease()
    val waiterLease = CountingTelecomLease()
    val owner = scope.async(Dispatchers.Default) {
        manager.start(conversationId, config, lease.lease, scope)
    }
    assertTrue(session.startEntered.await(1, TimeUnit.SECONDS))
    val waiter = scope.async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        manager.start(conversationId, config, waiterLease.lease, scope)
    }

    invokeTerminal(action, manager)
    assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
    releaseStart.countDown()

    assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())
    assertEquals(1, lease.retireCalls)
    assertEquals(1, waiterLease.retireCalls)
    assertEquals(1, session.closeNowCalls)
    assertEquals(null, manager.activeConversationId.value)
    assertEquals(VoiceAgentRouteMatchResult.NoMatch, manager.matchingRoute(conversationId, config))
}

private fun invokeTerminal(action: StartingTerminalAction, manager: VoiceAgentCallManager) {
    when (action) {
        StartingTerminalAction.End -> manager.end()
        StartingTerminalAction.DetachForEndAndDrain -> assertEquals(null, manager.detachForEndAndDrain())
        StartingTerminalAction.CloseNow -> manager.closeNow()
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
    private val endFailure: Throwable? = null,
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
        endFailure?.let { throw it }
    }
    override suspend fun endAndDrain() = Unit
    override fun closeNow() = Unit
}

private class BlockingStartManagedVoiceCallSession(
    private val releaseStart: CountDownLatch,
    private val closeFailure: Throwable? = null,
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
        closeFailure?.let { throw it }
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

private class BlockingFirstThenFailingVoiceAgentCallFactory(
    private val releaseFirstFactory: CountDownLatch,
    private val failure: Throwable,
) : VoiceAgentCallFactory {
    val firstFactoryEntered = CountDownLatch(1)
    val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        if (createdCalls.incrementAndGet() == 1) {
            firstFactoryEntered.countDown()
            check(releaseFirstFactory.await(1, TimeUnit.SECONDS)) {
                "timed out waiting to release first failing factory invocation"
            }
        }
        routeLease.retire()
        throw failure
    }
}

private class BlockingFirstFailingVoiceAgentCallFactory(
    private val releaseFirstFailure: CountDownLatch,
    private val firstFailure: Throwable,
    private val releaseSecondCreate: CountDownLatch? = null,
    private val subsequentSessions: Array<out ManagedVoiceCallSession>,
) : VoiceAgentCallFactory {
    val firstCreateEntered = CountDownLatch(1)
    val secondCreateEntered = CountDownLatch(1)
    private val createdCalls = AtomicInteger()

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession {
        return when (val index = createdCalls.getAndIncrement()) {
            0 -> {
                firstCreateEntered.countDown()
                check(releaseFirstFailure.await(1, TimeUnit.SECONDS)) {
                    "timed out waiting to release first factory failure"
                }
                routeLease.retire()
                throw firstFailure
            }
            else -> {
                if (index == 1 && releaseSecondCreate != null) {
                    secondCreateEntered.countDown()
                    check(releaseSecondCreate.await(1, TimeUnit.SECONDS)) {
                        "timed out waiting to release second factory invocation"
                    }
                }
                RouteOwnedVoiceCallSession(subsequentSessions[index - 1], routeLease)
            }
        }
    }
}

private class BlockedRetryDispatcher {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val releaseLatch = CountDownLatch(1)
    private val blockerEntered = CountDownLatch(1)

    init {
        dispatcher.executor.execute {
            blockerEntered.countDown()
            check(releaseLatch.await(5, TimeUnit.SECONDS)) { "timed out waiting to release retry dispatcher" }
        }
        check(blockerEntered.await(1, TimeUnit.SECONDS)) { "retry dispatcher blocker did not start" }
    }

    fun release() = releaseLatch.countDown()

    fun close() {
        release()
        dispatcher.close()
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
