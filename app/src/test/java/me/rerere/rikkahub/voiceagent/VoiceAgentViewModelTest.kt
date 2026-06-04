package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        coordinator.awaitToolJobsWithTimeout()

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
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertTrue(diagnostics.events.value.any { it.name == "unsupported_tool_call" && it.detail.contains("call-2") })
    }

    @Test
    fun `unsupported only tool call from codec records diagnostic without Hermes response`() = runTest {
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
        assertEquals(
            GeminiLiveEvent.ToolCalls(
                calls = emptyList(),
                unsupportedCalls = listOf(
                    GeminiLiveEvent.UnsupportedToolCall(
                        callId = "call-ignored",
                        name = "unsupported_tool",
                    )
                ),
            ),
            event,
        )

        coordinator.onGeminiEvent(event)
        coordinator.awaitToolJobsWithTimeout()

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
        coordinator.awaitToolJobsWithTimeout()

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
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `tool call cancellation cancels only matching Hermes call and suppresses its response`() = runTest {
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

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-a")))
        toolApi.awaitCancelled("call-a")

        assertEquals(VoiceToolStatus.CallingHermes("call-b"), coordinator.state.value.tool)
        assertEquals(
            mapOf("call-b" to VoiceToolStatus.CallingHermes("call-b")),
            coordinator.state.value.toolCalls,
        )
        assertEquals(false, toolApi.wasCancelled("call-b"))

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()
        toolApi.complete(response(callId = "call-a", answer = "First late answer"))

        assertEquals(listOf("call-b" to "Second answer"), gemini.toolResponses)
        assertEquals(false, toolApi.wasCancelled("call-b"))
        assertEquals(
            VoiceToolStatus.HermesAnswered(callId = "call-b", elapsedMs = 0L),
            coordinator.state.value.tool,
        )
        assertEquals(
            mapOf("call-b" to VoiceToolStatus.HermesAnswered(callId = "call-b", elapsedMs = 0L)),
            coordinator.state.value.toolCalls,
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "tool_call_cancellation" && it.detail.contains("call-a")
            }
        )
    }

    @Test
    fun `tool call cancellation received before tool call prevents Hermes request`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-before-start")))
        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(
                callId = "call-before-start",
                name = "ask_hermes",
                prompt = "do not start",
            )
        )
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
    }

    @Test
    fun `duplicate tool call id cancels replaced Hermes job and only latest response is sent`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-replay" to "old", toolApi.awaitRequest("call-replay"))

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay", name = "ask_hermes", prompt = "new")
        )
        toolApi.awaitCancelled("call-replay")
        assertEquals("call-replay" to "new", toolApi.awaitRequest("call-replay"))

        toolApi.complete(response(callId = "call-replay", answer = "new answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(listOf("call-replay" to "new answer"), gemini.toolResponses)
        assertEquals(
            VoiceToolStatus.HermesAnswered(callId = "call-replay", elapsedMs = 0L),
            coordinator.state.value.tool,
        )
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
        assertEquals(
            mapOf(
                "call-a" to VoiceToolStatus.CallingHermes("call-a"),
                "call-b" to VoiceToolStatus.CallingHermes("call-b"),
            ),
            coordinator.state.value.toolCalls,
        )

        toolApi.complete(response(callId = "call-a", answer = "First answer"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-a"] !is VoiceToolStatus.HermesAnswered) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VoiceToolStatus.CallingHermes("call-b"), coordinator.state.value.tool)
        assertEquals(
            mapOf(
                "call-a" to VoiceToolStatus.HermesAnswered(callId = "call-a", elapsedMs = 0L),
                "call-b" to VoiceToolStatus.CallingHermes("call-b"),
            ),
            coordinator.state.value.toolCalls,
        )

        toolApi.complete(response(callId = "call-b", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            setOf("call-a" to "First answer", "call-b" to "Second answer"),
            gemini.toolResponses.toSet(),
        )
        assertEquals(
            mapOf(
                "call-a" to VoiceToolStatus.HermesAnswered(callId = "call-a", elapsedMs = 0L),
                "call-b" to VoiceToolStatus.HermesAnswered(callId = "call-b", elapsedMs = 0L),
            ),
            coordinator.state.value.toolCalls,
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
    fun `batched failure remains aggregate summary after another call succeeds`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCalls(
                listOf(
                    GeminiLiveEvent.ToolCall(callId = "call-fail", name = "ask_hermes", prompt = "First"),
                    GeminiLiveEvent.ToolCall(callId = "call-ok", name = "ask_hermes", prompt = "Second"),
                )
            )
        )
        assertEquals("call-fail" to "First", toolApi.awaitRequest("call-fail"))
        assertEquals("call-ok" to "Second", toolApi.awaitRequest("call-ok"))

        toolApi.fail(callId = "call-fail", error = IllegalStateException("Hermes failed"))
        withTimeout(500) {
            while (coordinator.state.value.toolCalls["call-fail"] !is VoiceToolStatus.HermesFailed) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(VoiceToolStatus.CallingHermes("call-ok"), coordinator.state.value.tool)

        toolApi.complete(response(callId = "call-ok", answer = "Second answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            VoiceToolStatus.HermesFailed(callId = "call-fail", message = "Hermes failed"),
            coordinator.state.value.tool,
        )
        assertEquals(
            mapOf(
                "call-fail" to VoiceToolStatus.HermesFailed(callId = "call-fail", message = "Hermes failed"),
                "call-ok" to VoiceToolStatus.HermesAnswered(callId = "call-ok", elapsedMs = 0L),
            ),
            coordinator.state.value.toolCalls,
        )
        assertEquals(listOf("call-ok" to "Second answer"), gemini.toolResponses)
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
        coordinator.awaitToolJobsWithTimeout()

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

    @Test
    fun `late non tool events after close do not mutate state or play audio`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
        )

        coordinator.close()
        val stateAfterClose = coordinator.state.value

        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("late-pcm"))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("late input"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("late output"))
        coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "late error", raw = "{}"))

        assertEquals(stateAfterClose, coordinator.state.value)
        assertEquals(emptyList<String>(), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `close is idempotent and clears active tool state`() = runTest {
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
            GeminiLiveEvent.ToolCall(callId = "call-close-idempotent", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-idempotent" to "close", toolApi.awaitRequest("call-close-idempotent"))

        coordinator.close()
        toolApi.awaitCancelled("call-close-idempotent")
        coordinator.close()

        assertEquals(1, gemini.closeCalls)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
    }

    @Test
    fun `close waits for in flight Gemini tool response send before releasing resources`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-send")
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-send", name = "ask_hermes", prompt = "close race")
        )
        assertEquals("call-close-send" to "close race", toolApi.awaitRequest("call-close-send"))

        toolApi.complete(response(callId = "call-close-send", answer = "answer before close"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        assertEquals(0, gemini.closeCalls)
        assertEquals(0, audio.releaseCalls)

        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }

        assertEquals(listOf("call-close-send" to "answer before close"), gemini.toolResponses)
        assertEquals(1, gemini.closeCalls)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `close waits for in flight non tool event before releasing audio`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        val eventJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedPlayback.release.await(50, TimeUnit.MILLISECONDS))
        assertEquals(0, audio.releaseCalls)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
            closeJob.join()
        }

        assertEquals(listOf("blocked-pcm"), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
    }

    @Test
    fun `non tool lifecycle events record diagnostics without crashing`() = runTest {
        val diagnostics = VoiceDiagnostics()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            diagnostics = diagnostics,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.SetupComplete)
        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-1", "call-2")))
        coordinator.onGeminiEvent(
            GeminiLiveEvent.SessionResumptionUpdate(
                newHandle = "resume-handle",
                resumable = true,
            )
        )
        coordinator.onGeminiEvent(GeminiLiveEvent.Ignored("""{"serverContent":{}}"""))

        assertTrue(diagnostics.events.value.any { it.name == "gemini_setup_complete" })
        assertTrue(
            diagnostics.events.value.any {
                it.name == "tool_call_cancellation" &&
                    it.detail.contains("call-1") &&
                    it.detail.contains("call-2")
            }
        )
        assertTrue(
            diagnostics.events.value.any {
                it.name == "session_resumption_update" &&
                    it.detail.contains("resumable=true") &&
                    it.detail.contains("resume-handle")
            }
        )
        assertTrue(diagnostics.events.value.any { it.name == "gemini_event_ignored" })
    }

    @Test
    fun `Gemini error updates session error state`() = runTest {
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "Gemini failed", raw = "{}"))

        assertEquals(VoiceSessionStatus.Error("Gemini failed"), coordinator.state.value.session)
        assertEquals("Gemini failed", coordinator.state.value.error)
    }

    private fun response(callId: String, answer: String): MobileHermesResponse = MobileHermesResponse(
        callId = callId,
        answer = answer,
        model = "hermes-test",
        profileId = "profile",
        profileLabel = "Profile",
    )

    private suspend fun VoiceAgentCoordinator.awaitToolJobsWithTimeout() {
        withTimeout(500) {
            awaitToolJobs()
        }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
        val toolResponses = mutableListOf<Pair<String, String>>()
        var closeCalls = 0
        private val blockedResponses = mutableMapOf<String, MutableList<BlockedToolResponse>>()

        fun blockToolResponse(callId: String): BlockedToolResponse {
            return blockNextToolResponse(callId)
        }

        fun blockNextToolResponse(callId: String): BlockedToolResponse {
            return BlockedToolResponse().also { blocked ->
                synchronized(blockedResponses) {
                    blockedResponses.getOrPut(callId) { mutableListOf() } += blocked
                }
            }
        }

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
            val blocked = synchronized(blockedResponses) {
                blockedResponses[callId]?.removeFirstOrNull()
            }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
            toolResponses += callId to answer
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class BlockedToolResponse {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FakeVoiceToolApi : VoiceToolApi {
        val requests = mutableListOf<Pair<String, String>>()
        private val calls = mutableMapOf<String, MutableList<PendingHermesCall>>()

        override suspend fun askHermes(callId: String, prompt: String): MobileHermesResponse {
            val call = nextCallForRequest(callId)
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

        fun fail(callId: String, error: Throwable) {
            call(callId).result.completeExceptionally(error)
        }

        suspend fun awaitCancelled(callId: String) {
            withTimeout(500) {
                call(callId).cancelled.await()
            }
        }

        fun wasCancelled(callId: String): Boolean = call(callId).cancelled.isCompleted

        private fun nextCallForRequest(callId: String): PendingHermesCall = synchronized(calls) {
            val callList = calls.getOrPut(callId) { mutableListOf() }
            callList.firstOrNull { !it.request.isCompleted }
                ?: PendingHermesCall().also(callList::add)
        }

        private fun call(callId: String): PendingHermesCall = synchronized(calls) {
            val callList = calls.getOrPut(callId) { mutableListOf() }
            callList.firstOrNull { !it.result.isCompleted && !it.cancelled.isCompleted }
                ?: callList.lastOrNull()
                ?: PendingHermesCall().also(callList::add)
        }

        private fun firstCall(): PendingHermesCall = synchronized(calls) {
            calls.values.flatten().firstOrNull { !it.result.isCompleted && !it.cancelled.isCompleted }
                ?: PendingHermesCall().also { calls.getOrPut("") { mutableListOf() } += it }
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
        private val blockedPlaybacks = mutableListOf<BlockedPlayback>()

        override fun startCapture(onPcm16: (ByteArray) -> Unit) = Unit

        override fun stopCapture() = Unit

        override fun playPcm16(base64Pcm16: String) {
            val blocked = synchronized(blockedPlaybacks) { blockedPlaybacks.removeFirstOrNull() }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
            playedPcm16 += base64Pcm16
        }

        override fun suppressPlayback() {
            suppressPlaybackCalls += 1
        }

        override fun release() {
            releaseCalls += 1
        }

        fun blockNextPlayback(): BlockedPlayback {
            return BlockedPlayback().also { blocked ->
                synchronized(blockedPlaybacks) {
                    blockedPlaybacks += blocked
                }
            }
        }
    }

    private class BlockedPlayback {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }
}
