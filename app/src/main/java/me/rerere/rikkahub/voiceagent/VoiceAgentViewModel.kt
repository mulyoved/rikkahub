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
            is GeminiLiveEvent.ToolCalls -> event.calls.forEach(::handleToolCall)
            is GeminiLiveEvent.ToolCallCancellation -> diagnostics.record(
                name = "tool_call_cancellation",
                detail = event.callIds.joinToString(","),
            )
            is GeminiLiveEvent.SessionResumptionUpdate -> diagnostics.record(
                name = "session_resumption_update",
                detail = "resumable=${event.resumable}, newHandle=${event.newHandle.orEmpty()}",
            )
            is GeminiLiveEvent.Error -> _state.update { it.reduce(VoiceAgentEvent.SessionError(event.message)) }
            is GeminiLiveEvent.Ignored -> diagnostics.record("gemini_event_ignored", event.raw)
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

    private fun handleToolCall(call: GeminiLiveEvent.ToolCall) {
        diagnostics.record("tool_call_received", "callId=${call.callId}, name=${call.name}")
        if (call.name != ASK_HERMES_TOOL) {
            diagnostics.record("unsupported_tool_call", "callId=${call.callId}, name=${call.name}")
            return
        }

        _state.update { it.copy(tool = VoiceToolStatus.CallingHermes(call.callId)) }
        val job = coordinatorScope.launch(toolLaunchContext) {
            runHermesToolCall(callId = call.callId, prompt = call.prompt)
        }
        synchronized(toolJobsLock) {
            toolJobs += job
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
            gemini.sendToolResponse(callId, response.answer)
            _state.update { it.copy(tool = VoiceToolStatus.HermesAnswered(callId = callId, elapsedMs = 0L)) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_failed", "callId=$callId, message=$message")
            _state.update { it.copy(tool = VoiceToolStatus.HermesFailed(callId = callId, message = message)) }
        }
    }

    private companion object {
        const val ASK_HERMES_TOOL = "ask_hermes"
    }
}

class VoiceAgentViewModel : ViewModel() {
    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()
}
