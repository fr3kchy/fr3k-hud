package com.mcpintelligence.fr3k.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity
import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.DeviceManifest
import com.mcpintelligence.fr3k.protocol.DeviceStatus
import com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity
import com.mcpintelligence.fr3k.ui.automation.AutomationActivity
import com.mcpintelligence.fr3k.ui.clipboard.SmartClipboardActivity
import com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity
import com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity
import com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity
import com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity
import com.mcpintelligence.fr3k.ui.screenshot.ScreenshotActivity
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

/**
 * The main dashboard — single-pane application shell.
 *
 * Top: identity + profile-aware suggestions (§39/§40)
 * Actions: command palette + ask-about-this + smart-clipboard + screenshot
 * Inventory: capabilities + commands + fleet
 * Diagnostics: observability panel + settings
 * Plus the orb / Quick Settings tile entry point.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activity = this
        setContent {
            Fr3kTheme {
                Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
                    DashboardScreen(
                        activity = activity,
                        onOpenPalette = { activity.startActivity(Intent(activity, CommandPaletteActivity::class.java)) },
                        onAskAboutThis = { activity.startActivity(Intent(activity, AskAboutThisActivity::class.java)) },
                    )
                }
            }
        }
        // Auto-start the HUD service if overlay + notification perms are
        // already granted. The orb should appear on app launch without the
        // user having to tap "Start". If perms are missing, the user can
        // still tap "Start" from the dashboard to grant them.
        try {
            val canOverlay = com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.canDrawOverlays(this)
            val canNotif = android.os.Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canOverlay && canNotif) {
                android.util.Log.i("FR3K", "auto-starting HudOverlayService from MainActivity.onCreate")
                startForegroundService(Intent(this, HudOverlayService::class.java))
            } else {
                android.util.Log.i("FR3K", "skipping auto-start (overlay=$canOverlay, notif=$canNotif)")
            }
        } catch (t: Throwable) {
            android.util.Log.e("FR3K", "auto-start failed", t)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Notifications granted — start the HUD now if overlay was already OK.
            // Use startForegroundService, NOT startService: the HUD is a foreground
            // service on API 26+ and plain startService will throw
            // IllegalStateException.
            if (com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.canDrawOverlays(this)) {
                startForegroundService(Intent(this, HudOverlayService::class.java))
            }
        }
    }

    companion object {
        const val REQ_NOTIF = 7001
    }
}

@Composable
fun DashboardScreen(
    activity: ComponentActivity,
    onOpenPalette: () -> Unit,
    onAskAboutThis: () -> Unit,
) {
    val app = Fr3kApplication.get()
    val capabilities by app.capabilityRegistry.snapshot.collectAsState()
    val devices by app.deviceRegistry.snapshot.collectAsState()
    val commands by app.commandRegistry.commandsFlow.collectAsState()
    val settings by app.settings.settings.collectAsState()
    val context by app.fr3kCore.contextEngine.current.collectAsState()
    val suggestions = remember(capabilities, context) { app.fr3kCore.suggestedForCurrent() }
    val profile = remember(context) { app.fr3kCore.profileForCurrent() }

    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Header()
        Spacer(Modifier.height(12.dp))
        PrimaryActions(
            onOpenPalette = onOpenPalette,
            onAskAboutThis = onAskAboutThis,
            onClipboard = { activity.startActivity(Intent(activity, SmartClipboardActivity::class.java)) },
            onScreenshot = { activity.startActivity(Intent(activity, ScreenshotActivity::class.java)) },
        )
        Spacer(Modifier.height(12.dp))
        SuggestionsPanel(profile, suggestions, capabilities.keys) { cmd ->
            val ctx = app.fr3kCore.contextEngine.toCommandContext(
                deviceId = app.identity.deviceId,
                capabilities = app.fr3kCore.currentCapabilities(),
                consentLevel = ConsentLevel.NORMAL,
            )
            lastResult = when (val r = kotlinx.coroutines.runBlocking { cmd.execute(ctx, mapOf("prompt" to "from suggestion")) }) {
                is CommandResult.Ok -> "✓ ${r.message}"
                is CommandResult.Failed -> "✗ ${r.reason}"
                is CommandResult.Cancelled -> "– ${r.reason}"
                is CommandResult.NeedsConfirmation -> "? ${r.summary}"
            }
        }
        if (lastResult != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = lastResult!!,
                color = Fr3kPalette.Ok,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        ContextPanel(context.summary())
        Spacer(Modifier.height(12.dp))
        IdentityPanel()
        Spacer(Modifier.height(12.dp))
        OrbPanel(
            enabled = settings.hudEnabled,
            onStart = {
                // Auto-permission flow: overlay + notifications are required
                // before the orb can render. We launch Settings for overlay if
                // needed and request POST_NOTIFICATIONS inline if API >= 33.
                val needsOverlay = !com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.canDrawOverlays(activity)
                val needsNotif = android.os.Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                when {
                    needsOverlay -> activity.startActivity(
                        com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher.overlayIntent(activity)
                    )
                    needsNotif -> com.mcpintelligence.fr3k.permissions.PermissionRegistry.request(
                        activity,
                        com.mcpintelligence.fr3k.permissions.PermissionRegistry.Feature.HUD_ORB,
                        MainActivity.REQ_NOTIF,
                    )
                    else -> activity.startForegroundService(Intent(activity, HudOverlayService::class.java))
                }
            },
            onStop = { activity.stopService(Intent(activity, HudOverlayService::class.java)) },
        )
        Spacer(Modifier.height(12.dp))
        SecondaryActions(activity)
        Spacer(Modifier.height(12.dp))
        CapabilitiesPanel(capabilities.values.sortedBy { it.id })
        Spacer(Modifier.height(12.dp))
        CommandsPanel(commands)
        Spacer(Modifier.height(12.dp))
        FleetPanel(devices)
        Spacer(Modifier.height(12.dp))
        Footer()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "▰ FR3K HUD",
                style = MaterialTheme.typography.displaySmall,
                color = Fr3kPalette.Accent,
            )
            Text(
                text = "v${Fr3kApplication.get().identity.appVersion} · android capability layer",
                style = MaterialTheme.typography.labelSmall,
                color = Fr3kPalette.TextDim,
            )
        }
        Fr3kBadge(text = "online", color = Fr3kPalette.Ok)
    }
}

@Composable
private fun PrimaryActions(
    onOpenPalette: () -> Unit,
    onAskAboutThis: () -> Unit,
    onClipboard: () -> Unit,
    onScreenshot: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenPalette,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("PALETTE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onAskAboutThis,
                modifier = Modifier.weight(1f),
            ) { Text("ASK", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onClipboard,
                modifier = Modifier.weight(1f),
            ) { Text("CLIPBOARD", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            OutlinedButton(
                onClick = onScreenshot,
                modifier = Modifier.weight(1f),
            ) { Text("SCREENSHOT", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SuggestionsPanel(
    profile: String,
    suggestions: List<com.mcpintelligence.fr3k.core.ApplicationProfiles.CommandSuggestion>,
    enabledCapabilities: Set<String>,
    onRun: (com.mcpintelligence.fr3k.core.Fr3kCommand) -> Unit,
) {
    val app = Fr3kApplication.get()
    Fr3kPanel(title = "suggestions · profile: $profile") {
        if (suggestions.isEmpty()) {
            Text("no suggestions for current app", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        } else {
            Column {
                suggestions.forEach { s ->
                    val available = s.requiresCapabilities.all { it in enabledCapabilities }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(Fr3kPalette.Surface)
                            .clickable {
                                val cmd = app.fr3kCore.commandRegistry.get(s.commandId)
                                if (cmd != null) onRun(cmd)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (available) "▶" else "×",
                            color = if (available) Fr3kPalette.Accent else Fr3kPalette.TextDim,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.title, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(
                                text = "${s.commandId}${if (s.requiresCapabilities.isNotEmpty()) " · needs ${s.requiresCapabilities.joinToString(",")}" else ""}",
                                color = Fr3kPalette.TextDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                        Text(
                            text = "${s.priority}",
                            color = Fr3kPalette.Accent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextPanel(summary: String) {
    Fr3kPanel(title = "context engine") {
        Text(summary.ifEmpty { "no context yet — share, copy, or open an app" },
            color = Fr3kPalette.Text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp)
    }
}

@Composable
private fun IdentityPanel() {
    val id = Fr3kApplication.get().identity
    Fr3kPanel(title = "identity") {
        KeyValueRow("device", id.deviceId)
        KeyValueRow("android", id.androidId.take(16) + "…")
        KeyValueRow("fingerprint", id.fingerprint())
        KeyValueRow("platform", id.platform)
        KeyValueRow("version", id.appVersion)
    }
}

@Composable
private fun OrbPanel(enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Fr3kPanel(title = "floating HUD") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("status: ${if (enabled) "READY (tap START)" else "off"}",
                    color = Fr3kPalette.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp)
                Text("tap → quick hud · long-press → screenshot · double-tap → palette",
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Fr3kPalette.Surface else Fr3kPalette.Accent,
                    contentColor = if (enabled) Fr3kPalette.Text else Fr3kPalette.Bg,
                ),
            ) { Text(if (enabled) "RESTART" else "START", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            ) { Text("STOP", fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun SecondaryActions(activity: ComponentActivity) {
    Fr3kPanel(title = "more surfaces") {
        Column {
            SecondaryRow("QUICK HUD PANEL") { activity.startActivity(Intent(activity, QuickHudActivity::class.java)) }
            SecondaryRow("INTEGRATIONS · TERMUX/SHIZUKU/LSPATCH/MORPHE/ROOT") {
                activity.startActivity(Intent(activity, IntegrationsActivity::class.java))
            }
            SecondaryRow("AUTOMATION") { activity.startActivity(Intent(activity, AutomationActivity::class.java)) }
            SecondaryRow("OPEN ON…") { activity.startActivity(Intent(activity, DeviceHandoffActivity::class.java)) }
            SecondaryRow("DEVELOPER OVERLAY") { activity.startActivity(Intent(activity, DeveloperOverlayActivity::class.java)) }
            SecondaryRow("DIAGNOSTICS") { activity.startActivity(Intent(activity, DiagnosticsActivity::class.java)) }
            SecondaryRow("SETTINGS") { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        }
    }
}

@Composable
private fun SecondaryRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CapabilitiesPanel(caps: List<Capability>) {
    Fr3kPanel(title = "capabilities (${caps.size})") {
        if (caps.isEmpty()) {
            Text("none registered", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace)
        } else {
            Column {
                caps.forEach { cap ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status = DeviceStatus.ONLINE, size = 6.dp, modifier = Modifier.padding(end = 6.dp))
                        Text(
                            text = cap.id,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Fr3kPalette.Text,
                            modifier = Modifier.weight(1f),
                        )
                        Fr3kBadge(text = "tier ${cap.tier.ordinal}", color = Fr3kPalette.AccentDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandsPanel(commands: List<com.mcpintelligence.fr3k.core.Fr3kCommand>) {
    Fr3kPanel(title = "commands (${commands.size})") {
        if (commands.isEmpty()) {
            Text("no commands available", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace)
        } else {
            Column {
                commands.forEach { cmd ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "›",
                            fontFamily = FontFamily.Monospace,
                            color = Fr3kPalette.Accent,
                            modifier = Modifier.width(16.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cmd.title,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Fr3kPalette.Text,
                            )
                            Text(
                                text = cmd.id,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Fr3kPalette.TextDim,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FleetPanel(devices: List<DeviceManifest>) {
    Fr3kPanel(title = "fleet (${devices.size})") {
        if (devices.isEmpty()) {
            Text("no peers discovered", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace)
        } else {
            Column {
                devices.forEach { dev ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status = dev.status, size = 8.dp, modifier = Modifier.padding(end = 6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dev.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Fr3kPalette.Text,
                            )
                            Text(
                                text = "${dev.deviceId} · ${dev.platform} v${dev.version}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Fr3kPalette.TextDim,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer() {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Fr3kPalette.Border)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "FR3K HUD // adaptive android agent interface · capability-aware",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = Fr3kPalette.TextDim,
        )
    }
}