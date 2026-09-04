package com.mcpintelligence.fr3k.integrations.opencode

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.protocol.AgentAskRequest
import com.mcpintelligence.fr3k.protocol.AgentProfile
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * "Ask OpenCode Zen" command. Mirrors [HermesAskCommand] but routes to
 * [OpenCodeZenProvider] which uses the public bearer key against
 * opencode.ai/zen/v1/chat/completions. Default model is [OpenCodeZenProvider.DEFAULT_MODEL].
 */
class AskOpenCodeCommand(
    private val provider: () -> OpenCodeZenProvider,
) : Fr3kCommand {

    override val id: String = "agent.ask.opencode"
    override val title: String = "Ask OpenCode Zen"
    override val description: String = "Send the prompt to an OpenCode Zen free model (no API key)"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.AGENT_ASK)
    override val keywords: Set<String> = setOf("ask", "ai", "opencode", "zen", "big-pickle", "free", "explain")
    override val pluginId: String = "fr3k.integrations.opencode"

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
                "model" to (response.model ?: model ?: OpenCodeZenProvider.DEFAULT_MODEL),
            ),
        )
    }
}
