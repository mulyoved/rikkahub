package me.rerere.rikkahub.voiceagent

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val closeLock = Any()
    private val eventLock = Any()
    private val toolJobsLock = Any()
    private val toolJobs = mutableMapOf<String, ToolJobHandle>()
    private val toolCallLocks = mutableMapOf<String, Any>()
    private val cancelledToolCallIds = mutableSetOf<String>()
    private var closing = false
    private var closed = false

    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    fun onGeminiEvent(event: GeminiLiveEvent) {
        when (event) {
            is GeminiLiveEvent.ToolCall -> {
                if (shouldIgnoreEventAfterClose(event)) return
                handleToolCall(event)
            }
            is GeminiLiveEvent.ToolCalls -> {
                if (shouldIgnoreEventAfterClose(event)) return
                event.unsupportedCalls.forEach(::recordUnsupportedToolCall)
                event.calls.forEach(::handleToolCall)
            }
            is GeminiLiveEvent.ToolCallCancellation -> {
                if (shouldIgnoreEventAfterClose(event)) return
                handleToolCallCancellation(event)
            }
            else -> synchronized(eventLock) {
                onNonToolGeminiEvent(event)
            }
        }
    }

    private fun onNonToolGeminiEvent(event: GeminiLiveEvent) {
        if (shouldIgnoreEventAfterClose(event)) return
        when (event) {
            GeminiLiveEvent.SetupComplete -> diagnostics.record("gemini_setup_complete")
            is GeminiLiveEvent.InputTranscript -> appendInputTranscript(event.text)
            is GeminiLiveEvent.OutputTranscript -> appendOutputTranscript(event.text)
            is GeminiLiveEvent.OutputAudio -> playOutputAudio(event.base64Pcm16)
            is GeminiLiveEvent.Interrupted -> handleInterrupted(event)
            is GeminiLiveEvent.SessionResumptionUpdate -> diagnostics.record(
                name = "session_resumption_update",
                detail = "resumable=${event.resumable}, newHandle=${event.newHandle.orEmpty()}",
            )
            is GeminiLiveEvent.Error -> _state.update { it.reduce(VoiceAgentEvent.SessionError(event.message)) }
            is GeminiLiveEvent.Ignored -> handleIgnored(event)
            is GeminiLiveEvent.ToolCall,
            is GeminiLiveEvent.ToolCalls,
            is GeminiLiveEvent.ToolCallCancellation,
                -> Unit
        }
    }

    suspend fun awaitToolJobs() {
        while (true) {
            val jobs = synchronized(toolJobsLock) {
                if (toolJobs.isEmpty()) {
                    return
                }
                toolJobs.values.map { it.job }
            }
            jobs.joinAll()
        }
    }

    fun close() {
        synchronized(closeLock) {
            val handles = synchronized(eventLock) {
                synchronized(toolJobsLock) {
                    if (closed || closing) return
                    closing = true
                    toolJobs.values.toList()
                }
            }
            val jobs = handles.map { handle ->
                synchronized(handle.sendLock) {
                    handle.job
                }
            }
            synchronized(toolJobsLock) {
                toolJobs.clear()
                closed = true
                closing = false
            }
            _state.update { current ->
                current.copy(
                    tool = VoiceToolStatus.Idle,
                    toolCalls = emptyMap(),
                )
            }
            jobs.forEach { it.cancel() }
            gemini.close()
            audio.release()
            if (ownsScope) {
                coordinatorScope.cancel()
            }
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

        val handle = ToolJobHandle()
        val job = coordinatorScope.launch(toolLaunchContext, start = CoroutineStart.LAZY) {
            runHermesToolCall(callId = call.callId, prompt = call.prompt, handle = handle)
        }
        handle.job = job
        val shouldStart = registerToolHandle(callId = call.callId, handle = handle)
        if (!shouldStart) {
            job.cancel()
            return
        }
        diagnostics.record("hermes_tool_started", "callId=${call.callId}")
        job.invokeOnCompletion {
            synchronized(toolJobsLock) {
                if (toolJobs[call.callId] === handle) {
                    toolJobs -= call.callId
                }
            }
        }
        job.start()
    }

    private suspend fun runHermesToolCall(callId: String, prompt: String, handle: ToolJobHandle) {
        try {
            val response = toolApi.askHermes(callId = callId, prompt = prompt)
            val coroutineContext = currentCoroutineContext()
            synchronized(handle.sendLock) {
                if (!isToolHandleActive(callId, handle)) return
                coroutineContext.ensureActive()
                gemini.sendToolResponse(callId, response.answer)
            }
            if (!isToolHandleActive(callId, handle)) return
            diagnostics.record("hermes_tool_succeeded", "callId=$callId")
            updateToolStatus(callId, VoiceToolStatus.HermesAnswered(callId = callId, elapsedMs = 0L))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isToolHandleActive(callId, handle)) return
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("hermes_tool_failed", "callId=$callId, message=$message")
            updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
        }
    }

    private fun isClosed(): Boolean = synchronized(toolJobsLock) { closed || closing }

    private fun shouldIgnoreEventAfterClose(event: GeminiLiveEvent): Boolean {
        if (!isClosed()) return false
        diagnostics.record("gemini_event_after_close", event.javaClass.simpleName)
        return true
    }

    private fun isToolHandleActive(callId: String, handle: ToolJobHandle): Boolean = synchronized(toolJobsLock) {
        !closed && !closing && callId !in cancelledToolCallIds && toolJobs[callId] === handle
    }

    private fun registerToolHandle(callId: String, handle: ToolJobHandle): Boolean {
        synchronized(toolCallLock(callId)) {
            val currentHandle = synchronized(toolJobsLock) {
                if (closed || closing || callId in cancelledToolCallIds) return false
                toolJobs[callId]
            }
            if (currentHandle != null) {
                synchronized(currentHandle.sendLock) {
                    synchronized(toolJobsLock) {
                        if (closed || closing || callId in cancelledToolCallIds) return false
                        if (toolJobs[callId] === currentHandle) {
                            toolJobs[callId] = handle
                            updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId))
                            currentHandle.job.cancel()
                            return true
                        }
                    }
                }
            }
            synchronized(toolJobsLock) {
                if (closed || closing || callId in cancelledToolCallIds) return false
                if (toolJobs[callId] !== currentHandle) {
                    return false
                }
                toolJobs[callId] = handle
                updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId))
                return true
            }
        }
    }

    private fun handleToolCallCancellation(event: GeminiLiveEvent.ToolCallCancellation) {
        diagnostics.record(
            name = "tool_call_cancellation",
            detail = event.callIds.joinToString(","),
        )
        val jobsToCancel = event.callIds.mapNotNull(::cancelToolCall)
        removeToolStatuses(event.callIds)
        jobsToCancel.forEach { it.cancel() }
    }

    private fun cancelToolCall(callId: String): Job? {
        synchronized(toolCallLock(callId)) {
            val handle = synchronized(toolJobsLock) {
                toolJobs[callId] ?: run {
                    cancelledToolCallIds += callId
                    return null
                }
            }
            return synchronized(handle.sendLock) {
                synchronized(toolJobsLock) {
                    cancelledToolCallIds += callId
                    if (toolJobs[callId] === handle) {
                        toolJobs.remove(callId)?.job
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun toolCallLock(callId: String): Any = synchronized(toolJobsLock) {
        toolCallLocks.getOrPut(callId) { Any() }
    }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        _state.update { current ->
            val toolCalls = current.toolCalls + (callId to status)
            current.copy(
                tool = summarizeToolStatus(toolCalls, status),
                toolCalls = toolCalls,
            )
        }
    }

    private fun removeToolStatuses(callIds: Collection<String>) {
        _state.update { current ->
            val toolCalls = current.toolCalls - callIds.toSet()
            current.copy(
                tool = summarizeToolStatus(
                    toolCalls = toolCalls,
                    fallback = toolCalls.values.lastOrNull() ?: VoiceToolStatus.Idle,
                ),
                toolCalls = toolCalls,
            )
        }
    }

    private fun summarizeToolStatus(
        toolCalls: Map<String, VoiceToolStatus>,
        fallback: VoiceToolStatus,
    ): VoiceToolStatus {
        return when (fallback) {
            is VoiceToolStatus.CallingHermes -> fallback
            else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
                ?: fallback
        }
    }

    private fun recordUnsupportedToolCall(call: GeminiLiveEvent.UnsupportedToolCall) {
        diagnostics.record("unsupported_tool_call", "callId=${call.callId}, name=${call.name}")
    }

    private companion object {
        const val ASK_HERMES_TOOL = "ask_hermes"
    }

    private class ToolJobHandle(
        val sendLock: Any = Any(),
    ) {
        lateinit var job: Job
    }
}

class VoiceAgentViewModel : ViewModel() {
    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()
}
