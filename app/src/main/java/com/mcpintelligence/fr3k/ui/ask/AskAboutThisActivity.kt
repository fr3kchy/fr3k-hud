package com.mcpintelligence.fr3k.ui.ask

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.FrappeLocationRef_unused as _FrappeLocationRef_unused
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.integrations.hermes.HermesAskCommand
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * The "Ask About This" surface. §12 universal operation.
 *
 * Always shows what will be transmitted (§11 context firewall):
 *   - package
 *   - url
 *   - selected text
 *   - screenshot
 *   - location
 *   - clipboard
 *
 * The user edits / inspects the prompt and explicit context fields, then sends.
 */
class AskAboutThisActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = AskExtras(
            initialPrompt = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                ?: "Describe the current context.",
            initialUrl = intent.getStringExtra("url"),
            selectedText = intent.getStringExtra(Intent.EXTRA_TEXT),
            fullText = intent.getStringExtra(Intent.EXTRA_TEXT),
            screenshotUri = intent.getStringExtra("screenshot"),
            foregroundPackage = applicationContext.packageName,
        )
        setContent { Fr3kTheme { AskScreen(extras = extras, onClose = { finish() }) } }
    }
}

/** Plain bundle of inputs the screen needs. Extracted from the launch intent. */
data class AskExtras(
    val initialPrompt: String,
    val initialUrl: String?,
    val selectedText: String?,
    val fullText: String?,
    val screenshotUri: String?,
    val foregroundPackage: String?,
)

@Composable
private fun AskScreen(extras: AskExtras, onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val capabilities by app.capabilityRegistry.snapshot.collectAsState()
    val coroutine = rememberCoroutineScope()

    var prompt by remember { mutableStateOf(extras.initialPrompt) }
    var includeUrl by remember { mutableStateOf(true) }
    var includeSelected by remember { mutableStateOf(true) }
    var includeScreenshot by remember { mutableStateOf(false) }
    var includeLocation by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fr3kPalette.Bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "ASK ABOUT THIS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "tap to close",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Fr3kPalette.Surface)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "context firewall") {
            ContextRow("application", extras.foregroundPackage ?: "—")
            ContextRow("url", if (includeUrl) (extras.initialUrl ?: "(current url)") else "NO")
            ContextRow("selected text", if (includeSelected) (extras.selectedText?.take(60) ?: "(selection)") else "NO")
            ContextRow("screenshot", if (includeScreenshot) (extras.screenshotUri ?: "YES") else "NO")
            ContextRow("location", if (includeLocation) "YES" else "NO")
            ContextRow("clipboard", "NO")
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Prompt", fontFamily = FontFamily.Monospace) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("URL", includeUrl) { includeUrl = !includeUrl }
            ToggleChip("Selected", includeSelected) { includeSelected = !includeSelected }
            ToggleChip("Screenshot", includeScreenshot) { includeScreenshot = !includeScreenshot }
            ToggleChip("Location", includeLocation) { includeLocation = !includeLocation }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (inFlight) return@Button
                inFlight = true
                response = null
                coroutine.launch {
                    val capIds = capabilities.keys
                    val provider = app.aiProviders.get("hermes") as? com.mcpintelligence.fr3k.integrations.hermes.HermesProvider
                    val command = if (provider != null) {
                        HermesAskCommand(provider = { provider })
                    } else {
                        response = "Hermes provider unavailable"
                        inFlight = false
                        return@launch
                    }
                    val ctx = Fr3kContext(
                        deviceId = app.identity.deviceId,
                        consentLevel = ConsentLevel.NORMAL,
                        foregroundPackage = extras.foregroundPackage,
                        currentUrl = if (includeUrl) extras.initialUrl else null,
                        selectedText = if (includeSelected) extras.selectedText else null,
                        fullText = if (includeSelected) extras.fullText else null,
                        screenshotUri = if (includeScreenshot) extras.screenshotUri else null,
                        enabledCapabilities = capIds,
                    )
                    val result = command.execute(ctx, mapOf("prompt" to prompt))
                    response = when (result) {
                        is CommandResult.Ok -> result.message
                        is CommandResult.Failed -> "Failed: ${result.reason}"
                        is CommandResult.Cancelled -> "Cancelled: ${result.reason}"
                        is CommandResult.NeedsConfirmation -> "Needs: ${result.summary}"
                    }
                    inFlight = false
                }
            },
            enabled = !inFlight,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (inFlight) "SENDING…" else "SEND TO HERMES",
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }

        if (response != null) {
            Spacer(Modifier.height(16.dp))
            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "response") {
                Text(
                    text = response!!,
                    color = Fr3kPalette.Text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CLOSE", fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label.uppercase(), color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text(value, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) Fr3kPalette.Accent else Fr3kPalette.Surface
    val fg = if (active) Fr3kPalette.Bg else Fr3kPalette.TextDim
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(2.dp))
    }
}