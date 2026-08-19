package me.rerere.rikkahub.voiceagent.recovery

import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.voiceagent.VoiceAgentConfigResolver
import me.rerere.rikkahub.voiceagent.VoiceAgentConfigResult
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceApi
import me.rerere.rikkahub.voiceagent.hermesvoice.HermesVoiceHttpTransport
import me.rerere.rikkahub.voiceagent.telemetry.sha256Hex
import me.rerere.rikkahub.voiceagent.toHermesVoiceBaseUrl

internal data class ResolvedHermesRecoveryEndpoint(
    val endpointBindingHash: String,
    val remote: HermesRecoveryRemote,
)

internal fun hermesEndpointBindingHash(normalizedBaseUrl: String): String =
    recoverySha256(
        listOf(
            HERMES_RECOVERY_PROTOCOL,
            normalizedBaseUrl.toHermesVoiceBaseUrl(),
            HERMES_SERVER_DEFAULT_PROFILE,
        ).joinToString("\n")
    )

internal fun recoverySha256(value: String): String = sha256Hex(value)

internal open class HermesRecoveryEndpointResolver(
    private val settingsStore: SettingsStore? = null,
    private val configResolver: VoiceAgentConfigResolver = VoiceAgentConfigResolver(),
    private val transport: HermesVoiceHttpTransport? = null,
) {
    open suspend fun resolve(conversation: Conversation): ResolvedHermesRecoveryEndpoint? {
        val settings = settingsStore?.settingsFlow?.first() ?: return null
        return resolve(settings = settings, conversation = conversation)
    }

    open fun resolve(settings: Settings, conversation: Conversation): ResolvedHermesRecoveryEndpoint? {
        val configResult = configResolver.resolve(settings = settings, conversation = conversation)
        if (configResult !is VoiceAgentConfigResult.Available) {
            return null
        }
        val config = configResult.config
        val bindingHash = hermesEndpointBindingHash(config.hermesVoiceBaseUrl)
        val api = if (transport != null) {
            HermesVoiceApi(
                baseUrl = config.hermesVoiceBaseUrl,
                credentials = config.credentials,
                transport = transport,
            )
        } else {
            HermesVoiceApi(
                baseUrl = config.hermesVoiceBaseUrl,
                credentials = config.credentials,
            )
        }
        return ResolvedHermesRecoveryEndpoint(
            endpointBindingHash = bindingHash,
            remote = HermesRecoveryRemote(api),
        )
    }
}
