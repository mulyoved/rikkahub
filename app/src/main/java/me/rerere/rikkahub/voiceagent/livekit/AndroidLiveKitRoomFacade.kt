package me.rerere.rikkahub.voiceagent.livekit

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent as SdkRoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class AndroidLiveKitRoomFacade(
    context: Context,
    private val room: Room = LiveKit.create(
        appContext = context,
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(audioHandler = NoAudioHandler()),
        ),
    ),
) : LiveKitRoomFacade {
    override val events: Flow<LiveKitRoomEvent> = room.events.events.mapNotNull(::toFacadeEvent)

    override suspend fun connect(url: String, token: String) {
        room.connect(url, token)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean =
        room.localParticipant.setMicrophoneEnabled(enabled)

    override suspend fun performRpc(destination: String, method: String, payload: String): String =
        room.performRpc(Participant.Identity(destination), method, payload)

    override fun registerRpcMethod(
        method: String,
        handler: suspend (LiveKitRpcInvocation) -> String,
    ) {
        room.registerRpcMethod(method) { invocation ->
            handler(
                LiveKitRpcInvocation(
                    callerIdentity = invocation.callerIdentity.value,
                    payload = invocation.payload,
                ),
            )
        }
    }

    override fun unregisterRpcMethod(method: String) {
        room.unregisterRpcMethod(method)
    }

    override fun disconnect() {
        room.disconnect()
    }

    override fun close() {
        room.release()
    }

    private fun toFacadeEvent(event: SdkRoomEvent): LiveKitRoomEvent? = when (event) {
        is SdkRoomEvent.Connected -> LiveKitRoomEvent.Connected
        is SdkRoomEvent.Reconnecting -> LiveKitRoomEvent.Reconnecting
        is SdkRoomEvent.Reconnected -> LiveKitRoomEvent.Reconnected
        is SdkRoomEvent.Disconnected -> LiveKitRoomEvent.Disconnected(event.error)
        is SdkRoomEvent.FailedToConnect -> LiveKitRoomEvent.Failed(event.error)
        is SdkRoomEvent.ParticipantDisconnected -> LiveKitRoomEvent.ParticipantDisconnected(
            event.participant.identity?.value ?: return null,
        )
        is SdkRoomEvent.DataReceived -> LiveKitRoomEvent.Data(
            participantIdentity = event.participant?.identity?.value ?: return null,
            topic = event.topic ?: return null,
            payload = event.data.decodeToString(),
        )
        else -> null
    }
}
