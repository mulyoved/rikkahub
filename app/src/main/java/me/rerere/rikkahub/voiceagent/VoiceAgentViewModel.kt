package me.rerere.rikkahub.voiceagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilder
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import me.rerere.rikkahub.voiceagent.voicelab.VoiceLabMobileApi
import java.util.Base64
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

interface VoiceSessionApi {
    suspend fun createSession(modelId: String): MobileVoiceSessionResponse
}

class VoiceLabVoiceSessionApi(
    private val api: VoiceLabMobileApi,
) : VoiceSessionApi {
    override suspend fun createSession(modelId: String): MobileVoiceSessionResponse =
        api.createSession(modelId = modelId)
}

interface VoiceToolApi {
    suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse
}

class VoiceLabHermesToolApi(
    private val api: VoiceLabMobileApi,
    private val profileId: String? = null,
) : VoiceToolApi {
    override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse =
        api.askHermes(callId = callId, prompt = prompt, profileId = profileId)
}

interface VoiceConversationStore {
    val conversation: StateFlow<Conversation>
    suspend fun update(transform: (Conversation) -> Conversation)
}

class ChatServiceVoiceConversationStore(
    private val conversationId: Uuid,
    private val chatService: ChatService,
) : VoiceConversationStore {
    override val conversation: StateFlow<Conversation> = chatService.getConversationFlow(conversationId)

    override suspend fun update(transform: (Conversation) -> Conversation) {
        val updatedConversation = transform(conversation.value)
        chatService.saveConversation(conversationId = conversationId, conversation = updatedConversation)
    }
}

interface VoiceAgentContextProvider {
    fun build(conversation: Conversation): VoiceContext
}

class SettingsVoiceAgentContextProvider(
    private val settingsStore: SettingsStore,
    private val contextBuilder: VoiceContextBuilder = VoiceContextBuilder(),
) : VoiceAgentContextProvider {
    override fun build(conversation: Conversation): VoiceContext {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
        return contextBuilder.build(
            assistantName = assistant?.name?.takeIf { it.isNotBlank() } ?: "RikkaHub",
            assistantPrompt = conversation.customSystemPrompt
                ?: assistant?.systemPrompt
                ?: "",
            conversation = conversation,
        )
    }
}

