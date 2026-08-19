package me.rerere.rikkahub.voiceagent.notification

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class HermesNotificationWorkerTest {

    private val testConversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")

    @Test
    fun `doWork returns failure when conversationId is missing`() = runTest {
        val coordinator = mockk<HermesNotificationDeliveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf()

        val worker = HermesNotificationWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork returns failure when conversationId is invalid uuid`() = runTest {
        val coordinator = mockk<HermesNotificationDeliveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf(INPUT_CONVERSATION_ID to "not-a-valid-uuid")

        val worker = HermesNotificationWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork invokes deliver and returns success`() = runTest {
        val coordinator = mockk<HermesNotificationDeliveryCoordinator>()
        val context = mockk<Context>(relaxed = true)
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns workDataOf(INPUT_CONVERSATION_ID to testConversationId.toString())
        coEvery { coordinator.deliver(testConversationId) } returns Unit

        val worker = HermesNotificationWorker(context, params, coordinator)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { coordinator.deliver(testConversationId) }
    }

    @Test
    fun `production request factory binds to HermesNotificationWorker with exact attributes`() {
        val factory = HermesNotificationWorkRequestFactory { spec ->
            hermesNotificationRequest<HermesNotificationWorker>(spec)
        }
        val spec = HermesNotificationWorkSpec(
            conversationId = testConversationId,
            delay = 60.seconds,
        )
        val request = factory.create(spec)
        val workSpec = request.workSpec

        assertEquals(testConversationId.toString(), workSpec.input.getString(INPUT_CONVERSATION_ID))
        assertEquals(60_000L, workSpec.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, workSpec.constraints.requiredNetworkType)
        assertEquals(HermesNotificationWorker::class.java.name, workSpec.workerClassName)
    }
}
