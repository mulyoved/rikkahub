package me.rerere.rikkahub.voiceagent

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.ChatService
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

class VoiceAgentCallService : Service() {
    private val manager: VoiceAgentCallManager by inject()
    private val settingsStore: SettingsStore by inject()
    private val chatService: ChatService by inject()
    private val notificationFactory: VoiceAgentNotificationFactory by inject()
    private val telecomAdapter: VoiceAgentTelecomAdapter by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeConversationId: Uuid? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            VoiceAgentCallContract.ACTION_START -> startCall(intent)
            VoiceAgentCallContract.ACTION_END -> endCall()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        notificationJob = null
        manager.closeNow()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCall(intent: Intent) {
        val id = intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: run {
                stopSelf()
                return
            }
        activeConversationId = id
        notificationJob?.cancel()
        manager.updateCallStatus(VoiceCallStatus.ForegroundStarting)
        startForegroundFor(id.toString(), VoiceAgentUiState(call = VoiceCallStatus.ForegroundStarting))
        serviceScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val conversation = chatService.getConversationFlow(id).value
            when (val result = VoiceAgentConfigResolver().resolve(settings = settings, conversation = conversation)) {
                is VoiceAgentConfigResult.Available -> {
                    manager.start(conversationId = id, config = result.config, scope = serviceScope)
                    manager.updateCallStatus(VoiceCallStatus.BackgroundCapable)
                    telecomAdapter.register()
                        .onFailure {
                            val detail = it.message ?: it.javaClass.simpleName
                            manager.recordDiagnostic("telecom_register_failed", detail)
                            manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom unavailable: $detail"))
                        }
                    telecomAdapter.startCall()
                        .onFailure {
                            val detail = it.message ?: it.javaClass.simpleName
                            manager.recordDiagnostic("telecom_start_failed", detail)
                            manager.updateCallStatus(VoiceCallStatus.Degraded("Telecom call unavailable: $detail"))
                        }
                    notificationJob = serviceScope.launch {
                        manager.state.collect { state ->
                            startForegroundFor(id.toString(), state)
                        }
                    }
                }
                is VoiceAgentConfigResult.Unavailable -> {
                    manager.updateCallStatus(VoiceCallStatus.Degraded(result.message))
                    startForegroundFor(id.toString(), manager.state.value)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun endCall() {
        notificationJob?.cancel()
        notificationJob = null
        manager.end()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundFor(conversationId: String, state: VoiceAgentUiState) {
        val notification = notificationFactory.activeNotification(conversationId = conversationId, state = state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                VoiceAgentCallContract.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(VoiceAgentCallContract.NOTIFICATION_ID, notification)
        }
    }
}
