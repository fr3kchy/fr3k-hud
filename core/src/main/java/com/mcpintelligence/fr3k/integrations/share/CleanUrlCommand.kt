package com.mcpintelligence.fr3k.integrations.share

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.core.UrlSanitiser
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * Strips tracking parameters from the current URL (§27).
 * Conservative default — only ever removes the well-known set, never
 * a domain-functional parameter.
 */
class CleanUrlCommand : Fr3kCommand {
    override val id: String = "share.url.clean"
    override val title: String = "Clean URL"
    override val description: String = "Remove known tracking parameters from the current URL"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.BROWSER_CLEAN_URL)
    override val keywords: Set<String> = setOf("url", "clean", "strip", "utm", "tracking")
    override val pluginId: String = "fr3k.integrations.share"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val url = args["url"] ?: context.currentUrl
            ?: return CommandResult.Failed(reason = "no URL provided")
        val out = UrlSanitiser().clean(url)
        return CommandResult.Ok(
            message = out.clean,
            data = mapOf(
                "removed" to out.removed.joinToString(","),
                "changed" to out.changed.toString(),
            ),
        )
    }
}