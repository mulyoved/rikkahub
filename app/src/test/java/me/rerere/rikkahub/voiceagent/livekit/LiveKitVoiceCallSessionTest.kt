package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.OrchestratorFakeRoute
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAudioStatus
import me.rerere.rikkahub.voiceagent.VoiceSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitVoiceCallSessionTest {
    @Test
    fun `room connection is not usable until expected worker ready`() = runTest {
        val fixture = fixture()

        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        fixture.room.emit(
            LiveKitRoomEvent.Data(
                participantIdentity = AGENT_IDENTITY,
                topic = READY_TOPIC,
                payload = readyJson(),
            ),
        )
        runCurrent()

        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertEquals(listOf(LIVEKIT_URL to PARTICIPANT_TOKEN), fixture.room.connections)
        assertEquals(listOf(true), fixture.room.microphoneValues)
    }

    @Test
    fun `ready rejects the wrong agent topic and voice session`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Connected)
        fixture.room.emit(LiveKitRoomEvent.Data("other-agent", READY_TOPIC, readyJson()))
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, "other.topic", readyJson()))
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson("lvs_other")))
        runCurrent()

        assertFalse(fixture.session.state.value.session is VoiceSessionStatus.Connected)
    }

    @Test
    fun `mute and explicit interrupt use only LiveKit`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()

        fixture.session.setMuted(true)
        fixture.session.setMuted(false)
        fixture.session.interrupt()
        runCurrent()

        assertEquals(listOf(false, true), fixture.room.microphoneValues.takeLast(2))
        assertEquals(VoiceAudioStatus.Listening, fixture.session.state.value.audio)
        assertEquals(
            listOf(Triple(AGENT_IDENTITY, INTERRUPT_RPC, "")),
            fixture.room.rpcCalls,
        )
    }

    @Test
    fun `RPC methods are registered before connect and unregistered by one idempotent cleanup`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        fixture.session.start()
        runCurrent()

        assertEquals(listOf("register:hermes.job.accepted", "connect"), fixture.room.lifecycle.take(2))
        assertEquals("persisted", fixture.room.invoke("hermes.job.accepted", AGENT_IDENTITY, "payload"))

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd),
        )

        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
        assertEquals(1, fixture.route.retirementCalls)
    }

    @Test
    fun `reconnect events preserve readiness and remote disconnect ends experimental path`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Reconnecting)
        runCurrent()
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Reconnecting)

        fixture.room.emit(LiveKitRoomEvent.Reconnected)
        runCurrent()
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)

        fixture.room.emit(LiveKitRoomEvent.ParticipantDisconnected(AGENT_IDENTITY))
        runCurrent()
        val status = fixture.session.state.value.session
        assertTrue(status is VoiceSessionStatus.Error)
        assertTrue((status as VoiceSessionStatus.Error).message.contains("experimental", ignoreCase = true))
    }

    @Test
    fun `manual reconnect makes a new room connection attempt`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()

        fixture.session.reconnect()
        runCurrent()

        assertEquals(2, fixture.room.connections.size)
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Reconnecting)
    }

    @Test
    fun `connect failure and readiness timeout map to experimental errors`() = runTest {
        val failed = fixture(connectFailure = IllegalStateException("socket unavailable"))
        failed.session.start()
        runCurrent()
        val failureStatus = failed.session.state.value.session
        assertTrue(failureStatus is VoiceSessionStatus.Error)
        assertTrue((failureStatus as VoiceSessionStatus.Error).message.contains("experimental", ignoreCase = true))

        val timedOut = fixture(readyTimeoutMillis = 1_000)
        timedOut.session.start()
        runCurrent()
        timedOut.room.emit(LiveKitRoomEvent.Connected)
        advanceTimeBy(1_001)
        runCurrent()
        val timeoutStatus = timedOut.session.state.value.session
        assertTrue(timeoutStatus is VoiceSessionStatus.Error)
        assertTrue((timeoutStatus as VoiceSessionStatus.Error).message.contains("timed out", ignoreCase = true))
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        rpcMethods: Map<String, suspend (LiveKitRpcInvocation) -> String> = emptyMap(),
        connectFailure: Throwable? = null,
        readyTimeoutMillis: Long = 30_000,
    ): SessionFixture {
        val room = FakeLiveKitRoomFacade(connectFailure)
        val route = OrchestratorFakeRoute()
        return SessionFixture(
            session = LiveKitVoiceCallSession(
                details = details(),
                room = room,
                routeLease = route.lease,
                scope = backgroundScope,
                rpcMethods = rpcMethods,
                connectTimeoutMillis = 10_000,
                readyTimeoutMillis = readyTimeoutMillis,
            ),
            room = room,
            route = route,
        )
    }

    private data class SessionFixture(
        val session: LiveKitVoiceCallSession,
        val room: FakeLiveKitRoomFacade,
        val route: OrchestratorFakeRoute,
    )
}