class VoiceAgentCoordinator(
    private val gemini: GeminiLiveVoiceClient,
    private val toolApi: VoiceToolApi,
    private val audio: VoiceAudioEngine,
    private val diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val conversationStore: VoiceConversationStore? = null,
    private val persister: VoiceConversationPersister = VoiceConversationPersister(),
    scope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val ownsScope = scope == null
    private val coordinatorScope = scope ?: CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.Default))
    private val toolLaunchContext = dispatcher ?: EmptyCoroutineContext
    private val closeLock = Any()
    private val eventLock = Any()
    private val playbackSuppressionLock = Any()
    private val toolJobsLock = Any()
    private val persistenceJobsLock = Any()
    private val persistenceLock = Mutex()
    private val persistenceScope = CoroutineScope(SupervisorJob() + (dispatcher ?: Dispatchers.IO))
    private val persistenceJobs = mutableSetOf<Job>()
    private var lastPersistenceJob: Job? = null
    private var activeSessionId = 0L
    private val toolJobs = mutableMapOf<String, ToolJobHandle>()
    private val toolCallLocks = mutableMapOf<String, Any>()
    private val cancelledToolCallIds = mutableSetOf<String>()
    private var closing = false
    private var closed = false
    private var outputAudioSuppressed = false
    private var activeTranscriptSpeaker: TranscriptSpeaker? = null
    private var inputTurnTranscript = ""
    private var outputTurnTranscript = ""
    private var transcriptTurnSequence = 0L
    private var inputTurnId = ""
    private var outputTurnId = ""

    private val _state = MutableStateFlow(VoiceAgentUiState())
    val state: StateFlow<VoiceAgentUiState> = _state.asStateFlow()

    fun updateSessionStatus(status: VoiceSessionStatus) {
        _state.update { it.copy(session = status, error = (status as? VoiceSessionStatus.Error)?.message) }
    }

    fun updateAudioStatus(status: VoiceAudioStatus) {
        _state.update { it.copy(audio = status) }
    }

    fun nextSessionId(): Long = synchronized(toolJobsLock) {
        activeSessionId += 1
        activeSessionId
    }

    fun onGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        if (!isActiveSession(sessionId)) {
            diagnostics.record("stale_gemini_event", event.javaClass.simpleName)
            return
        }
        onGeminiEvent(event)
    }

    fun suppressPlayback() {
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = true
            if (outputTurnTranscript.isNotBlank()) {
                persistAssistantTranscript()
            }
        }
        audio.suppressPlayback()
        _state.update { it.reduce(VoiceAgentEvent.UserInterrupted) }
    }

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

    suspend fun awaitPersistenceJobs() {
        while (true) {
            val jobs = synchronized(persistenceJobsLock) {
                if (persistenceJobs.isEmpty()) {
                    return
                }
                persistenceJobs.toList()
            }
            jobs.joinAll()
        }
    }

    suspend fun closeAndDrain() {
        close()
        awaitPersistenceJobs()
        persistenceScope.cancel()
    }

    fun stopPersistenceScope() {
        persistenceScope.cancel()
    }

    fun launchPersistenceDrain() {
        persistenceScope.launch {
            awaitPersistenceJobs()
            stopPersistenceScope()
        }
    }

    fun close() {
        synchronized(closeLock) {
            synchronized(toolJobsLock) {
                if (closed || closing) return
                closing = true
            }
            synchronized(eventLock) {
                // Wait for any in-flight non-tool event to finish before resources are released.
            }
            val handles = synchronized(toolJobsLock) {
                toolJobs.values.toList()
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
                    session = VoiceSessionStatus.Ended,
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

    fun prepareForReconnect() {
        nextSessionId()
        val handles = synchronized(toolJobsLock) {
            toolJobs.values.toList()
        }
        val jobs = handles.map { handle ->
            synchronized(handle.sendLock) {
                handle.job
            }
        }
        synchronized(toolJobsLock) {
            toolJobs.clear()
            toolCallLocks.clear()
            cancelledToolCallIds.clear()
        }
        jobs.forEach { it.cancel() }
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = false
        }
        _state.update {
            it.copy(
                tool = VoiceToolStatus.Idle,
                toolCalls = emptyMap(),
            )
        }
    }

    private fun appendInputTranscript(text: String) {
        if (activeTranscriptSpeaker != TranscriptSpeaker.User) {
            inputTurnTranscript = ""
            inputTurnId = nextTranscriptTurnId(TranscriptSpeaker.User)
        }
        activeTranscriptSpeaker = TranscriptSpeaker.User
        inputTurnTranscript += text
        synchronized(playbackSuppressionLock) {
            outputAudioSuppressed = false
        }
        _state.update { it.copy(inputTranscript = it.inputTranscript + text) }
        val transcript = inputTurnTranscript
        val turnId = inputTurnId
        persistConversation { conversation ->
            persister.upsertUserTranscriptTurn(conversation = conversation, text = transcript, turnId = turnId)
        }
    }

    private fun appendOutputTranscript(text: String) {
        if (activeTranscriptSpeaker != TranscriptSpeaker.Assistant) {
            outputTurnTranscript = ""
            outputTurnId = nextTranscriptTurnId(TranscriptSpeaker.Assistant)
        }
        activeTranscriptSpeaker = TranscriptSpeaker.Assistant
        outputTurnTranscript += text
        _state.update { it.copy(outputTranscript = it.outputTranscript + text) }
        persistAssistantTranscript()
    }

    private fun playOutputAudio(base64Pcm16: String) {
        if (synchronized(playbackSuppressionLock) { outputAudioSuppressed }) {
            diagnostics.record("output_audio_suppressed_after_interruption")
            return
        }
        audio.playPcm16(base64Pcm16)
        if (synchronized(playbackSuppressionLock) { outputAudioSuppressed }) {
            diagnostics.record("output_audio_state_suppressed_after_interruption")
            return
        }
        _state.update { it.copy(audio = VoiceAudioStatus.AssistantSpeaking) }
    }

    private fun handleInterrupted(event: GeminiLiveEvent.Interrupted) {
        diagnostics.record("gemini_interrupted", event.reason)
        suppressPlayback()
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
        persistToolStatus(callId = call.callId, prompt = call.prompt, status = VoiceToolRecordStatus.Pending)
        job.invokeOnCompletion {
            synchronized(toolJobsLock) {
                if (toolJobs[call.callId] === handle && !handle.superseded) {
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
                synchronized(toolJobsLock) {
                    if (!isToolHandleActive(callId, handle)) return
                    handle.sendStarted = true
                }
                val sent = gemini.sendToolResponse(callId, response.answer)
                if (!isToolHandleActive(callId, handle)) return
                if (sent) {
                    diagnostics.record("hermes_tool_succeeded", "callId=$callId")
                    persistToolStatus(
                        callId = callId,
                        prompt = prompt,
                        status = VoiceToolRecordStatus.Complete(response.answer),
                    )
                    updateToolStatus(callId, VoiceToolStatus.HermesAnswered(callId = callId, elapsedMs = 0L))
                } else {
                    val message = "Failed to send Gemini tool response message"
                    diagnostics.record("hermes_tool_failed", "callId=$callId, message=$message")
                    persistToolStatus(
                        callId = callId,
                        prompt = prompt,
                        status = VoiceToolRecordStatus.Failed(message),
                    )
                    updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            synchronized(handle.sendLock) {
                if (!isToolHandleActive(callId, handle)) return
                diagnostics.record("hermes_tool_failed", "callId=$callId, message=$message")
                persistToolStatus(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Failed(message),
                )
                updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = message))
            }
        }
    }

    private fun isClosed(): Boolean = synchronized(toolJobsLock) { closed || closing }

    fun isActiveSession(sessionId: Long): Boolean = synchronized(toolJobsLock) {
        !closed && !closing && activeSessionId == sessionId
    }

    private fun shouldIgnoreEventAfterClose(event: GeminiLiveEvent): Boolean {
        if (!isClosed()) return false
        diagnostics.record("gemini_event_after_close", event.javaClass.simpleName)
        return true
    }

    private fun isToolHandleActive(callId: String, handle: ToolJobHandle): Boolean = synchronized(toolJobsLock) {
        !closed && !closing && !handle.superseded && callId !in cancelledToolCallIds && toolJobs[callId] === handle
    }

    private fun registerToolHandle(callId: String, handle: ToolJobHandle): Boolean {
        synchronized(toolCallLock(callId)) {
            val currentHandle = synchronized(toolJobsLock) {
                if (closed || closing || callId in cancelledToolCallIds) return false
                toolJobs[callId]
            }
            if (currentHandle != null) {
                val currentSendStarted = synchronized(toolJobsLock) {
                    if (closed || closing || callId in cancelledToolCallIds) return false
                    if (toolJobs[callId] !== currentHandle) return false
                    currentHandle.sendStarted
                }
                if (currentSendStarted) {
                    diagnostics.record("duplicate_tool_call_after_send_started", "callId=$callId")
                    return false
                }
                synchronized(toolJobsLock) {
                    if (closed || closing || callId in cancelledToolCallIds) return false
                    if (toolJobs[callId] !== currentHandle) return false
                    currentHandle.superseded = true
                }
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

    private fun persistToolStatus(callId: String, prompt: String, status: VoiceToolRecordStatus) {
        persistConversation { conversation ->
            persister.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = status,
            )
        }
    }

    private fun persistAssistantTranscript() {
        val transcript = outputTurnTranscript
        val turnId = outputTurnId
        val interrupted = synchronized(playbackSuppressionLock) { outputAudioSuppressed }
        persistConversation { conversation ->
            persister.upsertAssistantTranscriptTurn(
                conversation = conversation,
                text = transcript,
                interrupted = interrupted,
                turnId = turnId,
            )
        }
    }

    private fun nextTranscriptTurnId(speaker: TranscriptSpeaker): String {
        transcriptTurnSequence += 1
        return "${speaker.name.lowercase()}-$transcriptTurnSequence"
    }

    private fun persistConversation(transform: (Conversation) -> Conversation) {
        val store = conversationStore ?: return
        lateinit var job: Job
        synchronized(persistenceJobsLock) {
            val previousJob = lastPersistenceJob
            job = persistenceScope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                persistConversationNow(store = store, transform = transform)
            }
            persistenceJobs += job
            lastPersistenceJob = job
        }
        job.invokeOnCompletion {
            synchronized(persistenceJobsLock) {
                persistenceJobs -= job
                if (lastPersistenceJob === job) {
                    lastPersistenceJob = null
                }
            }
        }
        job.start()
    }

    private suspend fun persistConversationNow(
        store: VoiceConversationStore,
        transform: (Conversation) -> Conversation,
    ) {
        runCatching {
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saving) }
            persistenceLock.withLock {
                store.update(transform)
            }
        }.onSuccess {
            _state.update { it.copy(persistence = VoicePersistenceStatus.Saved) }
        }.onFailure { error ->
            val message = error.message ?: error.javaClass.simpleName
            diagnostics.record("conversation_persist_failed", message)
            _state.update { it.copy(persistence = VoicePersistenceStatus.SaveFailed(message)) }
        }
    }

    private companion object {
        const val ASK_HERMES_TOOL = "ask_hermes"
    }

    private enum class TranscriptSpeaker {
        User,
        Assistant,
    }

    private class ToolJobHandle(
        val sendLock: Any = Any(),
    ) {
        lateinit var job: Job
        var superseded: Boolean = false
        var sendStarted: Boolean = false
    }
}

class VoiceAgentViewModel(
    private val modelId: String,
    private val sessionApi: VoiceSessionApi,
    private val toolApi: VoiceToolApi,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    conversationStore: VoiceConversationStore,
    contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val lifecycleScope = scope ?: viewModelScope
    private val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = audio,
        diagnostics = diagnostics,
        conversationStore = conversationStore,
        scope = lifecycleScope,
    )
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false

    val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation
    private val contextProvider = contextProvider

    fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        val job = lifecycleScope.launch {
            runSession(currentSessionId)
        }
        startJob = job
    }

    private suspend fun runSession(currentSessionId: Long) {
        val sessionJob = currentCoroutineContext()[Job]
        startJob = sessionJob
        try {
            coordinator.updateSessionStatus(VoiceSessionStatus.PreparingContext)
            val voiceContext = contextProvider.build(conversation.value)
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.RequestingToken)
            val session = sessionApi.createSession(modelId = modelId)
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.ConnectingGemini)
            gemini.connect(
                token = session.token,
                websocketUrl = session.websocketUrl,
                providerModel = session.providerModel,
                liveConnectConfig = session.liveConnectConfig,
                systemInstruction = voiceContext.systemInstruction,
                contextTurns = voiceContext.turns,
                onEvent = { event -> coordinator.onGeminiEvent(currentSessionId, event) },
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            if (!muted) {
                startCapture()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (coordinator.isActiveSession(currentSessionId)) {
                coordinator.updateSessionStatus(
                    VoiceSessionStatus.Error(error.message ?: error.javaClass.simpleName)
                )
            }
        } finally {
            if (startJob === sessionJob) {
                startJob = null
            }
        }
    }

    private suspend fun ensureActiveSession(sessionId: Long) {
        currentCoroutineContext().ensureActive()
        check(coordinator.isActiveSession(sessionId)) { "Voice Agent session is stale" }
    }

    fun interrupt() {
        if (!ended) {
            coordinator.suppressPlayback()
        }
    }

    fun setMuted(value: Boolean) {
        if (ended || muted == value) return
        muted = value
        if (muted) {
            audio.stopCapture()
            coordinator.updateAudioStatus(VoiceAudioStatus.Muted)
        } else if (state.value.session == VoiceSessionStatus.Connected) {
            startCapture()
        }
    }

    fun reconnect() {
        if (ended) return
        val previousJob = startJob
        val reconnectJob = lifecycleScope.launch {
            previousJob?.cancelAndJoin()
            if (ended) return@launch
            audio.stopCapture()
            coordinator.prepareForReconnect()
            gemini.close()
            coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
            val currentSessionId = coordinator.nextSessionId()
            sessionId = currentSessionId
            runSession(currentSessionId)
        }
        startJob = reconnectJob
    }

    fun end() {
        if (ended) return
        ended = true
        val previousJob = startJob
        lifecycleScope.launch {
            previousJob?.cancelAndJoin()
            coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
            coordinator.close()
            coordinator.launchPersistenceDrain()
        }
    }

    private fun startCapture() {
        audio.startCapture { pcm16 ->
            gemini.sendAudio(Base64.getEncoder().encodeToString(pcm16))
            coordinator.updateAudioStatus(VoiceAudioStatus.UserSpeaking)
        }
        coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
    }

    override fun onCleared() {
        end()
        super.onCleared()
    }
}
