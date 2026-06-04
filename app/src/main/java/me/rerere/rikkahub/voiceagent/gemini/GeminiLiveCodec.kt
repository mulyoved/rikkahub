package me.rerere.rikkahub.voiceagent.gemini

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.rikkahub.utils.JsonInstant

class GeminiLiveCodec(
    private val json: Json = JsonInstant,
) {
    fun setupMessage(
        providerModel: String,
        liveConnectConfig: JsonObject,
        systemInstruction: String,
    ): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/$providerModel")
                putJsonObject("generationConfig") {
                    put(
                        "responseModalities",
                        liveConnectConfig["responseModalities"] ?: JsonArray(emptyList()),
                    )
                }
                put(
                    "inputAudioTranscription",
                    liveConnectConfig["inputAudioTranscription"] ?: JsonObject(emptyMap()),
                )
                put(
                    "outputAudioTranscription",
                    liveConnectConfig["outputAudioTranscription"] ?: JsonObject(emptyMap()),
                )
                put("tools", liveConnectConfig["tools"] ?: JsonArray(emptyList()))
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        add(
                            buildJsonObject {
                                put("text", systemInstruction)
                            }
                        )
                    }
                }
            }
        }
    )

    fun clientContentMessage(turns: List<GeminiContentTurn>): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    turns.forEach { turn ->
                        add(
                            buildJsonObject {
                                put("role", turn.role)
                                putJsonArray("parts") {
                                    add(
                                        buildJsonObject {
                                            put("text", turn.text)
                                        }
                                    )
                                }
                            }
                        )
                    }
                }
                put("turnComplete", false)
            }
        }
    )

    fun realtimeAudioMessage(base64Pcm16: String): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    add(
                        buildJsonObject {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Pcm16)
                        }
                    )
                }
            }
        }
    )

    fun toolResponseMessage(callId: String, answer: String): String = json.encodeToString(
        buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    add(
                        buildJsonObject {
                            put("id", callId)
                            put("name", ASK_HERMES_TOOL_NAME)
                            putJsonObject("response") {
                                put("answer", answer)
                            }
                        }
                    )
                }
            }
        }
    )

    fun parseServerMessage(text: String): GeminiLiveEvent {
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse { error ->
            return GeminiLiveEvent.Error(
                message = error.message ?: error.javaClass.simpleName,
                raw = text,
            )
        }
        val root = element as? JsonObject ?: return GeminiLiveEvent.Ignored(text)

        if ("setupComplete" in root) {
            return GeminiLiveEvent.SetupComplete
        }

        root.firstToolCall()?.let { return it }
        root.toolCallCancellation()?.let { return it }

        val serverContent = root["serverContent"] as? JsonObject
        if (serverContent?.get("interrupted")?.jsonPrimitive?.booleanOrNull == true) {
            return GeminiLiveEvent.Interrupted()
        }
        serverContent?.transcript("inputTranscription")?.let { return GeminiLiveEvent.InputTranscript(it) }
        serverContent?.transcript("outputTranscription")?.let { return GeminiLiveEvent.OutputTranscript(it) }
        serverContent?.outputAudio()?.let { return GeminiLiveEvent.OutputAudio(it) }

        return GeminiLiveEvent.Ignored(text)
    }

    private fun JsonObject.firstToolCall(): GeminiLiveEvent.ToolCall? {
        val functionCall = this["toolCall"]
            ?.jsonObjectOrNull()
            ?.get("functionCalls")
            ?.jsonArrayOrNull()
            ?.firstOrNull()
            ?.jsonObjectOrNull()
            ?: return null
        return GeminiLiveEvent.ToolCall(
            callId = functionCall["id"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty(),
            name = functionCall["name"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty(),
            prompt = functionCall["args"]
                ?.jsonObjectOrNull()
                ?.get("prompt")
                ?.jsonPrimitiveOrNull()
                ?.contentOrNull
                .orEmpty(),
        )
    }

    private fun JsonObject.toolCallCancellation(): GeminiLiveEvent.ToolCallCancellation? {
        val ids = this["toolCallCancellation"]
            ?.jsonObjectOrNull()
            ?.get("ids")
            ?.jsonArrayOrNull()
            ?.mapNotNull { it.jsonPrimitiveOrNull()?.contentOrNull }
            ?: return null
        return GeminiLiveEvent.ToolCallCancellation(ids)
    }

    private fun JsonObject.transcript(key: String): String? =
        this[key]
            ?.jsonObjectOrNull()
            ?.get("text")
            ?.jsonPrimitiveOrNull()
            ?.contentOrNull

    private fun JsonObject.outputAudio(): String? =
        this["modelTurn"]
            ?.jsonObjectOrNull()
            ?.get("parts")
            ?.jsonArrayOrNull()
            ?.firstNotNullOfOrNull { part ->
                part.jsonObjectOrNull()
                    ?.get("inlineData")
                    ?.jsonObjectOrNull()
                    ?.get("data")
                    ?.jsonPrimitiveOrNull()
                    ?.contentOrNull
            }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private companion object {
        const val ASK_HERMES_TOOL_NAME = "ask_hermes"
    }
}
