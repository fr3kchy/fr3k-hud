package com.mcpintelligence.fr3k.ui.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mcpintelligence.fr3k.core.SmartClipboardCommand
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme

/**
 * Smart clipboard (§29). Reads the current clipboard ONCE on entry
 * (never continuously), classifies the content, and proposes actions.
 * The user explicitly invokes this — no background clipboard reading.
 */
class SmartClipboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Single explicit read (§29 — no continuous monitor)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else null
        setContent {
            Fr3kTheme {
                SmartClipboardScreen(initialText = text, onClose = { finish() })
            }
        }
    }
}

@Composable
private fun SmartClipboardScreen(initialText: String?, onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val capabilities by app.capabilityRegistry.snapshot.collectAsState()

    var text by remember { mutableStateOf(initialText ?: "") }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialText) {
        if (initialText != null) {
            app.fr3kCore.contextEngine.update { it.copy(clipboardText = initialText) }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                text = "SMART CLIPBOARD",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "clipboard content") {
                if (text.isBlank()) {
                    Text(
                        text = "no text on the clipboard",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        text = text.take(500),
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "suggested actions") {
                Column {
                    listOf(
                        "classify" to { runCommand(app, "context.clipboard", mapOf("text" to text)) },
                        "explain" to { runCommand(app, "share.text.explain", mapOf("text" to text)) },
                        "rewrite" to { runCommand(app, "share.text.rewrite", mapOf("text" to text)) },
                        "translate" to { runCommand(app, "share.text.translate", mapOf("text" to text)) },
                        "summarise" to { runCommand(app, "share.text.summarise", mapOf("text" to text)) },
                        "send to mesh" to { runCommand(app, "share.mesh.send", mapOf("text" to text)) },
                    ).forEach { (label, action) ->
                        ActionRow(label.uppercase()) {
                            result = action()
                        }
                    }
                }
            }

            if (result != null) {
                Spacer(Modifier.height(12.dp))
                com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "result") {
                    Text(
                        text = result!!,
                        color = Fr3kPalette.Ok,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Fr3kPalette.Surface,
                    contentColor = Fr3kPalette.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DISMISS", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun runCommand(app: Fr3kApplication, id: String, args: Map<String, String>): String {
    val cmd = app.fr3kCore.commandRegistry.get(id) ?: return "command not registered"
    val caps = app.fr3kCore.currentCapabilities()
    val ok = cmd.requiredCapabilities.all { it in caps }
    if (!ok) return "missing capability: ${cmd.requiredCapabilities - caps}"
    val ctx = Fr3kContext(deviceId = app.identity.deviceId, consentLevel = ConsentLevel.NORMAL, enabledCapabilities = caps)
    return when (val r = kotlinx.coroutines.runBlocking { cmd.execute(ctx, args) }) {
        is CommandResult.Ok -> "✓ ${r.message}"
        is CommandResult.Failed -> "✗ ${r.reason}"
        is CommandResult.Cancelled -> "– ${r.reason}"
        is CommandResult.NeedsConfirmation -> "? ${r.summary}"
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "›",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = label,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}