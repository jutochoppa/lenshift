package dev.lenshift.app.ai

import dev.lenshift.app.ai.adapters.AnthropicAdapter
import dev.lenshift.app.ai.adapters.GeminiAdapter
import dev.lenshift.app.ai.adapters.OllamaAdapter
import dev.lenshift.app.ai.adapters.OpenAiChatAdapter
import dev.lenshift.app.ai.adapters.OpenAiResponsesAdapter
import dev.lenshift.app.data.settings.SettingsRepository

/**
 * Resolves a [ProviderProfile] to the adapter that speaks its protocol, and layers the
 * user's explicit capability overrides on top of inference.
 */
class ProviderRegistry(private val settings: SettingsRepository) {

    private val adapters: Map<ApiProtocol, ProviderAdapter> = mapOf(
        ApiProtocol.OPENAI_CHAT to OpenAiChatAdapter(),
        ApiProtocol.OPENAI_RESPONSES to OpenAiResponsesAdapter(),
        ApiProtocol.ANTHROPIC_MESSAGES to AnthropicAdapter(),
        ApiProtocol.GEMINI to GeminiAdapter(),
        ApiProtocol.OLLAMA to OllamaAdapter()
    )

    fun adapterFor(protocol: ApiProtocol): ProviderAdapter =
        adapters[protocol] ?: adapters.getValue(ApiProtocol.OPENAI_CHAT)

    fun adapterFor(profile: ProviderProfile): ProviderAdapter = adapterFor(profile.protocol)

    /**
     * Effective capabilities for a profile's current model: the user's override, then the
     * provider-reported spec, then documented values, then the name-based guess. A
     * capability of UNKNOWN means "do not send the parameter".
     */
    fun capabilitiesFor(profile: ProviderProfile): ModelCapabilities =
        CapabilityInference.forProfile(profile)

    /** Convenience for the active profile, or null when nothing is configured. */
    fun active(): ActiveProvider? {
        val profile = settings.activeProfile() ?: return null
        return ActiveProvider(
            profile = profile,
            adapter = adapterFor(profile),
            capabilities = capabilitiesFor(profile),
            apiKey = settings.apiKey(profile.id)
        )
    }

    fun activeOrNull(): ActiveProvider? = active()

    data class ActiveProvider(
        val profile: ProviderProfile,
        val adapter: ProviderAdapter,
        val capabilities: ModelCapabilities,
        val apiKey: String?
    )
}
