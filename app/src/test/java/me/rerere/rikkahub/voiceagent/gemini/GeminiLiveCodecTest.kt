package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveCodecTest {
    private val codec = GeminiLiveCodec()

    @Test
    fun `setup message includes model and live connect config fields`() {
        val liveConnectConfig = JsonObject(
            mapOf(
                "responseModalities" to JsonArray(listOf(JsonPrimitive("AUDIO"))),
                "inputAudioTranscription" to JsonObject(emptyMap()),
                "outputAudioTranscription" to JsonObject(emptyMap()),
                "tools" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "functionDeclarations" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "name" to JsonPrimitive("ask_hermes"),
                                                "description" to JsonPrimitive("Ask Hermes"),
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
            )
        )

        val message = codec.setupMessage(
            providerModel = "gemini-2.0-flash-live-001",
            liveConnectConfig = liveConnectConfig,
            systemInstruction = "You are Hermes.",
        ).jsonObject()

        val setup = message["setup"]!!.jsonObject
        assertEquals("models/gemini-2.0-flash-live-001", setup["model"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("AUDIO"),
            setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        assertTrue(setup["inputAudioTranscription"] is JsonObject)
        assertTrue(setup["outputAudioTranscription"] is JsonObject)
        assertEquals(liveConnectConfig["tools"], setup["tools"])
        assertEquals(
            "You are Hermes.",
            setup["systemInstruction"]!!
                .jsonObject["parts"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `client content message emits incomplete text turns`() {
        val message = codec.clientContentMessage(
            listOf(
                GeminiContentTurn(role = "user", text = "Hello"),
                GeminiContentTurn(role = "model", text = "Hi"),
            )
        ).jsonObject()

        val clientContent = message["clientContent"]!!.jsonObject
        assertFalse(clientContent["turnComplete"]!!.jsonPrimitive.boolean)
        val turns = clientContent["turns"]!!.jsonArray
        assertEquals("user", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello",
            turns[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("model", turns[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hi",
            turns[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `realtime audio message emits pcm media chunk`() {
        val message = codec.realtimeAudioMessage("base64-audio").jsonObject()

        val chunk = message["realtimeInput"]!!
            .jsonObject["mediaChunks"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("audio/pcm;rate=16000", chunk["mimeType"]!!.jsonPrimitive.content)
        assertEquals("base64-audio", chunk["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool response message emits ask hermes answer`() {
        val message = codec.toolResponseMessage(callId = "call-1", answer = "42").jsonObject()

        val response = message["toolResponse"]!!
            .jsonObject["functionResponses"]!!
            .jsonArray[0]
            .jsonObject
        assertEquals("call-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("ask_hermes", response["name"]!!.jsonPrimitive.content)
        assertEquals("42", response["response"]!!.jsonObject["answer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parse server setup complete`() {
        assertEquals(
            GeminiLiveEvent.SetupComplete,
            codec.parseServerMessage("""{"setupComplete":{}}"""),
        )
    }

    @Test
    fun `parse server transcriptions`() {
        assertEquals(
            GeminiLiveEvent.InputTranscript("heard"),
            codec.parseServerMessage("""{"serverContent":{"inputTranscription":{"text":"heard"}}}"""),
        )
        assertEquals(
            GeminiLiveEvent.OutputTranscript("spoken"),
            codec.parseServerMessage("""{"serverContent":{"outputTranscription":{"text":"spoken"}}}"""),
        )
    }

    @Test
    fun `parse server output audio inline data`() {
        val event = codec.parseServerMessage(
            """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"base64-pcm"}}
                  ]
                }
              }
            }
            """.trimIndent()
        )

        assertEquals(GeminiLiveEvent.OutputAudio(base64Pcm16 = "base64-pcm"), event)
        event as GeminiLiveEvent.OutputAudio
        assertEquals("base64-pcm", event.base64Pcm16)
    }

    @Test
    fun `parse server ignores non audio inline data`() {
        val raw = """
            {
              "serverContent":{
                "modelTurn":{
                  "parts":[
                    {"inlineData":{"mimeType":"image/png","data":"base64-image"}}
                  ]
                }
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse server interrupted boolean`() {
        assertEquals(
            GeminiLiveEvent.Interrupted(),
            codec.parseServerMessage("""{"serverContent":{"interrupted":true}}"""),
        )
        assertTrue(codec.parseServerMessage("""{"serverContent":{"interrupted":false}}""") is GeminiLiveEvent.Ignored)
    }

    @Test
    fun `parse server ignores malformed interrupted shape`() {
        val raw = """{"serverContent":{"interrupted":{"value":true}}}"""

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse first tool call function`() {
        assertEquals(
            GeminiLiveEvent.ToolCall(
                callId = "call-1",
                name = "ask_hermes",
                prompt = "What should I say?",
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"ask_hermes",
                        "args":{"prompt":"What should I say?"}
                      },
                      {
                        "id":"call-2",
                        "name":"ignored",
                        "args":{"prompt":"Ignore me"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse skips malformed tool calls and returns first valid function call`() {
        assertEquals(
            GeminiLiveEvent.ToolCall(
                callId = "call-2",
                name = "ask_hermes",
                prompt = "Use this prompt",
            ),
            codec.parseServerMessage(
                """
                {
                  "toolCall":{
                    "functionCalls":[
                      {
                        "id":"call-1",
                        "name":"ask_hermes",
                        "args":{}
                      },
                      {
                        "id":"call-2",
                        "name":"ask_hermes",
                        "args":{"prompt":"Use this prompt"}
                      }
                    ]
                  }
                }
                """.trimIndent()
            ),
        )
    }

    @Test
    fun `parse ignores tool call with missing required fields`() {
        val raw = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":"call-1",
                    "name":"ask_hermes",
                    "args":{}
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse ignores tool call with blank required fields`() {
        val raw = """
            {
              "toolCall":{
                "functionCalls":[
                  {
                    "id":" ",
                    "name":"ask_hermes",
                    "args":{"prompt":"What should I say?"}
                  }
                ]
              }
            }
        """.trimIndent()

        assertEquals(GeminiLiveEvent.Ignored(raw), codec.parseServerMessage(raw))
    }

    @Test
    fun `parse tool call cancellation ids`() {
        assertEquals(
            GeminiLiveEvent.ToolCallCancellation(listOf("call-1", "call-2")),
            codec.parseServerMessage("""{"toolCallCancellation":{"ids":["call-1","call-2"]}}"""),
        )
    }

    @Test
    fun `invalid json returns error with raw text`() {
        val raw = "{not-json"

        val event = codec.parseServerMessage(raw)

        assertTrue(event is GeminiLiveEvent.Error)
        event as GeminiLiveEvent.Error
        assertTrue(event.message.isNotBlank())
        assertEquals(raw, event.raw)
    }

    @Test
    fun `unrecognized json is ignored with raw text`() {
        val raw = """{"serverContent":{"modelTurn":{"parts":[{"text":"not audio"}]}}}"""

        val event = codec.parseServerMessage(raw)

        assertEquals(GeminiLiveEvent.Ignored(raw), event)
    }

    private fun String.jsonObject(): JsonObject = JsonInstant.parseToJsonElement(this).jsonObject
}
