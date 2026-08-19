package me.rerere.rikkahub.voiceagent.notification

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.voiceagent.ActiveVoiceAgentIdentity
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import kotlin.uuid.Uuid

internal enum class TerminalObservationContext {
    ConnectedRelay,
    Recovery,
}

internal fun interface HermesNotificationAdmission {
    fun decide(
        conversationId: Uuid,
        observation: TerminalObservationContext,
    ): HermesNotificationDisposition
}

internal class DefaultHermesNotificationAdmission(
    private val isAdmissionEnabled: () -> Boolean = { true },
    private val activeCallIdentity: () -> ActiveVoiceAgentIdentity? = { null },
    private val isForeground: () -> Boolean = {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    },
    private val isNotificationAllowed: () -> Boolean,
) : HermesNotificationAdmission {

    constructor(
        context: Context,
        isAdmissionEnabled: () -> Boolean = { true },
        activeCallIdentity: () -> ActiveVoiceAgentIdentity? = { null },
        isForeground: () -> Boolean = {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        },
    ) : this(
        isAdmissionEnabled = isAdmissionEnabled,
        activeCallIdentity = activeCallIdentity,
        isForeground = isForeground,
        isNotificationAllowed = {
            isNotificationAllowed(context)
        },
    )

    override fun decide(
        conversationId: Uuid,
        observation: TerminalObservationContext,
    ): HermesNotificationDisposition {
        if (!isAdmissionEnabled()) {
            return HermesNotificationDisposition.SuppressedNotEnabled
        }

        if (observation == TerminalObservationContext.ConnectedRelay) {
            return HermesNotificationDisposition.SuppressedInCall
        }

        val active = activeCallIdentity()
        if (active != null &&
            active.conversationId == conversationId &&
            active.transport == VoiceAgentTransport.LiveKitExperimental
        ) {
            return HermesNotificationDisposition.SuppressedInCall
        }

        if (isForeground()) {
            return HermesNotificationDisposition.SuppressedForeground
        }

        if (!isNotificationAllowed()) {
            return HermesNotificationDisposition.SuppressedPermission
        }

        return HermesNotificationDisposition.PendingPost
    }

    companion object {
        fun isNotificationAllowed(context: Context): Boolean {
            val notificationManager = NotificationManagerCompat.from(context)
            if (!notificationManager.areNotificationsEnabled()) {
                return false
            }
            val channel = notificationManager.getNotificationChannelCompat(HERMES_TASK_RESULTS_CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
                return false
            }
            return true
        }
    }
}
