package me.rerere.rikkahub.voiceagent.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoicePlaybackWriterTest {
    @Test
    fun `play enqueues decoded chunks and writes them fully in order`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 2)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(sink.awaitWrites(2))

        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5, 6)), sink.writes)
        assertEquals(1, sink.startCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.ChunkQueued && it.bytes == 3 })
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.ChunkWritten && it.bytes == 3 })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `play rejects stale session before enqueue`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        var sinkCreations = 0
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertFalse(writer.playBase64(base64Pcm16 = "AQID", sessionId = 99L))

        assertEquals(0, sinkCreations)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.StaleChunkRejected })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `suppress increments generation flushes active sink and skips queued stale chunks`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val staleRejectedLatch = CountDownLatch(2)
        val sink = FakeVoicePcm16Sink(
            expectedWrites = 1,
            blockFirstWrite = true,
        )
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = { diagnostic ->
                diagnostics += diagnostic
                if (diagnostic is VoicePlaybackDiagnostic.StaleChunkRejected) {
                    staleRejectedLatch.countDown()
                }
            },
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWriteStarted())
        assertTrue(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))

        writer.suppress()
        sink.releaseBlockedWrite()
        assertTrue(sink.awaitWrites(1))
        assertTrue(staleRejectedLatch.await(2, TimeUnit.SECONDS))

        assertEquals(listOf(listOf<Byte>(1, 2, 3)), sink.writes)
        assertEquals(1, sink.pauseAndFlushCalls)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.PlaybackSuppressed })
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.StaleChunkRejected })

        writer.release()
        scope.cancel()
    }

    @Test
    fun `release stops sink and rejects future playback`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        val sink = FakeVoicePcm16Sink(expectedWrites = 1)
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = { sink },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)
        assertTrue(writer.playBase64(base64Pcm16 = "AQID", sessionId = 100L))
        assertTrue(sink.awaitWrites(1))

        writer.release()

        assertEquals(1, sink.stopAndReleaseCalls)
        assertFalse(writer.playBase64(base64Pcm16 = "BAUG", sessionId = 100L))
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.Released })

        scope.cancel()
    }

    @Test
    fun `malformed base64 is rejected without creating sink`() {
        val scope = testScope()
        val diagnostics = CopyOnWriteArrayList<VoicePlaybackDiagnostic>()
        var sinkCreations = 0
        val writer = VoicePlaybackWriter(
            scope = scope,
            createSink = {
                sinkCreations += 1
                FakeVoicePcm16Sink()
            },
            onDiagnostic = diagnostics::add,
        )

        writer.activateSession(100L)

        assertFalse(writer.playBase64(base64Pcm16 = "not-base64%", sessionId = 100L))

        assertEquals(0, sinkCreations)
        assertTrue(diagnostics.any { it is VoicePlaybackDiagnostic.MalformedChunk })

        writer.release()
        scope.cancel()
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class FakeVoicePcm16Sink(
        expectedWrites: Int = 0,
        private val blockFirstWrite: Boolean = false,
    ) : VoicePcm16Sink {
        private val writesLatch = CountDownLatch(expectedWrites)
        private val writeStartedLatch = CountDownLatch(1)
        private val releaseBlockedWriteLatch = CountDownLatch(1)
        private val startCallCount = AtomicInteger()
        private val pauseAndFlushCallCount = AtomicInteger()
        private val stopAndReleaseCallCount = AtomicInteger()
        val writes = mutableListOf<List<Byte>>()

        val startCalls: Int
            get() = startCallCount.get()

        val pauseAndFlushCalls: Int
            get() = pauseAndFlushCallCount.get()

        val stopAndReleaseCalls: Int
            get() = stopAndReleaseCallCount.get()

        override fun start(): VoicePcm16Sink.StartResult {
            startCallCount.incrementAndGet()
            return VoicePcm16Sink.StartResult.Started
        }

        override fun writeFully(pcm16: ByteArray): VoicePcm16Sink.WriteResult {
            writeStartedLatch.countDown()
            if (blockFirstWrite && writes.isEmpty()) {
                assertTrue(releaseBlockedWriteLatch.await(2, TimeUnit.SECONDS))
            }
            writes += pcm16.toList()
            writesLatch.countDown()
            return VoicePcm16Sink.WriteResult.Written(pcm16.size)
        }

        override fun pauseAndFlush() {
            pauseAndFlushCallCount.incrementAndGet()
        }

        override fun stopAndRelease() {
            stopAndReleaseCallCount.incrementAndGet()
        }

        fun awaitWrites(seconds: Long): Boolean = writesLatch.await(seconds, TimeUnit.SECONDS)

        fun awaitWriteStarted(): Boolean = writeStartedLatch.await(2, TimeUnit.SECONDS)

        fun releaseBlockedWrite() {
            releaseBlockedWriteLatch.countDown()
        }
    }
}
