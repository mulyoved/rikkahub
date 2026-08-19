package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.PersistedConversationFolder
import me.rerere.rikkahub.data.repository.withPersistedLocation
import kotlin.uuid.Uuid

internal interface ConversationPersistenceGateway {
    suspend fun <T> serialize(
        conversationId: Uuid,
        persistPrimary: suspend (PersistedConversationFolder) -> T,
        onPrimaryCommitted: suspend (T) -> Unit,
        postPrimary: suspend (T) -> Unit,
    ): T

    suspend fun insertPrimary(
        conversation: Conversation,
        primaryTransaction: suspend () -> Unit = {},
    )

    suspend fun updatePrimary(
        conversation: Conversation,
        primaryTransaction: suspend () -> Unit = {},
    )

    fun synchronizeSession(conversationId: Uuid, conversation: Conversation)

    suspend fun index(conversation: Conversation)
}

/**
 * Owns the production ordering for a full conversation save. The folder repository
 * supplies serialization and the persisted location snapshot; this collaborator owns
 * insert/update selection, location normalization, live-session synchronization, and
 * post-commit FTS indexing in that order.
 */
internal class ConversationPersistenceOrchestrator(
    private val gateway: ConversationPersistenceGateway,
) {
    suspend fun persist(
        conversationId: Uuid,
        conversation: Conversation,
        preservePersistedLocation: Boolean,
        primaryTransaction: suspend () -> Unit = {},
    ) {
        gateway.serialize(
            conversationId = conversationId,
            persistPrimary = { persistedLocation ->
                if (!persistedLocation.exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
                    return@serialize null
                }

                val normalized = if (preservePersistedLocation) {
                    conversation.withPersistedLocation(persistedLocation)
                } else {
                    conversation
                }
                if (persistedLocation.exists) {
                    gateway.updatePrimary(normalized, primaryTransaction)
                } else {
                    gateway.insertPrimary(normalized, primaryTransaction)
                }
                normalized
            },
            onPrimaryCommitted = { normalized ->
                normalized?.let { gateway.synchronizeSession(conversationId, it) }
            },
            postPrimary = { normalized ->
                normalized?.let { gateway.index(it) }
            },
        )
    }
}
