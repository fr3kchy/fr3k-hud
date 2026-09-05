package com.mcpintelligence.fr3k.integrations.blackwave

import android.util.Log
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.core.Fr3kPlugin
import com.mcpintelligence.fr3k.protocol.Capabilities
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Blackwave plugin — replaces the generic MeshPlugin.
 *
 * Instead of a generic mesh transport abstraction, this plugin connects to
 * the blackwave fleet bridge (LAN HTTPS) and fetches a role manifest that
 * defines the capabilities this identity is allowed to use. The bridge
 * owns all device authority, radio configuration, and signing; HUD is just
 * a capability-aware consumer surface.
 */
class BlackwavePlugin(
    private val bridgeClient: BlackwaveBridgeClient,
) : Fr3kPlugin {

    override val pluginId: String = "fr3k.integrations.blackwave"
    override val displayName: String = "BLACKWAVE"
    override val version: String = "0.1.0"

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null
    private var cachedRole: BlackwaveRoleManifest? = null

    private val scopeToCapability: Map<String, String> = mapOf(
        "fleet.discover" to Capabilities.BLACKWAVE_FLEET_DISCOVER,
        "profile.apply" to Capabilities.BLACKWAVE_PROFILE_APPLY,
        "ota.apply" to Capabilities.BLACKWAVE_OTA_APPLY,
        "radio.status" to Capabilities.BLACKWAVE_RADIO_STATUS,
        "radio.configure" to Capabilities.BLACKWAVE_RADIO_CONFIGURE,
        "reticulum.status" to Capabilities.BLACKWAVE_RETICULUM_STATUS,
        "reticulum.link_test" to Capabilities.BLACKWAVE_RETICULUM_LINK_TEST,
        "epaper.status" to Capabilities.BLACKWAVE_EPAPER_STATUS,
        "battery.telemetry" to Capabilities.BLACKWAVE_BATTERY_TELEMETRY,
        "location.read" to Capabilities.BLACKWAVE_LOCATION_READ,
        "device.reboot" to Capabilities.BLACKWAVE_DEVICE_REBOOT,
        "device.describe" to Capabilities.BLACKWAVE_DEVICE_DESCRIBE,
    )

    override fun capabilities(): List<Capability> {
        val role = cachedRole
        if (role != null) {
            return role.allowed_scopes.mapNotNull { scope ->
                val capId = scopeToCapability[scope] ?: return@mapNotNull null
                Capability(
                    id = capId,
                    displayName = scope.replace('.', ' ').replaceFirstChar { it.uppercase() },
                    description = "BLACKWAVE: $scope",
                    tier = CapabilityTier.TIER_1,
                )
            }
        }
        // No role yet — register only the base discover capability
        return BLackwaveBASE_CAPABILITIES
    }

    override fun commands(): List<Fr3kCommand> = listOf(
        BlackwaveFleetStatusCommand(bridgeClient, { cachedRole }),
        BlackwaveDeviceStatusCommand(bridgeClient),
    )

    override suspend fun start() {
        Log.i(TAG, "BlackwavePlugin starting")
        scope = CoroutineScope(Dispatchers.IO)
        pollRole()
        startPolling()
    }

    override suspend fun stop() {
        Log.i(TAG, "BlackwavePlugin stopping")
        pollJob?.cancel()
        pollJob = null
        scope?.let {
            it.coroutineContext[Job]?.children?.forEach { child -> child.cancel() }
        }
        scope = null
        cachedRole = null
    }

    private suspend fun pollRole() {
        if (!bridgeClient.isAvailable()) {
            Log.w(TAG, "bridge not available, skipping role poll")
            return
        }
        val result = bridgeClient.fetchRole()
        result.onSuccess { manifest ->
            cachedRole = manifest
            Log.i(TAG, "role fetched: ${manifest.role_id} tier=${manifest.trust_tier}")
        }.onFailure { e ->
            Log.w(TAG, "role fetch failed: ${e.message}")
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope?.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                pollRole()
            }
        }
    }

    companion object {
        private const val TAG = "FR3K.blackwave"
        private const val POLL_INTERVAL_MS = 30_000L

        private val BLackwaveBASE_CAPABILITIES = listOf(
            Capability(
                id = Capabilities.BLACKWAVE_FLEET_DISCOVER,
                displayName = "BLACKWAVE fleet discover",
                tier = CapabilityTier.TIER_1,
            ),
        )
    }
}

/**
 * Command: show fleet status from the blackwave bridge.
 */
class BlackwaveFleetStatusCommand(
    private val bridgeClient: BlackwaveBridgeClient,
    private val roleProvider: () -> BlackwaveRoleManifest?,
) : Fr3kCommand {
    override val id = "blackwave.fleet.status"
    override val title = "BLACKWAVE fleet status"
    override val description = "List all devices in the fleet"
    override val requiredCapabilities = setOf(Capabilities.BLACKWAVE_FLEET_DISCOVER)
    override val keywords = setOf("blackwave", "fleet", "devices", "status")
    override val pluginId = "fr3k.integrations.blackwave"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val role = roleProvider()
        if (role == null) {
            return CommandResult.Failed("no role manifest — bridge unreachable")
        }
        val result = bridgeClient.fetchFleetStatus()
        return result.fold(
            onSuccess = { fleet ->
                val summary = fleet.devices.joinToString("\n") { device ->
                    "${device.display_name} (${device.model_id}) — ${device.online}"
                }
                CommandResult.Ok(
                    message = "Fleet: ${fleet.accounted} devices, ${fleet.online} online\n$summary",
                    data = mapOf(
                        "accounted" to fleet.accounted.toString(),
                        "online" to fleet.online,
                        "device_count" to fleet.devices.size.toString(),
                    ),
                )
            },
            onFailure = { CommandResult.Failed("fleet status failed: ${it.message}") },
        )
    }
}

/**
 * Command: show device status for a specific device.
 */
class BlackwaveDeviceStatusCommand(
    private val bridgeClient: BlackwaveBridgeClient,
) : Fr3kCommand {
    override val id = "blackwave.device.status"
    override val title = "BLACKWAVE device status"
    override val description = "Show detailed status for a fleet device"
    override val requiredCapabilities = setOf(Capabilities.BLACKWAVE_DEVICE_DESCRIBE)
    override val keywords = setOf("blackwave", "device", "status", "telemetry")
    override val pluginId = "fr3k.integrations.blackwave"

    override suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult {
        val deviceId = args["device_id"] ?: return CommandResult.Failed("missing device_id")
        val result = bridgeClient.fetchDeviceStatus(deviceId)
        return result.fold(
            onSuccess = { status ->
                CommandResult.Ok(
                    message = "Device $deviceId: software=${status.software} connectivity=${status.connectivity}",
                    data = status.software + status.connectivity + status.battery,
                )
            },
            onFailure = { CommandResult.Failed("device status failed: ${it.message}") },
        )
    }
}