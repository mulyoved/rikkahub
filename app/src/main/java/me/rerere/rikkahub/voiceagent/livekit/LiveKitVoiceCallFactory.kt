package me.rerere.rikkahub.voiceagent.livekit

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.ChatServiceVoiceConversationStore
import me.rerere.rikkahub.voiceagent.SynchronizedVoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceAgentCallFactory
import me.rerere.rikkahub.voiceagent.VoiceAgentCallRequest
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentSessionCreationResult
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactWriter
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureFixtureArming
import me.rerere.rikkahub.voiceagent.audio.VoiceCaptureSource
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventInput
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationEventName
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRunBinding
import me.rerere.rikkahub.voiceagent.automation.VoiceAutomationRuntime
import me.rerere.rikkahub.voiceagent.finishFailedOwnedVoiceSessionCreation
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpException
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceTraceHeaders
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import me.rerere.rikkahub.voiceagent.telemetry.newVoiceTraceContext
import me.rerere.rikkahub.voiceagent.voiceAgentRouteCleanupOperation
import me.rerere.rikkahub.voiceagent.createDefaultVoiceE2EArtifactWriter
import java.io.File
import kotlin.uuid.Uuid
import org.koin.core.context.GlobalContext

private const val LIVEKIT_DISPATCH_INTERNAL = "livekit_dispatch_internal"
private const val LIVEKIT_DISPATCH_DEADLINE_EXCEEDED = "livekit_dispatch_deadline_exceeded"
private val LIVEKIT_DISPATCH_FAILURE_CODES = setOf(
    "livekit_dispatch_canceled",
    LIVEKIT_DISPATCH_DEADLINE_EXCEEDED,
    "livekit_dispatch_not_found",
    "livekit_dispatch_permission_denied",
    "livekit_dispatch_unauthenticated",
    "livekit_dispatch_resource_exhausted",
    "livekit_dispatch_failed_precondition",
    LIVEKIT_DISPATCH_INTERNAL,
    "livekit_dispatch_unavailable",
    "livekit_dispatch_unknown",
)

internal fun liveKitStartupFailureCode(error: Throwable): String =
    (error as? HermesVoiceHttpException)
        ?.failure
        ?.safeSummary
        ?.takeIf(LIVEKIT_DISPATCH_FAILURE_CODES::contains)
        ?: LIVEKIT_DISPATCH_INTERNAL

private fun liveKitFactoryAutomationRuntimeOrNull(): VoiceAutomationRuntime? =
    runCatching {
        GlobalContext.get().get<VoiceAutomationRuntime>()
    }.getOrNull()

