package com.mcpintelligence.fr3k.ui.diagnostics

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.DiagnosticsExporter
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
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
}

@Composable
private fun DiagnosticsScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context = app.applicationContext
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

            Section("APPLICATION") { kv(bundle.app) }
            Section("DEVICE") { kv(bundle.device) }
            Section("PERMISSIONS") {
                bundle.permissions.forEach { (k, v) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Text(
                            text = if (v) "GRANTED" else "DENIED",
                            color = if (v) Fr3kPalette.Ok else Fr3kPalette.Err,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
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