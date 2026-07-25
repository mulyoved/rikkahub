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

    @Synchronized
    override fun prepare(binding: VoiceAutomationRunBinding) {
        check(currentStatus.state == VoiceAutomationRunState.Idle) {
            "Automation run is already prepared or finalized"
        }
        VoiceAutomationEventValidation.validate(binding)
        this.binding = binding
        writer = VoiceAutomationEventWriter.create(noBackupFilesDir, binding.runHash)
        currentStatus = activeStatus(binding, eventCount = 0)
        recordActive(VoiceAutomationEventInput(VoiceAutomationEventName.RUN_PREPARED))
    }

    @Synchronized
    override fun record(event: VoiceAutomationEventInput) {
        when (currentStatus.state) {
            VoiceAutomationRunState.Idle -> Unit
            VoiceAutomationRunState.Active -> recordActive(event)
            VoiceAutomationRunState.Finalized -> error("Automation run has already been finalized")
        }
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
        val event = VoiceAutomationEvent(
            monotonicMs = clock.monotonicMs(),
            wallClockMs = clock.wallClockMs(),
            runHash = activeBinding.runHash,
            comparisonHash = activeBinding.comparisonHash,
            requestedTransport = activeBinding.requestedTransport,
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
        checkNotNull(writer).append(event)
        currentStatus = activeStatus(activeBinding, currentStatus.eventCount + 1)
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
