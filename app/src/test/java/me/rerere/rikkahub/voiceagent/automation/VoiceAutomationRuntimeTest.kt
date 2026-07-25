package me.rerere.rikkahub.voiceagent.automation

import java.nio.file.Files
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceAutomationRuntimeTest {
    @Test
    fun `record is inactive until a run is prepared`() {
        val root = Files.createTempDirectory("voice-automation-runtime-inactive").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())

        runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.CALL_START_REQUESTED))

        assertEquals(VoiceAutomationRunState.Idle, runtime.status().state)
        assertFalse(java.io.File(root, "voice-e2e").exists())
    }

    @Test
    fun `prepared run records typed events then finalizes its jsonl artifact`() {
        val root = Files.createTempDirectory("voice-automation-runtime-active").toFile()
        val runtime = DefaultVoiceAutomationRuntime(root, FakeClock())
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)

        runtime.prepare(binding)
        runtime.record(
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.CALL_ACTIVE,
                observedTransport = VoiceAgentTransport.DirectGemini,
                succeeded = true,
            ),
        )
        val artifact = runtime.finalizeRun()

        assertTrue(artifact.isFile)
        assertEquals(3, artifact.readLines().size)
        assertEquals(VoiceAutomationRunState.Finalized, runtime.status().state)
        assertEquals(RUN_HASH, runtime.status().runHash)
        assertEquals(3, runtime.status().eventCount)
    }

    @Test
    fun `runtime fails closed for duplicate preparation binding drift and invalid finalization`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-closed").toFile(),
            FakeClock(),
        )
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)

        assertFailsWith<IllegalStateException> { runtime.finalizeRun() }
        runtime.prepare(binding)
        assertFailsWith<IllegalStateException> {
            runtime.prepare(binding.copy(requestedTransport = VoiceAgentTransport.LiveKitExperimental))
        }
        runtime.finalizeRun()
        assertFailsWith<IllegalStateException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.CALL_STOPPED))
        }
    }

    @Test
    fun `reset cannot reprepare a finalized run hash`() {
        val runtime = DefaultVoiceAutomationRuntime(
            Files.createTempDirectory("voice-automation-runtime-reprepare").toFile(),
            FakeClock(),
        )
        val binding = VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini)
        runtime.prepare(binding)
        runtime.finalizeRun()
        runtime.reset()

        assertFailsWith<IllegalStateException> { runtime.prepare(binding) }
    }

    @Test
    fun `record rejects forged run prepared boundary`() {
        val runtime = preparedRuntime()

        assertFailsWith<IllegalArgumentException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_PREPARED))
        }
    }

    @Test
    fun `record rejects forged run finalized boundary`() {
        val runtime = preparedRuntime()

        assertFailsWith<IllegalArgumentException> {
            runtime.record(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_FINALIZED))
        }
    }

    private fun preparedRuntime(): DefaultVoiceAutomationRuntime = DefaultVoiceAutomationRuntime(
        Files.createTempDirectory("voice-automation-runtime-boundary").toFile(),
        FakeClock(),
    ).also { runtime ->
        runtime.prepare(VoiceAutomationRunBinding(RUN_HASH, COMPARISON_HASH, VoiceAgentTransport.DirectGemini))
    }

    private class FakeClock : VoiceAutomationClock {
        private var tick = 0L

        override fun monotonicMs(): Long = ++tick

        override fun wallClockMs(): Long = 1_000 + tick
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMPARISON_HASH = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
