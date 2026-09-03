package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier

/**
 * System commands (§25, §49) — read-only queries over Android state. Cheap
 * to run, useful in dashboards and the command palette.
 */
class SystemPlugin : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.system"
    override val displayName: String = "System"
    override val version: String = "0.1.0"

    override fun capabilities(): List<Capability> = listOf(
        Capability(id = Capabilities.SYSTEM_BATTERY, displayName = "Battery status", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.SYSTEM_NETWORK, displayName = "Network status", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.SYSTEM_BLUETOOTH, displayName = "Bluetooth status", tier = CapabilityTier.TIER_0),
        Capability(id = Capabilities.SYSTEM_STORAGE, displayName = "Storage status", tier = CapabilityTier.TIER_0),
    )

    override fun commands(): List<Fr3kCommand> = listOf(
        BatteryStatusCommand(),
        NetworkStatusCommand(),
        BluetoothStatusCommand(),
        StorageStatusCommand(),
    )

    override suspend fun start() {}
    override suspend fun stop() {}
}

class BatteryStatusCommand : Fr3kCommand {
    override val id = "system.battery"
    override val title = "Battery status"
    override val description = "Show battery level, charging state, temperature"
    override val requiredCapabilities = setOf(Capabilities.SYSTEM_BATTERY)
    override val keywords = setOf("battery", "power", "charge")
    override val pluginId = "fr3k.integrations.system"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "battery: see Diagnostics activity")
    }
}

class NetworkStatusCommand : Fr3kCommand {
    override val id = "system.network"
    override val title = "Network status"
    override val description = "Show Wi-Fi / mobile / connectivity"
    override val requiredCapabilities = setOf(Capabilities.SYSTEM_NETWORK)
    override val keywords = setOf("network", "wifi", "data")
    override val pluginId = "fr3k.integrations.system"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "network: see Diagnostics activity")
    }
}

class BluetoothStatusCommand : Fr3kCommand {
    override val id = "system.bluetooth"
    override val title = "Bluetooth status"
    override val description = "Show Bluetooth adapter state"
    override val requiredCapabilities = setOf(Capabilities.SYSTEM_BLUETOOTH)
    override val keywords = setOf("bluetooth", "ble", "adapter")
    override val pluginId = "fr3k.integrations.system"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "bluetooth: see Diagnostics activity")
    }
}

class StorageStatusCommand : Fr3kCommand {
    override val id = "system.storage"
    override val title = "Storage status"
    override val description = "Show free / total storage"
    override val requiredCapabilities = setOf(Capabilities.SYSTEM_STORAGE)
    override val keywords = setOf("storage", "disk", "free")
    override val pluginId = "fr3k.integrations.system"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        return CommandResult.Ok(message = "storage: see Diagnostics activity")
    }
}