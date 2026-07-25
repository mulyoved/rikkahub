package me.rerere.rikkahub.voiceagent.livekit

import java.nio.ByteBuffer
import livekit.org.webrtc.AudioTrackSink
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbe
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationAudioProbes

internal class LiveKitRemoteAudioProbe(
    private val automationAudioProbe: VoiceAutomationAudioProbe = VoiceAutomationAudioProbes.shared,
    private val monotonicMs: () -> Long = { System.nanoTime() / NANOS_PER_MILLISECOND },
) : AudioTrackSink, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var pendingBytes = 0
    private var lastNonSilent: Boolean? = null
    private var lastProgressMs: Long? = null

    override fun onData(
        audioData: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        absoluteCaptureTimestampMs: Long,
    ) {
        val byteCount = audioData.remaining()
        if (byteCount <= 0) return
        val nonSilent = audioData.hasNonZeroByte()
        val nowMs = monotonicMs()
        synchronized(lock) {
            if (closed) return
            val previousState = lastNonSilent
            if (previousState != null && previousState != nonSilent) {
                flushProgress(previousState, nowMs)
            }
            if (pendingBytes > Int.MAX_VALUE - byteCount) {
                flushProgress(previousState ?: nonSilent, nowMs)
            }
            pendingBytes += byteCount
            val stateChanged = previousState == null || previousState != nonSilent
            lastNonSilent = nonSilent
            if (
                stateChanged ||
                lastProgressMs == null ||
                nowMs - checkNotNull(lastProgressMs) >= PROGRESS_INTERVAL_MS
            ) {
                flushProgress(nonSilent, nowMs)
            }
            if (!nonSilent) {
                automationAudioProbe.onOutputSilenceConfirmed()
            }
        }
    }

    override fun close() {
        val nowMs = monotonicMs()
        synchronized(lock) {
            if (closed) return
            closed = true
            lastNonSilent?.let { state ->
                flushProgress(state, nowMs)
            }
            automationAudioProbe.onOutputDrained()
        }
    }

    private fun flushProgress(nonSilent: Boolean, nowMs: Long) {
        if (pendingBytes <= 0) return
        automationAudioProbe.onOutputWritten(pendingBytes, nonSilent)
        pendingBytes = 0
        lastProgressMs = nowMs
    }

    private fun ByteBuffer.hasNonZeroByte(): Boolean {
        val bytes = duplicate()
        while (bytes.hasRemaining()) {
            if (bytes.get().toInt() != 0) return true
        }
        return false
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val PROGRESS_INTERVAL_MS = 50L
    }
}
