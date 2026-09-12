package com.mcpintelligence.fr3k.integrations.openrouter

import com.mcpintelligence.fr3k.core.Fr3kPlugin
import com.mcpintelligence.fr3k.integrations.hermes.AiProviderRegistry
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * OpenRouter integration plugin. Registers the [OpenRouterProvider] (API-key
 * backed, default free-model routing) and the [AskOpenRouterCommand], so
 * OpenRouter appears alongside Hermes / OpenCode Zen in the provider registry.
 */
class OpenRouterPlugin(
    private val provider: OpenRouterProvider,
    private val aiProviderRegistry: AiProviderRegistry,
    private val commandFactory: () -> AskOpenRouterCommand,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.openrouter"
    override val displayName: String = "OpenRouter"
    override val version: String = "0.1.0"

    override fun capabilities() = listOf(
        Capability(
            id = Capabilities.AI_LOCAL_CHAT,
            displayName = "OpenRouter chat",
            description = "Cloud chat via OpenRouter (free-routed models, API key in secure store)",
            tier = CapabilityTier.TIER_0,
        ),
    )

    override fun commands() = listOf(commandFactory())

    override suspend fun start() {
        aiProviderRegistry.register(provider)
        // Warm the free-model list in the background; failures are non-fatal
        // (the provider still asks against its default model).
        runCatching { provider.refreshFreeModels() }
    }

    override suspend fun stop() {
        aiProviderRegistry.unregister(provider.id)
    }
}