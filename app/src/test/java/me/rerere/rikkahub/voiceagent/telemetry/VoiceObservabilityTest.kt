package me.rerere.rikkahub.voiceagent.telemetry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceObservabilityTest {
    private val trace = VoiceTraceContext(
        traceId = "trace-123",
        voiceSessionId = "session-456",
    )

    @Test
    fun `no-op observability executes span callback`() = runBlocking {
        val result = NoOpVoiceObservability.withSpan("voice.span", trace) { span ->
            span.setAttribute("step", "started")
            span.setAttributes(mapOf("ignored" to null))
            "done"
        }

        assertEquals("done", result)
    }

    @Test
    fun `recording observability stores events spans and exceptions without null attributes`() = runBlocking {
        val observability = RecordingVoiceObservability()
        val error = IllegalStateException("boom")

        observability.recordEvent(
            name = "voice.event",
            trace = trace,
            attributes = mapOf(
                "prompt" to "status",
                "ignored" to null,
            ),
        )
        val spanResult = observability.withSpan("voice.span", trace) { span ->
            span.setAttribute("step", "route")
            span.setAttributes(
                mapOf(
                    "ok" to true,
                    "ignored" to null,
                )
            )
            "span-result"
        }
        observability.captureException(
            throwable = error,
            trace = trace,
            attributes = mapOf(
                "route" to "hermes",
                "ignored" to null,
            ),
        )

        assertEquals("span-result", spanResult)
        assertEquals(
            listOf(
                RecordedVoiceEvent(
                    name = "voice.event",
                    trace = trace,
                    attributes = mapOf("prompt" to "status"),
                )
            ),
            observability.events,
        )
        assertEquals(
            listOf(
                RecordedVoiceSpan(
                    name = "voice.span",
                    trace = trace,
                    attributes = mapOf("step" to "route", "ok" to true),
                    status = RecordedVoiceSpanStatus.Ok,
                )
            ),
            observability.spans,
        )
        assertEquals(1, observability.exceptions.size)
        assertSame(error, observability.exceptions.single().throwable)
        assertEquals(mapOf("route" to "hermes"), observability.exceptions.single().attributes)
    }

    @Test
    fun `recording observability records failed spans before rethrowing`() = runBlocking {
        val observability = RecordingVoiceObservability()
        val error = IllegalStateException("boom")

        val thrown = runCatching {
            observability.withSpan("voice.span", trace) { span ->
                span.setAttribute("step", "route")
                throw error
            }
        }.exceptionOrNull()

        assertSame(error, thrown)
        assertEquals(
            listOf(
                RecordedVoiceSpan(
                    name = "voice.span",
                    trace = trace,
                    attributes = mapOf("step" to "route"),
                    status = RecordedVoiceSpanStatus.Error,
                )
            ),
            observability.spans,
        )
    }

    @Test
    fun `voice text payload keeps preview metadata and sha256`() {
        val attributes = voiceTextPayload(
            key = "prompt",
            text = "alpha beta gamma",
            previewChars = 10,
        )

        assertEquals("alpha beta", attributes["prompt"])
        assertEquals(true, attributes["prompt.truncated"])
        assertEquals(16, attributes["prompt.chars"])
        assertEquals(
            "64989ccbf3efa9c84e2afe7cee9bc5828bf0fcb91e44f8c1e591638a2c2e90e3",
            attributes["prompt.sha256"],
        )
        assertFalse(attributes.containsKey("prompt.normalized"))
    }

    @Test
    fun `voice text payload does not mark short text as truncated`() {
        val attributes = voiceTextPayload(
            key = "answer",
            text = "done",
        )

        assertEquals("done", attributes["answer"])
        assertEquals(false, attributes["answer.truncated"])
        assertEquals(4, attributes["answer.chars"])
        assertTrue(attributes["answer.sha256"].toString().isNotBlank())
    }
}
