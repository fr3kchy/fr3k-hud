package com.mcpintelligence.fr3k.integrations.hermes

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.Fr3kPlugin
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * Hermes integration plugin. Registers the Hermes AI provider and the
 * "Ask Hermes" command.
 */
class HermesPlugin(
    private val provider: HermesProvider,
    private val aiProviderRegistry: AiProviderRegistry,
    private val commandFactory: () -> HermesAskCommand,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.hermes"
    override val displayName: String = "Hermes"
    override val version: String = "0.1.0"

    override fun capabilities() = listOf(
        hermesCapability(),
        com.mcpintelligence.fr3k.protocol.Capability(
            id = Capabilities.AI_LOCAL_CHAT,
            displayName = "Hermes chat",
            description = "Hermes-backed chat capability",
            tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
        ),
    )

    override fun commands(): List<com.mcpintelligence.fr3k.core.Fr3kCommand> =
        listOf(commandFactory())

    override suspend fun start() {
        aiProviderRegistry.register(provider)
    }

    override suspend fun stop() {
        aiProviderRegistry.unregister(provider.id)
    }
}

/** Tiny DI-free registry for AI providers — kept separate to avoid coupling. */
class AiProviderRegistry {
    private val providers = LinkedHashMap<String, AiProvider>()
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<List<AiProvider>>(emptyList())
    val flow: kotlinx.coroutines.flow.StateFlow<List<AiProvider>> = _flow

    fun register(provider: AiProvider) {
        providers[provider.id] = provider
        _flow.value = providers.values.toList()
    }

    fun unregister(id: String) {
        providers.remove(id)
        _flow.value = providers.values.toList()
    }

    fun get(id: String): AiProvider? = providers[id]

    fun all(): List<AiProvider> = providers.values.toList()
}