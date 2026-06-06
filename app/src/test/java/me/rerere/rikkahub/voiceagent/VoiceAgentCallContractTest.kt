package me.rerere.rikkahub.voiceagent

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class VoiceAgentCallContractTest {
    @Test
    fun `call actions are stable`() {
        val conversationId = Uuid.parse("11111111-1111-4111-8111-111111111111")

        assertEquals("11111111-1111-4111-8111-111111111111", conversationId.toString())
        assertEquals("me.rerere.rikkahub.voiceagent.action.START", VoiceAgentCallContract.ACTION_START)
        assertEquals("me.rerere.rikkahub.voiceagent.action.END", VoiceAgentCallContract.ACTION_END)
        assertEquals("conversationId", VoiceAgentCallContract.EXTRA_CONVERSATION_ID)
    }

    @Test
    fun `notification route extra matches RouteActivity contract`() {
        assertEquals("voiceAgentConversationId", VoiceAgentCallContract.EXTRA_ROUTE_VOICE_AGENT_CONVERSATION_ID)
    }
}
