package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.RpcHandler
import io.livekit.android.room.participant.RpcInvocationData
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.TrackPublication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import livekit.LivekitModels
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLiveKitRoomSdkAdapterTest {
    @Test
    fun `maps actual LiveKit room events and filters unsupported events`() = runTest {
        val room = mockk<Room>()
        val participant = mockk<RemoteParticipant>()
        val disconnectError = IllegalStateException("disconnected")
        val connectError = IllegalArgumentException("failed")
        val data = byteArrayOf(1, 2, 3)
        val roomEvents = MutableSharedFlow<RoomEvent>()
        val eventListenable = mockk<EventListenable<RoomEvent>>()
        every { participant.identity } returns Participant.Identity("agent")
        every { room.events } returns eventListenable
        every { eventListenable.events } returns roomEvents
        val sdkEvents = listOf(
            RoomEvent.Connected(room),
            RoomEvent.Reconnecting(room),
            RoomEvent.Reconnected(room),
            RoomEvent.Disconnected(room, disconnectError, DisconnectReason.CLIENT_INITIATED),
            RoomEvent.FailedToConnect(room, connectError),
            RoomEvent.ParticipantDisconnected(room, participant),
            RoomEvent.ParticipantConnected(room, participant),
            RoomEvent.DataReceived(
                room = room,
                data = data,
                participant = participant,
                topic = "voice-agent",
                encryptionType = LivekitModels.Encryption.Type.NONE,
            ),
        )

        val mappedResult = async {
            AndroidLiveKitRoomSdkAdapter(room).events.take(7).toList()
        }
        runCurrent()
        sdkEvents.forEach { roomEvents.emit(it) }
        val mapped = mappedResult.await()

        assertEquals(LiveKitSdkRoomEvent.Connected, mapped[0])
        assertEquals(LiveKitSdkRoomEvent.Reconnecting, mapped[1])
        assertEquals(LiveKitSdkRoomEvent.Reconnected, mapped[2])
        assertEquals(LiveKitSdkRoomEvent.Disconnected(disconnectError), mapped[3])
        assertEquals(LiveKitSdkRoomEvent.FailedToConnect(connectError), mapped[4])
        assertEquals(LiveKitSdkRoomEvent.ParticipantDisconnected("agent"), mapped[5])
        val received = mapped[6] as LiveKitSdkRoomEvent.DataReceived
        assertEquals("agent", received.participantIdentity)
        assertEquals("voice-agent", received.topic)
        assertArrayEquals(data, received.data)
        assertEquals(7, mapped.size)
    }

    @Test
    fun `forwards room microphone rpc registration and lifecycle operations`() = runTest {
        val room = mockk<Room>()
        val localParticipant = mockk<LocalParticipant>()
        val rpcHandler = slot<RpcHandler>()
        every { room.localParticipant } returns localParticipant
        coJustRun { room.connect("wss://livekit.example", "token") }
        coEvery { localParticipant.setMicrophoneEnabled(true) } returns true
        coEvery {
            room.performRpc(Participant.Identity("agent"), "method", "payload", any(), any())
        } returns "rpc-result"
        every { room.registerRpcMethod("method", capture(rpcHandler)) } just Runs
        every { room.unregisterRpcMethod("method") } just Runs
        every { room.disconnect() } just Runs
        every { room.release() } just Runs
        val adapter = AndroidLiveKitRoomSdkAdapter(room, flowOf<RoomEvent>())

        adapter.connect("wss://livekit.example", "token")
        assertEquals(true, adapter.setMicrophoneEnabled(true))
        assertEquals("rpc-result", adapter.performRpc("agent", "method", "payload"))
        adapter.registerRpcMethod("method") { invocation ->
            "${invocation.callerIdentity}:${invocation.payload}"
        }
        val handlerResult = rpcHandler.captured(
            RpcInvocationData(
                requestId = "request-id",
                callerIdentity = Participant.Identity("caller"),
                payload = "request-payload",
                responseTimeout = 10.seconds,
            ),
        )
        adapter.unregisterRpcMethod("method")
        adapter.disconnect()
        adapter.release()

        assertEquals("caller:request-payload", handlerResult)
        coVerify(exactly = 1) { room.connect("wss://livekit.example", "token") }
        coVerify(exactly = 1) { localParticipant.setMicrophoneEnabled(true) }
        coVerify(exactly = 1) {
            room.performRpc(Participant.Identity("agent"), "method", "payload", any(), any())
        }
        verify(exactly = 1) { room.registerRpcMethod("method", any()) }
        verify(exactly = 1) { room.unregisterRpcMethod("method") }
        verify(exactly = 1) { room.disconnect() }
        verify(exactly = 1) { room.release() }
    }

    @Test
    fun `remote audio sink follows subscribe unsubscribe disconnect and release ownership`() = runTest {
        val room = mockk<Room>()
        val participant = mockk<RemoteParticipant>()
        val publication = mockk<TrackPublication>()
        val roomEvents = MutableSharedFlow<RoomEvent>()
        val tracks = List(4) { mockk<RemoteAudioTrack>() }
        val probes = List(4) { mockk<LiveKitRemoteAudioProbe>() }
        tracks.forEach { track ->
            every { track.addSink(any()) } just Runs
            every { track.removeSink(any()) } just Runs
        }
        probes.forEach { probe ->
            every { probe.close() } just Runs
        }
        every { room.disconnect() } just Runs
        every { room.release() } just Runs
        val pendingProbes = ArrayDeque(probes)
        val adapter = AndroidLiveKitRoomSdkAdapter(
            room = room,
            sdkEvents = roomEvents,
            remoteAudioProbeFactory = { pendingProbes.removeFirst() },
        )
        val collection = backgroundScope.launch {
            adapter.events.collect { }
        }
        runCurrent()

        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[0], publication, participant))
        roomEvents.emit(RoomEvent.TrackUnsubscribed(room, tracks[0], publication, participant))
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[1], publication, participant))
        roomEvents.emit(
            RoomEvent.Disconnected(
                room,
                IllegalStateException("network lost"),
                DisconnectReason.CLIENT_INITIATED,
            ),
        )
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[2], publication, participant))
        adapter.disconnect()
        roomEvents.emit(RoomEvent.TrackSubscribed(room, tracks[3], publication, participant))
        adapter.release()
        collection.cancel()

        tracks.zip(probes).forEach { (track, probe) ->
            verify(exactly = 1) { track.addSink(probe) }
            verify(exactly = 1) { track.removeSink(probe) }
            verify(exactly = 1) { probe.close() }
        }
        verify(exactly = 1) { room.disconnect() }
        verify(exactly = 1) { room.release() }
    }
}
