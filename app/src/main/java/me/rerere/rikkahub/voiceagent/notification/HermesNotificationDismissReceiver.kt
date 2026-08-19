package me.rerere.rikkahub.voiceagent.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

class HermesNotificationDismissReceiver : BroadcastReceiver(), KoinComponent {
    private val acknowledger: HermesNotificationAcknowledger by inject()
    private val appScope: AppScope by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS_HERMES_RESULT) return
        val conversationIdStr = intent.getStringExtra("conversationId")
            ?: intent.data?.lastPathSegment
            ?: return
        val conversationId = runCatching { Uuid.parse(conversationIdStr) }.getOrNull() ?: return
        val pendingResult = goAsync()
        appScope.launch {
            try {
                acknowledger.acknowledgeConversation(conversationId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
