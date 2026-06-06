package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import java.util.Base64

class VoiceAgentCallSession(
    private val modelId: String,
    private val sessionApi: VoiceSessionApi,
    private val toolApi: VoiceToolApi,
    private val gemini: GeminiLiveVoiceClient,
    private val audio: VoiceAudioEngine,
    private val conversationStore: VoiceConversationStore,
    private val contextProvider: VoiceAgentContextProvider,
    diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    private val scope: CoroutineScope,
) : ManagedVoiceCallSession {
    private val coordinator = VoiceAgentCoordinator(
        gemini = gemini,
        toolApi = toolApi,
        audio = audio,
        diagnostics = diagnostics,
        conversationStore = conversationStore,
        scope = scope,
    )
    private var startJob: Job? = null
    private var muted = false
    private var sessionId = 0L
    private var ended = false

    override val state: StateFlow<VoiceAgentUiState> = coordinator.state
    private val conversation = conversationStore.conversation

    override fun start() {
        if (ended || startJob?.isActive == true) return
        val currentSessionId = coordinator.nextSessionId()
        sessionId = currentSessionId
        val job = scope.launch {
            runSession(currentSessionId)
        }
        startJob = job
    }

    private suspend fun runSession(currentSessionId: Long) {
        val sessionJob = currentCoroutineContext()[Job]
        startJob = sessionJob
        var geminiStarted = false
        try {
            coordinator.updateSessionStatus(VoiceSessionStatus.PreparingContext)
            val voiceContext = contextProvider.build(conversation.value).withTurnsFoldedIntoSystemInstruction()
            coordinator.recordDiagnostic(
                name = "voice_context_prepared",
                detail = "turns=${voiceContext.turns.size}, systemInstructionChars=${voiceContext.systemInstruction.length}",
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.RequestingToken)
            val session = sessionApi.createSession(modelId = modelId)
            coordinator.recordDiagnostic(
                name = "voice_session_created",
                detail = "modelId=${session.modelId}, providerModel=${session.providerModel}, " +
                    "inputSampleRate=${session.inputSampleRate}, outputSampleRate=${session.outputSampleRate}",
            )
            ensureActiveSession(currentSessionId)
            coordinator.updateSessionStatus(VoiceSessionStatus.ConnectingGemini)
            geminiStarted = true
            gemini.connect(
                token = session.token,
                websocketUrl = session.websocketUrl,
                providerModel = session.providerModel,
                liveConnectConfig = session.liveConnectConfig,
                systemInstruction = voiceContext.systemInstruction,
                contextTurns = voiceContext.turns,
                onEvent = { event -> handleGeminiEvent(currentSessionId, event) },
            )
            ensureActiveSession(currentSessionId)
            if (coordinator.state.value.session is VoiceSessionStatus.Error) {
                cleanupFailedStartup(currentSessionId, closeGemini = true)
                return
            }
            coordinator.updateSessionStatus(VoiceSessionStatus.Connected)
            gemini.activateOutboundSession(currentSessionId)
            audio.activatePlaybackSession(currentSessionId)
            if (!muted) {
                startCapture(currentSessionId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (coordinator.isActiveSession(currentSessionId)) {
                cleanupFailedStartup(currentSessionId, closeGemini = geminiStarted)
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

    private fun handleGeminiEvent(sessionId: Long, event: GeminiLiveEvent) {
        coordinator.onGeminiEvent(sessionId, event)
        when (event) {
            is GeminiLiveEvent.Error,
            is GeminiLiveEvent.WebSocketClosed,
            is GeminiLiveEvent.WebSocketFailure,
                -> cleanupFailedStartup(sessionId, closeGemini = true)
            else -> Unit
        }
    }

    private fun cleanupFailedStartup(sessionId: Long, closeGemini: Boolean) {
        if (!coordinator.isActiveSession(sessionId)) return
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        if (closeGemini) {
            gemini.close()
        }
    }

    private suspend fun ensureActiveSession(sessionId: Long) {
        currentCoroutineContext().ensureActive()
        check(coordinator.isActiveSession(sessionId)) { "Voice Agent session is stale" }
    }

    override fun interrupt() {
        if (!ended) {
            coordinator.suppressPlayback()
        }
    }

    override fun setMuted(value: Boolean) {
        if (ended || muted == value) return
        muted = value
        if (muted) {
            gemini.sendAudioStreamEnd(sessionId)
            audio.stopCapture()
            coordinator.updateAudioStatus(VoiceAudioStatus.Muted)
        } else if (state.value.session == VoiceSessionStatus.Connected) {
            startCapture(sessionId)
        }
    }

    override fun reconnect() {
        if (ended) return
        val previousJob = startJob
        coordinator.prepareForReconnect()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        val reconnectJob = scope.launch {
            previousJob?.cancelAndJoin()
            if (ended) return@launch
            coordinator.updateSessionStatus(VoiceSessionStatus.Reconnecting)
            val currentSessionId = coordinator.nextSessionId()
            sessionId = currentSessionId
            runSession(currentSessionId)
        }
        startJob = reconnectJob
    }

    override fun end() {
        endWithVisibleReason(visibleReason = null)
    }

    fun recordDiagnostic(name: String, detail: String) {
        coordinator.recordDiagnostic(name = name, detail = detail)
    }

    private fun endWithVisibleReason(visibleReason: String?) {
        if (ended) return
        ended = true
        val previousJob = startJob
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        gemini.close()
        scope.launch {
            previousJob?.cancelAndJoin()
            coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
            coordinator.close()
            visibleReason?.let(coordinator::setVisibleError)
            coordinator.launchPersistenceDrain()
        }
    }

    override fun closeNow() {
        if (!ended) {
            ended = true
        }
        startJob?.cancel()
        coordinator.prepareForSessionEnd()
        invalidateAudioSessions()
        audio.stopCapture()
        audio.suppressPlayback()
        coordinator.updateSessionStatus(VoiceSessionStatus.Ending)
        coordinator.close(waitForStartedSends = false)
        coordinator.launchPersistenceDrain()
    }

    private fun startCapture(currentSessionId: Long) {
        audio.startCapture { pcm16 ->
            if (!coordinator.isActiveSession(currentSessionId)) {
                return@startCapture
            }
            val sent = gemini.sendAudio(
                base64Pcm16 = Base64.getEncoder().encodeToString(pcm16),
                sessionId = currentSessionId,
            )
            if (sent && coordinator.isActiveSession(currentSessionId)) {
                coordinator.updateAudioStatus(VoiceAudioStatus.UserSpeaking)
            }
        }
        coordinator.updateAudioStatus(VoiceAudioStatus.Listening)
    }

    private fun invalidateAudioSessions() {
        gemini.invalidateOutboundSession()
        audio.invalidatePlaybackSession()
    }

}

private fun VoiceContext.withTurnsFoldedIntoSystemInstruction(): VoiceContext {
    if (turns.isEmpty()) return this

    val previousContext = turns.joinToString(separator = "\n\n") { turn ->
        "${turn.voiceContextLabel()}: ${turn.text}"
    }
    return copy(
        systemInstruction = listOf(
            systemInstruction,
            "Previous RikkaHub conversation context:\n$previousContext",
        )
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n"),
        turns = emptyList(),
    )
}

private fun GeminiContentTurn.voiceContextLabel(): String =
    when (role) {
        "model" -> "Assistant"
        else -> "User"
    }
