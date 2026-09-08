package me.rerere.rikkahub.voiceagent.livekit

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val LIVEKIT_EXPERIENCE_IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,128}$")
private val LIVEKIT_EXPERIENCE_HASH = Regex("^sha256:[0-9a-f]{64}$")

private val LIVEKIT_EXPERIENCE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

@Serializable
internal data class LiveKitJobCorrelation(
    val ownerHash: String,
    val conversationHash: String,
    val voiceSessionHash: String,
    val roomHash: String,
    val traceHash: String,
) {
    fun isValid(voiceSessionId: String): Boolean =
        listOf(ownerHash, conversationHash, voiceSessionHash, roomHash, traceHash)
            .all(String::isLiveKitExperienceHash) &&
            voiceSessionHash == voiceSha256(voiceSessionId)
}

internal fun LiveKitSessionCorrelationBinding.toJobCorrelation(): LiveKitJobCorrelation =
    LiveKitJobCorrelation(
        ownerHash = ownerHash,
        conversationHash = conversationHash,
        voiceSessionHash = voiceSessionHash,
        roomHash = roomHash,
        traceHash = traceHash,
    )

@Serializable
internal sealed interface LiveKitVoiceExperienceEvent {
    val version: Int
    val voiceSessionId: String
    val eventId: String
    val kind: String
    val observedAt: String

    @Serializable
    data class JobAccepted(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val userTurnId: String,
        val requestHash: String,
        val toolCallId: String,
        val argumentHash: String,
        val jobId: String,
        val ownerHash: String,
        val conversationHash: String,
        val voiceSessionHash: String,
        val roomHash: String,
        val traceHash: String,
        val prompt: String,
    ) : LiveKitVoiceExperienceEvent {
        fun correlation(): LiveKitJobCorrelation = LiveKitJobCorrelation(
            ownerHash = ownerHash,
            conversationHash = conversationHash,
            voiceSessionHash = voiceSessionHash,
            roomHash = roomHash,
            traceHash = traceHash,
        )
    }

    @Serializable
    data class JobState(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val userTurnId: String,
        val requestHash: String,
        val toolCallId: String,
        val argumentHash: String,
        val jobId: String,
        val ownerHash: String,
        val conversationHash: String,
        val voiceSessionHash: String,
        val roomHash: String,
        val traceHash: String,
        val resultHash: String? = null,
        val answer: String? = null,
        val failureReason: String? = null,
    ) : LiveKitVoiceExperienceEvent {
        fun correlation(): LiveKitJobCorrelation = LiveKitJobCorrelation(
            ownerHash = ownerHash,
            conversationHash = conversationHash,
            voiceSessionHash = voiceSessionHash,
            roomHash = roomHash,
            traceHash = traceHash,
        )
    }

    @Serializable
    data class Transcript(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val turnId: String,
        val role: String,
        val text: String,
        val interrupted: Boolean,
        val groundedJobId: String? = null,
        val groundedResultHash: String? = null,
    ) : LiveKitVoiceExperienceEvent

    @Serializable
    data class Delivery(
        override val version: Int,
        override val voiceSessionId: String,
        override val eventId: String,
        override val kind: String,
        override val observedAt: String,
        val toolCallId: String,
        val jobId: String,
        val assistantTurnId: String,
    ) : LiveKitVoiceExperienceEvent
}

internal fun parseLiveKitVoiceExperienceEvent(
    payload: String,
    expectedVoiceSessionId: String,
    expectedCorrelation: LiveKitJobCorrelation,
): LiveKitVoiceExperienceEvent? {
    if (hasDuplicateTopLevelKeys(payload)) return null
    val objectValue = runCatching {
        LIVEKIT_EXPERIENCE_JSON.parseToJsonElement(payload).jsonObject
    }.getOrNull() ?: return null
    val kind = objectValue.string("kind") ?: return null
    val event = when (kind) {
        "job_accepted" -> decodeExact<LiveKitVoiceExperienceEvent.JobAccepted>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + "prompt",
        )

        "job_running",
        "still_working",
            -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
                payload = payload,
                objectValue = objectValue,
                requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS,
            )

        "job_succeeded" -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + setOf("resultHash", "answer"),
        )

        "job_failed",
        "job_expired",
        "job_canceled",
            -> decodeExact<LiveKitVoiceExperienceEvent.JobState>(
                payload = payload,
                objectValue = objectValue,
                requiredKeys = BASE_EVENT_KEYS + JOB_CORRELATION_KEYS + "failureReason",
            )

        "transcript" -> decodeExact<LiveKitVoiceExperienceEvent.Transcript>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + setOf("turnId", "role", "text", "interrupted"),
            optionalKeys = setOf("groundedJobId", "groundedResultHash"),
        )

        "delivery_announced" -> decodeExact<LiveKitVoiceExperienceEvent.Delivery>(
            payload = payload,
            objectValue = objectValue,
            requiredKeys = BASE_EVENT_KEYS + DELIVERY_KEYS + "assistantTurnId",
        )

        else -> null
    } ?: return null
    return event.takeIf {
        it.isValid() &&
            it.voiceSessionId == expectedVoiceSessionId &&
            it.hasExpectedCorrelation(expectedCorrelation)
    }
}

