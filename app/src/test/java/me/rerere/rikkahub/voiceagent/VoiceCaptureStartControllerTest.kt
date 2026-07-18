package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptureStartControllerTest {
    @Test
    fun `replacement cancels exact prior job without clearing current ownership`() = runTest {
        val starts = ArrayDeque<SuspendedControllerStart>()
        val controller = VoiceCaptureStartController(
            scope = this,
            lock = Any(),
            canStart = { true },
            canHandleFailure = { true },
            startCapture = {
                val start = starts.removeFirst()
                start.entered.complete(Unit)
                try {
                    start.release.await()
                } finally {
                    start.completed.complete(Unit)
                }
            },
            onFailure = { _, _ -> error("unexpected capture failure") },
        )
        val first = SuspendedControllerStart().also(starts::addLast)
        val second = SuspendedControllerStart().also(starts::addLast)

        controller.launch(sessionId = 1L)
        await(first.entered)
        controller.launch(sessionId = 1L)
        await(first.completed)
        await(second.entered)

        assertTrue(controller.hasOwnedJob())
        second.release.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            while (controller.hasOwnedJob()) delay(10)
        }
        assertFalse(controller.hasOwnedJob())
    }

    @Test
    fun `only exact current active job can claim background failure`() = runTest {
        var canHandleFailure = false
        val failures = mutableListOf<Pair<Long, Throwable>>()
        val staleFailure = IllegalStateException("stale")
        val currentFailure = IllegalArgumentException("current")
        val starts = ArrayDeque<suspend () -> Unit>().apply {
            addLast { throw staleFailure }
            addLast { throw currentFailure }
        }
        val controller = VoiceCaptureStartController(
            scope = this,
            lock = Any(),
            canStart = { true },
            canHandleFailure = { canHandleFailure },
            startCapture = { starts.removeFirst().invoke() },
            onFailure = { sessionId, failure -> failures += sessionId to failure },
        )

        controller.launch(sessionId = 1L)
        withTimeout(TEST_TIMEOUT_MS) {
            while (controller.hasOwnedJob()) delay(10)
        }
        canHandleFailure = true
        controller.launch(sessionId = 2L)
        withTimeout(TEST_TIMEOUT_MS) {
            while (failures.isEmpty()) delay(10)
        }

        assertEquals(listOf(2L to currentFailure), failures)
        assertFalse(controller.hasOwnedJob())
    }

    private suspend fun await(signal: Deferred<Unit>) {
        withTimeout(TEST_TIMEOUT_MS) { signal.await() }
    }

    private fun runTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(block = block)

    private data class SuspendedControllerStart(
        val entered: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        val completed: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private companion object {
        const val TEST_TIMEOUT_MS = 500L
    }
}
