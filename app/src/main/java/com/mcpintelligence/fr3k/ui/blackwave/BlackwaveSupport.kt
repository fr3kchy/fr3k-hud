package com.mcpintelligence.fr3k.ui.blackwave

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveRoleManifest
import com.mcpintelligence.fr3k.integrations.blackwave.FleetStatusResponse
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared widgets + probe helpers for the §6/§7 BLACKWAVE screens.
 * Kept outside the per-activity files so the main screen, the setup
 * wizard and the add-device flow all render the same honest status rows.
 */
enum class BwLevel { PASS, WARN, FAIL }

/** Snapshot of one bridge probe: endpoint reachability, role manifest, fleet. */
data class BridgeProbe(
    val endpoint: String,
    val hasCredential: Boolean,
    val clientId: String,
    val reachable: Boolean = false,
    val role: BlackwaveRoleManifest? = null,
    val fleet: FleetStatusResponse? = null,
    val error: String? = null,
) {
    val level: BwLevel
        get() = when {
            !reachable -> BwLevel.FAIL
            role == null -> BwLevel.WARN
            role.isExpired -> BwLevel.WARN
            else -> BwLevel.PASS
        }

    val fleetOnline: Int
        get() = fleet?.online?.split("/")?.firstOrNull()?.toIntOrNull() ?: 0
    val fleetAccounted: Int
        get() = fleet?.online?.split("/")?.getOrNull(1)?.toIntOrNull() ?: fleet?.accounted ?: 0

    fun hasScope(scope: String): Boolean = role?.allowed_scopes?.contains(scope) == true
}

/** Probe the app's configured bridge (endpoint/credential/clientId from settings). */
suspend fun probeBridge(app: Fr3kApplication, timeoutMs: Long = 10_000L): BridgeProbe {
    val settings = app.settings.settings.value
    return probeBridgeWith(
        bridge = app.blackwaveBridgeClient,
        endpoint = settings.blackwaveEndpoint,
        hasCredential = !app.secureStore.get(settings.blackwaveCredentialKey).isNullOrBlank(),
        clientId = settings.blackwaveClientId,
        timeoutMs = timeoutMs,
    )
}

/** Probe an arbitrary bridge (used by the manual-IP transport in §7). */
suspend fun probeBridgeWith(
    bridge: BlackwaveBridgeClient,
    endpoint: String,
    hasCredential: Boolean,
    clientId: String,
    timeoutMs: Long = 10_000L,
): BridgeProbe {
    val base = BridgeProbe(
        endpoint = endpoint.ifBlank { "unset" },
        hasCredential = hasCredential,
        clientId = clientId,
    )
    return withContext(Dispatchers.IO) {
        val reachable = try {
            withTimeoutOrNull(timeoutMs) { bridge.isAvailable() } == true
        } catch (_: Throwable) {
            false
        }
        if (!reachable) {
            base.copy(error = "bridge unreachable")
        } else {
            val role = try {
                withTimeoutOrNull(timeoutMs) { bridge.fetchRole().getOrNull() }
            } catch (_: Throwable) {
                null
            }
            val fleet = try {
                withTimeoutOrNull(timeoutMs) { bridge.fetchFleetStatus().getOrNull() }
            } catch (_: Throwable) {
                null
            }
            base.copy(reachable = true, role = role, fleet = fleet)
        }
    }
}

/** §10-style service check: is the HUD overlay service actually alive. */
fun hudServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return am.getRunningServices(200).any { it.service.className == HudOverlayService::class.java.name }
}

/** Short scope identifiers used by the fleet bridge + their human labels. */
object BwScopes {
    val LABELS: Map<String, String> = mapOf(
        "fleet.discover" to "Fleet discovery",
        "profile.apply" to "Live Apply — profile switching",
        "ota.apply" to "OTA firmware updates",
        "radio.status" to "Radio status",
        "radio.configure" to "Radio configuration",
        "reticulum.status" to "Reticulum status",
        "reticulum.link_test" to "Reticulum link test",
        "epaper.status" to "E-paper status",
        "battery.telemetry" to "Battery telemetry",
        "location.read" to "Location read",
        "device.reboot" to "Device reboot",
        "device.describe" to "Device describe",
    )

    fun label(scope: String): String = LABELS[scope] ?: scope
}

/** Status row in the same shape as the §10 diagnostics rows. */
@Composable
fun StatusRow(label: String, level: BwLevel, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            color = Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(78.dp),
        )
        Text(
            text = detail,
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            modifier = Modifier.weight(1f),
        )
        Fr3kBadge(
            text = level.name,
            color = when (level) {
                BwLevel.PASS -> Fr3kPalette.Ok
                BwLevel.WARN -> Fr3kPalette.Warn
                BwLevel.FAIL -> Fr3kPalette.Err
            },
        )
    }
}