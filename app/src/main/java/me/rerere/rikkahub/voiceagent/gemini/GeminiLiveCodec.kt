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
    ): String {
        val generationConfigElement = liveConnectConfig["generationConfig"]
        val generationConfig = generationConfigElement?.jsonObjectOrNull()
        val topLevelResponseModalities = liveConnectConfig["responseModalities"]

        return json.encodeToString(
            buildJsonObject {
                putJsonObject("setup") {
                    liveConnectConfig.forEach { (key, value) ->
                        if (key != "responseModalities" && key != "generationConfig") {
                            put(key, value)
                        }
                    }
                    put("model", "models/$providerModel")
                    if (generationConfig != null || topLevelResponseModalities != null) {
                        put(
                            "generationConfig",
                            buildJsonObject {
                                generationConfig?.forEach { (key, value) -> put(key, value) }
                                topLevelResponseModalities?.let { put("responseModalities", it) }
                            },
                        )
                    } else if (generationConfigElement != null) {
                        put("generationConfig", generationConfigElement)
                    }
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
    }

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
                putJsonObject("audio") {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Pcm16)
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

        if ("sessionResumptionUpdate" in root) {
            return root.sessionResumptionUpdate(text)
        }

        root.toolCallEvent()?.let { return it }
        root.toolCallCancellation()?.let { return it }

        val serverContent = root["serverContent"] as? JsonObject
        if (serverContent?.get("interrupted")?.jsonPrimitiveOrNull()?.booleanOrNull == true) {
            return GeminiLiveEvent.Interrupted()
        }
        serverContent?.transcript("inputTranscription")?.let { return GeminiLiveEvent.InputTranscript(it) }
        serverContent?.transcript("outputTranscription")?.let { return GeminiLiveEvent.OutputTranscript(it) }
        serverContent?.outputAudio()?.let { return GeminiLiveEvent.OutputAudio(it) }

        return GeminiLiveEvent.Ignored(text)
    }

    private fun JsonObject.toolCallEvent(): GeminiLiveEvent? {
        val functionCalls = this["toolCall"]
            ?.jsonObjectOrNull()
            ?.get("functionCalls")
            ?.jsonArrayOrNull()
            ?: return null
        val unsupportedCalls = mutableListOf<GeminiLiveEvent.UnsupportedToolCall>()
        val calls = functionCalls.mapNotNull { functionCallElement ->
            val functionCall = functionCallElement.jsonObjectOrNull() ?: return@mapNotNull null
            val callId = functionCall["id"]?.stringContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val name = functionCall["name"]?.stringContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (name != ASK_HERMES_TOOL_NAME) {
                unsupportedCalls += GeminiLiveEvent.UnsupportedToolCall(
                    callId = callId,
                    name = name,
                )
                return@mapNotNull null
            }
            val prompt = functionCall["args"]
                    ?.jsonObjectOrNull()
                    ?.get("prompt")
                    ?.stringContentOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
            GeminiLiveEvent.ToolCall(
                callId = callId,
                name = name,
                prompt = prompt,
            )
        }
        return when (calls.size) {
            0 -> if (unsupportedCalls.isEmpty()) null else {
                GeminiLiveEvent.ToolCalls(calls = emptyList(), unsupportedCalls = unsupportedCalls)
            }
            1 -> if (unsupportedCalls.isEmpty()) {
                calls.first()
            } else {
                GeminiLiveEvent.ToolCalls(calls = calls, unsupportedCalls = unsupportedCalls)
            }
            else -> GeminiLiveEvent.ToolCalls(calls = calls, unsupportedCalls = unsupportedCalls)
        }
    }

    private fun JsonObject.toolCallCancellation(): GeminiLiveEvent.ToolCallCancellation? {
        val ids = this["toolCallCancellation"]
            ?.jsonObjectOrNull()
            ?.get("ids")
            ?.jsonArrayOrNull()
            ?.mapNotNull { it.stringContentOrNull()?.takeIf { id -> id.isNotBlank() } }
            ?: return null
        if (ids.isEmpty()) return null
        return GeminiLiveEvent.ToolCallCancellation(ids)
    }

    private fun JsonObject.sessionResumptionUpdate(raw: String): GeminiLiveEvent {
        val update = this["sessionResumptionUpdate"]?.jsonObjectOrNull()
            ?: return GeminiLiveEvent.Ignored(raw)
        val resumable = update["resumable"]?.booleanContentOrNull()
            ?: return GeminiLiveEvent.Ignored(raw)
        val newHandle = if ("newHandle" in update) {
            update["newHandle"]?.stringContentOrNull()
                ?: return GeminiLiveEvent.Ignored(raw)
        } else {
            null
        }
        return GeminiLiveEvent.SessionResumptionUpdate(
            newHandle = newHandle,
            resumable = resumable,
        )
    }

    private fun JsonObject.transcript(key: String): String? =
        this[key]
            ?.jsonObjectOrNull()
            ?.get("text")
            ?.stringContentOrNull()

    private fun JsonObject.outputAudio(): String? =
        this["modelTurn"]
            ?.jsonObjectOrNull()
            ?.get("parts")
            ?.jsonArrayOrNull()
            ?.firstNotNullOfOrNull { part ->
                val inlineData = part.jsonObjectOrNull()
                    ?.get("inlineData")
                    ?.jsonObjectOrNull()
                val mimeType = inlineData
                    ?.get("mimeType")
                    ?.stringContentOrNull()
                inlineData
                    ?.takeIf { mimeType?.startsWith("audio/pcm") == true }
                    ?.get("data")
                    ?.stringContentOrNull()
            }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    private fun JsonElement.stringContentOrNull(): String? {
        val primitive = jsonPrimitiveOrNull() ?: return null
        return primitive.takeIf { it.isString }?.contentOrNull
    }

    private fun JsonElement.booleanContentOrNull(): Boolean? {
        val primitive = jsonPrimitiveOrNull() ?: return null
        return primitive.takeUnless { it.isString }?.booleanOrNull
    }

    private companion object {
        const val ASK_HERMES_TOOL_NAME = "ask_hermes"
    }
}
