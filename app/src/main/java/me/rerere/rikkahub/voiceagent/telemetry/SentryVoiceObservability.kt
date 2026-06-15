package me.rerere.rikkahub.voiceagent.telemetry

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

data class SentryVoiceObservabilityConfig(
    val dsn: String,
    val environment: String = "development",
    val tracesSampleRate: Double = 0.0,
)

class SentryVoiceObservability : VoiceObservability {
    override fun recordEvent(
        name: String,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        Sentry.addBreadcrumb(
            voiceBreadcrumb(
                name = name,
                trace = trace,
                attributes = attributes,
            )
        )
    }

    override suspend fun <T> withSpan(
        name: String,
        trace: VoiceTraceContext,
        block: suspend (VoiceSpan) -> T,
    ): T {
        val span = MutableSentryVoiceSpan()
        recordEvent("$name.started", trace)
        return try {
            val result = block(span)
            recordEvent("$name.succeeded", trace, span.attributes)
            result
        } catch (throwable: Throwable) {
            recordEvent("$name.failed", trace, span.attributes)
            captureException(throwable, trace, span.attributes)
            throw throwable
        }
    }

    override fun captureException(
        throwable: Throwable,
        trace: VoiceTraceContext,
        attributes: VoiceAttributes,
    ) {
        Sentry.withScope { scope ->
            scope.setTag("voice_trace_id", trace.traceId)
            scope.setTag("voice_session_id", trace.voiceSessionId)
            attributes.withoutNullValues().forEach { (key, value) ->
                scope.setExtra(key, value.toString())
            }
            Sentry.captureException(throwable)
        }
    }
}

fun createSentryVoiceObservability(
    context: Context,
    config: SentryVoiceObservabilityConfig,
): VoiceObservability {
    if (config.dsn.isBlank()) {
        return NoOpVoiceObservability
    }

    SentryAndroid.init(context) { options ->
        options.dsn = config.dsn
        options.environment = config.environment.ifBlank { "development" }
        options.tracesSampleRate = config.tracesSampleRate.coerceIn(0.0, 1.0)
    }
    return SentryVoiceObservability()
}

private class MutableSentryVoiceSpan : VoiceSpan {
    val attributes = mutableMapOf<String, Any?>()

    override fun setAttribute(key: String, value: Any?) {
        if (value != null) {
            attributes[key] = value
        }
    }

    override fun setAttributes(attributes: VoiceAttributes) {
        attributes.forEach { (key, value) -> setAttribute(key, value) }
    }
}

private fun voiceBreadcrumb(
    name: String,
    trace: VoiceTraceContext,
    attributes: VoiceAttributes,
): Breadcrumb {
    val breadcrumb = Breadcrumb.info(name)
    breadcrumb.setData("traceId", trace.traceId)
    breadcrumb.setData("voiceSessionId", trace.voiceSessionId)
    attributes.withoutNullValues().forEach { (key, value) ->
        breadcrumb.setData(key, value.toString())
    }
    return breadcrumb
}

private fun VoiceAttributes.withoutNullValues(): Map<String, Any> =
    mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
