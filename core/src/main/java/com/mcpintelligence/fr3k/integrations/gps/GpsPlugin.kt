package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * GPS command set (§25). Commands declare capability requirements; the registry
 * filters them out when the location plugin isn't running. The plugin registers
 * the capabilities at start.
 */
class GpsPlugin : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.gps"
    override val displayName: String = "GPS"
    override val version: String = "0.1.0"

    override fun capabilities(): List<Capability> = listOf(
        Capability(
            id = Capabilities.LOCATION_CURRENT,
            displayName = "Current location",
            description = "Read the current GPS fix",
            tier = CapabilityTier.TIER_1,
            requiredPermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
        ),
        Capability(
            id = Capabilities.LOCATION_WAYPOINT,
            displayName = "Send waypoint",
            description = "Send the current fix as a waypoint",
            tier = CapabilityTier.TIER_1,
        ),
        Capability(
            id = Capabilities.LOCATION_SHARE,
            displayName = "Share location",
            description = "Share current location via the share sheet",
            tier = CapabilityTier.TIER_1,
        ),
    )

    override fun commands(): List<Fr3kCommand> = listOf(
        CopyLocationCommand(),
        WaypointCommand(),
        ShareLocationCommand(),
    )

    override suspend fun start() {}
    override suspend fun stop() {}
}

/** Copy current coords to clipboard. V1 returns a synthetic string from context. */
class CopyLocationCommand : Fr3kCommand {
    override val id = "location.copy"
    override val title = "Copy coordinates"
    override val description = "Copy current lat/lon as text"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_CURRENT)
    override val keywords = setOf("copy", "coords", "latlon", "gps")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        // V1 returns the canonical text; real values are injected by the GPS plugin in V2.
        val text = args["text"] ?: "GPS placeholder — no fix yet"
        return CommandResult.Ok(message = "copied: $text", data = mapOf("clipboard" to text))
    }
}

/** Send a waypoint via the active transport (mesh first, else share). */
class WaypointCommand : Fr3kCommand {
    override val id = "location.waypoint"
    override val title = "Send waypoint"
    override val description = "Dispatch current fix as a waypoint"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_WAYPOINT)
    override val keywords = setOf("waypoint", "gps", "send")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(
            message = "waypoint queued (placeholder)",
            data = mapOf("payload" to (args["text"] ?: "no fix")),
        )
    }
}

/** Share location via the system share sheet. */
class ShareLocationCommand : Fr3kCommand {
    override val id = "location.share"
    override val title = "Share location"
    override val description = "Share current location via share sheet"
    override val requiredCapabilities = setOf(Capabilities.LOCATION_SHARE)
    override val keywords = setOf("share", "gps", "location")
    override val pluginId = "fr3k.integrations.gps"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "share sheet opened (placeholder)")
    }
}