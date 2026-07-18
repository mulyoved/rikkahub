package me.rerere.rikkahub.voiceagent.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAudioDebugCaptureRegistrationOwnerTest {
    @Test
    fun `callback failure terminates and unregisters exact capture`() {
        val owner = VoiceAudioDebugCaptureRegistrationOwner<Any, Any, FakeRegistration>(
            closeRegistration = FakeRegistration::close,
        )
        val token = Any()
        val recorder = Any()
        val registration = FakeRegistration()
        val callbackFailure = IllegalStateException("callback failed")
        var terminateCalls = 0
        var observedFailure: Exception? = null
        owner.publish(token, recorder, registration) { true }

        owner.deliver(
            token = token,
            recorder = recorder,
            buffer = byteArrayOf(1, 2),
            isCurrent = { true },
            onPcm16 = { throw callbackFailure },
            terminate = {
                terminateCalls += 1
                true
            },
            onFailure = { observedFailure = it },
        )

        assertEquals(1, terminateCalls)
        assertSame(callbackFailure, observedFailure)
        assertEquals(1, registration.closeCalls)
        assertFalse(owner.unregister(token, recorder))
    }

    @Test
    fun `stale owner unregister cannot close newer registration`() {
        val owner = VoiceAudioDebugCaptureRegistrationOwner<Any, Any, FakeRegistration>(
            closeRegistration = FakeRegistration::close,
        )
        val staleToken = Any()
        val staleRecorder = Any()
        val staleRegistration = FakeRegistration()
        val currentToken = Any()
        val currentRecorder = Any()
        val currentRegistration = FakeRegistration()
        owner.publish(staleToken, staleRecorder, staleRegistration) { true }
        owner.publish(currentToken, currentRecorder, currentRegistration) { true }

        assertFalse(owner.unregister(staleToken, staleRecorder))

        assertEquals(1, staleRegistration.closeCalls)
        assertEquals(0, currentRegistration.closeCalls)
        assertTrue(owner.unregister(currentToken, currentRecorder))
        assertEquals(1, currentRegistration.closeCalls)
    }

    private class FakeRegistration {
        var closeCalls = 0
            private set

        fun close() {
            closeCalls += 1
        }
    }
}
