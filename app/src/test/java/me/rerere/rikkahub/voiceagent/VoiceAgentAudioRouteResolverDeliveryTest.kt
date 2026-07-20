package me.rerere.rikkahub.voiceagent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAgentAudioRouteResolverDeliveryTest {
    @Test
    fun `main cancellation returns while exact delivery cleanup blocks`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val call = BlockingDeliveryCall(cleanupEntered, releaseCleanup)
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val observed = AtomicReference<Throwable>()
        val resolverReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel final delivery on Main")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        cleanupDispatcher = cleanupDispatcher,
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolverReturned.countDown()
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(resolution.isCompleted)
            assertFalse(resolverReturned.await(100, TimeUnit.MILLISECONDS))
            assertEquals(1, call.disconnectCalls.get())

            releaseCleanup.countDown()
            assertTrue(resolverReturned.await(1, TimeUnit.SECONDS))
            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(0, thrown.suppressed.size)
            assertEquals(1, call.disconnectCalls.get())
            assertAttemptWasConsumed(registry, VoiceAgentTelecomAttemptId(1))
        } finally {
            releaseCleanup.countDown()
            main.close()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun `failed asynchronous delivery cleanup is suppressed once and remains retryable`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val cleanupFailure = IllegalStateException("async delivery cleanup failed")
        val currentFailure = AtomicReference<Throwable?>(cleanupFailure)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val call = BlockingDeliveryCall(cleanupEntered, releaseCleanup, currentFailure)
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val observed = AtomicReference<Throwable>()
        val resolverReturned = CountDownLatch(1)
        val cancellation = CancellationException("cancel failing final cleanup")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        cleanupDispatcher = cleanupDispatcher,
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                } finally {
                    resolverReturned.countDown()
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS))
            assertFalse(resolution.isCompleted)
            releaseCleanup.countDown()
            assertTrue(resolverReturned.await(1, TimeUnit.SECONDS))

            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(cancellation.message, thrown.message)
            assertEquals(1, thrown.suppressed.size)
            assertSame(cleanupFailure, thrown.suppressed.single())
            assertEquals(1, call.disconnectCalls.get())

            val blocked = registry.beginAttempt()
            assertTrue(blocked is VoiceAgentTelecomAttemptStartResult.CleanupFailed)
            assertSame(cleanupFailure, (blocked as VoiceAgentTelecomAttemptStartResult.CleanupFailed).error)
            assertEquals(2, call.disconnectCalls.get())
            currentFailure.set(null)
            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, replacement.value)
            assertEquals(3, call.disconnectCalls.get())
        } finally {
            releaseCleanup.countDown()
            main.close()
            cleanupScope.cancel()
            cleanupDispatcher.close()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun `rejected cleanup scheduling retains the exact lease for retry`() = runBlocking {
        val registry = VoiceAgentTelecomCallRegistry()
        val call = BlockingDeliveryCall(CountDownLatch(0), CountDownLatch(0))
        val gateway = DeliveryTestGateway { attempt ->
            assertTrue(registry.activate(attempt, call))
        }
        val main = MainDeliveryGateDispatcher()
        val schedulingFailure = IllegalStateException("cleanup dispatcher rejected")
        val cleanupScope = CoroutineScope(SupervisorJob() + RejectingDispatcher(schedulingFailure))
        val observed = AtomicReference<Throwable>()
        val cancellation = CancellationException("cancel with rejected cleanup scheduling")
        val cancelReturned = CountDownLatch(1)

        try {
            val resolution = async(main) {
                try {
                    VoiceAgentAudioRouteResolver(
                        gateway = gateway,
                        registry = registry,
                        timeoutMs = 1_000,
                        cleanupScope = cleanupScope,
                        cleanupDispatcher = RejectingDispatcher(schedulingFailure),
                        deliveryProbe = VoiceAgentRouteDeliveryProbe { job ->
                            job.cancel(cancellation)
                            cancelReturned.countDown()
                        },
                    ).resolve()
                } catch (error: Throwable) {
                    observed.set(error)
                    throw error
                }
            }
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
            assertTrue(main.awaitIdle())
            val thrown = observed.get()
            assertTrue(thrown is CancellationException)
            assertEquals(1, thrown.suppressed.size)
            assertSame(schedulingFailure, thrown.suppressed.single())
            assertEquals(0, call.disconnectCalls.get())

            val replacement = registry.beginAttempt().requireAllocatedAttemptId()
            assertEquals(2L, replacement.value)
            assertEquals(1, call.disconnectCalls.get())
        } finally {
            main.close()
            cleanupScope.cancel()
        }
    }
}

private class MainDeliveryGateDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute(block)
    }

    fun awaitIdle(): Boolean = executor.submit {}.run {
        runCatching { get(1, TimeUnit.SECONDS) }.isSuccess
    }

    override fun close() {
        executor.shutdownNow()
    }
}

private class RejectingDispatcher(
    private val failure: Throwable,
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        throw failure
    }
}

private class DeliveryTestGateway(
    private val onStart: (VoiceAgentTelecomAttemptId) -> Unit,
) : VoiceAgentTelecomGateway {
    override fun register() = Result.success(Unit)

    override fun startCall(attemptId: VoiceAgentTelecomAttemptId): Result<Unit> {
        onStart(attemptId)
        return Result.success(Unit)
    }
}

private class BlockingDeliveryCall(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
    private val failure: AtomicReference<Throwable?> = AtomicReference(null),
) : VoiceAgentTelecomCall {
    val disconnectCalls = AtomicInteger()

    override fun disconnectFromApp() {
        disconnectCalls.incrementAndGet()
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "delivery cleanup was not released" }
        failure.get()?.let { throw it }
    }
}
