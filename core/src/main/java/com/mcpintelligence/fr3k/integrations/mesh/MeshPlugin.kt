package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * Mesh command set (§21). V2 wires the real adapters (MeshCore / Meshtastic /
 * Reticulum); V1 ships the command contracts so the palette shows them only
 * when an adapter registers them.
 */
class MeshPlugin : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.mesh"
    override val displayName: String = "Mesh"
    override val version: String = "0.1.0"

    override fun capabilities(): List<Capability> = listOf(
        Capability(id = Capabilities.MESH_SEND, displayName = "Mesh send", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESH_BROADCAST, displayName = "Mesh broadcast", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESH_NODES, displayName = "Mesh nodes", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESH_STATUS, displayName = "Mesh status", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESH_LOCATION, displayName = "Mesh location", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHTASTIC_SEND, displayName = "Meshtastic send", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHTASTIC_NODES, displayName = "Meshtastic nodes", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHTASTIC_POSITION, displayName = "Meshtastic position", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHCORE_SEND, displayName = "MeshCore send", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHCORE_CONTACTS, displayName = "MeshCore contacts", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.MESHCORE_STATUS, displayName = "MeshCore status", tier = CapabilityTier.TIER_0),
    )

    override fun commands(): List<Fr3kCommand> = listOf(
        MeshSendCommand(),
        MeshStatusCommand(),
        MeshNodesCommand(),
        MeshLocationCommand(),
    )

    override suspend fun start() {}
    override suspend fun stop() {}
}

class MeshSendCommand : Fr3kCommand {
    override val id = "mesh.send"
    override val title = "Send via mesh"
    override val description = "Send content via the active mesh adapter"
    override val requiredCapabilities = setOf(Capabilities.MESH_SEND)
    override val keywords = setOf("mesh", "broadcast", "send", "lorawan", "meshtastic", "meshcore")
    override val pluginId = "fr3k.integrations.mesh"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "mesh send queued (V2 will route via active adapter)")
    }
}

class MeshStatusCommand : Fr3kCommand {
    override val id = "mesh.status"
    override val title = "Mesh status"
    override val description = "Show mesh adapter status"
    override val requiredCapabilities = setOf(Capabilities.MESH_STATUS)
    override val keywords = setOf("status", "online", "offline")
    override val pluginId = "fr3k.integrations.mesh"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "mesh: no adapter registered yet (V2)")
    }
}

class MeshNodesCommand : Fr3kCommand {
    override val id = "mesh.nodes"
    override val title = "Mesh nodes"
    override val description = "List reachable mesh nodes"
    override val requiredCapabilities = setOf(Capabilities.MESH_NODES)
    override val keywords = setOf("nodes", "peers")
    override val pluginId = "fr3k.integrations.mesh"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "0 nodes (V2)")
    }
}

class MeshLocationCommand : Fr3kCommand {
    override val id = "mesh.location"
    override val title = "Share location via mesh"
    override val description = "Send current fix via mesh"
    override val requiredCapabilities = setOf(Capabilities.MESH_LOCATION)
    override val keywords = setOf("location", "mesh", "waypoint")
    override val pluginId = "fr3k.integrations.mesh"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "location queued (V2)")
    }
}