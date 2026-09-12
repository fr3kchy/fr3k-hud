package com.mcpintelligence.fr3k.ui.blackwave

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.blackwave.FleetDeviceCard
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * §6 main BLACKWAVE screen.
 *
 * Shows the bridge status, the role manifest, the fleet, and the
 * feature-scope availability. Feature rows are honest: OTA / Live Apply
 * only report "available" when the authenticated role actually grants the
 * scope AND at least one device is online, otherwise they explain why and
 * offer a **Configure →** action into the right wizard step.
 */
class BlackwaveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Fr3kTheme { BlackwaveScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun BlackwaveScreen(onClose: () -> Unit) {
    val app = Fr3kApplication.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember { mutableStateOf<BridgeProbe?>(null) }
    var probing by remember { mutableStateOf(false) }

    fun runProbe() {
        scope.launch {
            probing = true
            probe = probeBridge(app)
            probing = false
        }
    }

    LaunchedEffect(Unit) { runProbe() }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "▰ BLACKWAVE",
                        color = Fr3kPalette.Accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "§6 setup · §7 add device",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                OutlinedButton(onClick = onClose) {
                    Text("BACK", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            val current = probe
            Fr3kPanel(title = "bridge") {
                StatusRow(
                    "endpoint",
                    if (current?.reachable == true) BwLevel.PASS else BwLevel.FAIL,
                    current?.endpoint ?: "not probed",
                )
                StatusRow(
                    "credential",
                    if (current?.hasCredential == true) BwLevel.PASS else BwLevel.FAIL,
                    if (current?.hasCredential == true) "stored (encrypted)" else "missing — set in SETUP",
                )
                StatusRow(
                    "identity",
                    BwLevel.PASS,
                    app.identity.deviceId,
                )
                StatusRow(
                    "role",
                    current?.let { if (it.role != null && !it.role.isExpired) BwLevel.PASS else BwLevel.WARN } ?: BwLevel.FAIL,
                    current?.let {
                        val role = it.role
                        if (role == null) "no role manifest"
                        else "trust ${role.trust_tier} · scopes ${role.allowed_scopes.size}" +
                            if (role.isExpired) " · EXPIRED" else ""
                    } ?: "unprobed",
                )
                StatusRow(
                    "fleet",
                    if ((current?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
                    if (current == null) "unprobed"
                    else "${current.fleetOnline}/${current.fleetAccounted} online" +
                        if (current.fleet?.stale == true) " · stale cache" else "",
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { runProbe() },
                        enabled = !probing,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) {
                        Text(if (probing) "PROBING…" else "REPROBE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Fr3kPanel(title = "actions") {
                Button(
                    onClick = { context.startActivity(Intent(app, BlackwaveSetupActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                ) {
                    Text("SET UP BLACKWAVE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { context.startActivity(Intent(app, BlackwaveAddDeviceActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Surface, contentColor = Fr3kPalette.Text),
                ) {
                    Text("ADD DEVICE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            FeatureAccessPanel(app, current, onConfigure = { step ->
                val intent = Intent(app, BlackwaveSetupActivity::class.java)
                    .putExtra(BlackwaveSetupActivity.EXTRA_INITIAL_STEP, step.ordinal)
                context.startActivity(intent)
            })
            Spacer(Modifier.height(12.dp))

            Fr3kPanel(title = "enabled scopes (${app.settings.settings.value.blackwaveEnabledScopes.size})") {
                val enabled = app.settings.settings.value.blackwaveEnabledScopes
                if (enabled.isEmpty()) {
                    Text(
                        "no scopes enabled yet — run SET UP BLACKWAVE",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                } else {
                    Column {
                        enabled.sorted().forEach { s ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
                                Text(
                                    text = BwScopes.label(s),
                                    color = Fr3kPalette.Text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Fr3kBadge(text = s, color = Fr3kPalette.AccentDim)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Fr3kPanel(title = "fleet (${current?.fleet?.devices?.size ?: 0})") {
                val devices = current?.fleet?.devices.orEmpty()
                if (devices.isEmpty()) {
                    Text(
                        "no devices visible — probe the bridge or add one manually",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                } else {
                    Column {
                        devices.sortedBy { it.display_name }.forEach { card ->
                            FleetRow(card) {
                                context.startActivity(
                                    Intent(app, BlackwaveAddDeviceActivity::class.java)
                                        .putExtra(BlackwaveAddDeviceActivity.EXTRA_MODEL_ID, card.model_id)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * §6 feature-scope panel. Live Apply / OTA must never surface as unexplained
 * disabled buttons — each row explains the exact reason and gives a direct
 * **Configure →** action into the matching wizard step.
 */
@Composable
private fun FeatureAccessPanel(app: Fr3kApplication, probe: BridgeProbe?, onConfigure: (WizardStep) -> Unit) {
    val online = probe?.fleetOnline ?: 0
    val hasOta = probe?.hasScope("ota.apply") == true
    val hasLive = probe?.hasScope("profile.apply") == true

    Fr3kPanel(title = "feature access") {
        // OTA
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("OTA FIRMWARE", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        hasOta && online > 0 -> "available on $online authenticated device(s)"
                        !hasOta -> "OTA unavailable: role lacks ota.apply scope."
                        else -> "OTA unavailable: no authenticated OTA-capable device connected."
                    },
                    color = if (hasOta && online > 0) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            if (hasOta && online > 0) {
                Fr3kBadge(text = "available", color = Fr3kPalette.Ok)
            } else {
                OutlinedButton(onClick = { onConfigure(WizardStep.OTA_CONFIG) }) {
                    Text("CONFIGURE →", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
            }
        }
        // Live Apply
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LIVE APPLY", color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        hasLive && online > 0 -> "available on $online authenticated device(s)"
                        !hasLive -> "Live Apply unavailable: role lacks profile.apply scope."
                        else -> "Live Apply unavailable: no authenticated device with profile.apply scope connected."
                    },
                    color = if (hasLive && online > 0) Fr3kPalette.Ok else Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            if (hasLive && online > 0) {
                Fr3kBadge(text = "available", color = Fr3kPalette.Ok)
            } else {
                OutlinedButton(onClick = { onConfigure(WizardStep.LIVE_APPLY_CONFIG) }) {
                    Text("CONFIGURE →", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun FleetRow(card: FleetDeviceCard, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(card.display_name.ifBlank { card.model_id }, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                text = listOf(card.model_id, card.device_class, if (card.firmware_version.isNotBlank()) "fw ${card.firmware_version}" else null)
                    .filterNotNull().joinToString(" · "),
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
        Fr3kBadge(
            text = card.online,
            color = when (card.online) {
                "online" -> Fr3kPalette.Ok
                "offline" -> Fr3kPalette.Err
                else -> Fr3kPalette.TextDim
            },
        )
    }
}