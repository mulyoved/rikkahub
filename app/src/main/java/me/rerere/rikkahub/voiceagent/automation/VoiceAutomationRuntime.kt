package me.rerere.rikkahub.voiceagent.automation

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport

internal interface VoiceAutomationClock {
    fun monotonicMs(): Long
    fun wallClockMs(): Long
}

internal interface VoiceAutomationRuntime {
    fun prepare(binding: VoiceAutomationRunBinding)
    fun record(event: VoiceAutomationEventInput)
    fun recordIfActiveRun(runHash: String, event: VoiceAutomationEventInput): Boolean {
        val status = status()
        if (status.state != VoiceAutomationRunState.Active || status.runHash != runHash) {
            return false
        }
        record(event)
        return true
    }
    fun status(): VoiceAutomationStatus
    fun finalizeRun(): File
    fun reset()
}

@Serializable
internal enum class VoiceAutomationRunState {
    @SerialName("idle") Idle,
    @SerialName("active") Active,
    @SerialName("finalized") Finalized,
}

internal data class VoiceAutomationStatus(
    val state: VoiceAutomationRunState,
    val runHash: String? = null,
    val comparisonHash: String? = null,
    val requestedTransport: VoiceAgentTransport? = null,
    val eventCount: Long = 0,
)

internal class DefaultVoiceAutomationRuntime(
    private val noBackupFilesDir: File,
    private val clock: VoiceAutomationClock = SystemVoiceAutomationClock,
) : VoiceAutomationRuntime {
    private var binding: VoiceAutomationRunBinding? = null
    private var writer: VoiceAutomationEventWriter? = null
    private var currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle)
    private var lastEmittedMonotonicMs: Long? = null

    @Synchronized
    override fun prepare(binding: VoiceAutomationRunBinding) {
        check(currentStatus.state != VoiceAutomationRunState.Active) {
            "Automation run is already active"
        }
        VoiceAutomationEventValidation.validate(binding)
        val candidateWriter = VoiceAutomationEventWriter.create(noBackupFilesDir, binding.runHash)
        val monotonicMs = nextMonotonicMs()
        candidateWriter.append(
            event(
                binding = binding,
                input = VoiceAutomationEventInput(VoiceAutomationEventName.RUN_PREPARED),
                monotonicMs = monotonicMs,
            ),
        )
        this.binding = binding
        writer = candidateWriter
        lastEmittedMonotonicMs = monotonicMs
        currentStatus = activeStatus(binding, eventCount = 1)
    }

    @Synchronized
    override fun record(event: VoiceAutomationEventInput) {
        when (currentStatus.state) {
            VoiceAutomationRunState.Idle -> Unit
            VoiceAutomationRunState.Active -> {
                require(event.name !in setOf(
                    VoiceAutomationEventName.RUN_PREPARED,
                    VoiceAutomationEventName.RUN_FINALIZED,
                )) { "Run lifecycle boundaries are reserved for the runtime" }
                recordActive(event)
            }
            VoiceAutomationRunState.Finalized -> error("Automation run has already been finalized")
        }
    }

    @Synchronized
    override fun recordIfActiveRun(
        runHash: String,
        event: VoiceAutomationEventInput,
    ): Boolean {
        if (
            currentStatus.state != VoiceAutomationRunState.Active ||
            binding?.runHash != runHash
        ) {
            return false
        }
        require(event.name !in setOf(
            VoiceAutomationEventName.RUN_PREPARED,
            VoiceAutomationEventName.RUN_FINALIZED,
        )) { "Run lifecycle boundaries are reserved for the runtime" }
        recordActive(event)
        return true
    }

    @Synchronized
    override fun status(): VoiceAutomationStatus = currentStatus

    @Synchronized
    override fun finalizeRun(): File {
        check(currentStatus.state == VoiceAutomationRunState.Active) { "No active automation run to finalize" }
        recordActive(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_FINALIZED))
        currentStatus = activeStatus(checkNotNull(binding), currentStatus.eventCount).copy(
            state = VoiceAutomationRunState.Finalized,
        )
        return checkNotNull(writer).file
    }

    @Synchronized
    override fun reset() {
        binding = null
        writer = null
        currentStatus = VoiceAutomationStatus(VoiceAutomationRunState.Idle)
    }

    private fun recordActive(input: VoiceAutomationEventInput) {
        val activeBinding = checkNotNull(binding)
        val monotonicMs = nextMonotonicMs()
        checkNotNull(writer).append(event(activeBinding, input, monotonicMs))
        lastEmittedMonotonicMs = monotonicMs
        currentStatus = activeStatus(activeBinding, currentStatus.eventCount + 1)
    }

    private fun event(
        binding: VoiceAutomationRunBinding,
        input: VoiceAutomationEventInput,
        monotonicMs: Long,
    ) = VoiceAutomationEvent(
        monotonicMs = monotonicMs,
        wallClockMs = clock.wallClockMs(),
        runHash = binding.runHash,
        comparisonHash = binding.comparisonHash,
        requestedTransport = binding.requestedTransport,
        observedTransport = input.observedTransport,
        name = input.name,
        route = input.route,
        network = input.network,
        lifecycle = input.lifecycle,
        playbackEpoch = input.playbackEpoch,
        byteCount = input.byteCount,
        succeeded = input.succeeded,
        correlationKind = input.correlationKind,
        correlationHash = input.correlationHash,
    )

    private fun nextMonotonicMs(): Long {
        val observed = clock.monotonicMs()
        val previous = lastEmittedMonotonicMs ?: return observed
        check(previous < Long.MAX_VALUE) { "Automation monotonic timestamp exhausted" }
        return maxOf(observed, previous + 1)
    }

    private fun activeStatus(binding: VoiceAutomationRunBinding, eventCount: Long) = VoiceAutomationStatus(
        state = VoiceAutomationRunState.Active,
        runHash = binding.runHash,
        comparisonHash = binding.comparisonHash,
        requestedTransport = binding.requestedTransport,
        eventCount = eventCount,
    )
}

private object SystemVoiceAutomationClock : VoiceAutomationClock {
    override fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    override fun wallClockMs(): Long = System.currentTimeMillis()
}
