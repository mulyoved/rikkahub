package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.ChatServiceVoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesToolRecordWriter
import me.rerere.rikkahub.voiceagent.hermes.hermesQueueRecords
import me.rerere.rikkahub.voiceagent.hermes.latestByHermesDurableIdentity
import me.rerere.rikkahub.voiceagent.livekit.voiceSha256
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

internal data class AcceptedHermesBinding(
    val conversationId: Uuid,
    val callId: String,
    val jobId: String,
    val prompt: String,
    val producer: String,
    val originatingUserTurnId: String,
    val requestHash: String,
    val voiceSessionId: String,
    val argumentHash: String,
    val acceptingOwnerHash: String,
    val endpointBindingHash: String,
    val acceptedAtEpochMillis: Long,
)

internal enum class RecoveryTrigger {
    Scheduled,
    CallEnded,
    Cancellation,
    StartupRepair,
    ConversationOpened,
    ConfigurationChanged,
    ExplicitRetry,
}

internal enum class RecoveryOutcome { Success, Retry }

internal fun hermesRecoveryKey(conversationId: Uuid, callId: String, jobId: String): String =
    recoverySha256("$conversationId\u0000$callId\u0000$jobId")

internal interface HermesRecoveryCoordinator {
    suspend fun registerAccepted(binding: AcceptedHermesBinding): String
    fun onPersistedRelayEvent(recoveryKey: String)
    fun onCallEnded(voiceSessionId: String)
    suspend fun requestCancellation(recoveryKey: String)
    suspend fun reconcile(recoveryKey: String, trigger: RecoveryTrigger): RecoveryOutcome
    suspend fun reactivateConversation(conversationId: Uuid, trigger: RecoveryTrigger)
    suspend fun reactivateDormant(trigger: RecoveryTrigger = RecoveryTrigger.ConfigurationChanged)
    suspend fun repairAll()
    suspend fun repairConversation(conversationId: Uuid)

    companion object {
        operator fun invoke(
            ledger: HermesRecoveryLedger,
            scheduler: HermesRecoveryWorkScheduler,
            relayRegistry: HermesRelayRegistry,
            endpointResolver: HermesRecoveryEndpointResolver,
            chatService: ChatService? = null,
            conversationStoreProvider: ((Uuid) -> VoiceConversationStore)? = null,
            snapshotReconciler: HermesSnapshotReconciler = HermesSnapshotReconciler(),
            terminalCommitter: HermesTerminalCommitter = HermesTerminalCommitter(ledger),
            clock: RecoveryClock = SystemRecoveryClock,
            toolRecordWriter: HermesToolRecordWriter = HermesToolRecordWriter(),
            transcriptPersister: VoiceTranscriptPersister = VoiceTranscriptPersister(),
            coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            conversationIdsProvider: (suspend () -> List<Uuid>)? = null,
        ): HermesRecoveryCoordinator = DefaultHermesRecoveryCoordinator(
            ledger = ledger,
            scheduler = scheduler,
            relayRegistry = relayRegistry,
            endpointResolver = endpointResolver,
            chatService = chatService,
            conversationStoreProvider = conversationStoreProvider,
            snapshotReconciler = snapshotReconciler,
            terminalCommitter = terminalCommitter,
            clock = clock,
            toolRecordWriter = toolRecordWriter,
            transcriptPersister = transcriptPersister,
            coroutineScope = coroutineScope,
            conversationIdsProvider = conversationIdsProvider,
        )
    }
}

