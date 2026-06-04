package me.rerere.rikkahub.voiceagent.gemini

sealed interface GeminiLiveEvent {
    data object SetupComplete : GeminiLiveEvent

    data class InputTranscript(
        val text: String,
    ) : GeminiLiveEvent

    data class OutputTranscript(
        val text: String,
    ) : GeminiLiveEvent

    data class OutputAudio(
        val base64Pcm16: String,
    ) : GeminiLiveEvent

    data class Interrupted(
        val reason: String = "serverContent.interrupted",
    ) : GeminiLiveEvent

    data class ToolCall(
        val callId: String,
        val name: String,
        val prompt: String,
    ) : GeminiLiveEvent

    data class ToolCallCancellation(
        val callIds: List<String>,
    ) : GeminiLiveEvent

    data class Error(
        val message: String,
        val raw: String,
    ) : GeminiLiveEvent

    data class Ignored(
        val raw: String,
    ) : GeminiLiveEvent
}

data class GeminiContentTurn(
    val role: String,
    val text: String,
)
