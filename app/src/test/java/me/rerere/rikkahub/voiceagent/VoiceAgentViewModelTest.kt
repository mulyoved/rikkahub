package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveCodec
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
        assertEquals("call-1" to "Look this up", toolApi.awaitRequest("call-1"))

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
    fun `ignored unsupported tool call from codec records diagnostic without Hermes response`() = runTest {
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
        val rawUnsupportedToolCall = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-ignored",
                    "name":"unsupported_tool",
                    "args":{"prompt":"Do not use this prompt"}
                  }
                ]
              }
            }
        """.trimIndent()
        val event = GeminiLiveCodec().parseServerMessage(rawUnsupportedToolCall)
        assertEquals(GeminiLiveEvent.Ignored(rawUnsupportedToolCall), event)

        coordinator.onGeminiEvent(event)
        coordinator.awaitToolJobs()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "unsupported_tool_call" &&
                    it.detail.contains("call-ignored") &&
                    it.detail.contains("unsupported_tool")
            }
        )
    }

    @Test
    fun `mixed tool call from codec sends Hermes call and records unsupported diagnostic`() = runTest {
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
        val rawMixedToolCall = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-unsupported",
                    "name":"unsupported_tool",
                    "args":{"prompt":"Do not use this prompt"}
                  },
                  {
                    "id":"call-supported",
                    "name":"ask_hermes",
                    "args":{"prompt":"Use this prompt"}
                  }
                ]
              }
            }
        """.trimIndent()
        val event = GeminiLiveCodec().parseServerMessage(rawMixedToolCall)
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = listOf(
                    GeminiLiveEvent.ToolCall(
                        callId = "call-supported",
                        name = "ask_hermes",
                        prompt = "Use this prompt",
                    )
                ),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-unsupported",
                        name = "unsupported_tool",
                    )
                ),
            ),
            event,
        )

        coordinator.onGeminiEvent(event)
        assertEquals("call-supported" to "Use this prompt", toolApi.awaitRequest("call-supported"))
        toolApi.complete(response(callId = "call-supported", answer = "Supported answer"))
        coordinator.awaitToolJobs()

        assertEquals(listOf("call-supported" to "Supported answer"), gemini.toolResponses)
        assertTrue(
            diagnostics.events.value.any {
                it.name == "unsupported_tool_call" &&
                    it.detail.contains("call-unsupported") &&
                    it.detail.contains("unsupported_tool")
            }
        )
    }

    @Test
    fun `close cancels in-flight Hermes call from external scope and prevents Gemini response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-close", name = "ask_hermes", prompt = "wait"))
        assertEquals("call-close" to "wait", toolApi.awaitRequest("call-close"))

        coordinator.onGeminiEvent(GeminiLiveEvent.Interrupted())
        assertEquals(VoiceToolStatus.CallingHermes("call-close"), coordinator.state.value.tool)
        assertEquals(false, toolApi.wasCancelled("call-close"))

        coordinator.close()
        withTimeout(500) {
            toolApi.awaitCancelled("call-close")
        }
        toolApi.complete(response(callId = "call-close", answer = "late answer"))
        coordinator.awaitToolJobs()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `batched Hermes tool calls send both responses and record per-call success diagnostics`() = runTest {
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
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-a", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-b", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-a" to "First", toolApi.awaitRequest("call-a"))
        assertEquals("call-b" to "Second", toolApi.awaitRequest("call-b"))

        toolApi.complete(response(callId = "call-a", answer = "First answer"))
        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobs()

        assertEquals(
            setOf("call-a" to "First answer", "call-b" to "Second answer"),
            gemini.toolResponses.toSet(),
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_succeeded" && it.detail.contains("callId=call-a")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_succeeded" && it.detail.contains("callId=call-b")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_started" && it.detail.contains("callId=call-a")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "hermes_tool_started" && it.detail.contains("callId=call-b")
            }
        )
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
        assertEquals("call-3" to "fail", toolApi.awaitRequest("call-3"))

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
        private val calls = mutableMapOf<String, PendingHermesCall>()

        override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse {
            val call = synchronized(calls) {
                calls.getOrPut(callId) { PendingHermesCall() }
            }
            requests += callId to prompt
            call.request.complete(callId to prompt)
            return try {
                call.result.await()
            } catch (error: kotlinx.coroutines.CancellationException) {
                call.cancelled.complete(Unit)
                throw error
            }
        }

        suspend fun awaitRequest(callId: String? = null): Pair<String, String> {
            return withTimeout(500) {
                if (callId == null) {
                    firstCall().request.await()
                } else {
                    call(callId).request.await()
                }
            }
        }

        fun complete(response: MobileHermesResponse) {
            call(response.callId).result.complete(response)
        }

        fun fail(error: Throwable) {
            firstCall().result.completeExceptionally(error)
        }

        suspend fun awaitCancelled(callId: String) {
            withTimeout(500) {
                call(callId).cancelled.await()
            }
        }

        fun wasCancelled(callId: String): Boolean = call(callId).cancelled.isCompleted

        private fun call(callId: String): PendingHermesCall = synchronized(calls) {
            calls.getOrPut(callId) { PendingHermesCall() }
        }

        private fun firstCall(): PendingHermesCall = synchronized(calls) {
            calls.values.firstOrNull() ?: PendingHermesCall().also { calls[""] = it }
        }
    }

    private class PendingHermesCall {
        val request = CompletableDeferred<Pair<String, String>>()
        val result = CompletableDeferred<MobileHermesResponse>()
        val cancelled = CompletableDeferred<Unit>()
    }

    private class FakeVoiceAudioEngine : VoiceAudioEngine {
        val playedPcm16 = mutableListOf<String>()
        var suppressPlaybackCalls = 0
        var releaseCalls = 0

        override fun startCapture(onPcm16: (ByteArray) -> Unit) = Unit

        override fun stopCapture() = Unit

        override fun playPcm16(base64Pcm16: String) {
            playedPcm16 += base64Pcm16
        }

        override fun suppressPlayback() {
            suppressPlaybackCalls += 1
        }

        override fun release() {
            releaseCalls += 1
        }
    }
}
