package me.rerere.rikkahub.voiceagent.notification

import kotlin.uuid.Uuid

internal fun interface HermesNotificationPostGate {
    fun beforePost(conversationId: Uuid)
}

internal object AllowHermesNotificationPost : HermesNotificationPostGate {
    override fun beforePost(conversationId: Uuid) = Unit
}
