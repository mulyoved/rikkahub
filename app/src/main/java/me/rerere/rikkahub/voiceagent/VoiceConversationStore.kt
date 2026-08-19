package me.rerere.rikkahub.voiceagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.voiceagent.persistence.VoiceContext
import me.rerere.rikkahub.voiceagent.persistence.VoiceContextBuilder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

interface VoiceConversationStore {
    val conversation: StateFlow<Conversation>

    suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T

    suspend fun update(transform: (Conversation) -> Conversation) {
        updateAtomically(
            transform = { conversation -> transform(conversation) to Unit },
            commit = {},
        )
    }

    fun close() = Unit
}

class InMemoryVoiceConversationStore(
    initialConversation: Conversation = Conversation.ofId(id = Uuid.random()),
) : VoiceConversationStore {
    private val conversationFlow = MutableStateFlow(initialConversation)
    override val conversation: StateFlow<Conversation> = conversationFlow

    override suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T {
        val (updated, result) = transform(conversationFlow.value)
        commit(result)
        conversationFlow.value = updated
        return result
    }
}

class SynchronizedVoiceConversationStore(
    private val delegate: VoiceConversationStore,
) : VoiceConversationStore {
    private val lock = Mutex()

    override val conversation: StateFlow<Conversation> = delegate.conversation

    override suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T {
        return lock.withLock {
            delegate.updateAtomically(transform, commit)
        }
    }

    override fun close() {
        delegate.close()
    }
}

class ChatServiceVoiceConversationStore(
    private val conversationId: Uuid,
    private val chatService: ChatService,
) : VoiceConversationStore {
    private val closed = AtomicBoolean(false)

    init {
        chatService.addConversationReference(conversationId)
    }

    override val conversation: StateFlow<Conversation> = chatService.getConversationFlow(conversationId)

    override suspend fun <T> updateAtomically(
        transform: (Conversation) -> Pair<Conversation, T>,
        commit: suspend (T) -> Unit,
    ): T {
        return chatService.saveConversationAtomically(
            conversationId = conversationId,
            transform = transform,
            commit = commit,
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            chatService.removeConversationReference(conversationId)
        }
    }
}

interface VoiceAgentContextProvider {
    fun build(conversation: Conversation): VoiceContext
}

class SettingsVoiceAgentContextProvider(
    private val settingsStore: SettingsStore,
    private val voiceModelName: String = "Gemini Live",
    private val contextBuilder: VoiceContextBuilder = VoiceContextBuilder(),
) : VoiceAgentContextProvider {
    override fun build(conversation: Conversation): VoiceContext {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(conversation.assistantId)
        return contextBuilder.build(
            assistantName = assistant?.name?.takeIf { it.isNotBlank() } ?: "RikkaHub",
            assistantPrompt = conversation.customSystemPrompt
                ?: assistant?.systemPrompt
                ?: "",
            conversation = conversation,
            voiceModelName = voiceModelName,
            userNickname = settings.displaySetting.userNickname,
        )
    }
}
