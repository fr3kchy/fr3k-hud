package com.mcpintelligence.fr3k.ui.palette

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kCommand
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.StatusDot
import com.mcpintelligence.fr3k.protocol.DeviceStatus
import kotlinx.coroutines.launch

/**
 * Global command palette — full-screen overlay with fuzzy search over the
 * current command registry. Filters by current capabilities so the user only
 * sees commands they can actually execute.
 */
class CommandPaletteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { PaletteScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun PaletteScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val capabilities by app.capabilityRegistry.snapshot.collectAsState()
    val commands by app.commandRegistry.commandsFlow.collectAsState()
    val capIds = capabilities.keys
    val coroutine = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val filtered = remember(query, commands, capIds) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) commands.filter { cmd -> cmd.requiredCapabilities.all { it in capIds } }
        else commands.filter { cmd ->
            cmd.requiredCapabilities.all { it in capIds } &&
                (cmd.title.lowercase().contains(q) ||
                    cmd.id.lowercase().contains(q) ||
                    cmd.keywords.any { it.lowercase().contains(q) })
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Fr3kPalette.Surface),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    Text("FR3K COMMAND", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        cursorBrush = SolidColor(Fr3kPalette.Accent),
                        textStyle = TextStyle(color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Escape) {
                                    onClose(); true
                                } else false
                            },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${filtered.size} command${if (filtered.size == 1) "" else "s"} · ${capIds.size} capabilities active",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = Fr3kPalette.TextDim,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )

            if (lastResult != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Fr3kPalette.Surface),
                ) {
                    Text(
                        text = lastResult!!,
                        color = Fr3kPalette.Ok,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.id }) { cmd ->
                    CommandRow(
                        cmd = cmd,
                        onClick = {
                            coroutine.launch {
                                val ctx = Fr3kContext(
                                    deviceId = app.identity.deviceId,
                                    consentLevel = ConsentLevel.NORMAL,
                                    enabledCapabilities = capIds,
                                )
                                val result = cmd.execute(ctx, emptyMap())
                                lastResult = when (result) {
                                    is CommandResult.Ok -> "✓ ${result.message}"
                                    is CommandResult.Failed -> "✗ ${result.reason}"
                                    is CommandResult.Cancelled -> "– ${result.reason}"
                                    is CommandResult.NeedsConfirmation -> "? ${result.summary}"
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    LaunchedEffectOnce { focusRequester.requestFocus(); keyboard?.show() }
}

@Composable
private fun CommandRow(cmd: Fr3kCommand, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .background(Fr3kPalette.Accent),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cmd.title,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Text(
                text = "${cmd.id} · ${cmd.requiredCapabilities.size} req",
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text("RUN", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/** Helper: requestFocus exactly once after composition. */
@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { scope.launch { block() } }
}