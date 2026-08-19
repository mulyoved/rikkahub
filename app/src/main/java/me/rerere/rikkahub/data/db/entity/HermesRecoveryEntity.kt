package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hermes_recovery",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("conversation_id"),
        Index("recovery_state"),
        Index(value = ["notification_disposition", "notification_next_attempt_at"])
    ]
)
data class HermesRecoveryEntity(
    @PrimaryKey
    @ColumnInfo("recovery_key")
    val recoveryKey: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("call_id")
    val callId: String,
    @ColumnInfo("job_id")
    val jobId: String,
    @ColumnInfo("producer")
    val producer: String,
    @ColumnInfo("original_voice_session_hash")
    val originalVoiceSessionHash: String? = null,
    @ColumnInfo("original_argument_hash")
    val originalArgumentHash: String? = null,
    @ColumnInfo("original_owner_hash")
    val originalOwnerHash: String? = null,
    @ColumnInfo("original_endpoint_hash")
    val originalEndpointHash: String? = null,
    @ColumnInfo("accepted_at")
    val acceptedAt: Long,
    @ColumnInfo("automatic_deadline_at")
    val automaticDeadlineAt: Long,
    @ColumnInfo("recovery_state")
    val recoveryState: String,
    @ColumnInfo("dormant_reason")
    val dormantReason: String? = null,
    @ColumnInfo("last_attempt_at")
    val lastAttemptAt: Long,
    @ColumnInfo("cancel_requested_at")
    val cancelRequestedAt: Long? = null,
    @ColumnInfo("notification_disposition")
    val notificationDisposition: String,
    @ColumnInfo("notification_disposition_changed_at")
    val notificationDispositionChangedAt: Long,
    @ColumnInfo("terminal_committed_at")
    val terminalCommittedAt: Long? = null,
    @ColumnInfo("terminal_deadline_at")
    val terminalDeadlineAt: Long? = null,
    @ColumnInfo("notification_next_attempt_at")
    val notificationNextAttemptAt: Long? = null,
    @ColumnInfo("notification_attempt_count")
    val notificationAttemptCount: Int = 0,
)
