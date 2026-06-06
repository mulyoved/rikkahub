package me.rerere.rikkahub.voiceagent

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import org.koin.android.ext.android.inject

class VoiceAgentConnectionService : ConnectionService() {
    private val callManager: VoiceAgentCallManager by inject()

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        return VoiceAgentTelecomConnection(context = applicationContext).apply {
            setInitializing()
            setActive()
        }
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        val detail = "Android Telecom rejected Voice Agent call"
        callManager.recordDiagnostic("telecom_outgoing_failed", detail)
        callManager.updateCallStatus(VoiceCallStatus.Degraded(detail))
    }
}
