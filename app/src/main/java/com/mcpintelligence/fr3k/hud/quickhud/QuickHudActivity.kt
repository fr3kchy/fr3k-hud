package com.mcpintelligence.fr3k.hud.quickhud

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.R
import com.mcpintelligence.fr3k.protocol.DeviceStatus as ProtoDeviceStatus
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.StatusDot
import android.content.Intent
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity
import androidx.compose.ui.graphics.Color as GraphicsColor

/**
 * The quick HUD panel (§8). A compact overlay launched from the HUD orb (tap),
 * Quick Settings tile, or notification action. Shows:
 *
 *   - device id + fingerprint
 *   - profile-aware adapter status (Hermes, MeshCore, Meshtastic, GPS, Termux)
 *   - command palette entry
 *   - Ask About This
 *   - close
 *
 * Renders from current capability registry (no hardcoded service list — V2
 * mesh plugins will light up automatically when added).
 */
class QuickHudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { QuickHudScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun QuickHudScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val caps by app.capabilityRegistry.snapshot.collectAsState()
    val profile by remember { mutableStateOf(app.fr3kCore.profileForCurrent()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GraphicsColor.Black.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp),
        ) {
            com.mcpintelligence.fr3k.ui.Fr3kPanel(modifier = Modifier.fillMaxWidth(), title = "FR3K") {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = app.fr3kCore.identity.deviceId.take(20),
                            color = Fr3kPalette.Text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = "profile: $profile",
                            color = Fr3kPalette.TextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Fr3kPalette.Border)
                    Spacer(Modifier.height(8.dp))
                    AdapterRows(caps = caps.keys)
                }
            }

            Spacer(Modifier.height(12.dp))

            com.mcpintelligence.fr3k.ui.Fr3kPanel(modifier = Modifier.fillMaxWidth(), title = "ACTIONS") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Column {
                    QuickAction("OPEN COMMAND PALETTE") {
                        ctx.startActivity(Intent(ctx, CommandPaletteActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("ASK ABOUT THIS") {
                        ctx.startActivity(Intent(ctx, com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("OPEN DASHBOARD") {
                        ctx.startActivity(Intent(ctx, com.mcpintelligence.fr3k.ui.MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        onClose()
                    }
                    QuickAction("CLOSE") { onClose() }
                }
            }
        }
    }
}

@Composable
private fun AdapterRows(caps: Set<String>) {
    val ordered = listOf(
        "Hermes" to caps.any { it.startsWith("agent.") },
        "MeshCore" to caps.any { it.startsWith("meshcore.") },
        "Meshtastic" to caps.any { it.startsWith("meshtastic.") },
        "GPS" to caps.any { it.startsWith("location.") },
        "Termux" to caps.any { it.startsWith("termux.") },
        "Local AI" to caps.any { it.startsWith("ai.local.") },
    )
    ordered.forEach { (name, online) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                color = Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = if (online) "ONLINE" else "OFFLINE",
                    color = if (online) Fr3kPalette.Ok else Fr3kPalette.Err,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(
                    status = if (online) ProtoDeviceStatus.ONLINE else ProtoDeviceStatus.OFFLINE,
                    size = 6.dp,
                )
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Fr3kPalette.Surface)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = Fr3kPalette.Accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}