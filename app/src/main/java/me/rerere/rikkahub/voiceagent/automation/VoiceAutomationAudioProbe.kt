package me.rerere.rikkahub.voiceagent.automation

import org.koin.core.context.GlobalContext

internal interface VoiceAutomationAudioProbe {
    fun onInjectionStarted(totalBytes: Long)
    fun onInjectionChunk(byteCount: Int)
    fun onInjectionCompleted()
    fun onOutputQueued(byteCount: Int)
    fun onOutputWritten(byteCount: Int, nonSilent: Boolean)
    fun onOutputDrained()
    fun onInterruptionStarted()
    fun onOutputSilenceConfirmed()
}

internal class DefaultVoiceAutomationAudioProbe(
    private val runtimeProvider: () -> VoiceAutomationRuntime?,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : VoiceAutomationAudioProbe {
    private var runHash: String? = null
    private var lastSeenEventCount = 0L
    private var injectionTotalBytes: Long? = null
    private var injectedBytes = 0L
    private var injectionFirstChunkRecorded = false
    private var injectionCompleted = false
    private var playbackEpoch = 0L
    private var firstNonSilentRecorded = false
    private var outputActive = false
    private var lastNonSilentMs: Long? = null
    private var dropoutActive = false
    private var interruptionActive = false
    private var incrementEpochOnNextOutput = false

    @Synchronized
    override fun onInjectionStarted(totalBytes: Long) {
        if (totalBytes < 0) return
        withActiveRuntime { runtime ->
            injectionTotalBytes = totalBytes
            injectedBytes = 0L
            injectionFirstChunkRecorded = false
            injectionCompleted = false
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INJECTION_STARTED,
                    byteCount = totalBytes,
                ),
            )
        }
    }

    @Synchronized
    override fun onInjectionChunk(byteCount: Int) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            if (injectionTotalBytes == null || injectionCompleted) return@withActiveRuntime
            injectedBytes += byteCount
            if (!injectionFirstChunkRecorded) {
                injectionFirstChunkRecorded = true
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.INJECTION_FIRST_CHUNK,
                        byteCount = byteCount.toLong(),
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun onInjectionCompleted() {
        withActiveRuntime { runtime ->
            val totalBytes = injectionTotalBytes ?: return@withActiveRuntime
            if (injectionCompleted || injectedBytes != totalBytes) return@withActiveRuntime
            injectionCompleted = true
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INJECTION_COMPLETED,
                    byteCount = totalBytes,
                ),
            )
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PROMPT_ENDED,
                    byteCount = totalBytes,
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputQueued(byteCount: Int) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            prepareOutputEpoch()
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_QUEUED,
                    playbackEpoch = playbackEpoch,
                    byteCount = byteCount.toLong(),
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputWritten(byteCount: Int, nonSilent: Boolean) {
        if (byteCount <= 0) return
        withActiveRuntime { runtime ->
            prepareOutputEpoch()
            val nowMs = monotonicMs()
            val silenceDurationMs = lastNonSilentMs?.let { nowMs - it }
            if (
                firstNonSilentRecorded &&
                !dropoutActive &&
                !interruptionActive &&
                silenceDurationMs != null &&
                silenceDurationMs >= DROPOUT_THRESHOLD_MS
            ) {
                dropoutActive = true
                outputActive = false
                record(
                    runtime,
                    VoiceAutomationEventInput(
                        name = VoiceAutomationEventName.DROPOUT_STARTED,
                        playbackEpoch = playbackEpoch,
                    ),
                )
            }
            if (nonSilent) {
                if (dropoutActive) {
                    dropoutActive = false
                    playbackEpoch += 1
                    record(
                        runtime,
                        VoiceAutomationEventInput(
                            name = VoiceAutomationEventName.DROPOUT_ENDED,
                            playbackEpoch = playbackEpoch,
                        ),
                    )
                }
                if (!firstNonSilentRecorded) {
                    firstNonSilentRecorded = true
                    record(
                        runtime,
                        VoiceAutomationEventInput(
                            name = VoiceAutomationEventName.REMOTE_AUDIO_FIRST_NON_SILENT,
                            playbackEpoch = playbackEpoch,
                        ),
                    )
                }
                if (!outputActive) {
                    outputActive = true
                    record(
                        runtime,
                        VoiceAutomationEventInput(
                            name = VoiceAutomationEventName.PLAYBACK_ACTIVE,
                            playbackEpoch = playbackEpoch,
                        ),
                    )
                }
                lastNonSilentMs = nowMs
            }
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_WRITTEN,
                    playbackEpoch = playbackEpoch,
                    byteCount = byteCount.toLong(),
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputDrained() {
        withActiveRuntime { runtime ->
            if (playbackEpoch == 0L) return@withActiveRuntime
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_DRAINED,
                    playbackEpoch = playbackEpoch,
                ),
            )
            outputActive = false
            dropoutActive = false
            interruptionActive = false
            lastNonSilentMs = null
            incrementEpochOnNextOutput = firstNonSilentRecorded
        }
    }

    @Synchronized
    override fun onInterruptionStarted() {
        withActiveRuntime { runtime ->
            if (!outputActive || lastNonSilentMs == null || interruptionActive) {
                return@withActiveRuntime
            }
            interruptionActive = true
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.INTERRUPT_STARTED,
                    playbackEpoch = playbackEpoch,
                ),
            )
        }
    }

    @Synchronized
    override fun onOutputSilenceConfirmed() {
        withActiveRuntime { runtime ->
            val lastOutputMs = lastNonSilentMs ?: return@withActiveRuntime
            if (
                !interruptionActive ||
                monotonicMs() - lastOutputMs < INTERRUPTION_SILENCE_MS
            ) {
                return@withActiveRuntime
            }
            record(
                runtime,
                VoiceAutomationEventInput(
                    name = VoiceAutomationEventName.PLAYBACK_STOPPED,
                    playbackEpoch = playbackEpoch,
                ),
            )
            outputActive = false
            dropoutActive = false
            interruptionActive = false
            lastNonSilentMs = null
            incrementEpochOnNextOutput = true
        }
    }

    private inline fun withActiveRuntime(block: (VoiceAutomationRuntime) -> Unit) {
        val runtime = runtimeProvider() ?: run {
            resetState()
            return
        }
        val status = runCatching(runtime::status).getOrNull() ?: run {
            resetState()
            return
        }
        if (status.state != VoiceAutomationRunState.Active) {
            resetState()
            return
        }
        if (runHash != status.runHash || status.eventCount < lastSeenEventCount) {
            resetState()
            runHash = status.runHash
        }
        lastSeenEventCount = status.eventCount
        block(runtime)
    }

    private fun record(runtime: VoiceAutomationRuntime, event: VoiceAutomationEventInput) {
        runCatching {
            if (runtime.status().state == VoiceAutomationRunState.Active) {
                runtime.record(event)
                lastSeenEventCount = runtime.status().eventCount
            }
        }
    }

    private fun prepareOutputEpoch() {
        if (playbackEpoch == 0L) {
            playbackEpoch = 1L
        } else if (incrementEpochOnNextOutput) {
            playbackEpoch += 1
            incrementEpochOnNextOutput = false
        }
    }

    private fun resetState() {
        runHash = null
        lastSeenEventCount = 0L
        injectionTotalBytes = null
        injectedBytes = 0L
        injectionFirstChunkRecorded = false
        injectionCompleted = false
        playbackEpoch = 0L
        firstNonSilentRecorded = false
        outputActive = false
        lastNonSilentMs = null
        dropoutActive = false
        interruptionActive = false
        incrementEpochOnNextOutput = false
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val DROPOUT_THRESHOLD_MS = 250L
        const val INTERRUPTION_SILENCE_MS = 100L
    }
}

internal object VoiceAutomationAudioProbes {
    val shared: VoiceAutomationAudioProbe by lazy {
        DefaultVoiceAutomationAudioProbe(
            runtimeProvider = ::runtimeOrNull,
        )
    }

    fun activeSharedOrNull(): VoiceAutomationAudioProbe? =
        if (
            runCatching {
                runtimeOrNull()?.status()?.state == VoiceAutomationRunState.Active
            }.getOrDefault(false)
        ) {
            shared
        } else {
            null
        }

    private fun runtimeOrNull(): VoiceAutomationRuntime? =
        runCatching {
            GlobalContext.get().get<VoiceAutomationRuntime>()
        }.getOrNull()
}
