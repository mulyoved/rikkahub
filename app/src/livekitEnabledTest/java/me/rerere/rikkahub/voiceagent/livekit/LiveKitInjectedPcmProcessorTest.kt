package me.rerere.rikkahub.voiceagent.livekit

import java.nio.ByteBuffer
import io.livekit.android.audio.NoAudioHandler
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioDebugInjector
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveKitInjectedPcmProcessorTest {
    @Test
    fun `fixture bytes remain ordered across differently sized SDK buffers`() {
        val capture = FakeLiveKitCaptureRegistrar()
        val source = activeSource(capture)
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH)
        capture.deliver(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        capture.complete()

        assertArrayEquals(byteArrayOf(1, 2, 3), process(processor, size = 3))
        assertFalse(source.injectionComplete())
        assertArrayEquals(byteArrayOf(4, 5), process(processor, size = 2))
        assertFalse(source.injectionComplete())
        assertArrayEquals(byteArrayOf(6, 7, 0, 0), process(processor, size = 4))
        assertTrue(source.injectionComplete())
        assertArrayEquals(byteArrayOf(0, 0), process(processor, size = 2))

        activation.close()
    }

    @Test
    fun `active processor zero fills before a fixture and overwrites every remaining sentinel byte`() {
        val capture = FakeLiveKitCaptureRegistrar()
        val source = activeSource(capture)
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH)

        assertArrayEquals(byteArrayOf(0, 0, 0), process(processor, size = 3))
        capture.deliver(byteArrayOf(11, 12))
        capture.complete()
        val hardware = byteArrayOf(99, 99, 99, 99, 99, 99)
        val buffer = ByteBuffer.wrap(hardware).apply {
            position(1)
            limit(5)
        }

        processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)

        assertArrayEquals(byteArrayOf(99, 11, 12, 0, 0, 99), hardware)
        assertEquals(5, buffer.position())
        assertTrue(source.injectionComplete())
        activation.close()
    }

    @Test
    fun `inactive processor is disabled and leaves normal capture untouched`() {
        val source = LiveKitAutomationPcmSource(
            automationStatus = { liveKitStatus() },
            captureRegistrar = FakeLiveKitCaptureRegistrar(),
        )
        val processor = LiveKitInjectedPcmProcessor(source)
        val hardware = byteArrayOf(41, 42, 43, 44)
        val buffer = ByteBuffer.wrap(hardware).apply {
            position(1)
            limit(3)
        }

        assertFalse(processor.isEnabled())
        processor.processAudio(numBands = 1, numFrames = 1, buffer = buffer)

        assertArrayEquals(byteArrayOf(41, 42, 43, 44), hardware)
        assertEquals(1, buffer.position())
    }

    @Test
    fun `SDK lifecycle callbacks do not consume or transform queued fixture bytes`() {
        val capture = FakeLiveKitCaptureRegistrar()
        val source = activeSource(capture)
        val processor = LiveKitInjectedPcmProcessor(source)
        val activation = source.activate(RUN_HASH)
        capture.deliver(byteArrayOf(21, 22, 23, 24))
        capture.complete()

        processor.initializeAudioProcessing(sampleRateHz = 48_000, numChannels = 1)
        processor.resetAudioProcessing(newRate = 32_000)

        assertFalse(source.injectionComplete())
        assertArrayEquals(byteArrayOf(21, 22, 23, 24), process(processor, size = 4))
        assertTrue(source.injectionComplete())
        activation.close()
    }

    @Test
    fun `LiveKit audio options install the active post processor without bypass`() {
        val source = activeSource(FakeLiveKitCaptureRegistrar())
        val processor = LiveKitInjectedPcmProcessor(source)

        val options = liveKitAutomationAudioOptions(processor)
        val processorOptions = requireNotNull(options.audioProcessorOptions)

        assertTrue(options.audioHandler is NoAudioHandler)
        assertSame(processor, processorOptions.capturePostProcessor)
        assertFalse(processorOptions.capturePostBypass)
        assertEquals("rikka-stage1-pcm", processor.getName())
    }

    @Test
    fun `activation requires the matching active LiveKit run and owns one registration at a time`() {
        var status = liveKitStatus()
        val capture = FakeLiveKitCaptureRegistrar()
        val source = LiveKitAutomationPcmSource(
            automationStatus = { status },
            captureRegistrar = capture,
        )

        status = status.copy(requestedTransport = VoiceAgentTransport.DirectGemini)
        assertTrue(runCatching { source.activate(RUN_HASH) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, capture.registrationCount)

        status = liveKitStatus()
        val activation = source.activate(RUN_HASH)
        assertTrue(source.isActive)
        assertEquals(1, capture.registrationCount)
        assertTrue(runCatching { source.activate(RUN_HASH) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, capture.registrationCount)

        activation.close()
        assertFalse(source.isActive)
        capture.deliver(byteArrayOf(9, 9))
        assertFalse(source.injectionComplete())
    }

    @Test
    fun `source from run A rejects callbacks and capture after runtime rolls to run B`() {
        listOf(
            liveKitStatus(runHash = RUN_HASH_B),
            liveKitStatus(runHash = RUN_HASH_B).copy(
                requestedTransport = VoiceAgentTransport.DirectGemini,
            ),
        ).forEach { replacementStatus ->
            var status = liveKitStatus()
            val capture = FakeLiveKitCaptureRegistrar()
            val source = LiveKitAutomationPcmSource(
                automationStatus = { status },
                captureRegistrar = capture,
            )
            val processor = LiveKitInjectedPcmProcessor(source)
            val activation = source.activate(RUN_HASH)
            capture.deliver(byteArrayOf(1, 2))

            status = status.copy(state = VoiceAutomationRunState.Finalized)
            status = replacementStatus
            source.enqueuePcm16(byteArrayOf())
            assertEquals(1, capture.closeCount)
            source.enqueuePcm16(byteArrayOf(3, 4))
            capture.deliver(byteArrayOf(5, 6))
            capture.complete()
            val hardware = byteArrayOf(91, 92, 93, 94)
            val buffer = ByteBuffer.wrap(hardware)

            assertFalse(processor.isEnabled())
            processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)

            assertArrayEquals(byteArrayOf(91, 92, 93, 94), hardware)
            assertEquals(0, buffer.position())
            assertFalse(source.injectionComplete())
            activation.close()
        }
    }

    @Test
    fun `global injector lease prevents LiveKit and Direct capture coexistence`() {
        VoiceAudioDebugInjector.clearForTest()
        val first = LiveKitAutomationPcmSource(
            automationStatus = { liveKitStatus() },
            captureRegistrar = GlobalInjectorCaptureRegistrar,
        )
        val second = LiveKitAutomationPcmSource(
            automationStatus = { liveKitStatus() },
            captureRegistrar = GlobalInjectorCaptureRegistrar,
        )
        val firstActivation = first.activate(RUN_HASH)
        var unexpectedSecondActivation: AutoCloseable? = null
        var directRegistration: VoiceAudioDebugInjector.Registration? = null
        var finalSecondActivation: AutoCloseable? = null
        try {
            val secondAttempt = runCatching { second.activate(RUN_HASH) }
            unexpectedSecondActivation = secondAttempt.getOrNull()
            assertTrue(secondAttempt.exceptionOrNull() is IllegalStateException)

            directRegistration = VoiceAudioDebugInjector.registerCaptureIfCurrent(
                onPcm16 = {},
                onInjectionComplete = {},
                isCurrent = { true },
            )
            assertEquals(null, directRegistration)

            firstActivation.close()
            directRegistration = VoiceAudioDebugInjector.registerCapture(onPcm16 = {})
            val blockedByDirect = runCatching { second.activate(RUN_HASH) }
            assertTrue(blockedByDirect.exceptionOrNull() is IllegalStateException)
            blockedByDirect.getOrNull()?.close()

            directRegistration.close()
            directRegistration = null
            finalSecondActivation = second.activate(RUN_HASH)
            firstActivation.close()
            val result = VoiceAudioDebugInjector.injectPcm16(
                pcm16 = byteArrayOf(7, 8),
                chunkBytes = 2,
                chunkDelayMs = 0,
            )

            assertTrue(result.delivered)
            assertArrayEquals(
                byteArrayOf(7, 8),
                process(LiveKitInjectedPcmProcessor(second), size = 2),
            )
        } finally {
            finalSecondActivation?.close()
            directRegistration?.close()
            unexpectedSecondActivation?.close()
            firstActivation.close()
            VoiceAudioDebugInjector.clearForTest()
        }
    }

    @Test
    fun `global injector lease rolls from stale run A to usable run B`() {
        VoiceAudioDebugInjector.clearForTest()
        var status = liveKitStatus()
        val sourceA = LiveKitAutomationPcmSource(
            automationStatus = { status },
            captureRegistrar = GlobalInjectorCaptureRegistrar,
        )
        val sourceB = LiveKitAutomationPcmSource(
            automationStatus = { status },
            captureRegistrar = GlobalInjectorCaptureRegistrar,
        )
        val activationA = sourceA.activate(RUN_HASH)
        var activationB: AutoCloseable? = null
        try {
            status = liveKitStatus(runHash = RUN_HASH_B)

            activationB = sourceB.activate(RUN_HASH_B)
            activationA.close()
            val injection = VoiceAudioDebugInjector.injectPcm16(
                pcm16 = byteArrayOf(7, 8),
                chunkBytes = 2,
                chunkDelayMs = 0,
            )

            assertTrue(injection.delivered)
            assertFalse(sourceA.isActive)
            assertArrayEquals(
                byteArrayOf(7, 8),
                process(LiveKitInjectedPcmProcessor(sourceB), size = 2),
            )
        } finally {
            activationB?.close()
            activationA.close()
            VoiceAudioDebugInjector.clearForTest()
        }
    }

    private fun activeSource(capture: FakeLiveKitCaptureRegistrar) =
        LiveKitAutomationPcmSource(
            automationStatus = { liveKitStatus() },
            captureRegistrar = capture,
        )

    private fun liveKitStatus(runHash: String = RUN_HASH) = VoiceAutomationStatus(
        state = VoiceAutomationRunState.Active,
        runHash = runHash,
        comparisonHash = COMPARISON_HASH,
        requestedTransport = VoiceAgentTransport.LiveKitExperimental,
    )

    private fun process(
        processor: LiveKitInjectedPcmProcessor,
        size: Int,
    ): ByteArray {
        val bytes = ByteArray(size) { 99 }
        processor.processAudio(
            numBands = 1,
            numFrames = size / 2,
            buffer = ByteBuffer.wrap(bytes),
        )
        return bytes
    }

    private class FakeLiveKitCaptureRegistrar : LiveKitAutomationCaptureRegistrar {
        private var onPcm16: ((ByteArray) -> Unit)? = null
        private var onInjectionComplete: (() -> Unit)? = null
        private var isCurrent: (() -> Boolean)? = null
        var registrationCount = 0
            private set
        var closeCount = 0
            private set

        override fun register(
            onPcm16: (ByteArray) -> Unit,
            onInjectionComplete: () -> Unit,
            isCurrent: () -> Boolean,
        ): AutoCloseable {
            registrationCount += 1
            this.onPcm16 = onPcm16
            this.onInjectionComplete = onInjectionComplete
            this.isCurrent = isCurrent
            return AutoCloseable {
                closeCount += 1
                this.onPcm16 = null
                this.onInjectionComplete = null
                this.isCurrent = null
            }
        }

        fun deliver(bytes: ByteArray) {
            if (isCurrent?.invoke() == true) onPcm16?.invoke(bytes)
        }

        fun complete() {
            if (isCurrent?.invoke() == true) onInjectionComplete?.invoke()
        }
    }

    private object GlobalInjectorCaptureRegistrar : LiveKitAutomationCaptureRegistrar {
        override fun register(
            onPcm16: (ByteArray) -> Unit,
            onInjectionComplete: () -> Unit,
            isCurrent: () -> Boolean,
        ): AutoCloseable? =
            VoiceAudioDebugInjector.registerCaptureIfCurrent(
                onPcm16 = onPcm16,
                onInjectionComplete = onInjectionComplete,
                isCurrent = isCurrent,
            )?.let { registration ->
                AutoCloseable(registration::close)
            }
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val RUN_HASH_B = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val COMPARISON_HASH =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
