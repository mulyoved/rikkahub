package me.rerere.rikkahub.voiceagent

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import kotlin.coroutines.EmptyCoroutineContext

interface VoiceToolApi {
    suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse
}

class VoiceAgentCoordinator(
    private val gemini: GeminiLiveVoiceClient,
    private val toolApi: VoiceToolApi,
    private val audio: VoiceAudioEngine,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val ownsScope = scope == null
    private val coordinatorScope = scope ?: CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
    private val toolLaunchContext = dispatcher ?: EmptyCoroutineContext
    private val toolJobsLock = Any()
    private val toolJobs = mutableSetOf<Job>()
    private var closed = false

    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    fun onGeminiEvent(event: GeminiLiveEvent) {
        when (event) {
            GeminiLiveEvent.SetupComplete -> diagnostics.record("gemini_setup_complete")
            is GeminiLiveEvent.InputTranscript -> appendInputTranscript(event.text)
            is GeminiLiveEvent.OutputTranscript -> appendOutputTranscript(event.text)
            is GeminiLiveEvent.OutputAudio -> playOutputAudio(event.base64Pcm16)
            is GeminiLiveEvent.Interrupted -> handleInterrupted(event)
            is GeminiLiveEvent.ToolCall -> handleToolCall(event)
            is GeminiLiveEvent.ToolCalls -> {
                event.unsupportedCalls.forEach(::recordUnsupportedToolCall)
                event.calls.forEach(::handleToolCall)
            }
            is GeminiLiveEvent.ToolCallCancellation -> diagnostics.record(
                name = "tool_call_cancellation",
                detail = event.callIds.joinToString(","),
            )
            is GeminiLiveEvent.SessionResumptionUpdate -> diagnostics.record(
                name = "session_resumption_update",
                detail = "resumable=${event.resumable}, newHandle=${event.newHandle.orEmpty()}",
            )
            is GeminiLiveEvent.Error -> _state.update { it.reduce(VoiceAgentEvent.SessionError(event.message)) }
            is GeminiLiveEvent.Ignored -> handleIgnored(event)
        }
    }

    suspend fun awaitToolJobs() {
        while (true) {
            val jobs = synchronized(toolJobsLock) {
                if (toolJobs.isEmpty()) {
                    return
                }
                toolJobs.toList()
            }
            jobs.joinAll()
        }
    }

    fun close() {
        val jobs = synchronized(toolJobsLock) {
            closed = true
            toolJobs.toList()
        }
        jobs.forEach { it.cancel() }
        gemini.close()
        audio.release()
        if (ownsScope) {
            coordinatorScope.cancel()
        }
    }

    private fun appendInputTranscript(text: String) {
        _state.update { it.copy(inputTranscript = it.inputTranscript + text) }
    }

    private fun appendOutputTranscript(text: String) {
        _state.update { it.copy(outputTranscript = it.outputTranscript + text) }
    }

    private fun playOutputAudio(base64Pcm16: String) {
        audio.playPcm16(base64Pcm16)
        _state.update { it.copy(audio = VoiceAudioStatus.AssistantSpeaking) }
    }

    private fun handleInterrupted(event: GeminiLiveEvent.Interrupted) {
        audio.suppressPlayback()
        diagnostics.record("gemini_interrupted", event.reason)
        _state.update { it.reduce(VoiceAgentEvent.UserInterrupted) }
    }

    private fun handleIgnored(event: GeminiLiveEvent.Ignored) {
        diagnostics.record("gemini_event_ignored", event.raw)
    }

    private fun handleToolCall(call: GeminiLiveEvent.ToolCall) {
        diagnostics.record("tool_call_received", "callId=${call.callId}, name=${call.name}")
        if (call.name != ASK_HERMES_TOOL) {
            recordUnsupportedToolCall(
                GeminiLiveEvent.UnsupportedToolCall(
                    callId = call.callId,
                    name = call.name,
                )
            )
            return
        }

        if (isClosed()) return
        diagnostics.record("hermes_tool_started", "callId=${call.callId}")
        updateToolStatus(call.callId, VoiceToolStatus.CallingHermes(call.callId))
        val job = coordinatorScope.launch(toolLaunchContext) {
            runHermesToolCall(callId = call.callId, prompt = call.prompt)
        }
        synchronized(toolJobsLock) {
            if (closed) {
                job.cancel()
            } else {
                toolJobs += job
            }
        }
        job.invokeOnCompletion {
            synchronized(toolJobsLock) {
                toolJobs -= job
            }
        }
    }

    private suspend fun runHermesToolCall(callId: String, prompt: String) {
        try {
            val response = toolApi.askHermes(callId = callId, prompt = prompt)
            val sent = synchronized(toolJobsLock) {
                if (closed) {
                    false
                } else {
                    gemini.sendToolResponse(callId, response.answer)
                    true
                }
            }
            if (!sent) return
            diagnostics.record("hermes_tool_succeeded", "callId=$callId")
            updateToolStatus(callId, VoiceToolStatus.HermesAnswered(callId = callId, elapsedMs = 0L))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_failed", "callId=$callId, message=$message")
            updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
        }
    }

    private fun isClosed(): Boolean = synchronized(toolJobsLock) { closed }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        _state.update { current ->
            val toolCalls = current.toolCalls + (callId to status)
            val summary = when (status) {
                is VoiceToolStatus.CallingHermes -> status
                else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
                    ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
                    ?: status
            }
            current.copy(
                tool = summary,
                toolCalls = toolCalls,
            )
        }
    }

    private fun recordUnsupportedToolCall(call: GeminiLiveEvent.UnsupportedToolCall) {
        diagnostics.record("unsupported_tool_call", "callId=${call.callId}, name=${call.name}")
    }

    private companion object {
        const val ASK_HERMES_TOOL = "ask_hermes"
    }
}

class VoiceAgentViewModel : ViewModel() {
    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()
}
