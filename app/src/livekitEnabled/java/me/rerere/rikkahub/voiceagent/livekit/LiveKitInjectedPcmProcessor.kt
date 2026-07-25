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
    private var activeOwner: ActiveOwner? = null
    private var captureRegistration: AutoCloseable? = null
    private var injectionEnded = false

    val isActive: Boolean
        get() {
            var staleRegistration: AutoCloseable? = null
            val active = synchronized(lock) {
                val owner = activeOwner ?: return@synchronized false
                if (owner.matchesCurrentStatus()) {
                    true
                } else {
                    staleRegistration = clearOwnerLocked(owner.generation)
                    false
                }
            }
            staleRegistration?.close()
            return active
        }

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

        val owner = synchronized(lock) {
            check(activeOwner == null) {
                "LiveKit automation audio already has an active owner"
            }
            check(nextGeneration < Long.MAX_VALUE) {
                "LiveKit automation audio generation exhausted"
            }
            ActiveOwner(nextGeneration + 1, runHash).also { next ->
                nextGeneration = next.generation
                activeOwner = next
                queuedPcm.clear()
                injectionEnded = false
            }
        }
        val registration = try {
            captureRegistrar.register(
                onPcm16 = { pcm16 -> enqueuePcm16(owner.generation, pcm16) },
                onInjectionComplete = { markInjectionEnded(owner.generation) },
                isCurrent = { isGenerationActive(owner.generation) },
            )
        } catch (error: Throwable) {
            deactivate(owner.generation)
            throw error
        }
        if (registration == null) {
            deactivate(owner.generation)
            error("LiveKit automation audio registration was rejected")
        }
        val published = synchronized(lock) {
            if (activeOwner == owner && owner.matchesCurrentStatus()) {
                captureRegistration = registration
                true
            } else {
                clearOwnerLocked(owner.generation)
                false
            }
        }
        if (!published) {
            registration.close()
            error("LiveKit automation audio activation became stale")
        }
        return AutoCloseable {
            deactivate(owner.generation)
        }
    }

    override fun enqueuePcm16(pcm16: ByteArray) {
        enqueuePcm16(expectedGeneration = null, pcm16 = pcm16)
    }

    private fun enqueuePcm16(
        expectedGeneration: Long?,
        pcm16: ByteArray,
    ) {
        var staleRegistration: AutoCloseable? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (expectedGeneration != null && owner.generation != expectedGeneration) {
                return@synchronized
            }
            if (!owner.matchesCurrentStatus()) {
                staleRegistration = clearOwnerLocked(owner.generation)
                return@synchronized
            }
            if (pcm16.isEmpty()) return@synchronized
            queuedPcm.addLast(QueuedPcm(pcm16.copyOf()))
            injectionEnded = false
        }
        staleRegistration?.close()
    }

    override fun injectionComplete(): Boolean {
        var staleRegistration: AutoCloseable? = null
        val complete = synchronized(lock) {
            val owner = activeOwner ?: return@synchronized false
            if (!owner.matchesCurrentStatus()) {
                staleRegistration = clearOwnerLocked(owner.generation)
                return@synchronized false
            }
            injectionEnded && queuedPcm.isEmpty()
        }
        staleRegistration?.close()
        return complete
    }

    fun replaceOrZero(buffer: ByteBuffer) {
        var staleRegistration: AutoCloseable? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleRegistration = clearOwnerLocked(owner.generation)
                return@synchronized
            }
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
        staleRegistration?.close()
    }

    private fun markInjectionEnded(expectedGeneration: Long) {
        var staleRegistration: AutoCloseable? = null
        synchronized(lock) {
            val owner = activeOwner ?: return@synchronized
            if (owner.generation != expectedGeneration) return@synchronized
            if (!owner.matchesCurrentStatus()) {
                staleRegistration = clearOwnerLocked(owner.generation)
                return@synchronized
            }
            injectionEnded = true
        }
        staleRegistration?.close()
    }

    private fun isGenerationActive(generation: Long): Boolean {
        var staleRegistration: AutoCloseable? = null
        val active = synchronized(lock) {
            val owner = activeOwner ?: return@synchronized false
            if (owner.generation != generation) return@synchronized false
            if (!owner.matchesCurrentStatus()) {
                staleRegistration = clearOwnerLocked(owner.generation)
                return@synchronized false
            }
            true
        }
        staleRegistration?.close()
        return active
    }

    private fun deactivate(generation: Long) {
        val registration = synchronized(lock) {
            clearOwnerLocked(generation)
        }
        registration?.close()
    }

    private fun clearOwnerLocked(generation: Long): AutoCloseable? {
        if (activeOwner?.generation != generation) return null
        activeOwner = null
        queuedPcm.clear()
        injectionEnded = false
        return captureRegistration.also {
            captureRegistration = null
        }
    }

    private fun ActiveOwner.matchesCurrentStatus(): Boolean =
        runCatching { automationStatus() }
            .getOrNull()
            ?.let { status ->
                status.state == VoiceAutomationRunState.Active &&
                    status.runHash == runHash &&
                    status.requestedTransport == VoiceAgentTransport.LiveKitExperimental
            }
            ?: false

    private data class ActiveOwner(
        val generation: Long,
        val runHash: String,
    )

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
