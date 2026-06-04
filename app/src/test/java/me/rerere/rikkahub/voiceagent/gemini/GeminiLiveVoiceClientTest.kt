package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveVoiceClientTest {
    private val liveConnectConfig = JsonObject(
        mapOf(
            "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
        )
    )

    @Test
    fun `connect sends setup then context and forwards parsed tool calls`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = listOf(
                GeminiContentTurn(role = "user", text = "Hello"),
                GeminiContentTurn(role = "model", text = "Hi"),
            ),
            onEvent = events::add,
        )

        assertEquals("wss://example.test/live", socket.openedUrl)
        assertEquals("token-1", socket.openedToken)
        assertEquals(2, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages[0].jsonObject())
        assertTrue("clientContent" in socket.sentMessages[1].jsonObject())

        socket.receive(
            """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-1",
                    "name":"ask_hermes",
                    "args":{"prompt":"First prompt"}
                  },
                  {
                    "id":"call-2",
                    "name":"ask_hermes",
                    "args":{"prompt":"Second prompt"}
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                GeminiLiveEvent.ToolCalls(
                    listOf(
                        GeminiLiveEvent.ToolCall(
                            callId = "call-1",
                            name = "ask_hermes",
                            prompt = "First prompt",
                        ),
                        GeminiLiveEvent.ToolCall(
                            callId = "call-2",
                            name = "ask_hermes",
                            prompt = "Second prompt",
                        ),
                    )
                )
            ),
            events,
        )

        client.sendToolResponse(callId = "call-1", answer = "42")

        val response = socket.sentMessages[2]
            .jsonObject()["toolResponse"]!!
            .jsonObject["functionResponses"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("call-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("42", response["response"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `connect omits client content when context is empty`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = {},
        )

        assertEquals(1, socket.sentMessages.size)
        assertTrue("setup" in socket.sentMessages.single().jsonObject())
        assertFalse("clientContent" in socket.sentMessages.single().jsonObject())
    }

    @Test
    fun `send audio uses realtime input audio shape`() {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.sendAudio("base64-audio")

        val audio = socket.sentMessages.single()
            .jsonObject()["realtimeInput"]!!
            .jsonObject["audio"]!!
            .jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("base64-audio", audio["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `socket close and failure become error events`() = runBlocking {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())
        val events = mutableListOf<GeminiLiveEvent>()

        client.connect(
            token = "token-1",
            websocketUrl = "wss://example.test/live",
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
            contextTurns = emptyList(),
            onEvent = events::add,
        )

        socket.closeFromServer(code = 1001, reason = "going away")
        socket.fail(IllegalStateException("network down"))

        assertEquals(
            listOf(
                GeminiLiveEvent.Error(
                    message = "WebSocket closed: 1001 going away",
                    raw = "",
                ),
                GeminiLiveEvent.Error(
                    message = "network down",
                    raw = "",
                ),
            ),
            events,
        )
    }

    @Test
    fun `close delegates to socket`() {
        val socket = FakeGeminiSocket()
        val client = TestableGeminiLiveVoiceClient(socket = socket, codec = GeminiLiveCodec())

        client.close()

        assertEquals(1, socket.closeCount)
    }

    @Test
    fun `okhttp socket access token preserves existing query parameters`() {
        val url = geminiLiveUrlWithAccessToken(
            url = "wss://example.test/live?alt=sse",
            token = "token value",
        )

        assertEquals("sse", url.queryParameter("alt"))
        assertEquals("token value", url.queryParameter("access_token"))
        assertEquals(2, url.querySize)
    }

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