internal fun LiveKitVoiceExperienceEvent.canonicalJson(): String = when (this) {
    is LiveKitVoiceExperienceEvent.JobAccepted -> canonicalVoiceExperienceJson(this)
    is LiveKitVoiceExperienceEvent.JobState -> canonicalVoiceExperienceJson(this)
    is LiveKitVoiceExperienceEvent.Transcript -> canonicalVoiceExperienceJson(this)
    is LiveKitVoiceExperienceEvent.Delivery -> canonicalVoiceExperienceJson(this)
}

internal fun LiveKitVoiceExperienceEvent.semanticFingerprint(): String =
    voiceSha256(canonicalJson())

internal fun voiceSha256(text: String): String =
    "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            byte.toInt().and(0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

private inline fun <reified T> decodeExact(
    payload: String,
    objectValue: JsonObject,
    requiredKeys: Set<String>,
    optionalKeys: Set<String> = emptySet(),
): T? {
    if (!objectValue.keys.containsAll(requiredKeys)) return null
    if (!requiredKeys.plus(optionalKeys).containsAll(objectValue.keys)) return null
    if (objectValue.values.any { it is JsonNull }) return null
    val decoded = runCatching {
        LIVEKIT_EXPERIENCE_JSON.decodeFromString<T>(payload)
    }.getOrNull() ?: return null
    return decoded
}

private fun LiveKitVoiceExperienceEvent.hasExpectedCorrelation(
    expected: LiveKitJobCorrelation,
): Boolean {
    return when (this) {
        is LiveKitVoiceExperienceEvent.JobAccepted -> correlation() == expected
        is LiveKitVoiceExperienceEvent.JobState -> correlation() == expected
        else -> true
    }
}

private fun hasDuplicateTopLevelKeys(payload: String): Boolean {
    val keys = mutableSetOf<String>()
    var nesting = 0
    var expectingKey = false
    var inString = false
    var escaped = false
    var keyStart = -1
    var currentStringIsKey = false

    payload.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> {
                    inString = false
                    if (currentStringIsKey) {
                        val encodedKey = payload.substring(keyStart, index + 1)
                        val key = runCatching {
                            LIVEKIT_EXPERIENCE_JSON.decodeFromString<String>(encodedKey)
                        }.getOrNull() ?: return false
                        if (!keys.add(key)) return true
                        expectingKey = false
                    }
                }
            }
        } else {
            when (character) {
                '{', '[' -> {
                    nesting += 1
                    if (nesting == 1 && character == '{') expectingKey = true
                }

                '}', ']' -> nesting -= 1
                ',' -> if (nesting == 1) expectingKey = true
                '"' -> {
                    inString = true
                    keyStart = index
                    currentStringIsKey = nesting == 1 && expectingKey
                }
            }
        }
    }
    return false
}

