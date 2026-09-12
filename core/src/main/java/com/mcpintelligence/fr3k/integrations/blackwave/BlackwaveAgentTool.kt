package com.mcpintelligence.fr3k.integrations.blackwave

import com.mcpintelligence.fr3k.core.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Read-only fleet queries through the same authenticated bridge used by the HUD. */
class BlackwaveAgentTool(private val bridge: BlackwaveBridgeClient) : AgentTool {
    override val id = "blackwave.status"
    override val displayName = "BLACKWAVE devices"
    override val description = "Read BLACKWAVE bridge state. action: fleet (list observed devices), device (deviceId), role (granted scopes). Cached or stale metadata is not proof a device is online. This tool cannot apply profiles, transmit, reboot, or install firmware."
    override val capability = "blackwave"
    override val requiresNetwork = true
    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult = withContext(Dispatchers.IO) {
        when (args.get("action") ?: "fleet") {
            "fleet" -> bridge.fetchFleetStatus().fold(
                { ToolResult.Success(BlackwaveBridgeClient.json.encodeToString(it)) },
                { ToolResult.Failure(it.message ?: "Fleet request failed", "blackwave.fleet") })
            "device" -> bridge.fetchDeviceStatus(args.require("deviceId")).fold(
                { ToolResult.Success(BlackwaveBridgeClient.json.encodeToString(it)) },
                { ToolResult.Failure(it.message ?: "Device request failed", "blackwave.device") })
            "role" -> bridge.fetchRole().fold(
                { ToolResult.Success(BlackwaveBridgeClient.json.encodeToString(it)) },
                { ToolResult.Failure(it.message ?: "Role request failed", "blackwave.role") })
            else -> ToolResult.Failure("Use fleet, device or role. Device control needs a separate authorized capability.", "blackwave.action")
        }
    }
}
