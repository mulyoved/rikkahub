package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceCredentials
import kotlin.uuid.Uuid

internal class OrchestratorFakeRoute {
    private val registry = VoiceAgentTelecomCallRegistry()
    private val attempt = registry.beginAttempt().requireAllocatedAttemptId()
    var retirementCalls = 0
    val lease: VoiceAgentRouteLease

    init {
        check(
            registry.activate(
                attempt,
                object : VoiceAgentTelecomCall {
                    override fun disconnectFromApp() {
                        retirementCalls += 1
                    }
                },
            ),
        )
        lease = registry.consumeActiveOutcome(attempt).requireResolvedLease()
    }
}

internal class OrchestratorFakeSession(
    initialState: VoiceAgentUiState = VoiceAgentUiState(session = VoiceSessionStatus.Connected),
    override val routeMetadata: VoiceAgentRouteMetadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.Telecom),
    override val cleanupOperation: VoiceAgentCleanupOperation = OrchestratorFakeCleanupOperation(),
    private val onStart: () -> Unit = {},
) : RouteOwnedManagedVoiceCallSession {
    private val mutableState = MutableStateFlow(initialState)
    override val state: StateFlow<VoiceAgentUiState> = mutableState
    override var isRouteUsable: Boolean = true
    var startCalls = 0
    var interruptCalls = 0
    var reconnectCalls = 0
    var mutedValues = mutableListOf<Boolean>()
    var diagnostics = mutableListOf<Pair<String, String>>()

    fun emit(value: VoiceAgentUiState) {
        mutableState.value = value
    }

    fun collectorCount(): Int = mutableState.subscriptionCount.value

    override fun start() {
        startCalls += 1
        onStart()
    }

    override fun interrupt() {
        interruptCalls += 1
    }

    override fun setMuted(value: Boolean) {
        mutedValues += value
    }

    override fun reconnect() {
        reconnectCalls += 1
    }

    override fun recordDiagnostic(name: String, detail: String) {
        diagnostics += name to detail
    }

    override fun end() = Unit

    override suspend fun endAndDrain() = Unit

    override suspend fun endAndDrainWithin(timeoutMillis: Long) = Unit

    override fun closeNow() = Unit
}

internal class OrchestratorFakeCleanupOperation(
    private val block: suspend (VoiceAgentCleanupMode) -> VoiceAgentCleanupResult = {
        VoiceAgentCleanupResult.Completed
    },
) : VoiceAgentCleanupOperation {
    override val token: Any = Any()
    val modes = mutableListOf<VoiceAgentCleanupMode>()

    override suspend fun run(mode: VoiceAgentCleanupMode): VoiceAgentCleanupResult {
        modes += mode
        return block(mode)
    }
}

internal class OrchestratorFakeFactory(
    private val createResult: suspend (
        VoiceAgentCallRequest,
        VoiceAgentRouteLease,
        CoroutineScope,
    ) -> VoiceAgentSessionCreationResult,
) : VoiceAgentCallFactory {
    var calls = 0
    val requests = mutableListOf<VoiceAgentCallRequest>()
    val leases = mutableListOf<VoiceAgentRouteLease>()
    val scopes = mutableListOf<CoroutineScope>()

    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): VoiceAgentSessionCreationResult {
        calls += 1
        requests += request
        leases += routeLease
        scopes += scope
        return createResult(request, routeLease, scope)
    }

    override fun create(
        conversationId: Uuid,
        config: VoiceAgentLaunchConfig,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
    ): RouteOwnedManagedVoiceCallSession = error("createOwned is the only supported test boundary")
}

internal fun orchestratorRequest(label: String): VoiceAgentCallRequest = VoiceAgentCallRequest(
    conversationId = Uuid.random(),
    config = VoiceAgentLaunchConfig(
        hermesVoiceBaseUrl = "https://$label.voice.test",
        credentials = HermesVoiceCredentials(deviceApiKey = "test-key"),
        voiceModelId = label,
        assistantName = "Assistant $label",
        assistantPrompt = "Prompt $label",
    ),
)
