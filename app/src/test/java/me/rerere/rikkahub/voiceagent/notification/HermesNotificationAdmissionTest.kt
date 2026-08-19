package me.rerere.rikkahub.voiceagent.notification

import me.rerere.rikkahub.voiceagent.ActiveVoiceAgentIdentity
import me.rerere.rikkahub.voiceagent.VoiceAgentTransport
import me.rerere.rikkahub.voiceagent.recovery.HermesNotificationDisposition
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class HermesNotificationAdmissionTest {

    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val otherConversationId = Uuid.parse("00000000-0000-0000-0000-000000000002")

    @Test
    fun `precedence 1 - disabled adapter returns SuppressedNotEnabled regardless of other state`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { false },
            activeCallIdentity = { ActiveVoiceAgentIdentity(conversationId, VoiceAgentTransport.LiveKitExperimental) },
            isForeground = { true },
            isNotificationAllowed = { false },
        )

        val relayResult = admission.decide(conversationId, TerminalObservationContext.ConnectedRelay)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, relayResult)

        val recoveryResult = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.SuppressedNotEnabled, recoveryResult)
    }

    @Test
    fun `precedence 2a - connected relay returns SuppressedInCall regardless of foreground or permission`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { null },
            isForeground = { true },
            isNotificationAllowed = { false },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.ConnectedRelay)
        assertEquals(HermesNotificationDisposition.SuppressedInCall, result)
    }

    @Test
    fun `precedence 2b - recovery with matching active LiveKit call returns SuppressedInCall`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { ActiveVoiceAgentIdentity(conversationId, VoiceAgentTransport.LiveKitExperimental) },
            isForeground = { false },
            isNotificationAllowed = { true },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.SuppressedInCall, result)
    }

    @Test
    fun `precedence 2c - recovery with active LiveKit call for different conversation does not suppress for in-call`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { ActiveVoiceAgentIdentity(otherConversationId, VoiceAgentTransport.LiveKitExperimental) },
            isForeground = { false },
            isNotificationAllowed = { true },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.PendingPost, result)
    }

    @Test
    fun `precedence 2d - recovery with active DirectGemini call for same conversation does not suppress for in-call`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { ActiveVoiceAgentIdentity(conversationId, VoiceAgentTransport.DirectGemini) },
            isForeground = { false },
            isNotificationAllowed = { true },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.PendingPost, result)
    }

    @Test
    fun `precedence 3 - foreground returns SuppressedForeground even if permission is denied`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { null },
            isForeground = { true },
            isNotificationAllowed = { false },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.SuppressedForeground, result)
    }

    @Test
    fun `precedence 4 - permission denied or blocked channel returns SuppressedPermission in background`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { null },
            isForeground = { false },
            isNotificationAllowed = { false },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.SuppressedPermission, result)
    }

    @Test
    fun `precedence 5 - background with permission allowed returns PendingPost`() {
        val admission = DefaultHermesNotificationAdmission(
            isAdmissionEnabled = { true },
            activeCallIdentity = { null },
            isForeground = { false },
            isNotificationAllowed = { true },
        )

        val result = admission.decide(conversationId, TerminalObservationContext.Recovery)
        assertEquals(HermesNotificationDisposition.PendingPost, result)
    }
}
