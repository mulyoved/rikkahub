package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.RecordingVoiceObservability
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartVoiceAgentCallSessionTest {
    @Test
    fun `mute wins against suspended initial capture completion`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val observability = RecordingVoiceObservability()
        val suspended = audio.suspendNextStartCapture()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            observability = observability,
            scope = this,
        )

        session.start()
        gemini.awaitConnect()
        awaitCaptureSignal(suspended.entered)
        session.setMuted(true)

        suspended.release.complete(Unit)
        awaitCaptureSignal(suspended.installed)
        audio.emitCapture(byteArrayOf(1, 2, 3))
        audio.completeDebugInjection()
        yield()

        assertTrue(gemini.audioMessages.isEmpty())
        assertEquals(VoiceAudioStatus.Muted, session.state.value.audio)
        assertEquals(2, audio.stopCaptureCalls)
        assertFalse(
            observability.events.any { it.name == "hermes_voice.mobile.audio.capture_started" }
        )
        session.closeNow()
    }

    @Test
    fun `mute cancels suspended unmute capture before callback installation`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.setMuted(true)

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertEquals(1, fixture.audio.startCaptureCalls)
        assertTrue(fixture.audio.stopCaptureCalls >= 1)
        fixture.session.closeNow()
    }

    @Test
    fun `manual reconnect cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.reconnect()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
        fixture.session.closeNow()
    }

    @Test
    fun `end cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.end()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.audio.releaseCalls < 1) delay(10)
        }
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
    }

    @Test
    fun `close now cancels suspended unmute capture before cleanup continues`() = runTest {
        val fixture = connectedMutedSession()
        val suspended = fixture.audio.suspendNextStartCapture()

        fixture.session.setMuted(false)
        awaitCaptureSignal(suspended.entered)
        fixture.session.closeNow()

        awaitCaptureSignal(suspended.cancelled)
        suspended.release.complete(Unit)
        delay(10)
        assertFalse(suspended.installed.isCompleted)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
    }

    @Test
    fun `unmute capture failure is handled without an uncaught supervisor child failure`() = runTest {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val sessionScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val observability = RecordingVoiceObservability()
        val diagnostics = VoiceDiagnostics()
        val fixture = connectedMutedSession(
            sessionScope = sessionScope,
            observability = observability,
            diagnostics = diagnostics,
        )
        fixture.audio.startCaptureError = IllegalStateException("microphone revoked")

        fixture.session.setMuted(false)

        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.session.state.value.session !is VoiceSessionStatus.Error) delay(10)
        }
        assertTrue(uncaught.isEmpty())
        assertEquals(VoiceSessionStatus.Error("microphone revoked"), fixture.session.state.value.session)
        assertFalse(fixture.session.hasOwnedCaptureStartJob())
        assertEquals(1, fixture.audio.startCaptureCalls)
        assertTrue(fixture.audio.stopCaptureCalls >= 2)
        assertTrue(fixture.audio.suppressPlaybackCalls >= 1)
        assertTrue(fixture.gemini.closeCalls >= 1)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "audio_capture_failure" && it.detail.contains("microphone revoked")
            }
        )
        assertEquals(
            listOf("audio_capture_failure" to "audio_capture_failure"),
            observability.events
                .filter { it.name == "hermes_voice.mobile.session.failed" }
                .map {
                    it.attributes["session.end_reason"] to it.attributes["session.failure.kind"]
                },
        )
        sessionScope.cancel()
    }

    @Test
    fun `unmute capture failure runs later cleanup stages and records combined failures`() = runTest {
        val captureFailure = IllegalStateException("microphone revoked")
        val stopFailure = IllegalArgumentException("capture stop failed")
        val suppressFailure = UnsupportedOperationException("playback suppression failed")
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val sessionScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val diagnostics = VoiceDiagnostics()
        val fixture = connectedMutedSession(
            sessionScope = sessionScope,
            diagnostics = diagnostics,
        )
        val cleanupEvents = mutableListOf<String>()
        fixture.audio.apply {
            startCaptureError = captureFailure
            stopCaptureError = stopFailure
            suppressPlaybackError = suppressFailure
            onStopCapture = { cleanupEvents += "audio.stopCapture" }
            onSuppressPlayback = { cleanupEvents += "audio.suppressPlayback" }
        }
        fixture.gemini.onClose = { cleanupEvents += "gemini.close" }

        fixture.session.setMuted(false)

        withTimeout(TEST_TIMEOUT_MS) {
            while (fixture.session.state.value.session !is VoiceSessionStatus.Error) delay(10)
        }
        assertTrue(uncaught.isEmpty())
        assertEquals(VoiceSessionStatus.Error("microphone revoked"), fixture.session.state.value.session)
        assertEquals(
            listOf("audio.stopCapture", "audio.suppressPlayback", "gemini.close"),
            cleanupEvents,
        )
        val failureDiagnostic = diagnostics.events.value.last {
            it.name == VoiceSessionStopReason.AudioCaptureFailure.diagnosticReason
        }
        assertTrue(failureDiagnostic.detail.contains("microphone revoked"))
        assertTrue(failureDiagnostic.detail.contains("capture stop failed"))
        assertTrue(failureDiagnostic.detail.contains("playback suppression failed"))
        sessionScope.cancel()
    }

    private suspend fun CoroutineScope.connectedMutedSession(
        sessionScope: CoroutineScope = this,
        observability: RecordingVoiceObservability = RecordingVoiceObservability(),
        diagnostics: VoiceDiagnostics = VoiceDiagnostics(),
    ): CaptureCancellationFixture {
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val session = VoiceAgentCallSession(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            diagnostics = diagnostics,
            observability = observability,
            scope = sessionScope,
        )
        session.setMuted(true)
        session.start()
        gemini.awaitConnect()
        withTimeout(TEST_TIMEOUT_MS) {
            while (session.state.value.session != VoiceSessionStatus.Connected) delay(10)
        }
        return CaptureCancellationFixture(session = session, gemini = gemini, audio = audio)
    }

    private suspend fun awaitCaptureSignal(signal: Deferred<Unit>) {
        withTimeout(TEST_TIMEOUT_MS) { signal.await() }
    }

    private fun VoiceAgentCallSession.hasOwnedCaptureStartJob(): Boolean {
        val controller = VoiceAgentCallSession::class.java
            .getDeclaredField("captureStartController")
            .also { it.isAccessible = true }
            .get(this) as VoiceCaptureStartController
        return controller.hasOwnedJob()
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private data class CaptureCancellationFixture(
        val session: VoiceAgentCallSession,
        val gemini: FakeGeminiLiveVoiceClient,
        val audio: FakeVoiceAudioEngine,
    )

    private companion object {
        const val TEST_TIMEOUT_MS = 500L
    }
}
