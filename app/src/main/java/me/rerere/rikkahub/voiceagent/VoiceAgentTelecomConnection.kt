package me.rerere.rikkahub.voiceagent

import android.content.Context
import android.telecom.Connection
import android.telecom.DisconnectCause

class VoiceAgentTelecomConnection(
    private val context: Context,
) : Connection() {
    override fun onDisconnect() {
        context.startService(voiceAgentCallEndIntent(context))
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }
}
