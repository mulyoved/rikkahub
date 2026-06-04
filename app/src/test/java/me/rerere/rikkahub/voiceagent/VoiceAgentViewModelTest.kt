package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentViewModelTest {
    @Test
    fun `batched Hermes tool call continues after interruption and sends response to Gemini`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(GeminiLiveEvent.ToolCall(callId = "call-1", name = "ask_hermes", prompt = "Look this up"))
            )
        )

        assertEquals(VoiceToolStatus.CallingHermes("call-1"), coordinator.state.value.tool)
        assertEquals("call-1" to "Look this up", toolApi.awaitRequest())

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        assertEquals(VoiceToolStatus.CallingHermes("call-1"), coordinator.state.value.tool)

        toolApi.complete(response(callId = "call-1", answer = "Hermes answer"))
        coordinator.awaitToolJobs()

        assertEquals(listOf("call-1" to "Hermes answer"), gemini.toolResponses)
        assertEquals(VoiceToolStatus.HermesAnswered(callId = "call-1", elapsedMs = 0L), coordinator.state.value.tool)
    }

    @Test
    fun `unsupported batched tool is ignored without calling Hermes or sending response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(GeminiLiveEvent.ToolCall(callId = "call-2", name = "unsupported_tool", prompt = "ignored"))
            )
        )
        coordinator.awaitToolJobs()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertTrue(diagnostics.events.value.any { it.name == "unsupported_tool_call" && it.detail.contains("call-2") })
    }

    @Test
    fun `Hermes failure updates failed tool status and does not crash`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-3", name = "ask_hermes", prompt = "fail"))
        assertEquals("call-3" to "fail", toolApi.awaitRequest())

        toolApi.fail(IllegalStateException("Hermes offline"))
        coordinator.awaitToolJobs()

        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-3", message = "Hermes offline"),
            coordinator.state.value.tool,
        )
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any { it.name == "hermes_tool_failed" && it.detail.contains("Hermes offline") }
        )
    }

    @Test
    fun `transcripts and output audio update state and playback`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hello "))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("world"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("answer "))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("text"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("base64-pcm"))

        assertEquals("hello world", coordinator.state.value.inputTranscript)
        assertEquals("answer text", coordinator.state.value.outputTranscript)
        assertEquals(VoiceAudioStatus.AssistantSpeaking, coordinator.state.value.audio)
        assertEquals(listOf("base64-pcm"), audio.playedPcm16)
    }

    private fun response(callId: String, answer: String): MobileHermesResponse = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile",
        profileLabel = "Profile",
    )

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
        val toolResponses = mutableListOf<Pair<String, String>>()

        override suspend fun connect(
            token: String,
            websocketUrl: String,
            providerModel: String,
            liveConnectConfig: JsonObject,
            systemInstruction: String,
            contextTurns: List<GeminiContentTurn>,
            onEvent: (GeminiLiveEvent) -> Unit,
        ) = Unit

        override fun sendAudio(base64Pcm16: String) = Unit

        override fun sendToolResponse(callId: String, answer: String) {
            toolResponses += callId to answer
        }

        override fun close() = Unit
    }

    private class FakeVoiceToolApi : VoiceToolApi {
        val requests = mutableListOf<Pair<String, String>>()
        private val nextRequest = CompletableDeferred<Pair<String, String>>()
        private val nextResult = CompletableDeferred<MobileHermesResponse>()

        override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse {
            requests += callId to prompt
            nextRequest.complete(callId to prompt)
            return nextResult.await()
        }

        suspend fun awaitRequest(): Pair<String, String> = nextRequest.await()

        fun complete(response: MobileHermesResponse) {
            nextResult.complete(response)
        }

        fun fail(error: Throwable) {
            nextResult.completeExceptionally(error)
        }
    }

    private class FakeVoiceAudioEngine : VoiceAudioEngine {
        val playedPcm16 = mutableListOf<String>()
        var suppressPlaybackCalls = 0

        override fun startCapture(onPcm16: (ByteArray) -> Unit) = Unit

        override fun stopCapture() = Unit

        override fun playPcm16(base64Pcm16: String) {
            playedPcm16 += base64Pcm16
        }

        override fun suppressPlayback() {
            suppressPlaybackCalls += 1
        }

        override fun release() = Unit
    }
}