private class FakeLiveKitRoomFacade(
    private val connectFailure: Throwable? = null,
) : LiveKitRoomFacade {
    private val mutableEvents = MutableSharedFlow<LiveKitRoomEvent>(extraBufferCapacity = 16)
    override val events: Flow<LiveKitRoomEvent> = mutableEvents
    val connections = mutableListOf<Pair<String, String>>()
    val microphoneValues = mutableListOf<Boolean>()
    val rpcCalls = mutableListOf<Triple<String, String, String>>()
    val lifecycle = mutableListOf<String>()
    private val handlers = mutableMapOf<String, suspend (LiveKitRpcInvocation) -> String>()
    var unregisterCalls = 0
    var disconnectCalls = 0
    var closeCalls = 0

    suspend fun emit(event: LiveKitRoomEvent) {
        mutableEvents.emit(event)
    }

    suspend fun invoke(method: String, caller: String, payload: String): String =
        requireNotNull(handlers[method])(LiveKitRpcInvocation(caller, payload))

    override suspend fun connect(url: String, token: String) {
        lifecycle += "connect"
        connections += url to token
        connectFailure?.let { throw it }
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        microphoneValues += enabled
        return true
    }

    override suspend fun performRpc(destination: String, method: String, payload: String): String {
        rpcCalls += Triple(destination, method, payload)
        return "ok"
    }

    override fun registerRpcMethod(method: String, handler: suspend (LiveKitRpcInvocation) -> String) {
        lifecycle += "register:$method"
        handlers[method] = handler
    }

    override fun unregisterRpcMethod(method: String) {
        lifecycle += "unregister:$method"
        handlers.remove(method)
        unregisterCalls += 1
    }

    override fun disconnect() {
        disconnectCalls += 1
    }

    override fun close() {
        closeCalls += 1
    }
}

private fun details() = LiveKitSessionDetails(
    livekitUrl = LIVEKIT_URL,
    participantToken = PARTICIPANT_TOKEN,
    roomName = "rikka_1",
    voiceSessionId = VOICE_SESSION_ID,
    mobileParticipantIdentity = "mobile_lvs_1",
    agentParticipantIdentity = AGENT_IDENTITY,
    dispatchId = "AD_1",
    expiresAt = "2026-07-20T02:00:00Z",
)

private fun readyJson(voiceSessionId: String = VOICE_SESSION_ID): String =
    """{"version":1,"voiceSessionId":"$voiceSessionId","kind":"ready","observedAt":"2026-07-20T00:00:00Z"}"""

private const val LIVEKIT_URL = "wss://project.livekit.cloud"
private const val PARTICIPANT_TOKEN = "participant-token"
private const val VOICE_SESSION_ID = "lvs_1"
private const val AGENT_IDENTITY = "agent_lvs_1"
private const val READY_TOPIC = "voice.ready.v1"
private const val INTERRUPT_RPC = "voice.interrupt"
