package me.rerere.rikkahub.voiceagent.livekit

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.hermes.HERMES_PRODUCER
import me.rerere.rikkahub.voiceagent.hermes.HermesQueuePersistenceResult
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStore
import me.rerere.rikkahub.voiceagent.hermes.HermesQueueStatus
import me.rerere.rikkahub.voiceagent.hermes.VoiceToolRecordStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceTranscriptPersister

internal interface LiveKitHistoryOwner {
    suspend fun drain()
    fun close()
}

internal class LiveKitVoiceHistoryBridge(
    private val voiceSessionId: String,
    private val agentIdentity: String,
    private val expectedCorrelation: LiveKitJobCorrelation,
    private val queueStore: HermesQueueStore,
    private val transcriptPersister: VoiceTranscriptPersister,
    private val conversationStore: VoiceConversationStore,
) : LiveKitHistoryOwner {
    private val mutex = Mutex()
    private val persistedEventFingerprints = mutableMapOf<String, String>()
    private val closed = AtomicBoolean(false)

    init {
        require(expectedCorrelation.isValid(voiceSessionId)) {
            "Expected LiveKit session correlation is invalid"
        }
    }

    suspend fun handle(callerIdentity: String, payload: String): String = mutex.withLock {
        require(!closed.get()) { "LiveKit history bridge is closed" }
        require(callerIdentity == agentIdentity) { "Unexpected LiveKit RPC caller" }
        val event = requireNotNull(
            parseLiveKitVoiceExperienceEvent(
                payload = payload,
                expectedVoiceSessionId = voiceSessionId,
                expectedCorrelation = expectedCorrelation,
            ),
        ) {
            "Invalid LiveKit history event"
        }
        val fingerprint = event.semanticFingerprint()
        val persistedFingerprint = persistedEventFingerprints[event.eventId]
        if (persistedFingerprint != null) {
            require(persistedFingerprint == fingerprint) {
                "LiveKit history event ID collision"
            }
        } else {
            persist(event)
            persistedEventFingerprints[event.eventId] = fingerprint
        }
        ""
    }

    override suspend fun drain() {
        mutex.withLock { }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            conversationStore.close()
        }
    }

    private suspend fun persist(event: LiveKitVoiceExperienceEvent) {
        when (event) {
            is LiveKitVoiceExperienceEvent.JobAccepted -> {
                queueStore.persistLiveKitAcceptance(
                    callId = event.toolCallId,
                    prompt = event.prompt,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                ).requireNonConflicting("LiveKit Hermes acceptance conflicts with persisted record")
            }

            is LiveKitVoiceExperienceEvent.JobState -> persistJobState(event)
            is LiveKitVoiceExperienceEvent.Transcript -> persistTranscript(event)
            is LiveKitVoiceExperienceEvent.Delivery ->
                queueStore.markLiveKitResultAnnounced(
                    callId = event.toolCallId,
                    jobId = event.jobId,
                    assistantTurnId = event.assistantTurnId,
                    voiceSessionId = voiceSessionId,
                ).requireNonConflicting(
                    "LiveKit delivery announcement has no matching grounded assistant turn"
                )
        }
    }

    private suspend fun persistJobState(event: LiveKitVoiceExperienceEvent.JobState) {
        when (event.kind) {
            "job_running" -> {
                val prompt = requireMatchingActivePrompt(event)
                queueStore.persistActive(
                    callId = event.toolCallId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                )
            }

            "still_working" -> {
                val prompt = requireMatchingActivePrompt(event)
                queueStore.persistActive(
                    callId = event.toolCallId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = event.jobId,
                    originatingUserTurnId = event.userTurnId,
                    requestHash = event.requestHash,
                    argumentHash = event.argumentHash,
                    producer = HERMES_PRODUCER,
                )
                queueStore.markStillWorkingAnnounced(
                    callId = event.toolCallId,
                    jobId = event.jobId,
                )
            }

            "job_succeeded" -> persistTerminalState(
                event = event,
                status = VoiceToolRecordStatus.Complete(requireNotNull(event.answer)),
            )

            "job_failed" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Failed(requireNotNull(event.failureReason)),
            )

            "job_expired" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Expired(requireNotNull(event.failureReason)),
            )

            "job_canceled" -> persistFailedState(
                event = event,
                status = VoiceToolRecordStatus.Canceled(requireNotNull(event.failureReason)),
            )
        }
    }

    private fun requireMatchingActivePrompt(event: LiveKitVoiceExperienceEvent.JobState): String {
        val existingRecord = queueStore.latestRecord(event.toolCallId, event.jobId)
        if (existingRecord != null) {
            require(existingRecord.originatingUserTurnId == event.userTurnId) {
                "LiveKit Hermes user turn correlation changed"
            }
            require(existingRecord.requestHash == event.requestHash) {
                "LiveKit Hermes request correlation changed"
            }
            require(existingRecord.argumentHash == event.argumentHash) {
                "LiveKit Hermes argument correlation changed"
            }
        }
        return existingRecord?.prompt.orEmpty()
    }

    private suspend fun persistFailedState(
        event: LiveKitVoiceExperienceEvent.JobState,
        status: VoiceToolRecordStatus,
    ) {
        persistTerminalState(event = event, status = status)
    }

    private suspend fun persistTerminalState(
        event: LiveKitVoiceExperienceEvent.JobState,
        status: VoiceToolRecordStatus,
    ) {
        val result = queueStore.persistLiveKitTerminal(
            callId = event.toolCallId,
            status = status,
            jobId = event.jobId,
            originatingUserTurnId = event.userTurnId,
            requestHash = event.requestHash,
            argumentHash = event.argumentHash,
            resultHash = event.resultHash,
            producer = HERMES_PRODUCER,
        )
        result.requireNonConflicting("LiveKit Hermes terminal state conflicts with persisted record")
    }

    private suspend fun persistTranscript(event: LiveKitVoiceExperienceEvent.Transcript) {
        if (event.groundedJobId != null) {
            require(
                queueStore.records().any { record ->
                    record.jobId == event.groundedJobId &&
                        record.status == HermesQueueStatus.Complete &&
                        record.resultHash == event.groundedResultHash
                }
            ) { "LiveKit grounded Hermes result does not match" }
        }
        conversationStore.update { conversation ->
            when (event.role) {
                "user" -> transcriptPersister.upsertUserTranscriptTurn(
                    conversation = conversation,
                    text = event.text,
                    turnId = event.turnId,
                    sessionId = voiceSessionId,
                )

                "assistant" -> transcriptPersister.upsertAssistantTranscriptTurn(
                    conversation = conversation,
                    text = event.text,
                    interrupted = event.interrupted,
                    turnId = event.turnId,
                    sessionId = voiceSessionId,
                    groundedJobId = event.groundedJobId,
                    groundedResultHash = event.groundedResultHash,
                )

                else -> conversation
            }
        }
    }

    private fun HermesQueuePersistenceResult.requireNonConflicting(message: String) {
        require(this != HermesQueuePersistenceResult.Conflict) { message }
    }
}
