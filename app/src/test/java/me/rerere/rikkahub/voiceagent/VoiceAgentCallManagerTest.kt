package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
    fun `start creates one active session and exposes its state`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val factory = FakeVoiceAgentCallFactory(session)
        val manager = VoiceAgentCallManager(factory = factory)
        val conversationId = Uuid.parse("33333333-3333-4333-8333-333333333333")
        val config = fakeLaunchConfig()

        manager.start(conversationId = conversationId, config = config, scope = this)

        val observedState = manager.state
        session.state.value = VoiceAgentUiState(session = VoiceSessionStatus.Connected)
        advanceUntilIdle()

        assertSame(observedState, manager.state)
        assertEquals(VoiceSessionStatus.Connected, manager.state.value.session)
        assertEquals(listOf(conversationId to config), factory.created)
        assertEquals(1, session.startCalls)
    }

    @Test
    fun `starting another conversation ends previous session before replacing it`() = runTest {
        val first = FakeManagedVoiceCallSession()
        val second = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(first, second))
        val firstConversationId = Uuid.parse("44444444-4444-4444-8444-444444444444")
        val secondConversationId = Uuid.parse("55555555-5555-4555-8555-555555555555")

        manager.start(firstConversationId, fakeLaunchConfig(), this)
        manager.start(secondConversationId, fakeLaunchConfig(), this)

        assertEquals(1, first.endCalls)
        assertEquals(0, first.closeNowCalls)
        assertEquals(1, second.startCalls)
        assertEquals(secondConversationId, manager.activeConversationId.value)
    }

    @Test
    fun `starting same conversation does not duplicate active session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))
        val conversationId = Uuid.parse("66666666-6666-4666-8666-666666666666")

        manager.start(conversationId, fakeLaunchConfig(), this)
        manager.start(conversationId, fakeLaunchConfig(), this)

        assertEquals(1, session.startCalls)
        assertEquals(0, session.endCalls)
    }

    @Test
    fun `detach does not end active session`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(Uuid.random(), fakeLaunchConfig(), this)
        manager.detachUi()

        assertEquals(0, session.endCalls)
        assertEquals(0, session.closeNowCalls)
    }

    @Test
    fun `end forwards to active session and clears active call`() = runTest {
        val session = FakeManagedVoiceCallSession()
        val manager = VoiceAgentCallManager(factory = FakeVoiceAgentCallFactory(session))

        manager.start(Uuid.random(), fakeLaunchConfig(), this)
        manager.end()

        assertEquals(1, session.endCalls)
        assertEquals(null, manager.activeConversationId.value)
    }
}

private class FakeManagedVoiceCallSession : ManagedVoiceCallSession {
    override val state = MutableStateFlow(VoiceAgentUiState())
    var startCalls = 0
    var endCalls = 0
    var closeNowCalls = 0

    override fun start() {
        startCalls += 1
    }

    override fun interrupt() = Unit

    override fun setMuted(value: Boolean) = Unit

    override fun reconnect() = Unit

    override fun end() {
        endCalls += 1
    }

    override fun closeNow() {
        closeNowCalls += 1
    }
}

private class FakeVoiceAgentCallFactory(
    private vararg val sessions: ManagedVoiceCallSession,
) : VoiceAgentCallFactory {
    val created = mutableListOf<Pair<Uuid, VoiceAgentLaunchConfig>>()
    private var nextSession = 0

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        scope: CoroutineScope,
    ): ManagedVoiceCallSession {
        created += conversationId to config
        return sessions[nextSession++]
    }
}

private fun fakeLaunchConfig() = VoiceAgentLaunchConfig(
    voiceLabBaseUrl = "https://voice.test",
    credentials = VoiceLabMobileCredentials(hermesProfileApiKey = "profile-key"),
    voiceModelId = "gemini-flash",
    assistantName = "Hermes",
    assistantPrompt = "system",
)
