package me.rerere.rikkahub.voiceagent.recovery

import me.rerere.rikkahub.data.db.dao.HermesRecoveryDAO
import me.rerere.rikkahub.data.db.entity.HermesRecoveryEntity
import kotlin.uuid.Uuid

internal enum class HermesRecoveryState { Active, Dormant, Finished }
internal enum class HermesDormantReason { AuthUnavailable, ProtocolMismatch, WindowElapsed, LegacyIncomplete }
internal enum class HermesNotificationDisposition {
    Undecided,
    PendingPost,
    Posted,
    Seen,
    SuppressedNotEnabled,
    SuppressedForeground,
    SuppressedInCall,
    SuppressedPermission,
    SuppressedPostFailure,
}

internal data class HermesRecoveryEntry(
    val recoveryKey: String,
    val conversationId: Uuid,
    val callId: String,
    val jobId: String,
    val producer: String,
    val originalVoiceSessionHash: String? = null,
    val originalArgumentHash: String? = null,
    val originalOwnerHash: String? = null,
    val originalEndpointHash: String? = null,
    val acceptedAt: Long,
    val automaticDeadlineAt: Long,
    val recoveryState: HermesRecoveryState,
    val dormantReason: HermesDormantReason? = null,
    val lastAttemptAt: Long,
    val cancelRequestedAt: Long? = null,
    val notificationDisposition: HermesNotificationDisposition = HermesNotificationDisposition.Undecided,
    val notificationDispositionChangedAt: Long = acceptedAt,
    val terminalCommittedAt: Long? = null,
    val terminalDeadlineAt: Long? = null,
    val notificationNextAttemptAt: Long? = null,
    val notificationAttemptCount: Int = 0,
)

internal class HermesRecoveryLedger(
    private val dao: HermesRecoveryDAO,
) {
    suspend fun find(recoveryKey: String): HermesRecoveryEntry? {
        return dao.find(recoveryKey)?.toEntry()
    }

    suspend fun active(): List<HermesRecoveryEntry> {
        return dao.active().map { it.toEntry() }
    }

    suspend fun dormant(): List<HermesRecoveryEntry> {
        return dao.dormant().map { it.toEntry() }
    }

    suspend fun forConversation(conversationId: Uuid): List<HermesRecoveryEntry> {
        return dao.forConversation(conversationId.toString()).map { it.toEntry() }
    }

    suspend fun pendingNotifications(conversationId: Uuid): List<HermesRecoveryEntry> {
        return dao.pendingForConversation(conversationId.toString()).map { it.toEntry() }
    }

    suspend fun allPendingNotifications(): List<HermesRecoveryEntry> {
        return dao.allPendingNotifications().map { it.toEntry() }
    }

    suspend fun pendingNotificationConversationIds(): List<Uuid> {
        return dao.pendingNotificationConversationIds().map { Uuid.parse(it) }
    }

    suspend fun insert(entry: HermesRecoveryEntry): Boolean {
        validate(entry)
        val rowId = dao.insert(entry.toEntity())
        return rowId != -1L
    }

    suspend fun update(entry: HermesRecoveryEntry) {
        validate(entry)
        dao.update(entry.toEntity())
    }

    suspend fun deleteOrphans(): Int {
        return dao.deleteOrphans()
    }

    suspend fun markPostedSeen(conversationId: Uuid, seenAt: Long): Int {
        return dao.markPostedSeen(conversationId.toString(), seenAt)
    }

    private fun validate(entry: HermesRecoveryEntry) {
        if (entry.recoveryState == HermesRecoveryState.Active) {
            requireNotNull(entry.originalVoiceSessionHash) { "originalVoiceSessionHash required for Active state" }
            requireNotNull(entry.originalArgumentHash) { "originalArgumentHash required for Active state" }
            requireNotNull(entry.originalOwnerHash) { "originalOwnerHash required for Active state" }
            requireNotNull(entry.originalEndpointHash) { "originalEndpointHash required for Active state" }
        }
    }
}

internal fun HermesRecoveryEntity.toEntry(): HermesRecoveryEntry = HermesRecoveryEntry(
    recoveryKey = recoveryKey,
    conversationId = Uuid.parse(conversationId),
    callId = callId,
    jobId = jobId,
    producer = producer,
    originalVoiceSessionHash = originalVoiceSessionHash,
    originalArgumentHash = originalArgumentHash,
    originalOwnerHash = originalOwnerHash,
    originalEndpointHash = originalEndpointHash,
    acceptedAt = acceptedAt,
    automaticDeadlineAt = automaticDeadlineAt,
    recoveryState = HermesRecoveryState.valueOf(recoveryState),
    dormantReason = dormantReason?.let { HermesDormantReason.valueOf(it) },
    lastAttemptAt = lastAttemptAt,
    cancelRequestedAt = cancelRequestedAt,
    notificationDisposition = HermesNotificationDisposition.valueOf(notificationDisposition),
    notificationDispositionChangedAt = notificationDispositionChangedAt,
    terminalCommittedAt = terminalCommittedAt,
    terminalDeadlineAt = terminalDeadlineAt,
    notificationNextAttemptAt = notificationNextAttemptAt,
    notificationAttemptCount = notificationAttemptCount,
)

internal fun HermesRecoveryEntry.toEntity(): HermesRecoveryEntity = HermesRecoveryEntity(
    recoveryKey = recoveryKey,
    conversationId = conversationId.toString(),
    callId = callId,
    jobId = jobId,
    producer = producer,
    originalVoiceSessionHash = originalVoiceSessionHash,
    originalArgumentHash = originalArgumentHash,
    originalOwnerHash = originalOwnerHash,
    originalEndpointHash = originalEndpointHash,
    acceptedAt = acceptedAt,
    automaticDeadlineAt = automaticDeadlineAt,
    recoveryState = recoveryState.name,
    dormantReason = dormantReason?.name,
    lastAttemptAt = lastAttemptAt,
    cancelRequestedAt = cancelRequestedAt,
    notificationDisposition = notificationDisposition.name,
    notificationDispositionChangedAt = notificationDispositionChangedAt,
    terminalCommittedAt = terminalCommittedAt,
    terminalDeadlineAt = terminalDeadlineAt,
    notificationNextAttemptAt = notificationNextAttemptAt,
    notificationAttemptCount = notificationAttemptCount,
)
