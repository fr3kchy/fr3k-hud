package com.mcpintelligence.fr3k.ui.automation

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AutomationEngine
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme

/**
 * Automation manager (§31). Lists current automations, shows the execution
 * log, and lets the user fire a manual automation by name.
 *
 * Out of the box V1 ships a small starter set so the screen is populated:
 *   - "share received" → ASK_HERMES (uses the just-received context)
 *   - "share received" → OPEN_PALETTE
 *   - "URL shared" → CLEAN_URL + OPEN_ASK
 *   - "foreground app matches browser" → OPEN_ASK
 */
class AutomationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { AutomationScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun AutomationScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val automations = remember(app) { app.fr3kCore.automationEngine.all() }
    var logs by remember(app) { androidx.compose.runtime.mutableStateOf<List<AutomationEngine.LogEntry>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        logs = app.fr3kCore.automationEngine.logs()
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "AUTOMATIONS",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "rules (${automations.size})") {
                Column {
                    automations.forEach { a ->
                        AutomationRow(a)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "recent logs (${logs.size})") {
                Column {
                    logs.take(40).forEach { entry ->
                        LogRow(entry)
                    }
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
                Text("CLOSE", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun AutomationRow(a: AutomationEngine.Automation) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(
                text = a.title,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${a.trigger.type.name.lowercase()}${if (a.trigger.debounceMs > 0) " · debounce ${a.trigger.debounceMs}ms" else ""}",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "→ ${actionLabel(a.action)}",
            color = Fr3kPalette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "fired ${a.fireCount} times · ${if (a.enabled) "enabled" else "DISABLED"}",
            color = if (a.enabled) Fr3kPalette.Ok else Fr3kPalette.Err,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun LogRow(entry: AutomationEngine.LogEntry) {
    val color = when (entry.outcome) {
        AutomationEngine.Outcome.FIRED -> Fr3kPalette.Ok
        AutomationEngine.Outcome.SKIPPED_DISABLED, AutomationEngine.Outcome.SKIPPED_DEBOUNCE,
        AutomationEngine.Outcome.SKIPPED_NO_MATCH -> Fr3kPalette.TextDim
        AutomationEngine.Outcome.FAILED -> Fr3kPalette.Err
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(
                text = entry.title,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Text(
                text = entry.outcome.name.lowercase(),
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        Text(
            text = "${entry.timestamp} · ${entry.trigger.name.lowercase()} · ${actionLabel(entry.action)}",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

private fun actionLabel(action: AutomationEngine.Action): String = when (action) {
    is AutomationEngine.Action.RunCommand -> "cmd: ${action.commandId}"
    is AutomationEngine.Action.OpenPalette -> "open palette"
    is AutomationEngine.Action.OpenAskAboutThis -> "open ask-about-this"
    is AutomationEngine.Action.SendToMesh -> "mesh: ${action.content.take(40)}"
    is AutomationEngine.Action.SendToDevice -> "device ${action.deviceId}"
    is AutomationEngine.Action.Notify -> "notify: ${action.title}"
}