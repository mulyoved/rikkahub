package me.rerere.rikkahub.voiceagent.notification

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class HermesResultNotifierTest {

    @Test
    fun `constants match specification`() {
        assertEquals("hermes_task_results", HERMES_TASK_RESULTS_CHANNEL_ID)
        assertEquals(2002, HERMES_RESULT_NOTIFICATION_ID)
        assertEquals("me.rerere.rikkahub.action.OPEN_HERMES_RESULT", ACTION_OPEN_HERMES_RESULT)
        assertEquals("me.rerere.rikkahub.action.DISMISS_HERMES_RESULT", ACTION_DISMISS_HERMES_RESULT)
        assertEquals("Hermes task finished", HERMES_RESULT_NOTIFICATION_TITLE)
        assertEquals("Open the conversation to view the result.", HERMES_RESULT_NOTIFICATION_BODY)
    }

    @Test
    fun `notification spec satisfies privacy, category, visibility, and auto-cancel guarantees`() {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val spec = HermesResultNotificationSpec.create(conversationId)

        assertEquals(HERMES_TASK_RESULTS_CHANNEL_ID, spec.channelId)
        assertEquals(HERMES_RESULT_NOTIFICATION_ID, spec.notificationId)
        assertEquals(conversationId.toString(), spec.tag)
        assertEquals("Hermes task finished", spec.title)
        assertEquals("Open the conversation to view the result.", spec.body)
        assertEquals(NotificationCompat.CATEGORY_STATUS, spec.category)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, spec.visibility)
        assertTrue(spec.autoCancel)

        // Public version guarantees
        val publicSpec = spec.publicVersion
        assertNotNull(publicSpec)
        assertEquals(HERMES_TASK_RESULTS_CHANNEL_ID, publicSpec.channelId)
        assertEquals("Hermes task finished", publicSpec.title)
        assertEquals("Open the conversation to view the result.", publicSpec.body)
        assertEquals(NotificationCompat.CATEGORY_STATUS, publicSpec.category)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, publicSpec.visibility)
        assertTrue(publicSpec.autoCancel)
    }

    @Test
    fun `notification never contains prompt, answer, error, model, profile, or outcome sentinels`() {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        val spec = HermesResultNotificationSpec.create(conversationId)

        val sentinels = listOf(
            "prompt",
            "answer",
            "error",
            "model",
            "profile",
            "outcome",
            "exception",
            "failed",
            "success",
            "gemini",
            "openai",
            "claude",
            "deepseek",
            "user question",
            "bot response",
        )

        val allNotificationStrings = listOf(
            spec.title,
            spec.body,
            spec.publicVersion.title,
            spec.publicVersion.body,
            spec.openDataUri,
            spec.dismissDataUri,
            spec.openAction,
            spec.dismissAction,
        )

        for (text in allNotificationStrings) {
            for (sentinel in sentinels) {
                assertFalse(
                    "Sentinel '$sentinel' found in notification text '$text'",
                    text.contains(sentinel, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `distinct data URIs prevent PendingIntent aliasing for different conversations`() {
        val conv1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val conv2 = Uuid.parse("22222222-2222-2222-2222-222222222222")

        val spec1 = HermesResultNotificationSpec.create(conv1)
        val spec2 = HermesResultNotificationSpec.create(conv2)

        assertEquals("rikkahub://hermes-result/conversation/11111111-1111-1111-1111-111111111111", spec1.openDataUri)
        assertEquals("rikkahub://hermes-result/conversation/22222222-2222-2222-2222-222222222222", spec2.openDataUri)
        assertEquals("rikkahub://hermes-result/conversation/11111111-1111-1111-1111-111111111111", spec1.dismissDataUri)
        assertEquals("rikkahub://hermes-result/conversation/22222222-2222-2222-2222-222222222222", spec2.dismissDataUri)

        assertTrue(spec1.openDataUri != spec2.openDataUri)
        assertTrue(spec1.dismissDataUri != spec2.dismissDataUri)
    }

    @Test
    fun `production post gate defaults to AllowHermesNotificationPost no-op`() {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        // Calling beforePost on AllowHermesNotificationPost succeeds without exception
        AllowHermesNotificationPost.beforePost(conversationId)
    }

    @Test
    fun `throwing post gate escapes post call before notify is executed`() = runTest {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        var notifyCalled = false

        val throwingGate = HermesNotificationPostGate { id ->
            throw IllegalStateException("Post gate rejected notification for $id")
        }

        val notifier = HermesResultNotifier(
            context = mockk(relaxed = true),
            postGate = throwingGate,
            notificationBuilder = { mockk(relaxed = true) },
            notifyAction = { _, _, _ -> notifyCalled = true },
            cancelAction = { _, _ -> },
        )

        var thrown: Throwable? = null
        try {
            notifier.post(conversationId)
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(thrown is IllegalStateException)
        assertEquals("Post gate rejected notification for $conversationId", thrown?.message)
        assertFalse("Notification notify must not be called when gate throws", notifyCalled)
    }

    @Test
    fun `successful post calls notify with exact tag and ID`() = runTest {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        var postedTag: String? = null
        var postedId: Int? = null

        val notifier = HermesResultNotifier(
            context = mockk(relaxed = true),
            postGate = AllowHermesNotificationPost,
            notificationBuilder = { mockk(relaxed = true) },
            notifyAction = { tag, id, _ ->
                postedTag = tag
                postedId = id
            },
            cancelAction = { _, _ -> },
        )

        notifier.post(conversationId)

        assertEquals(conversationId.toString(), postedTag)
        assertEquals(2002, postedId)
    }

    @Test
    fun `cancel calls notification manager with exact tag and ID`() {
        val conversationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
        var cancelledTag: String? = null
        var cancelledId: Int? = null

        val notifier = HermesResultNotifier(
            context = mockk(relaxed = true),
            postGate = AllowHermesNotificationPost,
            notificationBuilder = { mockk(relaxed = true) },
            notifyAction = { _, _, _ -> },
            cancelAction = { tag, id ->
                cancelledTag = tag
                cancelledId = id
            },
        )

        notifier.cancel(conversationId)

        assertEquals(conversationId.toString(), cancelledTag)
        assertEquals(2002, cancelledId)
    }

    @Test
    fun `cleanupStaleNotifications cancels only hermes notifications with stale conversation IDs`() {
        val activeConv1 = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val staleConv2 = Uuid.parse("22222222-2222-2222-2222-222222222222")

        val sbn1 = mockk<StatusBarNotification> {
            every { id } returns 2002
            every { tag } returns activeConv1.toString()
        }
        val sbn2 = mockk<StatusBarNotification> {
            every { id } returns 2002
            every { tag } returns staleConv2.toString()
        }
        val sbn3 = mockk<StatusBarNotification> {
            every { id } returns 1001 // Not Hermes notification ID
            every { tag } returns "other"
        }
        val sbn4 = mockk<StatusBarNotification> {
            every { id } returns 2002
            every { tag } returns "not-a-uuid"
        }

        val cancelledTags = mutableListOf<String>()

        val notifier = HermesResultNotifier(
            context = mockk(relaxed = true),
            postGate = AllowHermesNotificationPost,
            notificationBuilder = { mockk(relaxed = true) },
            notifyAction = { _, _, _ -> },
            cancelAction = { tag, id ->
                if (id == 2002) cancelledTags.add(tag)
            },
            activeNotificationsProvider = { arrayOf(sbn1, sbn2, sbn3, sbn4) },
        )

        notifier.cleanupStaleNotifications(setOf(activeConv1))

        assertEquals(listOf(staleConv2.toString(), "not-a-uuid"), cancelledTags)
    }
}
