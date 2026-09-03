package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.AgentProfile

/**
 * The AI provider abstraction. Implementations route to Hermes, OpenRouter,
 * local Ollama, Termux-hosted models, LAN model servers, etc. The UI is
 * provider-agnostic; it asks the policy + router, the router picks one.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val capabilities: Set<String>
    val requiresNetwork: Boolean
    val requiresApiKey: Boolean

    suspend fun ask(request: AgentAskRequest): AgentAskResponse

    suspend fun health(): ProviderHealth
}

data class ProviderHealth(
    val online: Boolean,
    val latencyMs: Long? = null,
    val model: String? = null,
    val message: String? = null,
)

/**
 * Policy + routing layer. Picks the right [AiProvider] based on profile and
 * available capabilities. Pure-function logic; doesn't know about the network.
 */
class AiPolicy(
    private val providers: () -> List<AiProvider>,
    private val capabilityProvider: () -> Set<String>,
) {
    fun select(request: AgentAskRequest): AiProvider? {
        val available = providers().filter { p -> p.capabilities.all { it in capabilityProvider() } }
        return when (request.profile) {
            AgentProfile.PRIVATE, AgentProfile.OFFLINE ->
                available.firstOrNull { !it.requiresNetwork } ?: available.firstOrNull()
            AgentProfile.FAST -> available.minByOrNull { it.displayName.length } ?: available.firstOrNull()
            AgentProfile.CHEAP -> available.lastOrNull() ?: available.firstOrNull()
            AgentProfile.RESEARCH, AgentProfile.CODE, AgentProfile.NORMAL ->
                available.firstOrNull { it.requiresNetwork && it.id.contains("hermes", ignoreCase = true) }
                    ?: available.firstOrNull { it.requiresNetwork }
                    ?: available.firstOrNull()
        }
    }
}

/** Standard provider ids. Concrete adapters live in the integrations layer. */
object ProviderIds {
    const val HERMES = "hermes"
    const val LOCAL = "ai.local"
    const val OPENROUTER = "openrouter"
    const val DEEPSEEK = "deepseek"
    const val GROQ = "groq"
    const val AUTO = "auto"
}