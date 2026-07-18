package me.rerere.rikkahub.voiceagent.audio

import android.media.AudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioRouteControllerTest {
    @Test
    fun `stop winning blocked route acquisition retires late route and skips remaining setup`() {
        val ownership = fakeSetupOwnership()
        val acquireEntered = CountDownLatch(1)
        val allowAcquireReturn = CountDownLatch(1)
        val setupReturned = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val setupResult = AtomicReference<VoiceAudioCaptureSetup<Any>?>()
        val setup = thread(name = "blocked-route-stop-setup") {
            setupResult.set(
                setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = {
                        acquireEntered.countDown()
                        allowAcquireReturn.await(5, TimeUnit.SECONDS)
                        lease
                    },
                    lookupBufferSize = { events += "buffer"; 64 },
                    createRecorder = { events += "recorder"; Any() },
                    configureRecorder = { _, _ -> events += "configure" },
                    isRecorderInitialized = { true },
                    releaseRecorder = { events += "recorderReleased" },
                ),
            )
            setupReturned.countDown()
        }
        assertTrue(acquireEntered.await(5, TimeUnit.SECONDS))

        ownership.stop()
        assertFalse(setupReturned.await(100, TimeUnit.MILLISECONDS))
        allowAcquireReturn.countDown()
        setup.join(5_000)

        assertFalse(setup.isAlive)
        assertEquals(null, setupResult.get())
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `release winning blocked route acquisition retires late route and stays released`() {
        val ownership = fakeSetupOwnership()
        val acquireEntered = CountDownLatch(1)
        val allowAcquireReturn = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val setupResult = AtomicReference<VoiceAudioCaptureSetup<Any>?>()
        val setup = thread(name = "blocked-route-release-setup") {
            setupResult.set(
                setupVoiceAudioCapture(
                    ownership = ownership,
                    acquireRoute = {
                        acquireEntered.countDown()
                        allowAcquireReturn.await(5, TimeUnit.SECONDS)
                        lease
                    },
                    lookupBufferSize = { events += "buffer"; 64 },
                    createRecorder = { events += "recorder"; Any() },
                    configureRecorder = { _, _ -> events += "configure" },
                    isRecorderInitialized = { true },
                    releaseRecorder = { events += "recorderReleased" },
                ),
            )
        }
        assertTrue(acquireEntered.await(5, TimeUnit.SECONDS))

        assertTrue(ownership.release())
        allowAcquireReturn.countDown()
        setup.join(5_000)

        assertFalse(setup.isAlive)
        assertEquals(null, setupResult.get())
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertEquals(
            "Voice audio engine is released",
            runCatching { ownership.reserve() }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `route acquisition failure keeps primary and aborts reserved owner for reuse`() {
        val acquireFailure = IllegalStateException("route unavailable")
        val ownership = fakeSetupOwnership()
        val events = mutableListOf<String>()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = {
                    events += "acquire"
                    throw acquireFailure
                },
                lookupBufferSize = { events += "buffer"; 64 },
                createRecorder = { events += "recorder"; Any() },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertSame(acquireFailure, thrown)
        assertEquals(emptyList<Throwable>(), acquireFailure.suppressed.toList())
        assertEquals(listOf("acquire"), events)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `buffer size failure aborts published route before recorder creation`() {
        val failure = IllegalStateException("minimum buffer unavailable")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()
        var recorderCreationCalls = 0

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = {
                    events += "bufferLookup"
                    throw failure
                },
                createRecorder = {
                    recorderCreationCalls += 1
                    Any()
                },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(0, recorderCreationCalls)
        assertEquals(listOf("bufferLookup", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
        assertTrue(ownership.abort(ownership.reserve()))
    }

    @Test
    fun `recorder creation failure wraps cause and aborts published route`() {
        val cause = IllegalArgumentException("factory failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { throw cause },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { true },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord creation failed", thrown?.message)
        assertSame(cause, thrown?.cause)
        assertEquals(listOf("routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `configuration failure keeps primary and suppresses recorder then route cleanup failures`() {
        val configureFailure = IllegalStateException("configuration failed")
        val releaseFailure = IllegalArgumentException("release failed")
        val routeFailure = UnsupportedOperationException("route retirement failed")
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease {
            events += "routeRetired"
            throw routeFailure
        }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { Any() },
                configureRecorder = { _, _ ->
                    events += "configure"
                    throw configureFailure
                },
                isRecorderInitialized = { true },
                releaseRecorder = {
                    events += "recorderReleased"
                    throw releaseFailure
                },
            )
        }.exceptionOrNull()

        assertSame(configureFailure, thrown)
        assertEquals(listOf(releaseFailure, routeFailure), configureFailure.suppressed.toList())
        assertEquals(listOf("configure", "recorderReleased", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `uninitialized recorder releases recorder then aborts published route`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        val ownership = fakeSetupOwnership()

        val thrown = runCatching {
            setupVoiceAudioCapture(
                ownership = ownership,
                acquireRoute = { lease },
                lookupBufferSize = { 64 },
                createRecorder = { Any() },
                configureRecorder = { _, _ -> events += "configure" },
                isRecorderInitialized = { false },
                releaseRecorder = { events += "recorderReleased" },
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals("AudioRecord initialization failed", thrown?.message)
        assertEquals(listOf("configure", "recorderReleased", "routeRetired"), events)
        assertEquals(1, lease.retireCalls)
    }

    @Test
    fun `read exception autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                throw IllegalStateException("read failed")
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException:${it.message}" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("readException:read failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `negative read autonomously retires exact capture route once`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                -3
            },
            onPcm16 = { events += "pcm" },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative:$it" },
            onPcmCallbackException = { events += "callbackException" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(listOf("negative:-3", "recorderRetired", "routeRetired"), events)
    }

    @Test
    fun `PCM callback exception autonomously retires exact capture route once with no later read`() {
        val events = mutableListOf<String>()
        val lease = FakeCaptureRouteLease { events += "routeRetired" }
        var reads = 0

        runVoiceAudioCaptureLoop(
            bufferSize = 4,
            shouldContinue = { true },
            read = {
                reads += 1
                2
            },
            onPcm16 = { throw IllegalArgumentException("callback failed") },
            onReadException = { events += "readException" },
            onNegativeRead = { events += "negative" },
            onPcmCallbackException = { events += "callbackException:${it.message}" },
            onTerminated = { events += "recorderRetired"; lease.retire() },
        )
        lease.retire()

        assertEquals(1, reads)
        assertEquals(
            listOf("callbackException:callback failed", "recorderRetired", "routeRetired"),
            events,
        )
    }

    @Test
    fun `capture route lease is exact once across autonomous stop and release race`() {
        val retireCalls = AtomicInteger()
        val lease = FakeCaptureRouteLease { retireCalls.incrementAndGet() }
        val racers = List(3) {
            thread(name = "capture-retirement-$it") { lease.retire() }
        }

        racers.forEach { it.join(5_000) }

        assertTrue(racers.none(Thread::isAlive))
        assertEquals(1, retireCalls.get())
    }

    @Test
    fun `stop or release waits for autonomous route retirement before later mutation`() {
        val retirementEntered = CountDownLatch(1)
        val releaseRetirement = CountDownLatch(1)
        val competingRetirementAttempted = CountDownLatch(1)
        val competingRetirementCompleted = CountDownLatch(1)
        val lease = FakeCaptureRouteLease {
            retirementEntered.countDown()
            releaseRetirement.await(5, TimeUnit.SECONDS)
        }
        val autonomous = thread(name = "autonomous-capture-retirement") { lease.retire() }
        assertTrue(retirementEntered.await(5, TimeUnit.SECONDS))
        val stopOrRelease = thread(name = "explicit-capture-retirement") {
            competingRetirementAttempted.countDown()
            lease.retire()
            competingRetirementCompleted.countDown()
        }

        try {
            assertTrue(competingRetirementAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitThreadState(stopOrRelease, Thread.State.WAITING))
            assertFalse(competingRetirementCompleted.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseRetirement.countDown()
            autonomous.join(5_000)
            stopOrRelease.join(5_000)
        }

        assertFalse(autonomous.isAlive)
        assertFalse(stopOrRelease.isAlive)
        assertTrue(competingRetirementCompleted.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `Telecom owner never constructs or calls direct controller`() {
        var directCreated = 0
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.Telecom) {
            directCreated += 1
            RecordingRouteController()
        }

        val lease = selected.acquireCapture()
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(0, directCreated)
    }

    @Test
    fun `direct fallback delegates lifecycle once`() {
        val direct = RecordingRouteController()
        val selected = selectVoiceAudioRouteController(VoiceAudioRouteOwner.DirectFallback) { direct }

        val lease = selected.acquireCapture()
        lease.retire()
        lease.retire()
        selected.close()

        assertEquals(listOf("acquire", "retire", "close"), direct.calls)
    }

    private class RecordingRouteController : VoiceAudioRouteController {
        val calls = mutableListOf<String>()

        override fun acquireCapture(): VoiceAudioCaptureRouteLease {
            calls += "acquire"
            return FakeCaptureRouteLease { calls += "retire" }
        }

        override fun close() {
            calls += "close"
        }
    }

    private fun fakeSetupOwnership() = VoiceAudioCaptureOwnership<Any, Any>(
        startRecorder = {},
        isRecorderRecording = { true },
        stopRecorder = {},
        releaseRecorder = {},
        startTask = { true },
        cancelTask = {},
    )

    private class FakeCaptureRouteLease(
        private val onRetire: () -> Unit,
    ) : VoiceAudioCaptureRouteLease {
        private val retirement = me.rerere.rikkahub.voiceagent.RetirementBarrier()

        var configureCalls = 0
            private set

        var retireCalls = 0
            private set

        override fun configureRecorder(recorder: AudioRecord) {
            configureCalls += 1
        }

        override fun retire() {
            retirement.retire {
                retireCalls += 1
                onRetire()
            }
        }
    }

}

private fun awaitThreadState(
    thread: Thread,
    expectedState: Thread.State,
): Boolean {
    val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
    while (System.nanoTime() < deadlineNanos) {
        if (thread.state == expectedState) return true
        Thread.yield()
    }
    return thread.state == expectedState
}
