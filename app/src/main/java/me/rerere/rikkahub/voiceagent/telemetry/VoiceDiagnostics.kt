package me.rerere.rikkahub.voiceagent.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

data class VoiceDiagnosticEvent(
    val name: String,
    val detail: String,
    val at: Instant = Clock.System.now(),
)

class VoiceDiagnostics {
    private val _events = MutableStateFlow<List<VoiceDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<VoiceDiagnosticEvent>> = _events.asStateFlow()

    fun record(event: VoiceDiagnosticEvent) {
        _events.update { it + event }
    }

    fun record(name: String, detail: String = "") {
        record(VoiceDiagnosticEvent(name = name, detail = detail))
    }
}
