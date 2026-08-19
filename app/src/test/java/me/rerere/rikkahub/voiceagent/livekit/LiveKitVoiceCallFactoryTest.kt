package me.rerere.rikkahub.voiceagent.livekit

import android.content.ContextWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifact
import me.rerere.rikkahub.voiceagent.VoiceE2EArtifactWriter
import me.rerere.rikkahub.voiceagent.OrchestratorFakeRoute
import me.rerere.rikkahub.voiceagent.SpyVoiceAgentRouteLease
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupMode
import me.rerere.rikkahub.voiceagent.VoiceAgentCleanupResult
import me.rerere.rikkahub.voiceagent.VoiceAgentRouteMetadata
import me.rerere.rikkahub.voiceagent.VoiceAgentSessionCreationResult
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.audio.VoiceAudioRouteOwner
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpException
import me.rerere.rikkahub.voiceagent.orchestratorRequest
import me.rerere.rikkahub.voiceagent.recovery.AcceptedHermesBinding
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryCoordinator
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.HermesTerminalCommitter
import me.rerere.rikkahub.voiceagent.recovery.RecoveryOutcome
import me.rerere.rikkahub.voiceagent.recovery.RecoveryTrigger
import me.rerere.rikkahub.voiceagent.recovery.hermesEndpointBindingHash
import me.rerere.rikkahub.voiceagent.telemetry.VoiceTraceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class LiveKitVoiceCallFactoryTest {
    @Test
    fun `non-Telecom route lease is rejected cleanly and lease is retired`() = runTest {
        val spyLease = SpyVoiceAgentRouteLease(
            metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback),
        )

        val factory = factory(
            sessionDetailsFactory = { _, _ -> factoryDetails(SESSION_BINDING) },
        )
        val result = factory.createOwned(
            request(),
            spyLease,
            backgroundScope,
        )

        assertTrue("Expected FailedClean outcome", result is VoiceAgentSessionCreationResult.FailedClean)
        assertEquals("Route lease retire() must be invoked exactly once", 1, spyLease.retireCalls)
    }

    @Test
    fun `non-Telecom route lease returns FailedDirty if retirement throws exception`() = runTest {
        val failingLease = SpyVoiceAgentRouteLease(
            metadata = VoiceAgentRouteMetadata(VoiceAudioRouteOwner.DirectFallback),
            onRetire = { throw IllegalStateException("Retirement error") },
        )

        val factory = factory(
            sessionDetailsFactory = { _, _ -> factoryDetails(SESSION_BINDING) },
        )
        val result = factory.createOwned(
            request(),
            failingLease,
            backgroundScope,
        )

        assertTrue("Expected FailedDirty outcome on cleanup failure", result is VoiceAgentSessionCreationResult.FailedDirty)
    }

    @Test
    fun `each locally recomputable binding mismatch fails before room construction`() = runTest {
        val mismatches = listOf(
            SESSION_BINDING.copy(conversationHash = hash('9')),
            SESSION_BINDING.copy(voiceSessionHash = hash('9')),
            SESSION_BINDING.copy(roomHash = hash('9')),
            SESSION_BINDING.copy(traceHash = hash('9')),
        )

        mismatches.forEachIndexed { index, binding ->
            var roomFactoryCalls = 0
            val factory = factory(
                sessionDetailsFactory = { _, _ -> factoryDetails(binding) },
                roomFactory = {
                    roomFactoryCalls += 1
                    InertLiveKitRoomFacade()
                },
            )

            val result = factory.createOwned(
                request(),
                OrchestratorFakeRoute().lease,
                backgroundScope,
            )

            assertTrue("binding mismatch $index", result is VoiceAgentSessionCreationResult.FailedClean)
            assertEquals("binding mismatch $index", 0, roomFactoryCalls)
        }
    }

    @Test
    fun `session request timeout retires the exact route and returns clean failure`() = runTest {
        val route = OrchestratorFakeRoute()
        var roomFactoryCalls = 0
        val factory = factory(
            sessionDetailsFactory = { _, _ -> CompletableDeferred<LiveKitSessionDetails>().await() },
            roomFactory = {
                roomFactoryCalls += 1
                InertLiveKitRoomFacade()
            },
            timeoutMillis = 100,
        )
        val result = async {
            factory.createOwned(request(), route.lease, backgroundScope)
        }

        advanceTimeBy(100)
        runCurrent()

        val failure = result.await() as VoiceAgentSessionCreationResult.FailedClean
        val error = failure.error as LiveKitExperimentalVoiceCallException
        assertEquals(LiveKitSessionFailureCategory.SessionTimeout, error.failureCategory)
        assertTrue(error.message.orEmpty().contains("timed out"))
        assertEquals(1, route.retirementCalls)
        assertEquals(0, roomFactoryCalls)
    }

    @Test
    fun `session HTTP server failure retains only a fixed diagnostic category`() = runTest {
        val privateDetail = "private upstream response detail"
        val requestError = HermesVoiceHttpException(
            statusCode = 502,
            safePreview = privateDetail,
        )
        val factory = factory(sessionDetailsFactory = { _, _ -> throw requestError })

        val result = factory.createOwned(
            request(),
            OrchestratorFakeRoute().lease,
            backgroundScope,
        )

        val failure = result as VoiceAgentSessionCreationResult.FailedClean
        val error = failure.error as LiveKitExperimentalVoiceCallException
        assertEquals(LiveKitSessionFailureCategory.HttpServerFailure, error.failureCategory)
        assertTrue(error.message.orEmpty().contains("request failed"))
        assertTrue(!error.message.orEmpty().contains(privateDetail))
    }

    @Test
    fun `session request error is wrapped and retires the exact route`() = runTest {
        val route = OrchestratorFakeRoute()
        val requestError = IllegalStateException("request failed")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw requestError })

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedClean
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertCausalChainContains(failure.error, requestError)
        assertEquals(1, route.retirementCalls)
    }

    @Test
    fun `session request cancellation stays exact after owned route retirement`() = runTest {
        val route = OrchestratorFakeRoute()
        val cancellation = CancellationException("caller cancelled")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw cancellation })

        val thrown = runCatching {
            factory.createOwned(request(), route.lease, backgroundScope)
        }.exceptionOrNull()

        assertSame(cancellation, thrown)
        assertEquals(1, route.retirementCalls)
    }

    @Test
    fun `route retirement failure returns dirty ownership and retries only that route`() = runTest {
        val retirementError = IllegalArgumentException("route retirement failed")
        var currentRetirementError: Throwable? = retirementError
        val route = OrchestratorFakeRoute { currentRetirementError?.let { throw it } }
        val requestError = IllegalStateException("request failed")
        val factory = factory(sessionDetailsFactory = { _, _ -> throw requestError })

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedDirty
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertCausalChainContains(failure.error, requestError)
        assertEquals(listOf(retirementError), failure.error.suppressed.toList())
        assertEquals(1, route.retirementCalls)

        currentRetirementError = null
        assertSame(
            VoiceAgentCleanupResult.Completed,
            failure.cleanup.run(VoiceAgentCleanupMode.Immediate),
        )
        assertEquals(2, route.retirementCalls)
    }

    @Test
    fun `room factory failure after details transfers no session and retires the route`() = runTest {
        val route = OrchestratorFakeRoute()
        val roomError = IllegalStateException("room construction failed")
        var detailsCalls = 0
        val factory = factory(
            sessionDetailsFactory = { _, _ ->
                detailsCalls += 1
                factoryDetails()
            },
            roomFactory = { throw roomError },
        )

        val result = factory.createOwned(request(), route.lease, backgroundScope)

        val failure = result as VoiceAgentSessionCreationResult.FailedClean
        assertTrue(failure.error is LiveKitExperimentalVoiceCallException)
        assertSame(roomError, failure.error.cause)
        assertEquals(1, detailsCalls)
        assertEquals(1, route.retirementCalls)
    }

    @Test
    fun `created session persists worker events in its requested conversation store`() = runTest {
        val root = Files.createTempDirectory("livekit-factory-persistence").toFile()
        val room = InertLiveKitRoomFacade()
        val request = request()
        val store = RecordingFactoryConversationStore(request.conversationId)
        try {
            val factory = factory(
                sessionDetailsFactory = { _, _ -> factoryDetails() },
                roomFactory = { room },
                conversationStoreFactory = { conversationId ->
                    assertEquals(request.conversationId, conversationId)
                    store
                },
                noBackupFilesDir = root,
            )

            val result = factory.createOwned(request, OrchestratorFakeRoute().lease, backgroundScope)
            val session = (result as VoiceAgentSessionCreationResult.Created).session
            session.start()
            runCurrent()
            val ack = room.invoke(
                method = LIVEKIT_PERSISTENCE_RPC,
                caller = factoryDetails().agentParticipantIdentity,
                payload = acceptedEventJson(),
            )

            assertEquals("persisted", parseLiveKitPersistenceAck(ack)?.status)
            assertEquals(1, store.updateCalls)
            assertEquals(
                VoiceAgentCleanupResult.Completed,
                session.cleanupOperation.run(VoiceAgentCleanupMode.GracefulEnd),
            )
            assertEquals(1, store.closeCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `construction failure after store opens closes the conversation store`() = runTest {
        val root = Files.createTempDirectory("livekit-factory-construction-failure").toFile()
        val route = OrchestratorFakeRoute()
        val store = RecordingFactoryConversationStore(request().conversationId)
        try {
            val factory = factory(
                sessionDetailsFactory = { _, _ -> factoryDetails() },
                roomFactory = { throw IllegalStateException("room construction failed") },
                conversationStoreFactory = { store },
                noBackupFilesDir = root,
            )

            val result = factory.createOwned(request(), route.lease, backgroundScope)

            assertTrue(result is VoiceAgentSessionCreationResult.FailedClean)
            assertEquals(1, store.closeCalls)
            assertEquals(1, route.retirementCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `room construction failure flushes and retires the enabled artifact writer`() = runTest {
        val root = Files.createTempDirectory("livekit-factory-writer-retirement").toFile()
        val writerJob = SupervisorJob()
        val writerScope = CoroutineScope(writerJob + StandardTestDispatcher(testScheduler))
        try {
            val factory = factory(
                sessionDetailsFactory = { _, _ -> factoryDetails() },
                roomFactory = { throw IllegalStateException("room construction failed") },
                artifactWriterFactory = { directory, trace, _ ->
                    VoiceE2EArtifactWriter.create(
                        enabled = true,
                        rootDirectory = directory,
                        traceId = trace.traceId,
                        scope = writerScope,
                    ).also { writer ->
                        writer.write(
                            VoiceE2EArtifact.VoiceExperienceEvents,
                            """{"kind":"construction_failed"}""",
                        )
                    }
                },
                noBackupFilesDir = root,
            )

            val result = factory.createOwned(request(), OrchestratorFakeRoute().lease, writerScope)

            assertTrue(result is VoiceAgentSessionCreationResult.FailedClean)
            val lines = File(
                root,
                "voice-e2e/VA123456-0000000000000001/voice-experience-events.ndjson",
            ).readLines()
            assertEquals("""{"kind":"construction_failed"}""", lines.first())
            assertEquals("session_binding", Json.parseToJsonElement(lines.last()).jsonObject["kind"]?.toString()?.trim('"'))
            assertTrue(writerJob.children.none { it.isActive })
        } finally {
            writerScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `immediate cleanup flushes persisted evidence before call scope cancellation`() = runTest {
        val root = Files.createTempDirectory("livekit-immediate-evidence").toFile()
        val callJob = SupervisorJob()
        val callScope = CoroutineScope(callJob + StandardTestDispatcher(testScheduler))
        val terminalWriteStarted = CountDownLatch(1)
        val releaseTerminalWrite = CountDownLatch(1)
        val room = InertLiveKitRoomFacade()
        try {
            val factory = factory(
                sessionDetailsFactory = { _, _ -> factoryDetails() },
                roomFactory = { room },
                artifactWriterFactory = { directory, trace, _ ->
                    VoiceE2EArtifactWriter.create(
                        enabled = true,
                        rootDirectory = directory,
                        traceId = trace.traceId,
                        scope = callScope,
                        atomicMove = { source, target, _ ->
                            if (target.fileName.toString() == "session.json") {
                                terminalWriteStarted.countDown()
                                check(releaseTerminalWrite.await(5, TimeUnit.SECONDS)) {
                                    "terminal write release timed out"
                                }
                            }
                            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                        },
                    ).also { writer ->
                        writer.writeTerminalSessionJson("""{"status":"active"}""")
                    }
                },
                noBackupFilesDir = root,
            )
            val result = factory.createOwned(
                request(),
                OrchestratorFakeRoute().lease,
                callScope,
            )
            val session = (result as VoiceAgentSessionCreationResult.Created).session
            assertTrue(terminalWriteStarted.await(5, TimeUnit.SECONDS))
            session.start()

            val ack = room.invoke(
                method = LIVEKIT_PERSISTENCE_RPC,
                caller = factoryDetails().agentParticipantIdentity,
                payload = acceptedEventJson(),
            )
            val cleanup = async {
                session.cleanupOperation.run(VoiceAgentCleanupMode.Immediate)
            }
            runCurrent()

            assertEquals("persisted", parseLiveKitPersistenceAck(ack)?.status)
            assertTrue("cleanup returned before evidence flush", !cleanup.isCompleted)

            releaseTerminalWrite.countDown()
            assertEquals(VoiceAgentCleanupResult.Completed, cleanup.await())
            callScope.cancel()
            runCurrent()

            val traceDirectory = File(root, "voice-e2e/VA123456-0000000000000001")
            val privateLines = File(traceDirectory, "voice-experience-private.ndjson").readLines()
            assertEquals(2, privateLines.size)
            assertEquals("session_binding", Json.parseToJsonElement(privateLines.first()).jsonObject["kind"]?.toString()?.trim('"'))
            assertEquals(acceptedEventJson(), privateLines.last())
            val sanitizedLines = File(traceDirectory, "voice-experience-events.ndjson").readLines()
            assertEquals(2, sanitizedLines.size)
            assertEquals(
                listOf("session_binding", "job_accepted"),
                sanitizedLines.map { line ->
                    Json.parseToJsonElement(line).jsonObject["kind"]?.toString()?.trim('"')
                },
            )
        } finally {
            releaseTerminalWrite.countDown()
            callScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `captures endpoint binding hash at session creation and uses it even if settings change before job accepted`() = runTest {
        val endpointA = "https://endpoint-a.example.com/api"
        val endpointB = "https://endpoint-b.example.com/api"
        val initialRequest = request()
        val requestA = initialRequest.copy(
            config = initialRequest.config.copy(hermesVoiceBaseUrl = endpointA),
        )

        var capturedBinding: AcceptedHermesBinding? = null
        val fakeCoordinator = object : HermesRecoveryCoordinator {
            override suspend fun registerAccepted(binding: AcceptedHermesBinding): String {
                capturedBinding = binding
                return "recovery-key-1"
            }
            override fun onPersistedRelayEvent(recoveryKey: String) = Unit
            override fun onCallEnded(voiceSessionId: String) = Unit
            override suspend fun requestCancellation(recoveryKey: String) = Unit
            override suspend fun reconcile(recoveryKey: String, trigger: RecoveryTrigger): RecoveryOutcome = RecoveryOutcome.Success
            override suspend fun reactivateConversation(conversationId: Uuid, trigger: RecoveryTrigger) = Unit
            override suspend fun reactivateDormant(trigger: RecoveryTrigger) = Unit
            override suspend fun repairAll() = Unit
            override suspend fun repairConversation(conversationId: Uuid) = Unit
        }

        val room = InertLiveKitRoomFacade()
        val factory = factory(
            sessionDetailsFactory = { _, _ -> factoryDetails() },
            roomFactory = { room },
            coordinator = fakeCoordinator,
        )

        val result = factory.createOwned(requestA, OrchestratorFakeRoute().lease, backgroundScope)
        val session = (result as VoiceAgentSessionCreationResult.Created).session
        session.start()
        runCurrent()

        // Settings change to endpoint B before job accepted
        val expectedEventHash = "sha256:${"7".repeat(64)}"
        val acceptedPayload = acceptedEventJson(
            userTurnId = "turn_lifetime_test",
            requestHash = expectedEventHash,
        )

        val ack = room.invoke(
            method = LIVEKIT_PERSISTENCE_RPC,
            caller = factoryDetails().agentParticipantIdentity,
            payload = acceptedPayload,
        )

        assertEquals("persisted", parseLiveKitPersistenceAck(ack)?.status)
        assertNotNull("registerAccepted must have been called", capturedBinding)
        assertEquals(hermesEndpointBindingHash(endpointA), capturedBinding!!.endpointBindingHash)
        assertNotEquals(hermesEndpointBindingHash(endpointB), capturedBinding!!.endpointBindingHash)
        assertEquals("turn_lifetime_test", capturedBinding!!.originatingUserTurnId)
        assertEquals(expectedEventHash, capturedBinding!!.requestHash)
    }

    private fun factory(
        sessionDetailsFactory: suspend (
            me.rerere.rikkahub.voiceagent.VoiceAgentCallRequest,
            VoiceTraceContext,
        ) -> LiveKitSessionDetails,
        roomFactory: () -> LiveKitRoomFacade = { InertLiveKitRoomFacade() },
        conversationStoreFactory: (Uuid) -> VoiceConversationStore = {
            RecordingFactoryConversationStore(it)
        },
        artifactWriterFactory: (File, VoiceTraceContext, CoroutineScope) -> VoiceE2EArtifactWriter =
            { _, _, _ -> VoiceE2EArtifactWriter.disabled() },
        coordinator: HermesRecoveryCoordinator? = null,
        terminalCommitter: HermesTerminalCommitter? = null,
        ledger: HermesRecoveryLedger? = null,
        noBackupFilesDir: File = File("build/tmp/livekit-factory-test"),
        timeoutMillis: Long = 1_000,
    ) = LiveKitVoiceCallFactory(
        context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = noBackupFilesDir
        },
        traceContextFactory = {
            VoiceTraceContext(
                traceId = "VA123456-0000000000000001",
                voiceSessionId = "voice-session",
            )
        },
        sessionDetailsFactory = sessionDetailsFactory,
        roomFactory = roomFactory,
        conversationStoreFactory = conversationStoreFactory,
        artifactWriterFactory = artifactWriterFactory,
        coordinator = coordinator,
        terminalCommitter = terminalCommitter,
        ledger = ledger,
        sessionCreationTimeoutMillis = timeoutMillis,
    )

    private fun request() = orchestratorRequest("livekit-factory").copy(
        conversationId = Uuid.parse(CONVERSATION_ID),
        transport = VoiceAgentTransport.LiveKitExperimental,
    )
}

private class InertLiveKitRoomFacade : LiveKitRoomFacade {
    override val events: Flow<LiveKitRoomEvent> = emptyFlow()
    override suspend fun connect(url: String, token: String) = Unit
    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean = true
    override suspend fun performRpc(destination: String, method: String, payload: String): String = ""
    private val rpcHandlers = mutableMapOf<String, suspend (LiveKitRpcInvocation) -> String>()
    suspend fun invoke(method: String, caller: String, payload: String): String =
        rpcHandlers.getValue(method)(LiveKitRpcInvocation(caller, payload))
    override fun registerRpcMethod(method: String, handler: suspend (LiveKitRpcInvocation) -> String) {
        rpcHandlers[method] = handler
    }
    override fun unregisterRpcMethod(method: String) {
        rpcHandlers.remove(method)
    }
    override fun disconnect() = Unit
    override fun close() = Unit
}

private class RecordingFactoryConversationStore(
    conversationId: Uuid,
) : VoiceConversationStore {
    private val mutableConversation = MutableStateFlow(Conversation.ofId(conversationId))
    override val conversation: StateFlow<Conversation> = mutableConversation
    var updateCalls = 0
    var closeCalls = 0

    override suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T {
        updateCalls += 1
        val (updated, result) = transform(mutableConversation.value)
        commit(result)
        mutableConversation.value = updated
        return result
    }

    override fun close() {
        closeCalls += 1
    }
}

private fun factoryDetails(
    correlationBinding: LiveKitSessionCorrelationBinding = SESSION_BINDING,
) = LiveKitSessionDetails(
    livekitUrl = "wss://project.livekit.cloud",
    participantToken = "participant-token",
    roomName = "rikka_1",
    voiceSessionId = "lvs_1",
    mobileParticipantIdentity = "mobile_lvs_1",
    agentParticipantIdentity = "agent_lvs_1",
    dispatchId = "AD_1",
    expiresAt = "2026-07-20T02:00:00Z",
    correlationBinding = correlationBinding,
)

private const val CONVERSATION_ID = "018f0000-0000-7000-8000-000000000001"
private val SESSION_BINDING = LiveKitSessionCorrelationBinding(
    ownerHash = hash('1'),
    conversationHash = "sha256:d604c61b95ebfb79347557e2b9bad92e0226bc9ef75850258cb18edb91885c4b",
    voiceSessionHash = "sha256:6dde1c43f223440f4bfba0ed05aa33cb837253ac01e0cadc1d223eff98914e06",
    roomHash = "sha256:3991f60c5217aa9e5a07f65f0fcbdd77e67e3ad561e3b36a0bab7afcea93aeee",
    traceHash = "sha256:e360c878ca1a503b8b97b628774ffb56350c57838c3053321436e09733acd3a0",
)

private fun hash(character: Char): String = "sha256:" + character.toString().repeat(64)

private fun acceptedEventJson(
    userTurnId: String = "turn_1",
    requestHash: String = "sha256:${"2".repeat(64)}",
): String =
    CanonicalVoiceExperienceJson.encodeObject(
        Json.parseToJsonElement(
            """{"version":1,"voiceSessionId":"lvs_1","eventId":"evt_accepted","kind":"job_accepted","observedAt":"2026-07-30T12:00:00Z","userTurnId":"$userTurnId","requestHash":"$requestHash","toolCallId":"call_1","argumentHash":"sha256:${"1".repeat(64)}","jobId":"hj_1","ownerHash":"${SESSION_BINDING.ownerHash}","conversationHash":"${SESSION_BINDING.conversationHash}","voiceSessionHash":"${SESSION_BINDING.voiceSessionHash}","roomHash":"${SESSION_BINDING.roomHash}","traceHash":"${SESSION_BINDING.traceHash}","prompt":"private question"}"""
        ).jsonObject,
    )

private fun assertCausalChainContains(error: Throwable, expected: Throwable) {
    assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it === expected })
}
