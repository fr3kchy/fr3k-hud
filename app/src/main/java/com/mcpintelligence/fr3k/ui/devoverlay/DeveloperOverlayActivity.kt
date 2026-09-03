package com.mcpintelligence.fr3k.ui.devoverlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme

/**
 * Developer overlay (§50). Shows live information about the foreground app:
 *   - package
 *   - activity
 *   - orientation
 *   - network type
 *   - memory pressure
 *   - FPS
 *   - display resolution + density
 *
 * Actions: copy info, open diagnostics, send to dev agent (Hermes).
 *
 * V1 sources package + activity via the ContextEngine (set on every resume),
 * the rest from a lightweight self-readout.
 */
class DeveloperOverlayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { DeveloperOverlayScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun DeveloperOverlayScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context by app.fr3kCore.contextEngine.current.collectAsState()

    val metrics = remember {
        val dm = app.resources.displayMetrics
        mapOf(
            "package" to (context.sourcePackage ?: "—"),
            "activity" to (context.sourceActivity ?: "—"),
            "orientation" to (if (app.resources.configuration.orientation == 1) "portrait" else "landscape"),
            "display" to "${dm.widthPixels}x${dm.heightPixels}",
            "density" to "${dm.density}x (${dm.densityDpi} dpi)",
            "memory" to "${Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }} MB used",
            "fps" to "—", // FPS requires Choreographer; left for V1.5
            "network" to "see system.network",
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "DEV OVERLAY",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "foreground app") {
                Column {
                    metrics.forEach { (k, v) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                            Text(k.uppercase(), color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(v, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Fr3kPalette.Border)
            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "actions") {
                Column {
                    ActionRow("COPY INFO") {
                        val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val text = metrics.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("FR3K dev overlay", text))
                    }
                    ActionRow("OPEN DIAGNOSTICS") {
                        app.startActivity(android.content.Intent(app, com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    ActionRow("SEND TO DEV AGENT") {
                        app.fr3kCore.contextEngine.update {
                            it.copy(userPrompt = "explain this app context: ${metrics}")
                        }
                        app.startActivity(android.content.Intent(app, com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            ) {
                Text("DISMISS", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Fr3kPalette.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Fr3kPalette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(end = 4.dp),
        )
    }
}