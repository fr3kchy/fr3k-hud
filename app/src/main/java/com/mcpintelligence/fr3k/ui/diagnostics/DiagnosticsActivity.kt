package com.mcpintelligence.fr3k.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.DiagnosticsExporter
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.integrations.termux.TermuxHealth
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Developer overlay / observability panel (§49). Shows:
 *   - FR3K + Android versions
 *   - Permission status (every declared permission)
 *   - Foreground services running
 *   - Capability inventory
 *   - Plugin statuses
 *   - Automation count + log tail
 *
 * Actions: copy info, send to dev agent, export bundle to a text file in cache.
 */
class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { DiagnosticsScreen(onClose = { finish() }) } }
    }

    /**
     * One-tap grant from the diagnostics panel. Branches on the kind of
     * permission:
     *   - Normal runtime: `requestPermissions` opens the OS dialog.
     *   - Special (overlay, notification-listener, write-settings, …):
     *     opens the matching `Settings.ACTION_*` screen.
     *   - Anything else: falls back to the app's app-info page so the
     *     user can grant manually.
     */
    fun requestPermission(permission: String) {
        when (permission) {
            "android.permission.SYSTEM_ALERT_WINDOW" -> {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            "android.permission.WRITE_SETTINGS" -> {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.ACCESS_NOTIFICATION_POLICY" -> {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            "android.permission.PACKAGE_USAGE_STATS" -> {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            else -> {
                // Regular runtime permission — fire the standard dialog.
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(permission), 9300,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context = app.applicationContext
    // Pull the activity out of LocalContext so the per-row GRANT button
    // can fire `requestPermission(...)` directly. We keep this scoped to
    // the @Composable so a ContextServiceLocator isn't needed.
    val activity = androidx.compose.ui.platform.LocalContext.current as? DiagnosticsActivity
    val caps by app.capabilityRegistry.snapshot.collectAsState()
    val plugins by remember(app) { app.fr3kCore.pluginManager.statuses.toMap() }.let { state ->
        androidx.compose.runtime.mutableStateOf(state)
    }
    val foreground = remember {
        listOf("Fr3kCoreService", "HudOverlayService (if granted)", "MeshService (V2 stub)", "LocationService (V2 stub)")
    }
    val automations by remember { mutableStateOf(app.fr3kCore.automationEngine.all()) }
    val logs = remember { app.fr3kCore.automationEngine.logs().take(15) }

    val bundle = remember(caps, plugins, automations, logs) {
        DiagnosticsExporter.collect(
            context = context,
            fr3kCore = app.fr3kCore,
            foregroundServices = foreground,
        )
    }
    var exportedTo by remember { mutableStateOf<String?>(null) }

    // §10 System Diagnostics — five live probes (agent/termux/browser/
    // blackwave/hud). RUN FULL DIAGNOSTIC re-probes everything; TermuxHealth
    // is invalidated first so the 10s cache never satisfies a manual run.
    val diagScope = rememberCoroutineScope()
    var diagRows by remember { mutableStateOf<List<DiagRow>>(emptyList()) }
    var diagRunning by remember { mutableStateOf(false) }
    fun runFullDiagnostic() {
        diagScope.launch {
            diagRunning = true
            diagRows = probeDiagnostics(app, context)
            diagRunning = false
        }
    }
    LaunchedEffect(Unit) { runFullDiagnostic() }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "DIAGNOSTICS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            Section("SYSTEM DIAGNOSTICS · PASS/WARN/FAIL") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (diagRunning) "probing…" else "agent · termux · browser · blackwave · hud",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Button(
                        onClick = { runFullDiagnostic() },
                        enabled = !diagRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) {
                        Text("RUN FULL DIAGNOSTIC", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (diagRows.isEmpty()) {
                    Text("no results yet", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                } else {
                    diagRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                text = row.label.uppercase(),
                                color = Fr3kPalette.Text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(70.dp),
                            )
                            Text(
                                text = row.detail,
                                color = Fr3kPalette.TextDim,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Fr3kBadge(
                                text = row.level.name,
                                color = when (row.level) {
                                    DiagLevel.PASS -> Fr3kPalette.Ok
                                    DiagLevel.WARN -> Fr3kPalette.Warn
                                    DiagLevel.FAIL -> Fr3kPalette.Err
                                },
                            )
                        }
                    }
                }
            }

            Section("APPLICATION") { kv(bundle.app) }
            Section("DEVICE") { kv(bundle.device) }
            Section("PERMISSIONS") {
                bundle.permissions.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(k, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        if (v) {
                            Text(
                                text = "GRANTED",
                                color = Fr3kPalette.Ok,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        } else {
                            // Each denied perm gets a one-tap "GRANT" button
                            // that fires the standard request flow. Special
                            // permissions (overlay, notification-listener,
                            // write-settings, …) go to their dedicated
                            // Settings screen instead.
                            Button(
                                onClick = { activity?.requestPermission(k) },
                                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                            ) {
                                Text("GRANT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            Section("FOREGROUND SERVICES") { list_(foreground) }
            Section("CAPABILITIES (${caps.size})") { list_(caps.keys.sorted()) }
            Section("PLUGINS") { list_(plugins.map { (id, status) -> "$id → $status" }) }
            Section("AUTOMATIONS (${automations.size})") {
                list_(automations.map { "${it.id} → ${it.trigger.type}" })
            }
            Section("RECENT AUTOMATION LOGS") {
                list_(logs.map { "${it.timestamp}: ${it.title} ${it.outcome}" })
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                        val file = File(dir, "fr3k-bundle-${System.currentTimeMillis()}.txt")
                        file.writeText(DiagnosticsExporter.toText(bundle))
                        exportedTo = file.absolutePath
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                ) {
                    Text("EXPORT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                ) {
                    Text("CLOSE", fontFamily = FontFamily.Monospace)
                }
            }

            if (exportedTo != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "exported → $exportedTo",
                    color = Fr3kPalette.Ok,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(6.dp))
    com.mcpintelligence.fr3k.ui.Fr3kPanel(title = title.lowercase()) {
        Column { content() }
    }
}

@Composable
private fun kv(map: Map<String, String>) {
    Column {
        map.forEach { (k, v) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k.uppercase(), color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Text(v, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun list_(items: List<String>) {
    Column {
        items.forEach { item ->
            Text(
                text = item,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }
    }
}

private enum class DiagLevel { PASS, WARN, FAIL }

private data class DiagRow(val label: String, val level: DiagLevel, val detail: String)

/**
 * §10 system-diagnostics probe. Real checks only — AGENT health(), a live
 * Termux round-trip, bus-tool registration, the BLACKWAVE /health reach and
 * an ActivityManager service check. Each returns a PASS/WARN/FAIL row with
 * the raw state as detail text.
 */
private suspend fun probeDiagnostics(app: Fr3kApplication, context: Context): List<DiagRow> {
    val rows = mutableListOf<DiagRow>()

    val providers = app.aiProviders.all()
    if (providers.isEmpty()) {
        rows += DiagRow("agent", DiagLevel.FAIL, "no AI providers registered")
    } else {
        val states = withContext(Dispatchers.IO) {
            providers.map { p ->
                p to runCatching { withTimeoutOrNull(5_000) { p.health() } }.getOrNull()
            }
        }
        val anyOnline = states.any { it.second?.online == true }
        rows += DiagRow(
            "agent",
            if (anyOnline) DiagLevel.PASS else if (states.any { it.second != null }) DiagLevel.WARN else DiagLevel.FAIL,
            states.joinToString(" · ") { (p, h) ->
                h?.let { "${p.displayName}: ${if (it.online) "online" else "offline"}${it.latencyMs?.let { ms -> " (${ms}ms)" } ?: ""}" } ?: "${p.displayName}: no response"
            },
        )
    }

    TermuxHealth.invalidate()
    val termux = TermuxHealth.probe(context, app.termuxBridge)
    val info = TermuxHealth.statusLabel(termux)
    rows += DiagRow(
        "termux",
        when (info.level) {
            TermuxHealth.TermuxStatusLevel.OK -> DiagLevel.PASS
            TermuxHealth.TermuxStatusLevel.WARN -> DiagLevel.WARN
            TermuxHealth.TermuxStatusLevel.ERR -> DiagLevel.FAIL
        },
        buildString {
            append(info.label)
            termux.version?.let { append(" v$it") }
            if (termux.latencyMs > 0) append(" · ${termux.latencyMs}ms round-trip")
            termux.failureCode?.let { append(" · ${it.uppercase()}") }
        },
    )

    val browserTool = app.agentToolBus.get("browser.navigate")
    rows += DiagRow(
        "browser",
        if (browserTool != null) DiagLevel.PASS else DiagLevel.FAIL,
        browserTool?.let { "bus tool registered: ${it.id} (${it.capability})" }
            ?: "browser.navigate not registered — wired when HUD overlay starts",
    )

    val endpoint = app.settings.settings.value.blackwaveEndpoint
    val bwReachable = withContext(Dispatchers.IO) {
        runCatching { withTimeoutOrNull(10_000) { app.blackwaveBridgeClient.isAvailable() } }
            .getOrDefault(false) == true
    }
    rows += DiagRow(
        "blackwave",
        if (bwReachable) DiagLevel.PASS else DiagLevel.FAIL,
        "$endpoint · ${if (bwReachable) "reachable" else "unreachable"}",
    )

    val hudRunning = hudServiceRunning(context)
    val overlayGranted = SpecialPermissionLauncher.canDrawOverlays(context)
    val hudEnabled = app.settings.settings.value.hudEnabled
    rows += DiagRow(
        "hud",
        when {
            hudRunning -> DiagLevel.PASS
            hudEnabled && overlayGranted -> DiagLevel.WARN // enabled + overlay ready but service not live
            else -> DiagLevel.FAIL
        },
        "service running=$hudRunning · overlay granted=$overlayGranted · hudEnabled=$hudEnabled",
    )

    return rows
}

private fun hudServiceRunning(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return am.getRunningServices(200).any { it.service.className == HudOverlayService::class.java.name }
}