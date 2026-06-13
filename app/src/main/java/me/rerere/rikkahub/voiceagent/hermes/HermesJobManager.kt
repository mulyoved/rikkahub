package me.rerere.rikkahub.voiceagent.hermes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.voiceagent.VoiceConversationStore
import me.rerere.rikkahub.voiceagent.VoiceToolApi
import me.rerere.rikkahub.voiceagent.VoiceToolStatus
import me.rerere.rikkahub.voiceagent.persistence.VoiceConversationPersister
import me.rerere.rikkahub.voiceagent.persistence.VoiceToolRecordStatus

interface HermesSessionBridge {
    fun sendQueuedAcknowledgement(callId: String, sessionId: Long): Boolean
    fun sendCompletionFollowUp(callId: String, prompt: String, answer: String, sessionId: Long): Boolean
}

class HermesJobManager(
    private val toolApi: VoiceToolApi,
    private val conversationStore: VoiceConversationStore,
    private val persister: VoiceConversationPersister,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val pollRetryDelayMs: Long = DEFAULT_POLL_RETRY_DELAY_MS,
    private val maxElapsedMs: Long = DEFAULT_MAX_ELAPSED_MS,
    private val updateToolStatus: (VoiceToolStatus) -> Unit = {},
    private val recordDiagnostic: (String, String) -> Unit = { _, _ -> },
    private val writeQueueEvent: (String) -> Unit = {},
    private val writeHermesAnswer: (String) -> Unit = {},
) {
    private val lock = Any()
    private val activeJobs = mutableMapOf<String, ManagedHermesJob>()
    private val toolCalls = mutableMapOf<String, VoiceToolStatus>()
    private val _toolStatus = MutableStateFlow<VoiceToolStatus>(VoiceToolStatus.Idle)
    val toolStatus: StateFlow<VoiceToolStatus> = _toolStatus
    private var bridgeAttachment: BridgeAttachment? = null

    fun submit(callId: String, prompt: String) {
        val managedJob = synchronized(lock) {
            if (activeJobs.containsKey(callId)) return
            ManagedHermesJob(callId = callId, prompt = prompt).also {
                activeJobs[callId] = it
            }
        }
        launchManagedJob(managedJob) {
            submitAndPoll(managedJob)
        }
    }

    fun resumeActiveJobs() {
        conversationStore.conversation.value.hermesQueueRecords()
            .filter { !it.status.isTerminal && it.jobId != null }
            .forEach { record ->
                val managedJob = synchronized(lock) {
                    if (activeJobs.containsKey(record.callId)) return@forEach
                    ManagedHermesJob(
                        callId = record.callId,
                        prompt = record.prompt,
                        jobId = record.jobId,
                    ).also {
                        activeJobs[record.callId] = it
                    }
                }
                launchManagedJob(managedJob) {
                    pollHermesJobSafely(managedJob, requireNotNull(record.jobId))
                }
            }
    }

    fun attachBridge(bridge: HermesSessionBridge, sessionId: Long) {
        synchronized(lock) {
            bridgeAttachment = BridgeAttachment(bridge = bridge, sessionId = sessionId)
        }
        scope.launch(dispatcher) {
            announceUnannouncedCompletedResults(bridge = bridge, sessionId = sessionId)
        }
    }

    fun attachBridge(bridge: HermesSessionBridge) {
        attachBridge(bridge = bridge, sessionId = 0L)
    }

    fun detachBridge(bridge: HermesSessionBridge) {
        synchronized(lock) {
            if (bridgeAttachment?.bridge === bridge) {
                bridgeAttachment = null
            }
        }
    }

    fun cancel(callId: String) {
        val managedJob = synchronized(lock) {
            activeJobs[callId]
        }
        val prompt = managedJob?.prompt ?: conversationStore.conversation.value.hermesQueueRecords()
            .lastOrNull { it.callId == callId }
            ?.prompt
            .orEmpty()
        val jobId = managedJob?.jobId ?: conversationStore.conversation.value.hermesQueueRecords()
            .lastOrNull { it.callId == callId }
            ?.jobId
        managedJob?.explicitlyCanceled = true
        managedJob?.job?.cancel()
        updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = CANCELED_MESSAGE))
        scope.launch(dispatcher) {
            jobId?.let { cancelRemoteJob(it) }
            persistToolStatus(
                callId = callId,
                prompt = prompt,
                status = VoiceToolRecordStatus.Canceled(CANCELED_MESSAGE),
                jobId = jobId,
            )
        }
    }

    private fun launchManagedJob(
        managedJob: ManagedHermesJob,
        block: suspend () -> Unit,
    ) {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            block()
        }
        managedJob.job = job
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJobs[managedJob.callId] === managedJob) {
                    activeJobs.remove(managedJob.callId)
                }
            }
        }
        job.start()
    }

    private suspend fun submitAndPoll(managedJob: ManagedHermesJob) {
        try {
            val submitted = toolApi.submitHermesJob(callId = managedJob.callId, prompt = managedJob.prompt)
            managedJob.jobId = submitted.jobId
            recordDiagnostic(
                "hermes_job_created",
                "callId=${managedJob.callId}, jobId=${submitted.jobId}, status=${submitted.status}",
            )
            writeQueueEvent("event=job_created,callId=${managedJob.callId},jobId=${submitted.jobId},status=${submitted.status}")
            if (managedJob.explicitlyCanceled) {
                cancelRemoteJob(submitted.jobId)
                return
            }
            val shouldPoll = persistSubmittedStatus(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = submitted.jobId,
                status = submitted.status,
                error = null,
            )
            if (!shouldPoll) return
            sendQueuedAcknowledgementIfAttached(callId = managedJob.callId)
            pollHermesJob(managedJob = managedJob, jobId = submitted.jobId)
        } catch (error: CancellationException) {
            if (!managedJob.explicitlyCanceled) throw error
        } catch (error: Throwable) {
            persistFailure(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = managedJob.jobId,
                error = error,
            )
        }
    }

    private suspend fun pollHermesJob(managedJob: ManagedHermesJob, jobId: String) {
        var pollFailures = 0
        while (true) {
            if (managedJob.hasTimedOut(maxElapsedMs)) {
                expireTimedOutJob(managedJob = managedJob, jobId = jobId)
                return
            }

            val poll = try {
                toolApi.getHermesJob(jobId = jobId)
            } catch (error: CancellationException) {
                if (!managedJob.explicitlyCanceled) throw error
                return
            } catch (error: Throwable) {
                pollFailures += 1
                if (managedJob.hasTimedOut(maxElapsedMs)) {
                    expireTimedOutJob(managedJob = managedJob, jobId = jobId)
                    return
                }
                delay(nextPollRetryDelayMs(pollFailures))
                continue
            }
            pollFailures = 0

            when (poll.status.lowercase()) {
                "queued" -> {
                    persistToolStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Queued,
                        jobId = jobId,
                    )
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.QueuedHermes(callId = managedJob.callId, jobId = jobId),
                    )
                }

                "running" -> {
                    persistToolStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Running,
                        jobId = jobId,
                    )
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.CallingHermes(
                            callId = managedJob.callId,
                            elapsedMs = managedJob.elapsedMs(),
                        ),
                    )
                }

                "succeeded" -> {
                    val answer = requireNotNull(poll.answer) { "Hermes job succeeded without an answer" }
                    recordDiagnostic(
                        "hermes_job_completed",
                        "callId=${managedJob.callId}, jobId=$jobId, answerChars=${answer.length}",
                    )
                    writeQueueEvent("event=job_completed,callId=${managedJob.callId},jobId=$jobId,status=succeeded")
                    writeHermesAnswer(answer)
                    persistToolStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        status = VoiceToolRecordStatus.Complete(answer),
                        jobId = jobId,
                        resultAnnounced = false,
                    )
                    updateToolStatus(
                        managedJob.callId,
                        VoiceToolStatus.HermesAnswered(
                            callId = managedJob.callId,
                            elapsedMs = managedJob.elapsedMs(),
                        ),
                    )
                    announceCompletedResult(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        answer = answer,
                        jobId = jobId,
                    )
                    return
                }

                "failed" -> {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Failed(poll.error ?: DEFAULT_FAILURE_MESSAGE),
                        visibleMessage = poll.error ?: DEFAULT_FAILURE_MESSAGE,
                    )
                    return
                }

                "expired" -> {
                    completeFailureStatus(
                        callId = managedJob.callId,
                        prompt = managedJob.prompt,
                        jobId = jobId,
                        status = VoiceToolRecordStatus.Expired(poll.error ?: EXPIRED_MESSAGE),
                        visibleMessage = poll.error ?: EXPIRED_MESSAGE,
                    )
                    return
                }

                else -> throw IllegalStateException("Unknown Hermes job status: ${poll.status}")
            }

            delay(pollIntervalMs)
        }
    }

    private suspend fun persistSubmittedStatus(
        callId: String,
        prompt: String,
        jobId: String,
        status: String,
        error: String?,
    ): Boolean {
        return when (status.lowercase()) {
            "running" -> {
                persistToolStatus(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Running,
                    jobId = jobId,
                )
                updateToolStatus(callId, VoiceToolStatus.CallingHermes(callId = callId))
                true
            }

            "failed" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Failed(error ?: DEFAULT_FAILURE_MESSAGE),
                    visibleMessage = error ?: DEFAULT_FAILURE_MESSAGE,
                )
                false
            }

            "expired" -> {
                completeFailureStatus(
                    callId = callId,
                    prompt = prompt,
                    jobId = jobId,
                    status = VoiceToolRecordStatus.Expired(error ?: EXPIRED_MESSAGE),
                    visibleMessage = error ?: EXPIRED_MESSAGE,
                )
                false
            }

            else -> {
                persistToolStatus(
                    callId = callId,
                    prompt = prompt,
                    status = VoiceToolRecordStatus.Queued,
                    jobId = jobId,
                )
                updateToolStatus(callId, VoiceToolStatus.QueuedHermes(callId = callId, jobId = jobId))
                true
            }
        }
    }

    private suspend fun pollHermesJobSafely(managedJob: ManagedHermesJob, jobId: String) {
        try {
            pollHermesJob(managedJob = managedJob, jobId = jobId)
        } catch (error: CancellationException) {
            if (!managedJob.explicitlyCanceled) throw error
        } catch (error: Throwable) {
            persistFailure(
                callId = managedJob.callId,
                prompt = managedJob.prompt,
                jobId = jobId,
                error = error,
            )
        }
    }

    private suspend fun completeFailureStatus(
        callId: String,
        prompt: String,
        jobId: String?,
        status: VoiceToolRecordStatus,
        visibleMessage: String,
    ) {
        recordDiagnostic(
            "hermes_job_failed",
            "callId=$callId${jobId?.let { ", jobId=$it" }.orEmpty()}, message=$visibleMessage",
        )
        writeQueueEvent(
            "event=job_failed,callId=$callId${jobId?.let { ",jobId=$it" }.orEmpty()},status=${status.queueEventStatus()}",
        )
        persistToolStatus(
            callId = callId,
            prompt = prompt,
            status = status,
            jobId = jobId,
        )
        updateToolStatus(callId, VoiceToolStatus.HermesFailed(callId = callId, message = visibleMessage))
    }

    private suspend fun expireTimedOutJob(managedJob: ManagedHermesJob, jobId: String) {
        cancelRemoteJob(jobId)
        completeFailureStatus(
            callId = managedJob.callId,
            prompt = managedJob.prompt,
            jobId = jobId,
            status = VoiceToolRecordStatus.Expired(TIMEOUT_MESSAGE),
            visibleMessage = TIMEOUT_MESSAGE,
        )
    }

    private suspend fun persistFailure(
        callId: String,
        prompt: String,
        jobId: String?,
        error: Throwable,
    ) {
        val message = error.message ?: error.javaClass.simpleName
        completeFailureStatus(
            callId = callId,
            prompt = prompt,
            jobId = jobId,
            status = VoiceToolRecordStatus.Failed(message),
            visibleMessage = message,
        )
    }

    private fun sendQueuedAcknowledgementIfAttached(callId: String) {
        val attachment = currentBridgeAttachment() ?: return
        runCatching {
            attachment.bridge.sendQueuedAcknowledgement(callId = callId, sessionId = attachment.sessionId)
        }
    }

    private suspend fun announceUnannouncedCompletedResults(bridge: HermesSessionBridge, sessionId: Long) {
        conversationStore.conversation.value.hermesQueueRecords()
            .filter { it.status == HermesQueueStatus.Complete && !it.resultAnnounced && it.answer != null }
            .forEach { record ->
                val current = currentBridgeAttachment() ?: return@forEach
                if (current.bridge !== bridge || current.sessionId != sessionId) return@forEach
                announceCompletedResult(
                    callId = record.callId,
                    prompt = record.prompt,
                    answer = requireNotNull(record.answer),
                    jobId = record.jobId,
                    attachment = current,
                )
            }
    }

    private suspend fun announceCompletedResult(
        callId: String,
        prompt: String,
        answer: String,
        jobId: String?,
        attachment: BridgeAttachment? = currentBridgeAttachment(),
    ) {
        val current = attachment ?: return
        val sent = runCatching {
            current.bridge.sendCompletionFollowUp(
                callId = callId,
                prompt = prompt,
                answer = answer,
                sessionId = current.sessionId,
            )
        }.getOrDefault(false)
        val stillCurrent = currentBridgeAttachment()?.let {
            it.bridge === current.bridge && it.sessionId == current.sessionId
        } ?: false
        if (sent && stillCurrent) {
            conversationStore.update { conversation ->
                persister.markHermesToolResultAnnounced(
                    conversation = conversation,
                    callId = callId,
                    jobId = jobId,
                    matchMissingJobId = jobId == null,
                )
            }
        }
    }

    private suspend fun persistToolStatus(
        callId: String,
        prompt: String,
        status: VoiceToolRecordStatus,
        jobId: String?,
        resultAnnounced: Boolean? = null,
    ) {
        val sessionId = currentBridgeAttachment()?.sessionId?.toString()
        conversationStore.update { conversation ->
            persister.upsertHermesTool(
                conversation = conversation,
                callId = callId,
                prompt = prompt,
                status = status,
                sessionId = sessionId,
                jobId = jobId,
                resultAnnounced = resultAnnounced,
            )
        }
    }

    private suspend fun cancelRemoteJob(jobId: String) {
        runCatching {
            toolApi.cancelHermesJob(jobId = jobId)
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    private fun updateToolStatus(callId: String, status: VoiceToolStatus) {
        val visibleStatus = synchronized(lock) {
            toolCalls[callId] = status
            summarizeToolStatus(toolCalls = toolCalls, fallback = status)
        }
        _toolStatus.value = visibleStatus
        updateToolStatus(status)
    }

    private fun VoiceToolRecordStatus.queueEventStatus(): String = when (this) {
        VoiceToolRecordStatus.Pending -> HermesQueueStatus.Pending.wireName
        VoiceToolRecordStatus.Queued -> HermesQueueStatus.Queued.wireName
        VoiceToolRecordStatus.Running -> HermesQueueStatus.Running.wireName
        is VoiceToolRecordStatus.Complete -> HermesQueueStatus.Complete.wireName
        is VoiceToolRecordStatus.Failed -> HermesQueueStatus.Failed.wireName
        is VoiceToolRecordStatus.Expired -> HermesQueueStatus.Expired.wireName
        is VoiceToolRecordStatus.Canceled -> HermesQueueStatus.Canceled.wireName
    }

    private fun summarizeToolStatus(
        toolCalls: Map<String, VoiceToolStatus>,
        fallback: VoiceToolStatus,
    ): VoiceToolStatus {
        return when (fallback) {
            is VoiceToolStatus.CallingHermes -> fallback
            is VoiceToolStatus.QueuedHermes -> fallback
            else -> toolCalls.values.filterIsInstance<VoiceToolStatus.CallingHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.QueuedHermes>().firstOrNull()
                ?: toolCalls.values.filterIsInstance<VoiceToolStatus.HermesFailed>().firstOrNull()
                ?: fallback
        }
    }

    private fun currentBridgeAttachment(): BridgeAttachment? = synchronized(lock) {
        bridgeAttachment
    }

    private fun nextPollRetryDelayMs(failures: Int): Long {
        val multiplier = when {
            failures <= 1 -> 1L
            failures >= 7 -> 64L
            else -> 1L shl (failures - 1)
        }
        return (pollRetryDelayMs * multiplier)
            .coerceAtMost(pollIntervalMs)
            .coerceAtLeast(1L)
    }

    private class ManagedHermesJob(
        val callId: String,
        val prompt: String,
        var jobId: String? = null,
    ) {
        private val startedAtMs = System.currentTimeMillis()
        var job: Job? = null
        @Volatile
        var explicitlyCanceled: Boolean = false

        fun elapsedMs(): Long = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        fun hasTimedOut(maxElapsedMs: Long): Boolean = elapsedMs() >= maxElapsedMs
    }

    private data class BridgeAttachment(
        val bridge: HermesSessionBridge,
        val sessionId: Long,
    )

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_POLL_RETRY_DELAY_MS = 1_000L
        const val DEFAULT_MAX_ELAPSED_MS = 120_000L
        const val DEFAULT_FAILURE_MESSAGE = "Hermes job was no longer available."
        const val EXPIRED_MESSAGE = "Hermes job was no longer available."
        const val TIMEOUT_MESSAGE = "Hermes job polling timed out."
        const val CANCELED_MESSAGE = "Hermes job canceled."
    }
}