internal class DefaultHermesRecoveryCoordinator(
    private val ledger: HermesRecoveryLedger,
    private val scheduler: HermesRecoveryWorkScheduler,
    private val relayRegistry: HermesRelayRegistry,
    private val endpointResolver: HermesRecoveryEndpointResolver,
    private val chatService: ChatService?,
    private val conversationStoreProvider: ((Uuid) -> VoiceConversationStore)?,
    private val snapshotReconciler: HermesSnapshotReconciler,
    private val terminalCommitter: HermesTerminalCommitter,
    private val clock: RecoveryClock,
    private val toolRecordWriter: HermesToolRecordWriter,
    private val transcriptPersister: VoiceTranscriptPersister,
    private val coroutineScope: CoroutineScope,
    private val conversationIdsProvider: (suspend () -> List<Uuid>)?,
) : HermesRecoveryCoordinator {

    private fun getConversationStore(conversationId: Uuid): VoiceConversationStore {
        if (conversationStoreProvider != null) {
            return conversationStoreProvider.invoke(conversationId)
        }
        if (chatService != null) {
            return ChatServiceVoiceConversationStore(
                conversationId = conversationId,
                chatService = chatService,
            )
        }
        error("Neither conversationStoreProvider nor chatService provided")
    }

    private fun getQueueStore(conversationId: Uuid): HermesQueueStore {
        val convStore = getConversationStore(conversationId)
        return HermesQueueStore(
            conversationStore = convStore,
            writer = toolRecordWriter,
            transcriptPersister = transcriptPersister,
        )
    }

    override suspend fun registerAccepted(binding: AcceptedHermesBinding): String {
        val recoveryKey = hermesRecoveryKey(binding.conversationId, binding.callId, binding.jobId)
        val queueStore = getQueueStore(binding.conversationId)

        val entry = HermesRecoveryEntry(
            recoveryKey = recoveryKey,
            conversationId = binding.conversationId,
            callId = binding.callId,
            jobId = binding.jobId,
            producer = binding.producer,
            originalVoiceSessionHash = voiceSha256(binding.voiceSessionId),
            originalArgumentHash = binding.argumentHash,
            originalOwnerHash = binding.acceptingOwnerHash,
            originalEndpointHash = binding.endpointBindingHash,
            acceptedAt = binding.acceptedAtEpochMillis,
            automaticDeadlineAt = binding.acceptedAtEpochMillis + 24.hours.inWholeMilliseconds,
            recoveryState = HermesRecoveryState.Active,
            dormantReason = null,
            lastAttemptAt = binding.acceptedAtEpochMillis,
            cancelRequestedAt = null,
            notificationDisposition = HermesNotificationDisposition.Undecided,
            notificationDispositionChangedAt = binding.acceptedAtEpochMillis,
        )

        val persistenceResult = queueStore.persistLiveKitAcceptance(
            callId = binding.callId,
            prompt = binding.prompt,
            jobId = binding.jobId,
            originatingUserTurnId = binding.originatingUserTurnId,
            requestHash = binding.requestHash,
            argumentHash = binding.argumentHash,
            producer = binding.producer,
            commit = { result ->
                if (result == HermesQueuePersistenceResult.Conflict) {
                    error("LiveKit acceptance conflict")
                }
                val existing = ledger.find(recoveryKey)
                if (existing == null) {
                    ledger.insert(entry)
                }
            },
        )

        if (persistenceResult == HermesQueuePersistenceResult.Conflict) {
            error("Accepted job conflict for key: $recoveryKey")
        }

        relayRegistry.acquire(recoveryKey, 30.seconds)
        scheduler.ensure(recoveryKey, 30.seconds)
        return recoveryKey
    }

    override fun onPersistedRelayEvent(recoveryKey: String) {
        relayRegistry.renew(recoveryKey, 30.seconds)
    }

    override fun onCallEnded(voiceSessionId: String) {
        coroutineScope.launch {
            val activeEntries = ledger.active()
            for (entry in activeEntries) {
                if (matchesVoiceSession(voiceSessionId, entry.originalVoiceSessionHash)) {
                    relayRegistry.invalidate(entry.recoveryKey)
                    scheduler.preempt(entry.recoveryKey, Duration.ZERO)
                }
            }
        }
    }

    override suspend fun requestCancellation(recoveryKey: String) {
        val entry = ledger.find(recoveryKey) ?: return
        if (entry.recoveryState == HermesRecoveryState.Finished) return
        val now = clock.epochMillis()
        if (entry.cancelRequestedAt == null) {
            ledger.update(entry.copy(cancelRequestedAt = now, lastAttemptAt = now))
        }
        scheduler.preempt(recoveryKey, Duration.ZERO)
    }

    override suspend fun reconcile(recoveryKey: String, trigger: RecoveryTrigger): RecoveryOutcome {
        // 1. Load active entry
        val entry = ledger.find(recoveryKey) ?: return RecoveryOutcome.Success
        if (entry.recoveryState != HermesRecoveryState.Active) {
            return RecoveryOutcome.Success
        }

        val now = clock.epochMillis()

        // 2. Automatic deadline
        if (now >= entry.automaticDeadlineAt) {
            ledger.update(
                entry.copy(
                    recoveryState = HermesRecoveryState.Dormant,
                    dormantReason = HermesDormantReason.WindowElapsed,
                    lastAttemptAt = now,
                ),
            )
            return RecoveryOutcome.Success
        }

        // 3. Advisory lease
        val remainingLease = relayRegistry.remainingLease(recoveryKey)
        if (remainingLease != null && remainingLease > Duration.ZERO) {
            scheduler.continueAfterCurrent(recoveryKey, remainingLease)
            return RecoveryOutcome.Success
        }

        // 4. Load conversation / current endpoint
        val convStore = getConversationStore(entry.conversationId)
        val conversation = convStore.conversation.value
        val resolvedEndpoint = endpointResolver.resolve(conversation)
        if (resolvedEndpoint == null) {
            ledger.update(
                entry.copy(
                    recoveryState = HermesRecoveryState.Dormant,
                    dormantReason = HermesDormantReason.AuthUnavailable,
                    lastAttemptAt = now,
                ),
            )
            return RecoveryOutcome.Success
        }

        // 5. Choose DELETE or GET & make ONE remote request
        val response = try {
            if (entry.cancelRequestedAt != null) {
                resolvedEndpoint.remote.cancel(entry.jobId)
            } else {
                resolvedEndpoint.remote.poll(entry.jobId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RecoveryOutcome.Retry
        }

        // 6. Classify HTTP / transport
        when (response.statusCode) {
            408, 429, in 500..599 -> {
                return RecoveryOutcome.Retry
            }
            401, 403 -> {
                ledger.update(
                    entry.copy(
                        recoveryState = HermesRecoveryState.Dormant,
                        dormantReason = HermesDormantReason.AuthUnavailable,
                        lastAttemptAt = now,
                    ),
                )
                return RecoveryOutcome.Success
            }
            in 400..499 -> {
                ledger.update(
                    entry.copy(
                        recoveryState = HermesRecoveryState.Dormant,
                        dormantReason = HermesDormantReason.ProtocolMismatch,
                        lastAttemptAt = now,
                    ),
                )
                return RecoveryOutcome.Success
            }
            !in 200..299 -> {
                ledger.update(
                    entry.copy(
                        recoveryState = HermesRecoveryState.Dormant,
                        dormantReason = HermesDormantReason.ProtocolMismatch,
                        lastAttemptAt = now,
                    ),
                )
                return RecoveryOutcome.Success
            }
        }

        // 7. Validate snapshot
        val validation = snapshotReconciler.reconcile(
            currentEndpointBindingHash = resolvedEndpoint.endpointBindingHash,
            entry = entry,
            response = response,
        )

        when (validation) {
            is SnapshotReconciliation.Dormant -> {
                ledger.update(
                    entry.copy(
                        recoveryState = HermesRecoveryState.Dormant,
                        dormantReason = validation.reason,
                        lastAttemptAt = now,
                    ),
                )
                return RecoveryOutcome.Success
            }
            is SnapshotReconciliation.Valid -> {
                val snapshot = validation.snapshot
                val queueStore = getQueueStore(entry.conversationId)

                // 8. Atomically apply queue + ledger
                if (snapshot.status.isTerminal) {
                    terminalCommitter.commitTerminal(
                        queueStore = queueStore,
                        entry = entry,
                        snapshot = snapshot,
                    )
                    return RecoveryOutcome.Success
                } else {
                    queueStore.persistValidatedRecoverySnapshot(snapshot)
                    ledger.update(entry.copy(lastAttemptAt = now))

                    // 9. Schedule active continuation with continueAfterCurrent(...)
                    val nextDelay = HermesRecoveryCadence.nextDelay(entry.acceptedAt, now)
                    if (nextDelay != null) {
                        scheduler.continueAfterCurrent(recoveryKey, nextDelay)
                    } else {
                        ledger.update(
                            entry.copy(
                                recoveryState = HermesRecoveryState.Dormant,
                                dormantReason = HermesDormantReason.WindowElapsed,
                                lastAttemptAt = now,
                            ),
                        )
                    }
                    return RecoveryOutcome.Success
                }
            }
        }
    }

    override suspend fun reactivateConversation(conversationId: Uuid, trigger: RecoveryTrigger) {
        val entries = ledger.forConversation(conversationId)
        val now = clock.epochMillis()
        for (entry in entries) {
            if (entry.recoveryState != HermesRecoveryState.Dormant) continue
            val shouldReactivate = when (entry.dormantReason) {
                HermesDormantReason.AuthUnavailable,
                HermesDormantReason.WindowElapsed -> when (trigger) {
                    RecoveryTrigger.StartupRepair,
                    RecoveryTrigger.ConversationOpened,
                    RecoveryTrigger.ConfigurationChanged,
                    RecoveryTrigger.ExplicitRetry -> true
                    else -> false
                }
                HermesDormantReason.ProtocolMismatch -> when (trigger) {
                    RecoveryTrigger.ConfigurationChanged,
                    RecoveryTrigger.ExplicitRetry -> true
                    else -> false
                }
                HermesDormantReason.LegacyIncomplete,
                null -> false
            }

            if (shouldReactivate) {
                ledger.update(
                    entry.copy(
                        recoveryState = HermesRecoveryState.Active,
                        dormantReason = null,
                        automaticDeadlineAt = now + 24.hours.inWholeMilliseconds,
                        lastAttemptAt = now,
                    ),
                )
                scheduler.preempt(entry.recoveryKey, Duration.ZERO)
            }
        }
    }

    override suspend fun reactivateDormant(trigger: RecoveryTrigger) {
        val dormantEntries = ledger.dormant()
        val distinctConversationIds = dormantEntries.map { it.conversationId }.distinct()
        for (convId in distinctConversationIds) {
            reactivateConversation(convId, trigger)
        }
    }

    override suspend fun repairConversation(conversationId: Uuid) {
        val convStore = getConversationStore(conversationId)
        val records = convStore.conversation.value.hermesQueueRecords()
            .latestByHermesDurableIdentity()
            .filter { !it.status.isTerminal }
        val now = clock.epochMillis()
        for (record in records) {
            val jobId = record.jobId ?: continue
            val key = hermesRecoveryKey(conversationId, record.callId, jobId)
            if (ledger.find(key) == null) {
                val isReconstructable = record.producer == HERMES_PRODUCER &&
                    !record.originatingUserTurnId.isNullOrBlank() &&
                    !record.requestHash.isNullOrBlank() &&
                    !record.argumentHash.isNullOrBlank() &&
                    !record.voiceSessionId.isNullOrBlank() &&
                    !record.acceptingOwnerHash.isNullOrBlank() &&
                    !record.endpointBindingHash.isNullOrBlank()

                if (isReconstructable) {
                    val entry = HermesRecoveryEntry(
                        recoveryKey = key,
                        conversationId = conversationId,
                        callId = record.callId,
                        jobId = jobId,
                        producer = record.producer,
                        originalVoiceSessionHash = voiceSha256(record.voiceSessionId),
                        originalArgumentHash = record.argumentHash,
                        originalOwnerHash = record.acceptingOwnerHash,
                        originalEndpointHash = record.endpointBindingHash,
                        acceptedAt = now,
                        automaticDeadlineAt = now + 24.hours.inWholeMilliseconds,
                        recoveryState = HermesRecoveryState.Active,
                        dormantReason = null,
                        lastAttemptAt = now,
                    )
                    ledger.insert(entry)
                    scheduler.ensure(key, Duration.ZERO)
                } else {
                    val entry = HermesRecoveryEntry(
                        recoveryKey = key,
                        conversationId = conversationId,
                        callId = record.callId,
                        jobId = jobId,
                        producer = record.producer ?: HERMES_PRODUCER,
                        originalVoiceSessionHash = null,
                        originalArgumentHash = record.argumentHash,
                        originalOwnerHash = record.acceptingOwnerHash,
                        originalEndpointHash = record.endpointBindingHash,
                        acceptedAt = now,
                        automaticDeadlineAt = now,
                        recoveryState = HermesRecoveryState.Dormant,
                        dormantReason = HermesDormantReason.LegacyIncomplete,
                        lastAttemptAt = now,
                    )
                    ledger.insert(entry)
                }
            }
        }
    }

    override suspend fun repairAll() {
        ledger.deleteOrphans()

        val now = clock.epochMillis()
        val activeEntries = ledger.active()
        for (entry in activeEntries) {
            val delay = HermesRecoveryCadence.nextDelay(entry.acceptedAt, now) ?: Duration.ZERO
            scheduler.ensure(entry.recoveryKey, delay)
        }

        val conversationIds = conversationIdsProvider?.invoke() ?: emptyList()
        for (convId in conversationIds) {
            repairConversation(convId)
            reactivateConversation(convId, RecoveryTrigger.StartupRepair)
        }
    }

    private fun matchesVoiceSession(session: String, originalVoiceSessionHash: String?): Boolean {
        if (originalVoiceSessionHash == null) return false
        return session == originalVoiceSessionHash ||
            voiceSha256(session) == originalVoiceSessionHash ||
            recoverySha256(session) == originalVoiceSessionHash
    }
}
