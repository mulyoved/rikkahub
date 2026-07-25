package me.rerere.rikkahub.voiceagent.automation

import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAutomationAudioProbeTest {
    @Test
    fun `steady output records queued active written and drained transitions in order`() {
        val runtime = RecordingRuntime()
        val clock = FakeProbeClock()
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
        )

        probe.onOutputQueued(byteCount = 640)
        probe.onOutputWritten(byteCount = 640, nonSilent = true)
        clock.advanceBy(20)
        probe.onOutputWritten(byteCount = 320, nonSilent = true)
        probe.onOutputDrained()

        assertEquals(
            listOf(
                expected(VoiceAutomationEventName.PLAYBACK_QUEUED, epoch = 1, bytes = 640),
                expected(VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT, epoch = 1),
                expected(VoiceAutomationEventName.PLAYBACK_ACTIVE, epoch = 1),
                expected(VoiceAutomationEventName.PLAYBACK_WRITTEN, epoch = 1, bytes = 640),
                expected(VoiceAutomationEventName.PLAYBACK_WRITTEN, epoch = 1, bytes = 320),
                expected(VoiceAutomationEventName.PLAYBACK_DRAINED, epoch = 1),
            ),
            runtime.events,
        )
    }

    @Test
    fun `a 249 millisecond output gap is not a dropout`() {
        val runtime = RecordingRuntime()
        val clock = FakeProbeClock()
        val scheduler = FakeProbeScheduler(clock)
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
            scheduler = scheduler,
        )
        probe.onOutputWritten(byteCount = 320, nonSilent = true)

        scheduler.advanceBy(249)
        probe.onOutputWritten(byteCount = 320, nonSilent = true)
        scheduler.advanceBy(1)

        assertEquals(
            listOf(
                VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT,
                VoiceAutomationEventName.PLAYBACK_ACTIVE,
                VoiceAutomationEventName.PLAYBACK_WRITTEN,
                VoiceAutomationEventName.PLAYBACK_WRITTEN,
            ),
            runtime.events.map(VoiceAutomationEventInput::name),
        )
        assertEquals(listOf(1L, 1L), runtime.events.takeLast(2).map { it.playbackEpoch })
    }

    @Test
    fun `a 250 millisecond output gap records a dropout and resumes in a new epoch`() {
        val runtime = RecordingRuntime()
        val clock = FakeProbeClock()
        val scheduler = FakeProbeScheduler(clock)
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
            scheduler = scheduler,
        )
        probe.onOutputWritten(byteCount = 320, nonSilent = true)

        scheduler.advanceBy(249)
        assertEquals(0, runtime.events.count { it.name == VoiceAutomationEventName.DROPOUT_STARTED })
        scheduler.advanceBy(1)
        assertEquals(
            expected(VoiceAutomationEventName.DROPOUT_STARTED, epoch = 1),
            runtime.events.last(),
        )
        probe.onOutputWritten(byteCount = 320, nonSilent = true)

        assertEquals(
            listOf(
                expected(VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT, epoch = 1),
                expected(VoiceAutomationEventName.PLAYBACK_ACTIVE, epoch = 1),
                expected(VoiceAutomationEventName.PLAYBACK_WRITTEN, epoch = 1, bytes = 320),
                expected(VoiceAutomationEventName.DROPOUT_STARTED, epoch = 1),
                expected(VoiceAutomationEventName.DROPOUT_ENDED, epoch = 2),
                expected(VoiceAutomationEventName.PLAYBACK_ACTIVE, epoch = 2),
                expected(VoiceAutomationEventName.PLAYBACK_WRITTEN, epoch = 2, bytes = 320),
            ),
            runtime.events,
        )
    }

    @Test
    fun `interruption after output records stop only after 100 milliseconds confirmed silence`() {
        val runtime = RecordingRuntime()
        val clock = FakeProbeClock()
        val scheduler = FakeProbeScheduler(clock)
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = clock::nowMs,
            scheduler = scheduler,
        )
        probe.onOutputWritten(byteCount = 320, nonSilent = true)
        probe.onInterruptionStarted()
        probe.onOutputDrained()

        clock.advanceBy(99)
        probe.onOutputSilenceConfirmed()
        clock.advanceBy(1)
        probe.onOutputSilenceConfirmed()
        probe.onOutputSilenceConfirmed()

        assertEquals(
            listOf(
                VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT,
                VoiceAutomationEventName.PLAYBACK_ACTIVE,
                VoiceAutomationEventName.PLAYBACK_WRITTEN,
                VoiceAutomationEventName.INTERRUPT_STARTED,
                VoiceAutomationEventName.PLAYBACK_DRAINED,
                VoiceAutomationEventName.PLAYBACK_STOPPED,
            ),
            runtime.events.map(VoiceAutomationEventInput::name),
        )
        assertEquals(1L, runtime.events.last().playbackEpoch)
        assertEquals(1, runtime.events.count { it.name == VoiceAutomationEventName.PLAYBACK_STOPPED })
    }

    @Test
    fun `interruption before active output is rejected`() {
        val runtime = RecordingRuntime()
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = { 1L },
        )

        probe.onInterruptionStarted()
        probe.onOutputSilenceConfirmed()

        assertEquals(emptyList<VoiceAutomationEventInput>(), runtime.events)
    }

    @Test
    fun `injection completes only after the exact announced byte count`() {
        val runtime = RecordingRuntime()
        val probe = DefaultVoiceAutomationAudioProbe(
            runtimeProvider = { runtime },
            monotonicMs = { 1L },
        )

        probe.onInjectionStarted(totalBytes = 6)
        probe.onInjectionChunk(byteCount = 4)
        probe.onInjectionCompleted()
        probe.onInjectionChunk(byteCount = 2)
        probe.onInjectionCompleted()
        probe.onInjectionCompleted()

        assertEquals(
            listOf(
                expected(VoiceAutomationEventName.INJECTION_STARTED, bytes = 6),
                expected(VoiceAutomationEventName.INJECTION_FIRST_CHUNK, bytes = 4),
                expected(VoiceAutomationEventName.INJECTION_COMPLETED, bytes = 6),
                expected(VoiceAutomationEventName.PROMPT_ENDED, bytes = 6),
            ),
            runtime.events,
        )
    }

    private fun expected(
        name: VoiceAutomationEventName,
        epoch: Long? = null,
        bytes: Long? = null,
    ) = VoiceAutomationEventInput(
        name = name,
        playbackEpoch = epoch,
        byteCount = bytes,
    )

    private class FakeProbeClock {
        private var currentMs = 1L

        fun nowMs(): Long = currentMs

        fun advanceBy(durationMs: Long) {
            currentMs += durationMs
        }
    }

    private class FakeProbeScheduler(
        private val clock: FakeProbeClock,
    ) : VoiceAutomationTransitionScheduler {
        private val tasks = mutableListOf<ScheduledTask>()

        override fun schedule(
            delayMs: Long,
            transition: () -> Unit,
        ): VoiceAutomationScheduledTransition {
            val task = ScheduledTask(
                deadlineMs = clock.nowMs() + delayMs,
                transition = transition,
            )
            tasks += task
            return VoiceAutomationScheduledTransition {
                task.cancelled = true
            }
        }

        fun advanceBy(durationMs: Long) {
            clock.advanceBy(durationMs)
            while (true) {
                val due = tasks
                    .filter { !it.cancelled && it.deadlineMs <= clock.nowMs() }
                    .minByOrNull(ScheduledTask::deadlineMs)
                    ?: return
                due.cancelled = true
                due.transition()
            }
        }

        private class ScheduledTask(
            val deadlineMs: Long,
            val transition: () -> Unit,
            var cancelled: Boolean = false,
        )
    }

    private class RecordingRuntime : VoiceAutomationRuntime {
        val events = mutableListOf<VoiceAutomationEventInput>()

        override fun prepare(binding: VoiceAutomationRunBinding) = Unit

        override fun record(event: VoiceAutomationEventInput) {
            events += event
        }

        override fun status() = VoiceAutomationStatus(
            state = VoiceAutomationRunState.Active,
            runHash = RUN_HASH,
            comparisonHash = COMPARISON_HASH,
            requestedTransport = VoiceAgentTransport.DirectGemini,
            eventCount = events.size.toLong() + 1,
        )

        override fun finalizeRun() = error("not used")

        override fun reset() = Unit
    }

    private companion object {
        const val RUN_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val COMPARISON_HASH = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
