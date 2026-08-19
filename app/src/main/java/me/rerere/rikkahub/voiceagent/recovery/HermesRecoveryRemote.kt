package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi

internal const val HERMES_RECOVERY_PROTOCOL = "livekit-recovery-v1"
internal const val HERMES_SERVER_DEFAULT_PROFILE = "server-default"

internal data class HermesRecoveryHttpResponse(
    val statusCode: Int,
    val ownerHash: String?,
    val payload: JsonObject?,
) {
    override fun toString(): String =
        "HermesRecoveryHttpResponse(" +
            "statusCode=$statusCode, " +
            "hasOwnerHash=${ownerHash != null}, " +
            "hasPayload=${payload != null}" +
            ")"
}

internal data class HermesRecoveryCorrelation(
    val ownerHash: String,
    val conversationId: String,
    val voiceSessionId: String,
    val traceId: String,
    val argumentHash: String,
) {
    override fun toString(): String =
        "HermesRecoveryCorrelation([redacted])"
}

internal data class HermesRecoveryFailure(
    val kind: String,
    val safeMessage: String,
    val safeSummary: String,
    val retryable: Boolean,
    val source: String,
) {
    override fun toString(): String =
        "HermesRecoveryFailure(kind=$kind, retryable=$retryable, source=$source)"
}

internal data class HermesRecoverySnapshot(
    val jobId: String,
    val callId: String?,
    val rawStatus: String,
    val acceptedAt: String?,
    val answer: String?,
    val resultHash: String?,
    val failure: HermesRecoveryFailure?,
    val correlation: HermesRecoveryCorrelation?,
) {
    override fun toString(): String =
        "HermesRecoverySnapshot(" +
            "jobId=$jobId, " +
            "callId=$callId, " +
            "rawStatus=$rawStatus, " +
            "acceptedAt=$acceptedAt, " +
            "hasAnswer=${answer != null}, " +
            "hasResultHash=${resultHash != null}, " +
            "failure=${failure?.kind}, " +
            "hasCorrelation=${correlation != null}" +
            ")"
}

internal interface HermesRecoveryRemote {
    suspend fun poll(jobId: String): HermesRecoveryHttpResponse
    suspend fun cancel(jobId: String): HermesRecoveryHttpResponse

    companion object {
        operator fun invoke(api: HermesVoiceApi): HermesRecoveryRemote =
            HermesVoiceRecoveryRemote(api)
    }
}

internal class HermesVoiceRecoveryRemote(
    private val api: HermesVoiceApi,
) : HermesRecoveryRemote {
    override suspend fun poll(jobId: String): HermesRecoveryHttpResponse =
        api.executeRecoveryJobRequest(jobId = jobId, cancel = false)

    override suspend fun cancel(jobId: String): HermesRecoveryHttpResponse =
        api.executeRecoveryJobRequest(jobId = jobId, cancel = true)
}

internal fun parseHermesRecoverySnapshot(payload: JsonObject?): HermesRecoverySnapshot? {
    if (payload == null) return null
    return payload.toHermesRecoverySnapshot()
}

internal fun JsonObject.toHermesRecoverySnapshot(): HermesRecoverySnapshot? {
    val jobIdPrimitive = this["jobId"] as? JsonPrimitive ?: return null
    if (jobIdPrimitive.isString.not() && jobIdPrimitive.contentOrNull == null) return null
    val jobId = jobIdPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

    val statusPrimitive = this["status"] as? JsonPrimitive ?: return null
    if (statusPrimitive.isString.not() && statusPrimitive.contentOrNull == null) return null
    val rawStatus = statusPrimitive.contentOrNull?.takeIf { it.isNotBlank() } ?: return null

    val callId = (this["callId"] as? JsonPrimitive)?.contentOrNull
    val acceptedAt = (this["acceptedAt"] as? JsonPrimitive)?.contentOrNull
    val answer = (this["answer"] as? JsonPrimitive)?.contentOrNull
    val resultHash = (this["resultHash"] as? JsonPrimitive)?.contentOrNull

    val failureObj = this["failure"]
    val failure = when {
        failureObj == null || failureObj is JsonNull -> null
        failureObj is JsonObject -> {
            val kind = (failureObj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val safeMessage = (failureObj["safeMessage"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val safeSummary = (failureObj["safeSummary"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val retryablePrimitive = (failureObj["retryable"] as? JsonPrimitive)?.takeIf { !it.isString }
            val retryable = retryablePrimitive?.booleanOrNull
            val source = (failureObj["source"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

            if (kind == null || safeMessage == null || safeSummary == null || retryable == null || source == null) {
                return null
            }
            HermesRecoveryFailure(
                kind = kind,
                safeMessage = safeMessage,
                safeSummary = safeSummary,
                retryable = retryable,
                source = source,
            )
        }
        else -> return null
    }

    val correlationObj = this["correlation"]
    val correlation = when {
        correlationObj == null || correlationObj is JsonNull -> null
        correlationObj is JsonObject -> {
            val ownerHash = (correlationObj["ownerHash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val conversationId = (correlationObj["conversationId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val voiceSessionId = (correlationObj["voiceSessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val traceId = (correlationObj["traceId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val argumentHash = (correlationObj["argumentHash"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

            if (ownerHash == null || conversationId == null || voiceSessionId == null || traceId == null || argumentHash == null) {
                return null
            }
            HermesRecoveryCorrelation(
                ownerHash = ownerHash,
                conversationId = conversationId,
                voiceSessionId = voiceSessionId,
                traceId = traceId,
                argumentHash = argumentHash,
            )
        }
        else -> return null
    }

    return HermesRecoverySnapshot(
        jobId = jobId,
        callId = callId,
        rawStatus = rawStatus,
        acceptedAt = acceptedAt,
        answer = answer,
        resultHash = resultHash,
        failure = failure,
        correlation = correlation,
    )
}
