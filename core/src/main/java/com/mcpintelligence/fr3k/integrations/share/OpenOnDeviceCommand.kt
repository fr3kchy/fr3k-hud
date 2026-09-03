package com.mcpintelligence.fr3k.integrations.share

import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.DeviceManifest
import com.mcpintelligence.fr3k.protocol.DeviceStatus

/**
 * "Open on..." device handoff (§19). Looks up the named FR3K device, formats
 * the current URL/text/command into the target's preferred representation,
 * then returns a structured payload describing what would be sent.
 *
 * The actual delivery is performed by an adapter matching the destination's
 * declared transports. For V1 the command emits a "ready to dispatch"
 * CommandResult; the dispatch happens via ShareReceiverActivity.
 */
class OpenOnDeviceCommand(
    private val deviceRegistryProvider: () -> com.mcpintelligence.fr3k.core.DeviceRegistry,
) : Fr3kCommand {

    override val id: String = "device.open"
    override val title: String = "Open on…"
    override val description: String = "Hand off the current URL or text to another FR3K device"
    override val requiredCapabilities: Set<String> = setOf(Capabilities.DEVICE_OPEN)
    override val keywords: Set<String> = setOf("handoff", "send", "open", "device")
    override val pluginId: String = "fr3k.integrations.share"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val targetId = args["deviceId"]
            ?: return CommandResult.Failed(reason = "no deviceId provided")
        val registry = deviceRegistryProvider()
        val manifest = registry.snapshot.value.firstOrNull { it.deviceId == targetId }
            ?: return CommandResult.Failed(reason = "device $targetId not in fleet")
        if (manifest.status == DeviceStatus.OFFLINE) {
            return CommandResult.Failed(reason = "device $targetId is offline")
        }
        val url = context.currentUrl
        val text = context.selectedText ?: context.fullText
        return CommandResult.Ok(
            message = "Ready to send to ${manifest.name}",
            data = mapOf(
                "deviceId" to manifest.deviceId,
                "deviceName" to manifest.name,
                "url" to (url ?: ""),
                "text" to (text ?: ""),
                "transports" to manifest.transports.joinToString(","),
            ),
        )
    }
}