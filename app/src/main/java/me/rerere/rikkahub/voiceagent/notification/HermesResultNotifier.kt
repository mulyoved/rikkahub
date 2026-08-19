package me.rerere.rikkahub.voiceagent.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.voiceagent.recovery.HermesRecoveryLedger
import me.rerere.rikkahub.voiceagent.recovery.RecoveryClock
import me.rerere.rikkahub.voiceagent.recovery.SystemRecoveryClock
import kotlin.uuid.Uuid

const val HERMES_TASK_RESULTS_CHANNEL_ID = "hermes_task_results"
const val HERMES_RESULT_NOTIFICATION_ID = 2002
const val ACTION_OPEN_HERMES_RESULT = "me.rerere.rikkahub.action.OPEN_HERMES_RESULT"
const val ACTION_DISMISS_HERMES_RESULT = "me.rerere.rikkahub.action.DISMISS_HERMES_RESULT"
const val HERMES_RESULT_NOTIFICATION_TITLE = "Hermes task finished"
const val HERMES_RESULT_NOTIFICATION_BODY = "Open the conversation to view the result."

internal data class HermesResultPublicNotificationSpec(
    val channelId: String,
    val title: String,
    val body: String,
    val category: String,
    val visibility: Int,
    val autoCancel: Boolean,
)

internal data class HermesResultNotificationSpec(
    val channelId: String,
    val notificationId: Int,
    val tag: String,
    val title: String,
    val body: String,
    val category: String,
    val visibility: Int,
    val autoCancel: Boolean,
    val openAction: String,
    val openDataUri: String,
    val dismissAction: String,
    val dismissDataUri: String,
    val publicVersion: HermesResultPublicNotificationSpec,
) {
    companion object {
        fun create(conversationId: Uuid): HermesResultNotificationSpec {
            val uri = "rikkahub://hermes-result/conversation/$conversationId"
            return HermesResultNotificationSpec(
                channelId = HERMES_TASK_RESULTS_CHANNEL_ID,
                notificationId = HERMES_RESULT_NOTIFICATION_ID,
                tag = conversationId.toString(),
                title = HERMES_RESULT_NOTIFICATION_TITLE,
                body = HERMES_RESULT_NOTIFICATION_BODY,
                category = NotificationCompat.CATEGORY_STATUS,
                visibility = NotificationCompat.VISIBILITY_PRIVATE,
                autoCancel = true,
                openAction = ACTION_OPEN_HERMES_RESULT,
                openDataUri = uri,
                dismissAction = ACTION_DISMISS_HERMES_RESULT,
                dismissDataUri = uri,
                publicVersion = HermesResultPublicNotificationSpec(
                    channelId = HERMES_TASK_RESULTS_CHANNEL_ID,
                    title = HERMES_RESULT_NOTIFICATION_TITLE,
                    body = HERMES_RESULT_NOTIFICATION_BODY,
                    category = NotificationCompat.CATEGORY_STATUS,
                    visibility = NotificationCompat.VISIBILITY_PUBLIC,
                    autoCancel = true,
                ),
            )
        }
    }
}

internal fun buildHermesResultNotification(context: Context, conversationId: Uuid): Notification {
    val spec = HermesResultNotificationSpec.create(conversationId)

    val openIntent = Intent(context, RouteActivity::class.java).apply {
        action = ACTION_OPEN_HERMES_RESULT
        data = Uri.parse(spec.openDataUri)
        putExtra("conversationId", conversationId.toString())
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val openPendingIntent = PendingIntent.getActivity(
        context,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val dismissIntent = Intent(context, HermesNotificationDismissReceiver::class.java).apply {
        action = ACTION_DISMISS_HERMES_RESULT
        data = Uri.parse(spec.dismissDataUri)
        putExtra("conversationId", conversationId.toString())
    }
    val dismissPendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        dismissIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val publicNotification = NotificationCompat.Builder(context, HERMES_TASK_RESULTS_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(spec.publicVersion.title)
        .setContentText(spec.publicVersion.body)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .build()

    return NotificationCompat.Builder(context, HERMES_TASK_RESULTS_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(spec.title)
        .setContentText(spec.body)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicNotification)
        .setAutoCancel(true)
        .setContentIntent(openPendingIntent)
        .setDeleteIntent(dismissPendingIntent)
        .build()
}

internal interface HermesResultNotifierInterface : HermesNotificationPoster {
    override suspend fun post(conversationId: Uuid)
    fun cancel(conversationId: Uuid)
    fun cleanupStaleNotifications(validConversationIds: Set<Uuid>)
}

@SuppressLint("MissingPermission")
internal class HermesResultNotifier(
    private val context: Context,
    private val postGate: HermesNotificationPostGate = AllowHermesNotificationPost,
    private val notificationBuilder: (Uuid) -> Notification = { id -> buildHermesResultNotification(context, id) },
    private val notifyAction: (tag: String, id: Int, notification: Notification) -> Unit = { tag, id, notification ->
        NotificationManagerCompat.from(context).notify(tag, id, notification)
    },
    private val cancelAction: (tag: String, id: Int) -> Unit = { tag, id ->
        NotificationManagerCompat.from(context).cancel(tag, id)
    },
    private val activeNotificationsProvider: () -> Array<StatusBarNotification>? = {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.activeNotifications
    },
) : HermesResultNotifierInterface {

    override suspend fun post(conversationId: Uuid) {
        postGate.beforePost(conversationId)
        val notification = notificationBuilder(conversationId)
        notifyAction(conversationId.toString(), HERMES_RESULT_NOTIFICATION_ID, notification)
    }

    override fun cancel(conversationId: Uuid) {
        cancelAction(conversationId.toString(), HERMES_RESULT_NOTIFICATION_ID)
    }

    override fun cleanupStaleNotifications(validConversationIds: Set<Uuid>) {
        val active = activeNotificationsProvider() ?: return
        for (sbn in active) {
            if (sbn.id == HERMES_RESULT_NOTIFICATION_ID) {
                val tag = sbn.tag ?: continue
                val parsedUuid = runCatching { Uuid.parse(tag) }.getOrNull()
                if (parsedUuid == null || parsedUuid !in validConversationIds) {
                    cancelAction(tag, HERMES_RESULT_NOTIFICATION_ID)
                }
            }
        }
    }
}

internal class HermesNotificationAcknowledger(
    private val ledger: HermesRecoveryLedger,
    private val notifier: HermesResultNotifierInterface,
    private val clock: RecoveryClock = SystemRecoveryClock,
) {
    suspend fun acknowledgeConversation(conversationId: Uuid) {
        ledger.markPostedSeen(conversationId, clock.epochMillis())
        notifier.cancel(conversationId)
    }
}
