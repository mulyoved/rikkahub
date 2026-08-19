package me.rerere.rikkahub.voiceagent.recovery

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentConfigResolver.Companion.CLOUDFLARE_ACCESS_CLIENT_ID_HEADER
import me.rerere.rikkahub.voiceagent.VoiceAgentConfigResolver.Companion.CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER
import me.rerere.rikkahub.voiceagent.VoiceAgentConfigResolver.Companion.HERMES_VOICE_BASE_URL_HEADER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class HermesRecoveryEndpointResolverTest {
    @Test
    fun `canonical hash input is protocol newline baseUrl newline profile`() {
        val hash = hermesEndpointBindingHash("https://voice.example.test")
        // livekit-recovery-v1\nhttps://voice.example.test\nserver-default
        assertEquals("274fef194f57818c2b532f22f609188612003b943b3ddd82f0e0b9c62a2ad9ba", hash)
    }

    @Test
    fun `URL path and trailing slash normalization matches VoiceAgentConfigResolver`() {
        val expectedHash = hermesEndpointBindingHash("https://voice.example.test")

        listOf(
            "https://voice.example.test",
            "https://voice.example.test/",
            "https://voice.example.test/v1",
            "https://voice.example.test/v1/",
            "https://voice.example.test/openai/v1",
            "https://voice.example.test/openai/v1/",
            "https://voice.example.test/api/openai/v1",
            "https://voice.example.test/api/mobile",
            "https://voice.example.test/api/mobile/",
        ).forEach { url ->
            assertEquals("Mismatch for URL: $url", expectedHash, hermesEndpointBindingHash(url))
        }
    }

    @Test
    fun `endpoint binding hash does not include API keys, Cloudflare credentials, or query fragment`() {
        val plainHash = hermesEndpointBindingHash("https://voice.example.test")

        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
            customHeaders = listOf(
                CustomHeader(CLOUDFLARE_ACCESS_CLIENT_ID_HEADER, "cf-client-id"),
                CustomHeader(CLOUDFLARE_ACCESS_CLIENT_SECRET_HEADER, "cf-client-secret"),
            ),
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    name = "Hermes",
                    chatModelId = modelId,
                )
            ),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    name = "RMS Hermes",
                    apiKey = "secret-device-api-key",
                    baseUrl = "https://voice.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val resolver = HermesRecoveryEndpointResolver()
        val resolved = resolver.resolve(settings, conversation)

        assertNotNull(resolved)
        assertEquals(plainHash, resolved?.endpointBindingHash)
    }

    @Test
    fun `resolve produces ResolvedHermesRecoveryEndpoint with expected hash and working remote`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    name = "Hermes",
                    chatModelId = modelId,
                )
            ),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    name = "RMS Hermes",
                    apiKey = "device-key",
                    baseUrl = "https://voice.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val resolver = HermesRecoveryEndpointResolver()
        val resolved = resolver.resolve(settings, conversation)

        assertNotNull(resolved)
        val endpoint = requireNotNull(resolved)
        assertEquals("274fef194f57818c2b532f22f609188612003b943b3ddd82f0e0b9c62a2ad9ba", endpoint.endpointBindingHash)
        assertNotNull(endpoint.remote)
    }

    @Test
    fun `resolve returns null when provider is missing or non-OpenAI`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val nonOpenAiSettings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    name = "Hermes",
                    chatModelId = modelId,
                )
            ),
            providers = listOf(
                ProviderSetting.Google(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
                    name = "Google Gemini",
                    apiKey = "gemini-key",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val resolver = HermesRecoveryEndpointResolver()
        assertNull(resolver.resolve(nonOpenAiSettings, conversation))

        val missingProviderSettings = nonOpenAiSettings.copy(providers = emptyList())
        assertNull(resolver.resolve(missingProviderSettings, conversation))
    }

    @Test
    fun `resolve returns null when API key is blank`() {
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "hermes-agent",
            displayName = "Hermes Agent",
        )
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = modelId,
            assistants = listOf(
                Assistant(
                    id = assistantId,
                    name = "Hermes",
                    chatModelId = modelId,
                )
            ),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    name = "RMS Hermes",
                    apiKey = "   ",
                    baseUrl = "https://voice.example.test/v1",
                    models = listOf(model),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val resolver = HermesRecoveryEndpointResolver()
        assertNull(resolver.resolve(settings, conversation))
    }

    @Test
    fun `resolve returns null when assistant or model is missing`() {
        val assistantId = Uuid.random()
        val settings = Settings(
            assistantId = assistantId,
            chatModelId = Uuid.random(),
            assistants = emptyList(),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                    name = "RMS Hermes",
                    apiKey = "device-key",
                    baseUrl = "https://voice.example.test/v1",
                    models = emptyList(),
                )
            ),
        )
        val conversation = Conversation.ofId(id = Uuid.random(), assistantId = assistantId)

        val resolver = HermesRecoveryEndpointResolver()
        assertNull(resolver.resolve(settings, conversation))
    }
}
