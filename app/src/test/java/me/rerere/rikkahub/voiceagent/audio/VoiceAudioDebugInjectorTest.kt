package me.rerere.rikkahub.voiceagent.audio

import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoiceAudioDebugInjectorTest {
    @Test
    fun `default injection reports exact chunks before completion`() {
        VoiceAudioDebugInjector.clearForTest()
        val callbacks = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val probe = object : VoiceAutomationAudioProbe {
            override fun onInjectionStarted(totalBytes: Long) {
                callbacks += "started:$totalBytes"
            }

            override fun onInjectionChunk(byteCount: Int) {
                callbacks += "chunk:$byteCount"
            }

            override fun onInjectionCompleted() {
                callbacks += "completed"
            }

            override fun onOutputQueued(byteCount: Int) = Unit
            override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) = Unit
            override fun onOutputDrained() = Unit
            override fun onInterruptionStarted() = Unit
            override fun onOutputSilenceConfirmed() = Unit
        }
        val registration = VoiceAudioDebugInjector.registerCapture(onPcm16 = {})

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = ByteArray(6_402) { 1 },
            chunkBytes = VoiceAudioDebugInjector.DEFAULT_CHUNK_BYTES,
            chunkDelayMs = VoiceAudioDebugInjector.DEFAULT_CHUNK_DELAY_MS,
            leadingSilenceMs = 0,
            trailingSilenceMs = 0,
            sleep = sleeps::add,
            automationAudioProbe = probe,
        )

        assertTrue(result.delivered)
        assertEquals(3, result.chunkCount)
        assertEquals(
            listOf(
                "started:6402",
                "chunk:3200",
                "chunk:3200",
                "chunk:2",
                "completed",
            ),
            callbacks,
        )
        assertEquals(listOf(100L, 100L), sleeps)
        registration.close()
    }

    @Test
    fun `stale delayed registration cannot replace newer active capture`() {
        VoiceAudioDebugInjector.clearForTest()
        val currentOwner = AtomicReference("A")
        val staleReady = CountDownLatch(1)
        val publishStale = CountDownLatch(1)
        val staleChunks = mutableListOf<ByteArray>()
        val currentChunks = mutableListOf<ByteArray>()
        var staleRegistration: VoiceAudioDebugInjector.Registration? = null
        val staleThread = Thread {
            staleReady.countDown()
            publishStale.await()
            staleRegistration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
                onPcm16 = staleChunks::add,
                onInjectionComplete = {},
                isCurrent = { currentOwner.get() == "A" },
            )
        }
        staleThread.start()
        assertTrue(staleReady.await(5, TimeUnit.SECONDS))

        currentOwner.set("B")
        val currentRegistration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
            onPcm16 = currentChunks::add,
            onInjectionComplete = {},
            isCurrent = { currentOwner.get() == "B" },
        )
        publishStale.countDown()
        staleThread.join(5_000)

        assertFalse(staleThread.isAlive)
        assertEquals(null, staleRegistration)
        assertTrue(currentRegistration != null)
        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )
        assertTrue(result.delivered)
        assertEquals(emptyList<ByteArray>(), staleChunks)
        assertEquals(listOf(byteArrayOf(1, 2).toList()), currentChunks.map(ByteArray::toList))
        currentRegistration?.close()
    }

    @Test
    fun `inject rejects when no capture callback is active`() {
        VoiceAudioDebugInjector.clearForTest()

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertFalse(result.delivered)
        assertEquals("No active Voice Agent capture session", result.message)
    }

    @Test
    fun `inject delivers ordered chunks to active capture callback`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val sleeps = mutableListOf<Long>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4, 5, 6),
            chunkBytes = 2,
            chunkDelayMs = 7L,
            sleep = sleeps::add,
        )

        assertTrue(result.delivered)
        assertEquals(6, result.bytes)
        assertEquals(3, result.chunkCount)
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5, 6)), chunks)
        assertEquals(listOf(7L, 7L), sleeps)
        registration.close()
    }

    @Test
    fun `inject notifies active capture when delivered prompt is complete`() {
        VoiceAudioDebugInjector.clearForTest()
        var completionCount = 0
        val registration = VoiceAudioDebugInjector.registerCapture(
            onPcm16 = {},
            onInjectionComplete = { completionCount += 1 },
        )

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertTrue(result.delivered)
        assertEquals(1, completionCount)
        registration.close()
    }

    @Test
    fun `closed registration no longer receives injected chunks`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<ByteArray>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk
        }
        registration.close()

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2),
            chunkBytes = 2,
            chunkDelayMs = 0L,
        )

        assertFalse(result.delivered)
        assertEquals(emptyList<ByteArray>(), chunks)
    }

    @Test
    fun `inject can wrap pcm with leading and trailing silence`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3, 4),
            chunkBytes = 8,
            chunkDelayMs = 0L,
            leadingSilenceMs = 1,
            trailingSilenceMs = 1,
        )

        assertTrue(result.delivered)
        assertEquals(68, result.bytes)
        assertEquals(9, result.chunkCount)
        assertEquals(List(32) { 0.toByte() } + listOf(1.toByte(), 2.toByte(), 3.toByte(), 4.toByte()) + List(32) { 0.toByte() }, chunks.flatten())
        registration.close()
    }

    @Test
    fun `inject aligns odd chunk size and odd pcm length to pcm16 samples`() {
        VoiceAudioDebugInjector.clearForTest()
        val chunks = mutableListOf<List<Byte>>()
        val registration = VoiceAudioDebugInjector.registerCapture { chunk ->
            chunks += chunk.toList()
        }

        val result = VoiceAudioDebugInjector.injectPcm16(
            pcm16 = byteArrayOf(1, 2, 3),
            chunkBytes = 3,
            chunkDelayMs = 0L,
            leadingSilenceMs = 0,
            trailingSilenceMs = 0,
        )

        assertTrue(result.delivered)
        assertEquals(4, result.bytes)
        assertEquals(2, result.chunkCount)
        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 0)), chunks)
        registration.close()
    }

    @Test
    fun `concurrent producers serialize complete sessions at the injector boundary`() {
        VoiceAudioDebugInjector.clearForTest()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstPaused = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val completionCount = AtomicInteger()
        val firstFailure = AtomicReference<Throwable?>()
        val secondFailure = AtomicReference<Throwable?>()
        val registration = VoiceAudioDebugInjector.registerCapture(
            onPcm16 = { chunk -> events += "capture:${chunk.first()}" },
            onInjectionComplete = {
                events += "capture-complete:${completionCount.incrementAndGet()}"
            },
        )
        val firstThread = Thread {
            try {
                VoiceAudioDebugInjector.injectPcm16(
                    pcm16 = byteArrayOf(1, 1, 2, 2),
                    chunkBytes = 2,
                    chunkDelayMs = 1,
                    leadingSilenceMs = 0,
                    trailingSilenceMs = 0,
                    sleep = {
                        firstPaused.countDown()
                        check(releaseFirst.await(5, TimeUnit.SECONDS)) {
                            "first producer release timed out"
                        }
                    },
                    automationAudioProbe = RecordingInjectionProbe("first", events),
                )
            } catch (error: Throwable) {
                firstFailure.set(error)
            }
        }
        val secondThread = Thread {
            try {
                VoiceAudioDebugInjector.injectPcm16(
                    pcm16 = byteArrayOf(3, 3),
                    chunkBytes = 2,
                    chunkDelayMs = 0,
                    leadingSilenceMs = 0,
                    trailingSilenceMs = 0,
                    sleep = {},
                    automationAudioProbe = RecordingInjectionProbe(
                        label = "second",
                        events = events,
                        onStarted = secondStarted::countDown,
                    ),
                )
            } catch (error: Throwable) {
                secondFailure.set(error)
            }
        }
        try {
            firstThread.start()
            assertTrue(firstPaused.await(5, TimeUnit.SECONDS))
            secondThread.start()

            assertFalse(secondStarted.await(200, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)

            assertFalse(firstThread.isAlive)
            assertFalse(secondThread.isAlive)
            assertEquals(null, firstFailure.get())
            assertEquals(null, secondFailure.get())
            assertTrue(events.indexOf("first-complete") < events.indexOf("second-start"))
            assertTrue(events.indexOf("capture-complete:1") < events.indexOf("second-start"))
            assertEquals(2, completionCount.get())
        } finally {
            releaseFirst.countDown()
            firstThread.join(5_000)
            secondThread.join(5_000)
            registration.close()
            VoiceAudioDebugInjector.clearForTest()
        }
    }

    private class RecordingInjectionProbe(
        private val label: String,
        private val events: MutableList<String>,
        private val onStarted: () -> Unit = {},
    ) : VoiceAutomationAudioProbe {
        override fun onInjectionStarted(totalBytes: Long) {
            events += "$label-start"
            onStarted()
        }

        override fun onInjectionChunk(byteCount: Int) {
            events += "$label-chunk"
        }

        override fun onInjectionCompleted() {
            events += "$label-complete"
        }

        override fun onOutputQueued(byteCount: Int) = Unit
        override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) = Unit
        override fun onOutputDrained() = Unit
        override fun onInterruptionStarted() = Unit
        override fun onOutputSilenceConfirmed() = Unit
    }
}
