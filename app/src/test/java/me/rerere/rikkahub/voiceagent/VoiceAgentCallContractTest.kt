package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceAgentCallContractTest {
    @Test
    fun `foreground service contract is stable`() {
        assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
        assertEquals("conversationId", VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
        assertEquals("enableVoiceE2EArtifacts", VoiceAgentCallContract.EXTRA_ENABLE_VOICE_E2E_ARTIFACTS)
        assertEquals(2401, VoiceAgentCallContract.NOTIFICATION_ID)
    }

    @Test
    fun `notification route extra matches RouteActivity contract`() {
        assertEquals("voiceAgentConversationId", VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID)
    }

    @Test
    fun `end foreground id uses active conversation id`() {
        val activeConversationId = "11111111-1111-4111-8111-111111111111"

        assertEquals(activeConversationId, voiceAgentEndForegroundConversationId(activeConversationId))
    }

    @Test
    fun `end foreground id uses stable placeholder when idle`() {
        assertEquals("ending", voiceAgentEndForegroundConversationId(null))
    }
}
