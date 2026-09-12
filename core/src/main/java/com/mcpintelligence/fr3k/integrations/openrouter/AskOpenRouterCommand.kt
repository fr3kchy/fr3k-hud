package com.mcpintelligence.fr3k.integrations.openrouter

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentProfile
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * "Ask OpenRouter" command. Mirrors [com.mcpintelligence.fr3k.integrations.opencode.AskOpenCodeCommand]
 * but routes to [OpenRouterProvider] with the user's stored API key. Default
 * model is the free-routed [OpenRouterProvider.DEFAULT_MODEL].
 */
class AskOpenRouterCommand(
    private val provider: () -> OpenRouterProvider,
) : Fr3kCommand {

    override val id: String = "agent.ask.openrouter"
    override val title: String = "Ask OpenRouter"
    override val description: String = "Send the prompt to an OpenRouter model using the stored API key"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val keywords: Set<String> = setOf("ask", "ai", "openrouter", "free", "llama", "explain")
    override val pluginId: String = "fr3k.integrations.openrouter"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val prompt = args["prompt"]
            ?: context.selectedText
            ?: context.fullText
            ?: context.currentUrl
            ?: "Describe the current context."
        val model = args["model"]?.takeIf { it.isNotBlank() }
        val request = AgentAskRequest(
            prompt = prompt,
            model = model,
            context = context.asAgentContext(),
            profile = AgentProfile.NORMAL,
        )
        val response = provider().ask(request)
        return CommandResult.Ok(
            message = response.text,
            data = mapOf(
                "format" to response.format.name,
                "model" to (response.model ?: model ?: OpenRouterProvider.DEFAULT_MODEL),
            ),
        )
    }
}