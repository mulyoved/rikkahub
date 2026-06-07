package me.rerere.rikkahub.voiceagent

import android.util.Log

internal object VoiceAgentDebugLog {
    fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
    }
}