internal class LiveKitVoiceCallFactory internal constructor(
    private val context: Context,
    private val chatService: ChatService? = null,
    private val traceContextFactory: () -> VoiceTraceContext = ::newVoiceTraceContext,
    private val sessionDetailsFactory: suspend (VoiceAgentCallRequest, VoiceTraceContext) -> LiveKitSessionDetails =
        { request, trace ->
            HermesVoiceApi(
                baseUrl = request.config.hermesVoiceBaseUrl,
                credentials = request.config.credentials,
                traceHeaders = HermesVoiceTraceHeaders.from(trace),
            ).createLiveKitSession(
                conversationId = request.conversationId.toString(),
                traceId = trace.traceId,
            )
        },
    private val roomFactory: () -> LiveKitRoomFacade = { AndroidLiveKitRoomFacade(context) },
    private val conversationStoreFactory: (Uuid) -> VoiceConversationStore = { conversationId ->
        ChatServiceVoiceConversationStore(
            conversationId = conversationId,
            chatService = requireNotNull(chatService) {
                "chatService is required for default conversation store"
            },
        )
    },
    private val artifactWriterFactory: (File, VoiceTraceContext, CoroutineScope) -> VoiceE2EArtifactWriter =
        ::createDefaultVoiceE2EArtifactWriter,
    private val automationRuntimeProvider: () -> VoiceAutomationRuntime? =
        ::liveKitFactoryAutomationRuntimeOrNull,
    private val sessionCreationTimeoutMillis: Long = DEFAULT_LIVEKIT_SESSION_CREATION_TIMEOUT_MS,
) : VoiceAgentCallFactory {
    constructor(
        context: Context,
        chatService: ChatService,
    ) : this(
        context = context,
        chatService = chatService,
        traceContextFactory = ::newVoiceTraceContext,
    )

    init {
        require(sessionCreationTimeoutMillis > 0) { "sessionCreationTimeoutMillis must be positive" }
    }

    override suspend fun createOwned(
        request: VoiceAgentCallRequest,
        routeLease: VoiceAgentRouteLease,
        scope: CoroutineScope,
        endDrainTimeoutMillis: Long,
    ): VoiceAgentSessionCreationResult {
        val cleanup = voiceAgentRouteCleanupOperation(routeLease)
        var captureSource: VoiceCaptureSource? = null
        var conversationStore: VoiceConversationStore? = null
        var artifactWriter: VoiceE2EArtifactWriter? = null
        var resourcesTransferred = false
        return try {
            captureSource = VoiceCaptureFixtureArming.claimSource(request.captureFixtureToken)
                .getOrElse { cause ->
                    throw LiveKitExperimentalVoiceCallException(
                        message = "Capture fixture token is not armed",
                        cause = cause,
                    )
                }
            val trace = traceContextFactory()
            val details = withTimeout(sessionCreationTimeoutMillis) {
                sessionDetailsFactory(request, trace)
            }
            val trustedBinding = details.requireTrustedCorrelationBinding(
                conversationId = request.conversationId.toString(),
                traceId = trace.traceId,
            )
            conversationStore = SynchronizedVoiceConversationStore(
                conversationStoreFactory(request.conversationId),
            )
            artifactWriter = artifactWriterFactory(context.noBackupFilesDir, trace, scope)
            val transcriptPersister = VoiceTranscriptPersister()
            val persistenceBridge = LiveKitVoicePersistenceBridge(
                voiceSessionId = details.voiceSessionId,
                agentIdentity = details.agentParticipantIdentity,
                expectedCorrelation = trustedBinding.toJobCorrelation(),
                queueStore = HermesQueueStore(
                    conversationStore = conversationStore,
                    writer = HermesToolRecordWriter(),
                    transcriptPersister = transcriptPersister,
                    persistenceSessionId = { details.voiceSessionId },
                ),
                transcriptPersister = transcriptPersister,
                conversationStore = conversationStore,
                evidence = VoiceExperienceEvidenceWriter(artifactWriter),
            )
            persistenceBridge.initialize()
            val persistenceOwner = LiveKitPersistenceResources(
                bridge = persistenceBridge,
                artifactWriter = artifactWriter,
            )
            val session = LiveKitVoiceCallSession(
                details = details,
                traceId = trace.traceId,
                room = roomFactory(),
                routeLease = routeLease,
                scope = scope,
                captureSource = checkNotNull(captureSource),
                persistenceHandler = { invocation ->
                    persistenceBridge.handle(invocation.callerIdentity, invocation.payload)
                },
                persistenceOwner = persistenceOwner,
            )
            resourcesTransferred = true
            VoiceAgentSessionCreationResult.Created(session)
        } catch (_: TimeoutCancellationException) {
            val safeCode = LIVEKIT_DISPATCH_DEADLINE_EXCEEDED
            val creationError = LiveKitExperimentalVoiceCallException(
                "LiveKit experimental voice session request failed ($safeCode)",
            )
            if (!resourcesTransferred) {
                runCatching { captureSource?.close() }
                    .onFailure(creationError::addSuppressed)
                runCatching { artifactWriter?.close() }
                    .onFailure(creationError::addSuppressed)
                runCatching { conversationStore?.close() }
                    .onFailure(creationError::addSuppressed)
            }
            recordStartupFailure(request, safeCode)
            finishFailedOwnedVoiceSessionCreation(
                creationError,
                cleanup,
            )
        } catch (creationError: Throwable) {
            if (!resourcesTransferred) {
                runCatching { captureSource?.close() }
                    .onFailure(creationError::addSuppressed)
                runCatching { artifactWriter?.close() }
                    .onFailure(creationError::addSuppressed)
                runCatching { conversationStore?.close() }
                    .onFailure(creationError::addSuppressed)
            }
            val safeCode = liveKitStartupFailureCode(creationError)
            recordStartupFailure(request, safeCode)
            finishFailedOwnedVoiceSessionCreation(
                if (creationError is CancellationException) {
                    creationError
                } else {
                    LiveKitExperimentalVoiceCallException(
                        message = "LiveKit experimental voice session request failed ($safeCode)",
                        cause = creationError,
                    )
                },
                cleanup,
            )
        }
    }

    private fun recordStartupFailure(request: VoiceAgentCallRequest, safeCode: String) {
        val requestBinding = request.automationBinding ?: return
        val runtime = automationRuntimeProvider() ?: return
        val runBinding = VoiceAutomationRunBinding(
            runHash = requestBinding.runHash,
            comparisonHash = requestBinding.comparisonHash,
            requestedTransport = me.rerere.rikkahub.voiceagent.VoiceAgentTransport.LiveKitExperimental,
        )
        if (!runtime.activeBindingMatches(runBinding)) return
        runtime.recordIfActiveRun(
            requestBinding.runHash,
            VoiceAutomationEventInput(
                name = VoiceAutomationEventName.FAILURE,
                observedTransport = me.rerere.rikkahub.voiceagent.VoiceAgentTransport.LiveKitExperimental,
                succeeded = false,
            ),
        )
    }

    private companion object {
        const val DEFAULT_LIVEKIT_SESSION_CREATION_TIMEOUT_MS = 15_000L
    }
}

private class LiveKitPersistenceResources(
    private val bridge: LiveKitPersistenceOwner,
    private val artifactWriter: VoiceE2EArtifactWriter,
) : LiveKitPersistenceOwner {
    override suspend fun drain() {
        bridge.drain()
        artifactWriter.close()
    }

    override fun close() {
        bridge.close()
    }
}

internal class LiveKitExperimentalVoiceCallException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
