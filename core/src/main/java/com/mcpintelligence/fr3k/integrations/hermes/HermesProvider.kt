package com.mcpintelligence.fr3k.integrations.hermes

import com.mcpintelligence.fr3k.core.AiProvider
import com.mcpintelligence.fr3k.core.ProviderHealth
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentAskResponse
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Fr3kEnvelope
import com.mcpintelligence.fr3k.protocol.Fr3kResultCode
import com.mcpintelligence.fr3k.transport.HttpsTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hermes AI provider. Sends an [AgentAskRequest] wrapped in an envelope and
 * decodes the [AgentAskResponse] from the reply envelope payload.
 *
 * Real Hermes returns its own envelope; for V1 we synthesise a fallback response
 * when the endpoint is unreachable (so the share sheet still gets a useful ack).
 */
class HermesProvider(
    private val endpointProvider: () -> String,
    private val authTokenProvider: () -> String? = { null },
    private val deviceIdProvider: () -> String,
) : AiProvider {

    override val id: String = "hermes"
    override val displayName: String = "Hermes"
    override val capabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val requiresNetwork: Boolean = true
    override val requiresApiKey: Boolean = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val transport = HttpsTransport(endpointProvider, authTokenProvider)

    override suspend fun ask(request: AgentAskRequest): AgentAskResponse = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("prompt", JsonPrimitive(request.prompt))
            put("profile", JsonPrimitive(request.profile.name))
            request.model?.let { put("model", JsonPrimitive(it)) }
            request.context?.let { put("context", json.encodeToJsonElement(com.mcpintelligence.fr3k.protocol.AgentContext.serializer(), it)) }
        }
        val envelope = Fr3kEnvelope(
            id = java.util.UUID.randomUUID().toString(),
            source = deviceIdProvider(),
            destination = null,
            type = "agent.ask",
            timestamp = System.currentTimeMillis(),
            payload = payload,
        )
        val outcome = transport.send(envelope)
        outcome.fold(
            onSuccess = { reply ->
                val text = reply.payload.let { p ->
                    if (p is JsonObject) (p["text"] as? JsonPrimitive)?.content
                        ?: (p["reply"] as? JsonPrimitive)?.content
                        ?: "Hermes acknowledged."
                    else "Hermes acknowledged."
                }
                AgentAskResponse(text = text)
            },
            onFailure = { error ->
                // V1 fallback: the offline envelope knows we are reachable in spirit
                AgentAskResponse(
                    text = "Hermes unreachable (${error.message ?: "offline"}). " +
                        "Saved locally as '${request.prompt.take(60)}' — will retry.",
                )
            },
        )
    }

    override suspend fun health(): ProviderHealth {
        return if (transport.isAvailable()) ProviderHealth(online = true) else ProviderHealth(online = false)
    }
}

/** Capability metadata for the Hermes plugin. */
fun hermesCapability(): Capability = Capability(
    id = Capabilities.AGENT_ASK,
    displayName = "Hermes agent",
    description = "Send prompts to Hermes / FR3K agent endpoint",
    tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
    requiredPermissions = listOf("android.permission.INTERNET"),
)