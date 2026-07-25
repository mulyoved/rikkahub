package me.rerere.rikkahub.voiceagent.livekit

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.util.ArrayDeque
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioDebugInjector
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunState
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationStatus
import org.koin.core.context.GlobalContext

internal fun interface LiveKitAutomationCaptureRegistrar {
    fun register(
        onPcm16: (ByteArray) -> Unit,
        onInjectionComplete: () -> Unit,
        isCurrent: () -> Boolean,
    ): AutoCloseable?
}

internal class LiveKitAutomationPcmSource(
    private val automationStatus: () -> VoiceAutomationStatus? = ::activeAutomationStatusOrNull,
    private val captureRegistrar: LiveKitAutomationCaptureRegistrar =
        VoiceAudioDebugLiveKitCaptureRegistrar,
) : LiveKitAutomationAudioBinding {
    private val lock = Any()
    private val queuedPcm = ArrayDeque<QueuedPcm>()
    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var captureRegistration: AutoCloseable? = null
    private var injectionEnded = false

    val isActive: Boolean
        get() = synchronized(lock) { activeGeneration != null }

    override fun activate(runHash: String): AutoCloseable {
        val status = checkNotNull(automationStatus()) {
            "LiveKit automation requires an active automation run"
        }
        check(status.state == VoiceAutomationRunState.Active) {
            "LiveKit automation requires an active automation run"
        }
        check(status.requestedTransport == VoiceAgentTransport.LiveKitExperimental) {
            "LiveKit automation requires the LiveKit experimental transport"
        }
        check(status.runHash == runHash) {
            "LiveKit automation run hash does not match the active run"
        }

        val generation = synchronized(lock) {
            check(activeGeneration == null) {
                "LiveKit automation audio already has an active owner"
            }
            check(nextGeneration < Long.MAX_VALUE) {
                "LiveKit automation audio generation exhausted"
            }
            (nextGeneration + 1).also { next ->
                nextGeneration = next
                activeGeneration = next
                queuedPcm.clear()
                injectionEnded = false
            }
        }
        val registration = try {
            captureRegistrar.register(
                onPcm16 = ::enqueuePcm16,
                onInjectionComplete = ::markInjectionEnded,
                isCurrent = { isGenerationActive(generation) },
            )
        } catch (error: Throwable) {
            deactivate(generation)
            throw error
        }
        if (registration == null) {
            deactivate(generation)
            error("LiveKit automation audio registration was rejected")
        }
        val published = synchronized(lock) {
            if (activeGeneration == generation) {
                captureRegistration = registration
                true
            } else {
                false
            }
        }
        if (!published) {
            registration.close()
            error("LiveKit automation audio activation became stale")
        }
        return AutoCloseable {
            deactivate(generation)
        }
    }

    override fun enqueuePcm16(pcm16: ByteArray) {
        if (pcm16.isEmpty()) return
        synchronized(lock) {
            if (activeGeneration == null) return
            queuedPcm.addLast(QueuedPcm(pcm16.copyOf()))
            injectionEnded = false
        }
    }

    override fun injectionComplete(): Boolean = synchronized(lock) {
        activeGeneration != null && injectionEnded && queuedPcm.isEmpty()
    }

    fun replaceOrZero(buffer: ByteBuffer) {
        synchronized(lock) {
            if (activeGeneration == null) return
            while (buffer.hasRemaining()) {
                val queued = queuedPcm.peekFirst()
                if (queued == null) {
                    buffer.put(0)
                    continue
                }
                val byteCount = minOf(buffer.remaining(), queued.remaining)
                buffer.put(queued.bytes, queued.offset, byteCount)
                queued.offset += byteCount
                if (queued.remaining == 0) {
                    queuedPcm.removeFirst()
                }
            }
        }
    }

    private fun markInjectionEnded() {
        synchronized(lock) {
            if (activeGeneration != null) {
                injectionEnded = true
            }
        }
    }

    private fun isGenerationActive(generation: Long): Boolean =
        synchronized(lock) { activeGeneration == generation }

    private fun deactivate(generation: Long) {
        val registration = synchronized(lock) {
            if (activeGeneration != generation) return
            activeGeneration = null
            queuedPcm.clear()
            injectionEnded = false
            captureRegistration.also {
                captureRegistration = null
            }
        }
        registration?.close()
    }

    private class QueuedPcm(
        val bytes: ByteArray,
        var offset: Int = 0,
    ) {
        val remaining: Int
            get() = bytes.size - offset
    }
}

internal class LiveKitInjectedPcmProcessor(
    private val source: LiveKitAutomationPcmSource,
) : AudioProcessorInterface {
    override fun getName(): String = "rikka-stage1-pcm"

    override fun isEnabled(): Boolean = source.isActive

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) = Unit

    override fun resetAudioProcessing(newRate: Int) = Unit

    override fun processAudio(
        numBands: Int,
        numFrames: Int,
        buffer: ByteBuffer,
    ) {
        source.replaceOrZero(buffer)
    }
}

private object VoiceAudioDebugLiveKitCaptureRegistrar : LiveKitAutomationCaptureRegistrar {
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

private fun activeAutomationStatusOrNull(): VoiceAutomationStatus? =
    runCatching {
        GlobalContext.get().get<VoiceAutomationRuntime>().status()
    }.getOrNull()
