package me.rerere.rikkahub.voiceagent.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.Assert.assertSame
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
        runCurrent()
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
    fun `latest mute request wins while initial microphone publication is suspended`() = runTest {
        val fixture = fixture()
        val initialMicrophoneGate = CompletableDeferred<Unit>()
        fixture.room.microphoneGate = initialMicrophoneGate
        fixture.session.start()
        runCurrent()
        assertEquals(listOf(true), fixture.room.microphoneValues)

        fixture.session.setMuted(true)
        fixture.session.setMuted(false)
        fixture.session.setMuted(true)

        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
        runCurrent()
        assertEquals(listOf(true), fixture.room.microphoneValues)

        initialMicrophoneGate.complete(Unit)
        runCurrent()

        assertEquals(listOf(true, false), fixture.room.microphoneValues)
        assertFalse(fixture.room.sdkMicrophoneEnabled)
        assertEquals(VoiceAudioStatus.Muted, fixture.session.state.value.audio)
    }

    @Test
    fun `RPC methods are registered before connect and unregistered by one idempotent cleanup`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        fixture.session.start()
        runCurrent()

        assertTrue(
            fixture.room.lifecycle.indexOf("register:hermes.job.accepted") < fixture.room.lifecycle.indexOf("connect"),
        )
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
    fun `cleanup retries failed stages without repeating completed stages`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        val disconnectFailure = IllegalStateException("disconnect failed")
        fixture.room.disconnectFailure = disconnectFailure
        fixture.session.start()
        runCurrent()

        val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        assertEquals(disconnectFailure, (first as VoiceAgentCleanupResult.Failed).error)
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(0, fixture.room.closeCalls)

        fixture.room.disconnectFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(2, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `cleanup joins in flight connect and event collection before room release`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        fixture.room.connectGate = CompletableDeferred()
        fixture.session.start()
        runCurrent()
        assertTrue("connect" in fixture.room.lifecycle)
        assertTrue("events-started" in fixture.room.lifecycle)

        val cleanup = async {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }
        runCurrent()

        assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
        assertTrue(
            fixture.room.lifecycle.indexOf("connect-finished") < fixture.room.lifecycle.indexOf("disconnect"),
        )
        assertTrue(
            fixture.room.lifecycle.indexOf("events-finished") < fixture.room.lifecycle.indexOf("disconnect"),
        )
        assertTrue(
            fixture.room.lifecycle.indexOf("disconnect") < fixture.room.lifecycle.indexOf("close"),
        )
    }

    @Test
    fun `cleanup preserves canonical cancellation and retries the incomplete stage`() = runTest {
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
        )
        val cancellation = CancellationException("unregister cancelled")
        fixture.room.unregisterFailure = cancellation
        fixture.session.start()
        runCurrent()

        val thrown = runCatching {
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(0, fixture.room.disconnectCalls)
        assertEquals(0, fixture.room.closeCalls)

        fixture.room.unregisterFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(2, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
    }

    @Test
    fun `cleanup aggregates independent route and room failures`() = runTest {
        val routeFailure = IllegalStateException("route failed")
        val unregisterFailure = IllegalArgumentException("unregister failed")
        var activeRouteFailure: Throwable? = routeFailure
        val route = OrchestratorFakeRoute {
            activeRouteFailure?.let { throw it }
        }
        val fixture = fixture(
            rpcMethods = mapOf("hermes.job.accepted" to { "persisted" }),
            route = route,
        )
        fixture.room.unregisterFailure = unregisterFailure
        fixture.session.start()
        runCurrent()

        val first = fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)

        assertTrue(first is VoiceAgentCleanupResult.Failed)
        val error = (first as VoiceAgentCleanupResult.Failed).error
        assertSame(routeFailure, error)
        assertEquals(listOf(unregisterFailure), error.suppressed.toList())
        assertEquals(1, fixture.route.retirementCalls)
        assertEquals(1, fixture.room.unregisterCalls)
        assertEquals(0, fixture.room.disconnectCalls)

        activeRouteFailure = null
        fixture.room.unregisterFailure = null

        assertEquals(
            VoiceAgentCleanupResult.Completed,
            fixture.session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(2, fixture.route.retirementCalls)
        assertEquals(2, fixture.room.unregisterCalls)
        assertEquals(1, fixture.room.disconnectCalls)
        assertEquals(1, fixture.room.closeCalls)
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
    fun `manual reconnect relies on native SDK reconnection without a second connect`() = runTest {
        val fixture = fixture()
        fixture.session.start()
        runCurrent()
        fixture.room.emit(LiveKitRoomEvent.Data(AGENT_IDENTITY, READY_TOPIC, readyJson()))
        runCurrent()

        fixture.session.reconnect()
        runCurrent()

        assertEquals(1, fixture.room.connectAttempts)
        assertTrue(fixture.session.state.value.session is VoiceSessionStatus.Connected)
        assertTrue(
            fixture.session.state.value.diagnostics.any { it.name == "livekit_native_reconnect_owned" },
        )
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
        route: OrchestratorFakeRoute = OrchestratorFakeRoute(),
    ): SessionFixture {
        val room = FakeLiveKitRoomFacade(connectFailure)
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
    val lifecycle = mutableListOf<String>()
    private val mutableEvents = MutableSharedFlow<LiveKitRoomEvent>(extraBufferCapacity = 16)
    override val events: Flow<LiveKitRoomEvent> = flow {
        lifecycle += "events-started"
        try {
            mutableEvents.collect { emit(it) }
        } finally {
            lifecycle += "events-finished"
        }
    }
    val connections = mutableListOf<Pair<String, String>>()
    val microphoneValues = mutableListOf<Boolean>()
    val rpcCalls = mutableListOf<Triple<String, String, String>>()
    private val handlers = mutableMapOf<String, suspend (LiveKitRpcInvocation) -> String>()
    var unregisterCalls = 0
    var disconnectCalls = 0
    var closeCalls = 0
    var connectAttempts = 0
    private var connected = false
    var connectGate: CompletableDeferred<Unit>? = null
    var unregisterFailure: Throwable? = null
    var disconnectFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var microphoneGate: CompletableDeferred<Unit>? = null
    var sdkMicrophoneEnabled = false

    suspend fun emit(event: LiveKitRoomEvent) {
        mutableEvents.emit(event)
    }

    suspend fun invoke(method: String, caller: String, payload: String): String =
        requireNotNull(handlers[method])(LiveKitRpcInvocation(caller, payload))

    override suspend fun connect(url: String, token: String) {
        connectAttempts += 1
        check(!connected) { "Room.connect attempted while room is not disconnected!" }
        lifecycle += "connect"
        connections += url to token
        try {
            connectFailure?.let { throw it }
            connectGate?.await()
            connected = true
        } finally {
            lifecycle += "connect-finished"
        }
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        microphoneValues += enabled
        microphoneGate?.await()
        sdkMicrophoneEnabled = enabled
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
        unregisterCalls += 1
        unregisterFailure?.let { throw it }
        handlers.remove(method)
    }

    override fun disconnect() {
        lifecycle += "disconnect"
        disconnectCalls += 1
        disconnectFailure?.let { throw it }
        connected = false
    }

    override fun close() {
        lifecycle += "close"
        closeCalls += 1
        closeFailure?.let { throw it }
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
