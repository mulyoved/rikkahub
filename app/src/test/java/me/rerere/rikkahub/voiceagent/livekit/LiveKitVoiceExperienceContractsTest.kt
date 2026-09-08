package me.rerere.rikkahub.voiceagent.livekit

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveKitVoiceExperienceContractsTest {
    @Test
    fun `shared history corpus matches source and production semantic decoder`() {
        val bytes = javaClass.classLoader
            ?.getResourceAsStream(CONTRACT_RESOURCE)
            ?.use { it.readBytes() }
            ?: error("LiveKit history contract is missing")
        val corpus = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        val context = corpus.decoderContext()

        assertEquals(EXPECTED_CONTRACT_SHA256, bytes.sha256())
        assertEquals("voice.persist.v1", corpus.getValue("rpcMethod").jsonPrimitive.content)
        assertEquals("transport-only", corpus.getValue("rpcResponseMeaning").jsonPrimitive.content)
        assertEquals(
            RETAINED_KINDS,
            corpus.getValue("retainedKinds").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            REMOVED_KINDS,
            corpus.getValue("removedKinds").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )

        corpus.getValue("positive").jsonArray.forEach { element ->
            val case = element.jsonObject
            assertNotNull(
                case.getValue("name").jsonPrimitive.content,
                context.decode(case.getValue("json").jsonPrimitive.content),
            )
        }
        corpus.getValue("negative").jsonArray.forEach { element ->
            val case = element.jsonObject
            assertNull(
                case.getValue("name").jsonPrimitive.content,
                context.decode(case.getValue("json").jsonPrimitive.content),
            )
        }
    }

    @Test
    fun `equivalent JSON has one semantic fingerprint`() {
        val corpus = contractCorpus()
        val context = corpus.decoderContext()

        corpus.getValue("equivalent").jsonArray.forEach { element ->
            val case = element.jsonObject
            val fingerprints = case.getValue("json").jsonArray.map { raw ->
                requireNotNull(context.decode(raw.jsonPrimitive.content)).semanticFingerprint()
            }
            assertEquals(case.getValue("name").jsonPrimitive.content, 1, fingerprints.toSet().size)
        }
    }

    @Test
    fun `duplicate escaped key is rejected before decoding`() {
        val corpus = contractCorpus()
        val context = corpus.decoderContext()
        val canonical = corpus.getValue("positive").jsonArray
            .first()
            .jsonObject
            .getValue("json")
            .jsonPrimitive
            .content
        val duplicate = canonical.replace("\"version\":1", "\"version\":1,\"vers\\u0069on\":1")

        assertNull(context.decode(duplicate))
    }

    @Test
    fun `explicit null transcript grounding is rejected`() {
        val corpus = contractCorpus()
        val context = corpus.decoderContext()
        val transcript = corpus.getValue("positive").jsonArray
            .first { element ->
                element.jsonObject.getValue("name").jsonPrimitive.content == "transcript"
            }
            .jsonObject
            .getValue("json")
            .jsonPrimitive
            .content
        val explicitNullGrounding = transcript
            .replace(Regex("\"groundedJobId\":\"[^\"]+\""), "\"groundedJobId\":null")
            .replace(
                Regex("\"groundedResultHash\":\"[^\"]+\""),
                "\"groundedResultHash\":null",
            )

        assertNull(context.decode(explicitNullGrounding))
    }
}

private data class DecoderContext(
    val voiceSessionId: String,
    val correlation: LiveKitJobCorrelation,
) {
    fun decode(payload: String): LiveKitVoiceExperienceEvent? =
        parseLiveKitVoiceExperienceEvent(
            payload = payload,
            expectedVoiceSessionId = voiceSessionId,
            expectedCorrelation = correlation,
        )
}

private fun contractCorpus() = Json.parseToJsonElement(
    requireNotNull(
        LiveKitVoiceExperienceContractsTest::class.java.classLoader
            ?.getResource(CONTRACT_RESOURCE),
    ).readText(),
).jsonObject

private fun kotlinx.serialization.json.JsonObject.decoderContext(): DecoderContext {
    val binding = getValue("binding").jsonObject
    return DecoderContext(
        voiceSessionId = binding.getValue("voice_session_id").jsonPrimitive.content,
        correlation = LiveKitJobCorrelation(
            ownerHash = binding.getValue("owner_hash").jsonPrimitive.content,
            conversationHash = binding.getValue("conversation_hash").jsonPrimitive.content,
            voiceSessionHash = binding.getValue("voice_session_hash").jsonPrimitive.content,
            roomHash = binding.getValue("room_hash").jsonPrimitive.content,
            traceHash = binding.getValue("trace_hash").jsonPrimitive.content,
        ),
    )
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte ->
        byte.toInt().and(0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
    }

private const val CONTRACT_RESOURCE = "contracts/livekit-history-wire-examples-v1.json"
private const val EXPECTED_CONTRACT_SHA256 =
    "add066d20b216ef4d7ca28764b54a7756f7f7d4f4a47d2f28e542cb7d935e595"
private val RETAINED_KINDS = setOf(
    "job_accepted",
    "job_running",
    "still_working",
    "job_succeeded",
    "job_failed",
    "job_expired",
    "job_canceled",
    "transcript",
    "delivery_announced",
)
private val REMOVED_KINDS = setOf(
    "delivery_eligible",
    "delivery_started",
    "delivery_blocked",
    "speech_started",
    "follow_up_correlation",
    "session_binding",
)
