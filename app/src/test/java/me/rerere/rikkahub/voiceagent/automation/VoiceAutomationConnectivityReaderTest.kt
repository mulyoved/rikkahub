package me.rerere.rikkahub.voiceagent.automation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationConnectivity
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationConnectivityReader
import me.rerere.rikkahub.voiceagent.debug.VoiceAutomationConnectivitySource
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAutomationConnectivityReaderTest {
    @Test
    fun `validated callback replaces a missing immediate default network`() = runTest {
        var observationClosed = false
        val reader = VoiceAutomationConnectivityReader(
            source = FakeConnectivitySource(
                immediate = VoiceAutomationConnectivity(VoiceAutomationNetwork.NONE, false),
                updates = flow {
                    try {
                        emit(VoiceAutomationConnectivity(VoiceAutomationNetwork.WIFI, true))
                    } finally {
                        observationClosed = true
                    }
                },
            ),
            timeoutMillis = 1_000,
        )

        assertEquals(
            VoiceAutomationConnectivity(VoiceAutomationNetwork.WIFI, true),
            reader.read(),
        )
        assertEquals(true, observationClosed)
    }

    @Test
    fun `timeout returns the latest unvalidated callback without claiming readiness`() = runTest {
        val reader = VoiceAutomationConnectivityReader(
            source = FakeConnectivitySource(
                immediate = VoiceAutomationConnectivity(VoiceAutomationNetwork.NONE, false),
                updates = flow {
                    emit(VoiceAutomationConnectivity(VoiceAutomationNetwork.CELLULAR, false))
                    suspendCancellableCoroutine<Nothing> { }
                },
            ),
            timeoutMillis = 100,
        )

        assertEquals(
            VoiceAutomationConnectivity(VoiceAutomationNetwork.CELLULAR, false),
            reader.read(),
        )
    }
}

private class FakeConnectivitySource(
    private val immediate: VoiceAutomationConnectivity,
    private val updates: Flow<VoiceAutomationConnectivity>,
) : VoiceAutomationConnectivitySource {
    override fun current(): VoiceAutomationConnectivity = immediate

    override fun updates(): Flow<VoiceAutomationConnectivity> = updates
}
