package me.rerere.rikkahub.voiceagent.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.voiceagent.VoiceAgentCallContract
import me.rerere.rikkahub.voiceagent.VoiceAgentCallService

class VoiceAgentDebugCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_START && action != ACTION_END) return

        runCatching {
            val serviceIntent = Intent(context, VoiceAgentCallService::class.java).setAction(
                when (action) {
                    ACTION_START -> VoiceAgentCallContract.ACTION_START
                    else -> VoiceAgentCallContract.ACTION_END
                }
            )
            if (action == ACTION_START) {
                serviceIntent.putExtra(
                    VoiceAgentCallContract.EXTRA_CONVERSATION_ID,
                    intent.getStringExtra(VoiceAgentCallContract.EXTRA_CONVERSATION_ID),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "debug_call_control result=success action=$action")
        }.onFailure { error ->
            Log.e(TAG, "debug_call_control failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.debug.voiceagent.START_CALL"
        const val ACTION_END = "me.rerere.rikkahub.debug.voiceagent.END_CALL"
        private const val TAG = "VoiceAgentDebugCall"
    }
}