private fun LiveKitVoiceExperienceEvent.isValid(): Boolean {
    if (
        version != LIVEKIT_EXPERIENCE_VERSION ||
        !voiceSessionId.isLiveKitExperienceIdentifier() ||
        !eventId.isLiveKitExperienceIdentifier() ||
        !observedAt.isCanonicalUtcTimestamp()
    ) return false
    return when (this) {
        is LiveKitVoiceExperienceEvent.JobAccepted ->
            kind == "job_accepted" &&
                hasValidJobCorrelation() &&
                prompt.isNotBlank()

        is LiveKitVoiceExperienceEvent.JobState -> when (kind) {
            "job_running",
            "still_working",
                -> hasValidJobCorrelation() &&
                    resultHash == null &&
                    answer == null &&
                    failureReason == null

            "job_succeeded" ->
                hasValidJobCorrelation() &&
                    answer?.isNotBlank() == true &&
                    resultHash == voiceSha256(answer) &&
                    failureReason == null

            "job_failed",
            "job_expired",
            "job_canceled",
                -> hasValidJobCorrelation() &&
                    failureReason.isSafeFailureReason() &&
                    resultHash == null &&
                    answer == null

            else -> false
        }

        is LiveKitVoiceExperienceEvent.Transcript ->
            kind == "transcript" &&
                turnId.isLiveKitExperienceIdentifier() &&
                role in TRANSCRIPT_ROLES &&
                text.isNotBlank() &&
                (role != "user" || !interrupted) &&
                hasValidGrounding()

        is LiveKitVoiceExperienceEvent.Delivery ->
            kind == "delivery_announced" &&
                toolCallId.isLiveKitExperienceIdentifier() &&
                jobId.isLiveKitExperienceIdentifier() &&
                assistantTurnId.isLiveKitExperienceIdentifier()
    }
}

private fun LiveKitVoiceExperienceEvent.JobAccepted.hasValidJobCorrelation(): Boolean =
    userTurnId.isLiveKitExperienceIdentifier() &&
        requestHash.isLiveKitExperienceHash() &&
        toolCallId.isLiveKitExperienceIdentifier() &&
        argumentHash.isLiveKitExperienceHash() &&
        jobId.isLiveKitExperienceIdentifier() &&
        correlation().isValid(voiceSessionId)

private fun LiveKitVoiceExperienceEvent.JobState.hasValidJobCorrelation(): Boolean =
    userTurnId.isLiveKitExperienceIdentifier() &&
        requestHash.isLiveKitExperienceHash() &&
        toolCallId.isLiveKitExperienceIdentifier() &&
        argumentHash.isLiveKitExperienceHash() &&
        jobId.isLiveKitExperienceIdentifier() &&
        correlation().isValid(voiceSessionId)

private fun LiveKitVoiceExperienceEvent.Transcript.hasValidGrounding(): Boolean {
    val bothGrounded = groundedJobId != null && groundedResultHash != null
    val neitherGrounded = groundedJobId == null && groundedResultHash == null
    if (!bothGrounded && !neitherGrounded) return false
    if (role == "user") return neitherGrounded
    return neitherGrounded || (
        groundedJobId?.isLiveKitExperienceIdentifier() == true &&
            groundedResultHash?.isLiveKitExperienceHash() == true
        )
}

private fun String?.isSafeFailureReason(): Boolean =
    this != null &&
        isNotBlank() &&
        length <= MAX_FAILURE_REASON_LENGTH &&
        none(Char::isISOControl)

private fun String.isLiveKitExperienceIdentifier(): Boolean =
    LIVEKIT_EXPERIENCE_IDENTIFIER.matches(this)

private fun String.isLiveKitExperienceHash(): Boolean =
    LIVEKIT_EXPERIENCE_HASH.matches(this)

private fun String.isCanonicalUtcTimestamp(): Boolean =
    CanonicalVoiceExperienceJson.isCanonicalInstant(this)

private inline fun <reified T> canonicalVoiceExperienceJson(value: T): String =
    CanonicalVoiceExperienceJson.encodeObject(
        LIVEKIT_EXPERIENCE_JSON.encodeToJsonElement(value).jsonObject,
    )

private fun JsonObject.string(key: String): String? =
    runCatching { getValue(key).jsonPrimitive.content }.getOrNull()

private const val LIVEKIT_EXPERIENCE_VERSION = 1
private const val MAX_FAILURE_REASON_LENGTH = 512
private val TRANSCRIPT_ROLES = setOf("user", "assistant")
private val BASE_EVENT_KEYS =
    setOf("version", "voiceSessionId", "eventId", "kind", "observedAt")
private val JOB_CORRELATION_KEYS =
    setOf(
        "userTurnId",
        "requestHash",
        "toolCallId",
        "argumentHash",
        "jobId",
        "ownerHash",
        "conversationHash",
        "voiceSessionHash",
        "roomHash",
        "traceHash",
    )
private val DELIVERY_KEYS = setOf("toolCallId", "jobId")
