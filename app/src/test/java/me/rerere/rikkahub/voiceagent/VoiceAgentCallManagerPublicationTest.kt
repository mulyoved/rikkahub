package me.rerere.rikkahub.voiceagent

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallManagerPublicationTest {
    @Test
    fun `terminal detach before final publication supersedes owner and matching callers`() = runPublicationTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.random()
        val config = fakeManagerLaunchConfig()
        val ownerLease = CountingTelecomLease()
        val waiterLease = CountingTelecomLease()
        val collectorDispatcher = BlockingCollectorDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
        try {
            val owner = async(Dispatchers.Default) {
                manager.start(conversationId, config, ownerLease.lease, collectorScope)
            }
            assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
            val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                manager.start(conversationId, config, waiterLease.lease, this@runPublicationTest)
            }
            var resolveCalls = 0
            val startup = VoiceAgentCallStartup(manager) {
                resolveCalls += 1
                error("pending matching publication must not resolve another route")
            }
            val startupWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                startup.start(conversationId, config, this@runPublicationTest) { true }
            }

            assertFalse(waiter.isCompleted)
            assertFalse(startupWaiter.isCompleted)
            manager.end()

            assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
            assertEquals(
                VoiceAgentCallStartupResult.Stale(ownerLease.lease.metadata),
                startupWaiter.await(),
            )
            assertEquals(0, resolveCalls)
            collectorDispatcher.release()
            assertEquals(VoiceAgentManagerStartResult.Superseded, owner.await())

            session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
            yield()
            assertEquals(null, manager.activeConversationId.value)
            assertEquals(VoiceCallStatus.Ending, manager.state.value.call)
            assertEquals(1, session.endCalls)
            assertEquals(0, session.closeNowCalls)
            assertEquals(1, ownerLease.retireCalls)
            assertEquals(1, waiterLease.retireCalls)
        } finally {
            collectorDispatcher.release()
            collectorScope.cancel()
            collectorDispatcher.close()
        }
    }

    @Test
    fun `replacement detach before final publication supersedes old callers and preserves new active`() =
        runPublicationTest {
            val staleSession = FakeManagedVoiceCallSession()
            val replacementSession = FakeManagedVoiceCallSession(
                initialState = VoiceAgentUiState(session = VoiceSessionStatus.PreparingContext),
            )
            val manager = VoiceAgentCallManager(FakeVoiceAgentCallFactory(staleSession, replacementSession))
            val staleConversation = Uuid.random()
            val staleConfig = fakeManagerLaunchConfig("stale")
            val staleLease = CountingTelecomLease()
            val waiterLease = CountingTelecomLease()
            val collectorDispatcher = BlockingCollectorDispatcher()
            val collectorScope = CoroutineScope(SupervisorJob() + collectorDispatcher)
            try {
                val staleOwner = async(Dispatchers.Default) {
                    manager.start(staleConversation, staleConfig, staleLease.lease, collectorScope)
                }
                assertTrue(collectorDispatcher.dispatchEntered.await(1, TimeUnit.SECONDS))
                val waiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    manager.start(staleConversation, staleConfig, waiterLease.lease, this@runPublicationTest)
                }
                var resolveCalls = 0
                val startup = VoiceAgentCallStartup(manager) {
                    resolveCalls += 1
                    error("superseded matching publication must not resolve another route")
                }
                val startupWaiter = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    startup.start(staleConversation, staleConfig, this@runPublicationTest) { true }
                }

                assertFalse(waiter.isCompleted)
                assertFalse(startupWaiter.isCompleted)
                val replacementConversation = Uuid.random()
                val replacementLease = CountingTelecomLease()
                assertEquals(
                    VoiceAgentManagerStartResult.Started(replacementLease.lease.metadata),
                    manager.start(
                        replacementConversation,
                        fakeManagerLaunchConfig("replacement"),
                        replacementLease.lease,
                        this,
                    ),
                )

                assertEquals(VoiceAgentManagerStartResult.Superseded, waiter.await())
                assertEquals(
                    VoiceAgentCallStartupResult.Stale(staleLease.lease.metadata),
                    startupWaiter.await(),
                )
                assertEquals(0, resolveCalls)
                collectorDispatcher.release()
                assertEquals(VoiceAgentManagerStartResult.Superseded, staleOwner.await())

                staleSession.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Error("stale"))
                yield()
                assertEquals(replacementConversation, manager.activeConversationId.value)
                assertEquals(VoiceSessionStatus.PreparingContext, manager.state.value.session)
                assertEquals(1, staleSession.endCalls)
                assertEquals(0, staleSession.closeNowCalls)
                assertEquals(1, staleLease.retireCalls)
                assertEquals(1, waiterLease.retireCalls)
                assertEquals(0, replacementLease.retireCalls)
            } finally {
                collectorDispatcher.release()
                collectorScope.cancel()
                collectorDispatcher.close()
            }
        }
}

private fun runPublicationTest(block: suspend CoroutineScope.() -> Unit) = runBlocking {
    val testScope = CoroutineScope(coroutineContext + SupervisorJob())
    try {
        testScope.block()
    } finally {
        testScope.cancel()
    }
}
