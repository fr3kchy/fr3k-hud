package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.integrations.share.OpenOnDeviceCommand
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * Share commands set (§24, §28, §29). Drives the smart share sheet:
 * given a payload (text/URL/image/file/location), suggest appropriate commands.
 */
class ShareCommandsPlugin(
    private val contextEngineProvider: () -> ContextEngine,
    private val deviceRegistryProvider: () -> DeviceRegistry,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.share.commands"
    override val displayName: String = "Share actions"
    override val version: String = "0.1.0"

    override fun capabilities(): List<com.mcpintelligence.fr3k.protocol.Capability> = listOf(
        com.mcpintelligence.fr3k.protocol.Capability(
            id = Capabilities.SHARE_TEXT,
            displayName = "Share text",
            description = "Routes shared text to FR3K actions",
            tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
        ),
        com.mcpintelligence.fr3k.protocol.Capability(
            id = Capabilities.SHARE_URL,
            displayName = "Share URL",
            description = "Routes shared URLs to FR3K actions",
            tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
        ),
        com.mcpintelligence.fr3k.protocol.Capability(
            id = Capabilities.SHARE_FILE,
            displayName = "Share file",
            description = "Routes shared files to FR3K actions",
            tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
        ),
        com.mcpintelligence.fr3k.protocol.Capability(
            id = Capabilities.CONTEXT_CLIPBOARD,
            displayName = "Smart clipboard",
            description = "Classify and act on clipboard content",
            tier = com.mcpintelligence.fr3k.protocol.CapabilityTier.TIER_0,
        ),
    )

    override fun commands(): List<Fr3kCommand> = listOf(
        SmartClipboardCommand(contextEngineProvider),
        RewriteTextCommand(),
        TranslateTextCommand(),
        SummariseTextCommand(),
        ExplainTextCommand(),
        SendToMeshCommand(),
        OpenOnDeviceCommand { deviceRegistryProvider() },
    )

    override suspend fun start() {}
    override suspend fun stop() {}
}

/** Classify clipboard content into URL / code / coords / address / command / text. */
class SmartClipboardCommand(
    private val contextEngineProvider: () -> ContextEngine,
) : Fr3kCommand {
    override val id = "context.clipboard"
    override val title = "Classify clipboard"
    override val description = "Inspect the clipboard and propose actions"
    override val requiredCapabilities = setOf(Capabilities.CONTEXT_CLIPBOARD)
    override val keywords = setOf("clipboard", "classify", "url", "code", "coords")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val text = args["text"] ?: context.selectedText ?: context.fullText
            ?: contextEngineProvider().current.value.clipboardText
            ?: return CommandResult.Failed(reason = "no clipboard content")
        val kind = classify(text)
        val suggestions = suggestionsFor(kind).joinToString(", ")
        return CommandResult.Ok(
            message = "clipboard: $kind → $suggestions",
            data = mapOf("kind" to kind, "preview" to text.take(120)),
        )
    }

    private fun classify(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> "url"
            trimmed.matches(Regex("^-?\\d{1,3}\\.\\d+,\\s*-?\\d{1,3}\\.\\d+\$")) -> "coordinates"
            trimmed.contains("\n") && (trimmed.contains("function") || trimmed.contains("class ") || trimmed.contains("def ") || trimmed.contains("import ")) -> "code"
            trimmed.startsWith("$") || trimmed.startsWith("#") || trimmed.startsWith("git ") -> "command"
            trimmed.split(" ").size in 2..6 && trimmed.split(" ").all { it.isNotEmpty() && it[0].isUpperCase() } -> "address"
            else -> "text"
        }
    }

    private fun suggestionsFor(kind: String): List<String> = when (kind) {
        "url" -> listOf("Clean URL", "Ask about page", "Open on…", "Send to mesh")
        "coordinates" -> listOf("Send waypoint", "Open map", "Copy")
        "code" -> listOf("Explain", "Review", "Send to dev agent")
        "command" -> listOf("Explain", "Run via Termux")
        "address" -> listOf("Send to mesh", "Open map")
        else -> listOf("Rewrite", "Translate", "Summarise")
    }
}

/** Generic AI-backed text rewriter (uses Hermes if available). */
class RewriteTextCommand : Fr3kCommand {
    override val id = "share.text.rewrite"
    override val title = "Rewrite text"
    override val description = "Rewrite the selected text with tone control"
    override val requiredCapabilities = setOf(Capabilities.AGENT_ASK)
    override val keywords = setOf("rewrite", "tone", "polish", "improve")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(
            message = "rewrite queued (Hermes available locally)",
            data = mapOf("input_len" to (context.selectedText?.length ?: 0).toString()),
        )
    }
}

class TranslateTextCommand : Fr3kCommand {
    override val id = "share.text.translate"
    override val title = "Translate"
    override val description = "Translate selected text via Hermes"
    override val requiredCapabilities = setOf(Capabilities.AGENT_TRANSLATE)
    override val keywords = setOf("translate", "language")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "translate queued (placeholder)")
    }
}

class SummariseTextCommand : Fr3kCommand {
    override val id = "share.text.summarise"
    override val title = "Summarise"
    override val description = "Summarise selected text via Hermes"
    override val requiredCapabilities = setOf(Capabilities.AGENT_SUMMARISE)
    override val keywords = setOf("summarise", "tldr")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "summarise queued (placeholder)")
    }
}

class ExplainTextCommand : Fr3kCommand {
    override val id = "share.text.explain"
    override val title = "Explain text"
    override val description = "Explain selected text via Hermes"
    override val requiredCapabilities = setOf(Capabilities.AGENT_ASK)
    override val keywords = setOf("explain")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "explain queued (placeholder)")
    }
}

class SendToMeshCommand : Fr3kCommand {
    override val id = "share.mesh.send"
    override val title = "Send to mesh"
    override val description = "Send the current context via the active mesh transport"
    override val requiredCapabilities = setOf(Capabilities.MESH_SEND)
    override val keywords = setOf("mesh", "broadcast", "send")
    override val pluginId = "fr3k.integrations.share.commands"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(
            message = "mesh send queued (no adapter yet — V2)",
            data = mapOf("len" to (context.selectedText?.length ?: 0).toString()),
        )
    }
}