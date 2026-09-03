package com.mcpintelligence.fr3k.integrations.hermes

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentContext
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * The universal "Ask about this" command. Wires the current share/context into
 * Hermes and returns the response. Works for text, URL, screenshot, and explicit
 * user prompts.
 */
class HermesAskCommand(
    private val provider: () -> HermesProvider,
) : Fr3kCommand {

    override val id: String = "agent.ask.hermes"
    override val title: String = "Ask Hermes"
    override val description: String = "Send the current context to Hermes"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val keywords: Set<String> = setOf("ask", "ai", "explain", "diagnose", "research")
    override val pluginId: String = "fr3k.integrations.hermes"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val prompt = args["prompt"]
            ?: context.selectedText
            ?: context.fullText
            ?: context.currentUrl
            ?: "Describe the current context."
        val request = AgentAskRequest(
            prompt = prompt,
            context = context.asAgentContext(),
            profile = com.mcpintelligence.fr3k.protocol.AgentProfile.NORMAL,
        )
        val response = provider().ask(request)
        return CommandResult.Ok(
            message = response.text,
            data = mapOf("format" to response.format.name),
        )
    }
}