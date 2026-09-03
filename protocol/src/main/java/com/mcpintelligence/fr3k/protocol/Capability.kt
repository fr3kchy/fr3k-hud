package com.mcpintelligence.fr3k.protocol

import kotlinx.serialization.Serializable

/**
 * Self-describing capability declaration. A FR3K node reports which capabilities it
 * currently advertises. The capability registry on each device owns the source of truth
 * — this struct is the wire form.
 */
@Serializable
data class Capability(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val tier: CapabilityTier = CapabilityTier.TIER_0,
    val requiredPermissions: List<String> = emptyList(),
    val experimental: Boolean = false,
    val version: Int = 1,
)

@Serializable
enum class CapabilityTier { TIER_0, TIER_1, TIER_2, TIER_3, TIER_4 }

/**
 * A signed device manifest exchanged between FR3K nodes for fleet discovery.
 *
 * Example JSON:
 * {
 *   "device_id": "fr3k-phone-01",
 *   "name": "Pixel 8 Pro",
 *   "platform": "android",
 *   "version": "0.1.0",
 *   "capabilities": [ {"id": "agent.ask", ...}, ... ],
 *   "transports": ["https", "websocket", "lan"],
 *   "status": "online",
 *   "last_seen": 1735862345123,
 *   "public_key": "...",
 *   "signature": "..."
 * }
 */
@Serializable
data class DeviceManifest(
    val deviceId: String,
    val name: String,
    val platform: String,
    val version: String,
    val capabilities: List<Capability> = emptyList(),
    val transports: List<String> = emptyList(),
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val lastSeen: Long = 0L,
    val publicKey: String? = null,
    val signature: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class DeviceStatus { ONLINE, DEGRADED, OFFLINE, UNKNOWN }

/**
 * Standardised capability identifiers used across the FR3K ecosystem.
 * New IDs must be namespaced (e.g. `ai.deepseek.chat`, `mesh.meshtastic.send`).
 */
object Capabilities {
    // Agent
    const val AGENT_ASK = "agent.ask"
    const val AGENT_RESEARCH = "agent.research"
    const val AGENT_CODE = "agent.code"
    const val AGENT_TRANSLATE = "agent.translate"
    const val AGENT_SUMMARISE = "agent.summarise"

    // Context
    const val CONTEXT_SELECTION = "context.selection"
    const val CONTEXT_URL = "context.url"
    const val CONTEXT_SCREEN = "context.screen"
    const val CONTEXT_NOTIFICATION = "context.notification"
    const val CONTEXT_CLIPBOARD = "context.clipboard"

    // Location
    const val LOCATION_CURRENT = "location.current"
    const val LOCATION_WAYPOINT = "location.waypoint"
    const val LOCATION_SHARE = "location.share"

    // Mesh
    const val MESH_SEND = "mesh.send"
    const val MESH_BROADCAST = "mesh.broadcast"
    const val MESH_NODES = "mesh.nodes"
    const val MESH_STATUS = "mesh.status"
    const val MESH_LOCATION = "mesh.location"

    // Meshtastic
    const val MESHTASTIC_SEND = "meshtastic.send"
    const val MESHTASTIC_NODES = "meshtastic.nodes"
    const val MESHTASTIC_POSITION = "meshtastic.position"

    // MeshCore
    const val MESHCORE_SEND = "meshcore.send"
    const val MESHCORE_CONTACTS = "meshcore.contacts"
    const val MESHCORE_STATUS = "meshcore.status"

    // Termux / SSH
    const val TERMUX_JOB = "termux.job"
    const val TERMUX_SCRIPT = "termux.script"
    const val TERMUX_SSH = "termux.ssh"

    // Devices / fleet
    const val DEVICE_LIST = "device.list"
    const val DEVICE_STATUS = "device.status"
    const val DEVICE_OPEN = "device.open"
    const val DEVICE_SEND = "device.send"
    const val DEVICE_COMMAND = "device.command"

    // Browser / URLs
    const val BROWSER_CURRENT_URL = "browser.current_url"
    const val BROWSER_CLEAN_URL = "browser.clean_url"

    // Share / clipboard
    const val SHARE_TEXT = "share.text"
    const val SHARE_URL = "share.url"
    const val SHARE_FILE = "share.file"

    // System
    const val SYSTEM_BATTERY = "system.battery"
    const val SYSTEM_NETWORK = "system.network"
    const val SYSTEM_BLUETOOTH = "system.bluetooth"
    const val SYSTEM_STORAGE = "system.storage"

    // AI provider routing
    const val AI_LOCAL_CHAT = "ai.local.chat"
    const val AI_LOCAL_VISION = "ai.local.vision"
    const val AI_LOCAL_EMBED = "ai.local.embedding"
}