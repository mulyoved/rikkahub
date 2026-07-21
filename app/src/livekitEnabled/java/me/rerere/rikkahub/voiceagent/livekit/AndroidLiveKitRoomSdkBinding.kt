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

internal fun createLiveKitRoomSdkAdapter(context: Context): LiveKitRoomSdkAdapter =
    AndroidLiveKitRoomSdkAdapter(
        LiveKit.create(
            appContext = context,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(audioHandler = NoAudioHandler()),
            ),
        ),
    )

internal class AndroidLiveKitRoomSdkAdapter(
    private val room: Room,
    sdkEvents: Flow<SdkRoomEvent> = room.events.events,
) : LiveKitRoomSdkAdapter {
    override val events: Flow<LiveKitSdkRoomEvent> = sdkEvents.mapNotNull(::toSdkEvent)

    override suspend fun connect(url: String, token: String) {
        room.connect(url, token)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean =
        room.localParticipant.setMicrophoneEnabled(enabled)

    override suspend fun performRpc(destination: String, method: String, payload: String): String =
        room.performRpc(Participant.Identity(destination), method, payload)

    override fun registerRpcMethod(
        method: String,
        handler: suspend (LiveKitSdkRpcInvocation) -> String,
    ) {
        room.registerRpcMethod(method) { invocation ->
            handler(
                LiveKitSdkRpcInvocation(
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

    override fun release() {
        room.release()
    }

    private fun toSdkEvent(event: SdkRoomEvent): LiveKitSdkRoomEvent? = when (event) {
        is SdkRoomEvent.Connected -> LiveKitSdkRoomEvent.Connected
        is SdkRoomEvent.Reconnecting -> LiveKitSdkRoomEvent.Reconnecting
        is SdkRoomEvent.Reconnected -> LiveKitSdkRoomEvent.Reconnected
        is SdkRoomEvent.Disconnected -> LiveKitSdkRoomEvent.Disconnected(event.error)
        is SdkRoomEvent.FailedToConnect -> LiveKitSdkRoomEvent.FailedToConnect(event.error)
        is SdkRoomEvent.ParticipantDisconnected -> LiveKitSdkRoomEvent.ParticipantDisconnected(
            event.participant.identity?.value,
        )
        is SdkRoomEvent.DataReceived -> LiveKitSdkRoomEvent.DataReceived(
            participantIdentity = event.participant?.identity?.value,
            topic = event.topic,
            data = event.data,
        )
        else -> null
    }
}
