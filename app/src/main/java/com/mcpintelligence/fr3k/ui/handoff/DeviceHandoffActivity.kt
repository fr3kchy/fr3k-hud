package com.mcpintelligence.fr3k.ui.handoff

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.DeviceHandoffAdapter
import com.mcpintelligence.fr3k.protocol.DeviceStatus
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.StatusDot

/**
 * "Open on…" device handoff surface (§19). Shows every known FR3K device
 * and lets the user pick one. The handoff adapter then produces the right
 * payload representation for that device:
 *
 *   - laptop (linux/darwin/windows) → URL + full text
 *   - embedded device              → short text snippet
 *   - mesh radio                   → compact mesh payload
 */
class DeviceHandoffActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        val targetId = intent.getStringExtra("deviceId")
        val initialContent = intent.getStringExtra("content")
        setContent {
            Fr3kTheme {
                DeviceHandoffScreen(
                    initialTargetId = targetId,
                    initialContent = initialContent,
                    onClose = { finish() },
                )
            }
        }
    }
}

@Composable
private fun DeviceHandoffScreen(
    initialTargetId: String?,
    initialContent: String?,
    onClose: () -> Unit,
) {
    val app = Fr3kApplication.get()
    val devices by app.deviceRegistry.snapshot.collectAsState()
    val context by app.fr3kCore.contextEngine.current.collectAsState()
    val caps by app.capabilityRegistry.snapshot.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "OPEN ON…",
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "context") {
                Column {
                    Text("url: ${context.url ?: "—"}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("text: ${(context.selectedText ?: context.fullText ?: "—").take(80)}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("package: ${context.sourcePackage ?: "—"}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Fr3kPalette.Border)
            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "fleet (${devices.size})") {
                Column {
                    if (devices.isEmpty()) {
                        Text(
                            text = "no peers discovered · connect a LAN FR3K device to populate",
                            color = Fr3kPalette.TextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    } else {
                        devices.forEach { dev ->
                            DeviceRow(
                                dev,
                                isInitialTarget = dev.deviceId == initialTargetId,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (initialTargetId != null && devices.any { it.deviceId == initialTargetId }) {
                val target = devices.first { it.deviceId == initialTargetId }
                val ctx = app.fr3kCore.contextEngine.toCommandContext(
                    deviceId = app.identity.deviceId,
                    capabilities = caps.keys,
                    consentLevel = com.mcpintelligence.fr3k.core.ConsentLevel.NORMAL,
                )
                val payload = DeviceHandoffAdapter().adapt(target, ctx, initialContent)
                com.mcpintelligence.fr3k.ui.Fr3kPanel(title = "ready to send") {
                    Column {
                        Text("target: ${target.name}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("deviceId: ${target.deviceId}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("representation: ${payload.representation}", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text("mime: ${payload.mime}", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("content:", color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Fr3kPalette.Surface)
                                .padding(8.dp),
                        ) {
                            Text(
                                text = payload.content.take(400),
                                color = Fr3kPalette.Text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DISMISS", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DeviceRow(
    dev: com.mcpintelligence.fr3k.protocol.DeviceManifest,
    isInitialTarget: Boolean,
) {
    val bg = if (isInitialTarget) Fr3kPalette.AccentDim else Fr3kPalette.Surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            StatusDot(status = dev.status, size = 6.dp, modifier = Modifier.padding(end = 6.dp))
            Text(dev.name, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "${dev.deviceId} · ${dev.platform} v${dev.version}",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Text(
            text = "transports: ${dev.transports.joinToString(", ")}",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}