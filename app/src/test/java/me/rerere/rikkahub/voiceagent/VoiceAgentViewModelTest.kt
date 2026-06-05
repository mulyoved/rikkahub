package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioEngine
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.voiceagent.gemini.GeminiContentTurn
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveCodec
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveEvent
import me.rerere.rikkahub.voiceagent.gemini.GeminiLiveVoiceClient
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.telemetry.VoiceDiagnostics
import me.rerere.rikkahub.voiceagent.voicelab.MobileHermesResponse
import me.rerere.rikkahub.voiceagent.voicelab.MobileVoiceSessionResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

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
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        audio.awaitSuppressPlaybackCalls(1)
        assertEquals(VoiceToolStatus.CallingHermes("call-1"), coordinator.state.value.tool)
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("late-audio"))
        assertEquals(emptyList<String>(), audio.playedPcm16)

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
    fun `duplicate tool call id is ignored when old send is already in progress`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val oldSend = gemini.blockNextToolResponse("call-replay-send")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-replay-send", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-replay-send" to "old", toolApi.awaitRequest("call-replay-send"))
        toolApi.complete(response(callId = "call-replay-send", answer = "old answer"))
        assertTrue(oldSend.started.await(500, TimeUnit.MILLISECONDS))

        val replayJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(
                GeminiLiveEvent.ToolCall(callId = "call-replay-send", name = "ask_hermes", prompt = "new")
            )
        }
        withTimeout(500) {
            replayJob.join()
        }
        assertFalse(("call-replay-send" to "new") in toolApi.requests)

        oldSend.release.countDown()
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(
            listOf("call-replay-send" to "old answer"),
            gemini.toolResponses,
        )
        assertEquals(
            VoiceToolStatus.HermesAnswered(callId = "call-replay-send", elapsedMs = 0L),
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
    fun `failed Gemini tool response send marks Hermes tool failed instead of answered`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient().apply {
            failToolResponses += "call-send-fails"
        }
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-send-fails", name = "ask_hermes", prompt = "send fails")
        )
        assertEquals("call-send-fails" to "send fails", toolApi.awaitRequest("call-send-fails"))

        toolApi.complete(response(callId = "call-send-fails", answer = "answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(
            VoiceToolStatus.HermesFailed(
                callId = "call-send-fails",
                message = "Failed to send Gemini tool response message",
            ),
            coordinator.state.value.tool,
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
    fun `close does not deadlock when tool response send emits event synchronously`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-reentry")
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )
        gemini.onBeforeToolResponseRecorded = {
            coordinator.onGeminiEvent(GeminiLiveEvent.Error(message = "late send error", raw = "{}"))
        }

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-reentry", name = "ask_hermes", prompt = "close race")
        )
        assertEquals("call-close-reentry" to "close race", toolApi.awaitRequest("call-close-reentry"))

        toolApi.complete(response(callId = "call-close-reentry", answer = "answer before close"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertEquals(0, audio.releaseCalls)

        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }

        assertEquals(listOf("call-close-reentry" to "answer before close"), gemini.toolResponses)
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
    fun `public interrupt suppresses playback while output audio write is in flight`() = runTest {
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
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-interrupt-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val interruptJob = launch(Dispatchers.Default) {
            coordinator.suppressPlayback()
        }

        withTimeout(500) {
            interruptJob.join()
        }
        assertEquals(1, audio.suppressPlaybackCalls)
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
        }
        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
    }

    @Test
    fun `public interrupt updates state before blocking audio suppression returns`() = runTest {
        val audio = FakeVoiceAudioEngine()
        val blockedSuppression = audio.blockNextSuppression()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.suppressPlayback()

        assertEquals(VoiceAudioStatus.PlaybackSuppressed, coordinator.state.value.audio)
        assertTrue(blockedSuppression.started.await(500, TimeUnit.MILLISECONDS))

        blockedSuppression.release.countDown()
    }

    @Test
    fun `close suppresses Hermes result while waiting for in flight non tool event`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val audio = FakeVoiceAudioEngine()
        val blockedPlayback = audio.blockNextPlayback()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = audio,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-wait", name = "ask_hermes", prompt = "close wait")
        )
        assertEquals("call-close-wait" to "close wait", toolApi.awaitRequest("call-close-wait"))

        val eventJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.OutputAudio("blocked-close-pcm"))
        }
        assertTrue(blockedPlayback.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        toolApi.complete(response(callId = "call-close-wait", answer = "late answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(0, audio.releaseCalls)

        blockedPlayback.release.countDown()
        withTimeout(500) {
            eventJob.join()
            closeJob.join()
        }

        assertEquals(listOf("blocked-close-pcm"), audio.playedPcm16)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
        assertEquals(emptyMap<String, VoiceToolStatus>(), coordinator.state.value.toolCalls)
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

    @Test
    fun `coordinator persists transcript fragments as turns and Hermes tool records`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hel"))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("lo"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("h"))
        coordinator.onGeminiEvent(GeminiLiveEvent.OutputTranscript("i"))
        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-persist", name = "ask_hermes", prompt = "look up"))
        assertEquals("call-persist" to "look up", toolApi.awaitRequest("call-persist"))
        toolApi.complete(response(callId = "call-persist", answer = "tool answer"))
        coordinator.awaitToolJobsWithTimeout()
        conversationStore.awaitUpdateCount(6)

        val messages = conversationStore.conversation.value.currentMessages
        assertEquals(3, messages.size)
        assertTrue(messages[0].parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "hello" })
        assertTrue(messages[1].parts.filterIsInstance<UIMessagePart.Text>().any { it.text == "hi" })
        val tool = messages[2].parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call-persist", tool.toolCallId)
        assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().any { it.text == "tool answer" })
    }

    @Test
    fun `tool cancellation persists accepted Hermes record as failed`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-cancel-persist", name = "ask_hermes", prompt = "cancel me")
        )
        assertEquals("call-cancel-persist" to "cancel me", toolApi.awaitRequest("call-cancel-persist"))

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-cancel-persist")))
        toolApi.awaitCancelled("call-cancel-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-cancel-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by Gemini", tool.output.text())
    }

    @Test
    fun `reconnect cleanup persists accepted Hermes record as failed`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-reconnect-persist", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-reconnect-persist" to "old", toolApi.awaitRequest("call-reconnect-persist"))

        coordinator.prepareForReconnect()
        toolApi.awaitCancelled("call-reconnect-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-reconnect-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by reconnect", tool.output.text())
    }

    @Test
    fun `close persists accepted Hermes record as failed`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-persist", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-persist" to "close", toolApi.awaitRequest("call-close-persist"))

        coordinator.close()
        toolApi.awaitCancelled("call-close-persist")
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-persist", tool.toolCallId)
        assertEquals("failed", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("Tool call canceled by session end", tool.output.text())
    }

    @Test
    fun `close after Gemini tool response send starts persists Hermes record as complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-close-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-close-sending-persist", name = "ask_hermes", prompt = "close")
        )
        assertEquals("call-close-sending-persist" to "close", toolApi.awaitRequest("call-close-sending-persist"))
        toolApi.complete(response(callId = "call-close-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val closeJob = launch(Dispatchers.Default) {
            coordinator.close()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        blockedSend.release.countDown()
        withTimeout(500) {
            closeJob.join()
        }
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-close-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `reconnect after Gemini tool response send starts persists Hermes record as complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-reconnect-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-reconnect-sending-persist", name = "ask_hermes", prompt = "old")
        )
        assertEquals("call-reconnect-sending-persist" to "old", toolApi.awaitRequest("call-reconnect-sending-persist"))
        toolApi.complete(response(callId = "call-reconnect-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val reconnectJob = launch(Dispatchers.Default) {
            coordinator.prepareForReconnect()
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        blockedSend.release.countDown()
        withTimeout(500) {
            reconnectJob.join()
        }
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-reconnect-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `Gemini cancellation after tool response send starts persists Hermes record as complete`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val gemini = FakeGeminiLiveVoiceClient()
        val blockedSend = gemini.blockToolResponse("call-cancel-sending-persist")
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
            dispatcher = Dispatchers.Default,
        )

        coordinator.onGeminiEvent(
            GeminiLiveEvent.ToolCall(callId = "call-cancel-sending-persist", name = "ask_hermes", prompt = "cancel")
        )
        assertEquals("call-cancel-sending-persist" to "cancel", toolApi.awaitRequest("call-cancel-sending-persist"))
        toolApi.complete(response(callId = "call-cancel-sending-persist", answer = "sent answer"))
        assertTrue(blockedSend.started.await(500, TimeUnit.MILLISECONDS))

        val cancelJob = launch(Dispatchers.Default) {
            coordinator.onGeminiEvent(GeminiLiveEvent.ToolCallCancellation(listOf("call-cancel-sending-persist")))
        }
        assertFalse(blockedSend.release.await(50, TimeUnit.MILLISECONDS))
        blockedSend.release.countDown()
        withTimeout(500) {
            cancelJob.join()
        }
        coordinator.awaitPersistenceJobsWithTimeout()

        val tool = conversationStore.conversation.value.currentMessages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .single()
        assertEquals("call-cancel-sending-persist", tool.toolCallId)
        assertEquals("complete", tool.metadata?.get("voice_tool_status")?.jsonPrimitive?.content)
        assertEquals("sent answer", tool.output.text())
    }

    @Test
    fun `coordinator snapshots transcript text before delayed persistence writes`() = runTest {
        val conversationStore = FakeVoiceConversationStore()
        val firstUpdate = conversationStore.blockNextUpdate()
        val coordinator = VoiceAgentCoordinator(
            gemini = FakeGeminiLiveVoiceClient(),
            toolApi = FakeVoiceToolApi(),
            audio = FakeVoiceAudioEngine(),
            conversationStore = conversationStore,
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("hel"))
        assertTrue(firstUpdate.started.await(500, TimeUnit.MILLISECONDS))
        coordinator.onGeminiEvent(GeminiLiveEvent.InputTranscript("lo"))

        firstUpdate.release.countDown()
        conversationStore.awaitUpdateCount(2)

        assertEquals("hel", conversationStore.updateAt(0).currentMessages.single().parts.text())
        assertEquals("hello", conversationStore.updateAt(1).currentMessages.single().parts.text())
    }

    @Test
    fun `reconnect cleanup cancels in flight Hermes call and suppresses stale response`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val toolApi = FakeVoiceToolApi()
        val coordinator = VoiceAgentCoordinator(
            gemini = gemini,
            toolApi = toolApi,
            audio = FakeVoiceAudioEngine(),
            scope = this,
        )

        coordinator.onGeminiEvent(GeminiLiveEvent.ToolCall(callId = "call-reconnect", name = "ask_hermes", prompt = "old"))
        assertEquals("call-reconnect" to "old", toolApi.awaitRequest("call-reconnect"))

        coordinator.prepareForReconnect()
        toolApi.complete(response(callId = "call-reconnect", answer = "old answer"))
        coordinator.awaitToolJobsWithTimeout()

        assertEquals(emptyList<Pair<String, String>>(), gemini.toolResponses)
        assertEquals(VoiceToolStatus.Idle, coordinator.state.value.tool)
    }

    @Test
    fun `stale session events after reconnect do not mutate coordinator state`() = runTest {
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
        val staleSessionId = coordinator.nextSessionId()

        coordinator.prepareForReconnect()
        coordinator.onGeminiEvent(staleSessionId, GeminiLiveEvent.InputTranscript("late input"))
        coordinator.onGeminiEvent(
            staleSessionId,
            GeminiLiveEvent.ToolCall(callId = "call-stale-session", name = "ask_hermes", prompt = "stale"),
        )
        delay(50)

        assertEquals("", coordinator.state.value.inputTranscript)
        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertTrue(diagnostics.events.value.any { it.name == "stale_gemini_event" })
    }

    @Test
    fun `ViewModel starts session forwards capture audio and closes resources`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val vm = VoiceAgentViewModel(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(
                    systemInstruction = "system voice prompt",
                    turns = listOf(GeminiContentTurn(role = "user", text = "prior turn")),
                )
            ),
            scope = this,
        )

        vm.start()
        gemini.awaitConnect()

        assertEquals(listOf("gemini-flash"), sessionApi.createdSessions)
        assertEquals("token-1", gemini.connectedToken)
        assertEquals("wss://voice.test/live", gemini.connectedWebsocketUrl)
        assertEquals("gemini-live-test", gemini.connectedProviderModel)
        assertEquals("system voice prompt", gemini.connectedSystemInstruction)
        assertEquals(listOf(GeminiContentTurn(role = "user", text = "prior turn")), gemini.connectedContextTurns)
        assertEquals(1, audio.startCaptureCalls)

        audio.emitCapture(byteArrayOf(1, 2, 3))
        assertEquals(listOf(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))), gemini.audioMessages)

        vm.end()
        withTimeout(500) {
            while (gemini.closeCalls < 1) {
                delay(10)
            }
        }

        assertEquals(1, gemini.closeCalls)
        assertEquals(1, audio.releaseCalls)
        assertEquals(VoiceSessionStatus.Ended, vm.state.value.session)
    }

    @Test
    fun `ViewModel reconnect cancels delayed old start before opening new Gemini session`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val blockedSession = sessionApi.blockNextSession()
        val gemini = FakeGeminiLiveVoiceClient()
        val vm = VoiceAgentViewModel(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        vm.start()
        withTimeout(500) {
            blockedSession.started.await()
        }

        vm.reconnect()
        gemini.awaitConnectCount(1)

        assertEquals(2, sessionApi.createdSessions.size)
        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Connected, vm.state.value.session)
    }

    @Test
    fun `ViewModel end is terminal and later start does not reopen session`() = runTest {
        val gemini = FakeGeminiLiveVoiceClient()
        val vm = VoiceAgentViewModel(
            modelId = "gemini-flash",
            sessionApi = FakeVoiceSessionApi(),
            toolApi = FakeVoiceToolApi(),
            gemini = gemini,
            audio = FakeVoiceAudioEngine(),
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        vm.start()
        gemini.awaitConnectCount(1)
        vm.end()
        withTimeout(500) {
            while (gemini.closeCalls < 1) {
                delay(10)
            }
        }

        vm.start()
        delay(50)

        assertEquals(1, gemini.eventHandlers.size)
        assertEquals(VoiceSessionStatus.Ended, vm.state.value.session)
    }

    @Test
    fun `ViewModel ignores delayed Gemini callbacks from previous session after reconnect`() = runTest {
        val sessionApi = FakeVoiceSessionApi()
        val gemini = FakeGeminiLiveVoiceClient()
        val audio = FakeVoiceAudioEngine()
        val toolApi = FakeVoiceToolApi()
        val vm = VoiceAgentViewModel(
            modelId = "gemini-flash",
            sessionApi = sessionApi,
            toolApi = toolApi,
            gemini = gemini,
            audio = audio,
            conversationStore = FakeVoiceConversationStore(),
            contextProvider = FakeVoiceAgentContextProvider(
                VoiceContext(systemInstruction = "system", turns = emptyList())
            ),
            scope = this,
        )

        vm.start()
        gemini.awaitConnectCount(1)
        val oldCallback = gemini.eventHandlers.single()

        vm.reconnect()
        gemini.awaitConnectCount(2)

        oldCallback(GeminiLiveEvent.ToolCall(callId = "stale-call", name = "ask_hermes", prompt = "stale"))
        delay(50)

        assertEquals(emptyList<Pair<String, String>>(), toolApi.requests)
        assertEquals(VoiceToolStatus.Idle, vm.state.value.tool)
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

    private suspend fun VoiceAgentCoordinator.awaitPersistenceJobsWithTimeout() {
        withTimeout(500) {
            awaitPersistenceJobs()
        }
    }

    private fun List<UIMessagePart>.text(): String =
        filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private class FakeGeminiLiveVoiceClient : GeminiLiveVoiceClient {
        val audioMessages = mutableListOf<String>()
        val toolResponses = mutableListOf<Pair<String, String>>()
        val failToolResponses = mutableSetOf<String>()
        var closeCalls = 0
        var onBeforeToolResponseRecorded: (() -> Unit)? = null
        var connectedToken: String? = null
        var connectedWebsocketUrl: String? = null
        var connectedProviderModel: String? = null
        var connectedSystemInstruction: String? = null
        var connectedContextTurns: List<GeminiContentTurn> = emptyList()
        val eventHandlers = mutableListOf<(GeminiLiveEvent) -> Unit>()
        private val connected = CompletableDeferred<Unit>()
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
        ) {
            connectedToken = token
            connectedWebsocketUrl = websocketUrl
            connectedProviderModel = providerModel
            connectedSystemInstruction = systemInstruction
            connectedContextTurns = contextTurns
            eventHandlers += onEvent
            connected.complete(Unit)
        }

        override fun sendAudio(base64Pcm16: String) {
            audioMessages += base64Pcm16
        }

        suspend fun awaitConnect() {
            withTimeout(500) {
                connected.await()
            }
        }

        suspend fun awaitConnectCount(count: Int) {
            withTimeout(500) {
                while (eventHandlers.size < count) {
                    delay(10)
                }
            }
        }

        override fun sendToolResponse(callId: String, answer: String): Boolean {
            val blocked = synchronized(blockedResponses) {
                blockedResponses[callId]?.removeFirstOrNull()
            }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
            onBeforeToolResponseRecorded?.invoke()
            if (callId in failToolResponses) {
                return false
            }
            toolResponses += callId to answer
            return true
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
        var startCaptureCalls = 0
        var stopCaptureCalls = 0
        private var captureCallback: ((ByteArray) -> Unit)? = null
        private val blockedPlaybacks = mutableListOf<BlockedPlayback>()
        private val blockedSuppressions = mutableListOf<BlockedPlayback>()

        override fun startCapture(onPcm16: (ByteArray) -> Unit) {
            startCaptureCalls += 1
            captureCallback = onPcm16
        }

        override fun stopCapture() {
            stopCaptureCalls += 1
            captureCallback = null
        }

        override fun playPcm16(base64Pcm16: String) {
            val blocked = synchronized(blockedPlaybacks) { blockedPlaybacks.removeFirstOrNull() }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
            playedPcm16 += base64Pcm16
        }

        override fun suppressPlayback() {
            val blocked = synchronized(blockedSuppressions) { blockedSuppressions.removeFirstOrNull() }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
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

        fun blockNextSuppression(): BlockedPlayback {
            return BlockedPlayback().also { blocked ->
                synchronized(blockedSuppressions) {
                    blockedSuppressions += blocked
                }
            }
        }

        suspend fun awaitSuppressPlaybackCalls(count: Int) {
            withTimeout(500) {
                while (suppressPlaybackCalls < count) {
                    delay(10)
                }
            }
        }

        fun emitCapture(pcm16: ByteArray) {
            captureCallback?.invoke(pcm16)
        }
    }

    private class BlockedPlayback {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FakeVoiceConversationStore : VoiceConversationStore {
        private val updates = mutableListOf<Conversation>()
        private val blockedUpdates = mutableListOf<BlockedUpdate>()
        override val conversation: StateFlow<Conversation> = MutableStateFlow(
            Conversation.ofId(id = Uuid.random())
        )

        override suspend fun update(transform: (Conversation) -> Conversation) {
            val blocked = synchronized(blockedUpdates) { blockedUpdates.removeFirstOrNull() }
            if (blocked != null) {
                blocked.started.countDown()
                blocked.release.await(500, TimeUnit.MILLISECONDS)
            }
            val flow = conversation as MutableStateFlow<Conversation>
            flow.value = transform(flow.value)
            synchronized(updates) {
                updates += flow.value
            }
        }

        fun blockNextUpdate(): BlockedUpdate {
            return BlockedUpdate().also { blocked ->
                synchronized(blockedUpdates) {
                    blockedUpdates += blocked
                }
            }
        }

        fun updateAt(index: Int): Conversation = synchronized(updates) {
            updates[index]
        }

        suspend fun awaitUpdateCount(count: Int) {
            withTimeout(500) {
                while (synchronized(updates) { updates.size } < count) {
                    delay(10)
                }
            }
        }
    }

    private class BlockedUpdate {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class FakeVoiceSessionApi : VoiceSessionApi {
        val createdSessions = mutableListOf<String>()
        private val blockedSessions = mutableListOf<BlockedSession>()

        override suspend fun createSession(modelId: String): MobileVoiceSessionResponse {
            createdSessions += modelId
            val blocked = synchronized(blockedSessions) {
                blockedSessions.removeFirstOrNull()
            }
            blocked?.let {
                it.started.complete(Unit)
                it.release.await()
            }
            return MobileVoiceSessionResponse(
                token = "token-1",
                modelId = modelId,
                providerModel = "gemini-live-test",
                apiVersion = "v1alpha",
                websocketUrl = "wss://voice.test/live",
                inputSampleRate = 16000,
                outputSampleRate = 24000,
                liveConnectConfig = buildJsonObject {},
            )
        }

        fun blockNextSession(): BlockedSession {
            return BlockedSession().also { blocked ->
                synchronized(blockedSessions) {
                    blockedSessions += blocked
                }
            }
        }
    }

    private class BlockedSession {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class FakeVoiceAgentContextProvider(
        private val context: VoiceContext,
    ) : VoiceAgentContextProvider {
        override fun build(conversation: Conversation): VoiceContext = context
    }
}
