package com.mcpintelligence.fr3k.integrations.opencode

import com.mcpintelligence.fr3k.core.Fr3kPlugin
import com.mcpintelligence.fr3k.integrations.hermes.AiProviderRegistry
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * OpenCode Zen integration plugin. Registers the [OpenCodeZenProvider]
 * (no API key, free models) and the [AskOpenCodeCommand]. Wakes the model
 * registry once on start so the integrations panel and the chat overlay
 * can render the live free-model list immediately.
 */
class OpenCodePlugin(
    private val provider: OpenCodeZenProvider,
    private val aiProviderRegistry: AiProviderRegistry,
    private val commandFactory: () -> AskOpenCodeCommand,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.opencode"
    override val displayName: String = "OpenCode Zen"
    override val version: String = "0.1.0"

    override fun capabilities() = listOf(
        Capability(
            id = Capabilities.AI_LOCAL_CHAT,
            displayName = "OpenCode Zen chat",
            description = "Hosted free-model chat via opencode.ai/zen (Big Pickle default)",
            tier = CapabilityTier.TIER_0,
        ),
    )

    override fun commands() = listOf(commandFactory())

    override suspend fun start() {
        aiProviderRegistry.register(provider)
        // Warm the model registry in the background; failures are non-fatal
        // (the chat overlay falls back to the default model).
        runCatching { provider.refreshFreeModels() }
    }

    override suspend fun stop() {
        aiProviderRegistry.unregister(provider.id)
    }
}
