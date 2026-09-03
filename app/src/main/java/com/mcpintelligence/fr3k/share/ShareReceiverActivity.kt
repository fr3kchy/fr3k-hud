package com.mcpintelligence.fr3k.share

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.core.UrlSanitiser
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.StatusDot
import kotlinx.coroutines.launch

/**
 * Android share target (§3, §24, §28). Accepts text, URLs, images, files.
 * Renders an action sheet whose contents depend on what was shared.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = extractPayload(intent)
        setContent {
            Fr3kTheme {
                ShareSheet(payload = payload, onDone = { finish() })
            }
        }
    }

    private fun extractPayload(intent: Intent?): SharePayload {
        if (intent == null) return SharePayload.Text("", "")
        return when (intent.type?.lowercase()) {
            "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                SharePayload.Text(text = text, subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
            }
            "text/uri-list" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                SharePayload.Url(url = text, subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
            }
            else -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) SharePayload.File(uri.toString(), mime = intent.type ?: "*/*")
                else SharePayload.Text("", "")
            }
        }
    }
}

sealed interface SharePayload {
    data class Text(val text: String, val subject: String) : SharePayload
    data class Url(val url: String, val subject: String) : SharePayload
    data class File(val uri: String, val mime: String) : SharePayload
}

@Composable
private fun ShareSheet(payload: SharePayload, onDone: () -> Unit) {
    val app = Fr3kApplication.get()
    val commands by app.commandRegistry.commandsFlow.collectAsState()
    val caps by app.capabilityRegistry.snapshot.collectAsState()
    val capIds = caps.keys
    val coroutine = rememberCoroutineScope()

    val sanitised = remember(payload) {
        when (payload) {
            is SharePayload.Url -> UrlSanitiser().clean(payload.url)
            is SharePayload.Text -> if (looksLikeUrl(payload.text)) UrlSanitiser().clean(payload.text) else null
            else -> null
        }
    }

    val suggested = commands.filter { cmd ->
        (cmd.requiredCapabilities.all { cap -> cap in capIds }) &&
            (cmd.id.startsWith("share.") || cmd.id == "agent.ask.hermes")
    }

    var lastResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fr3kPalette.Bg)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "FR3K ▸ SHARED",
            color = Fr3kPalette.Accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))

        com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "received") {
            when (payload) {
                is SharePayload.Text -> Text("text: ${payload.text.take(180)}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                is SharePayload.Url -> {
                    Text("url: ${payload.url}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    if (sanitised != null && sanitised.changed) {
                        Spacer(Modifier.height(4.dp))
                        Text("cleaned: ${sanitised.clean}", color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("removed: ${sanitised.removed.joinToString(",")}", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
                is SharePayload.File -> Text("file: ${payload.uri}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "actions") {
            Column {
                suggested.forEach { cmd ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Fr3kPalette.Surface)
                            .clickable {
                                coroutine.launch {
                                    val ctx = Fr3kContext(
                                        deviceId = app.identity.deviceId,
                                        foregroundPackage = app.applicationContext.packageName,
                                        currentUrl = if (payload is SharePayload.Url) payload.url else null,
                                        selectedText = if (payload is SharePayload.Text) payload.text else null,
                                        fullText = if (payload is SharePayload.Text) payload.text else null,
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
                            }
                            .padding(8.dp),
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            StatusDot(status = com.mcpintelligence.fr3k.protocol.DeviceStatus.ONLINE, size = 6.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = cmd.title,
                                color = Fr3kPalette.Text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        if (lastResult != null) {
            Spacer(Modifier.height(12.dp))
            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "result") {
                Text(
                    text = lastResult!!,
                    color = Fr3kPalette.Ok,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("DISMISS", fontFamily = FontFamily.Monospace)
        }
    }
}

private fun looksLikeUrl(text: String): Boolean =
    text.trim().startsWith("http://") || text.trim().startsWith("https://")