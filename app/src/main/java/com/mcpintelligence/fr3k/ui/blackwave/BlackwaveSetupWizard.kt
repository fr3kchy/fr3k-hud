package com.mcpintelligence.fr3k.ui.blackwave

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.integrations.blackwave.DeviceStatusResponse
import com.mcpintelligence.fr3k.integrations.blackwave.FleetDeviceCard
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.launch

/**
 * §6 BLACKWAVE setup wizard — 18 guided steps, single Compose activity.
 *
 * Steps are guided, not gated: NEXT is always enabled and each step shows
 * the real status for its concern plus a direct action where one exists.
 * The final steps persist endpoint/clientId/credential/scopes and run a
 * system-health summary with live probe results.
 */
enum class WizardStep(val title: String) {
    IDENTITY_PROFILE("identity/profile"),
    PERMISSIONS("permissions"),
    NETWORKING("networking"),
    BLUETOOTH("bluetooth"),
    WIFI_LAN_DISCOVERY("wi-fi/lan discovery"),
    CONNECT_HUD("connect fr3k hud"),
    DISCOVER_DEVICES("discover devices"),
    ADD_MANUAL("add manually"),
    PAIRING_AUTH("pairing/auth"),
    CAPABILITY_DISCOVERY("capability discovery"),
    IDENTITY_VERIFICATION("identity verification"),
    ENABLE_FEATURES("enable features"),
    OTA_CONFIG("ota config"),
    LIVE_APPLY_CONFIG("live apply config"),
    AGENT_CONTROL_PERMS("agent-control perms"),
    TEST_CONNECTIVITY("test connectivity"),
    SAVE_CONFIG("save config"),
    SYSTEM_HEALTH_SUMMARY("system-health summary"),
}

/** Wizard session state — survives permission round-trips and recomposition. */
class SetupState(
    initialEndpoint: String,
    initialClientId: String,
    initialCredential: String,
    initialScopes: Set<String>,
) {
    var stepIndex by mutableStateOf(0)
    var probing by mutableStateOf(false)
    var probe by mutableStateOf<BridgeProbe?>(null)
    var savedMessage by mutableStateOf<String?>(null)

    var endpoint by mutableStateOf(initialEndpoint)
    var clientId by mutableStateOf(initialClientId)
    var credential by mutableStateOf(initialCredential)

    var manualDeviceId by mutableStateOf("")
    var manualLookup by mutableStateOf<DeviceStatusResponse?>(null)
    var manualLookupError by mutableStateOf<String?>(null)

    var enabledScopes by mutableStateOf(initialScopes.toMutableSet())

    /** Bumped by onCreateResult → recomposes the PERMISSIONS step. */
    var permRound by mutableStateOf(0)
    var lastGranted by mutableStateOf<List<String>>(emptyList())

    fun toggleScope(scope: String) {
        enabledScopes = enabledScopes.toMutableSet().apply {
            if (!add(scope)) remove(scope)
        }
    }
}

class BlackwaveSetupActivity : ComponentActivity() {

    private lateinit var session: SetupState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = Fr3kApplication.get()
        val settings = app.settings.settings.value
        session = SetupState(
            initialEndpoint = settings.blackwaveEndpoint,
            initialClientId = settings.blackwaveClientId,
            initialCredential = app.secureStore.get(settings.blackwaveCredentialKey) ?: "",
            initialScopes = settings.blackwaveEnabledScopes.toSet(),
        )
        val startStep = intent?.getIntExtra(EXTRA_INITIAL_STEP, 0) ?: 0
        setContent {
            Fr3kTheme {
                SetupWizard(activity = this, session = session, startStep = startStep.coerceIn(0, WizardStep.entries.lastIndex))
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BW_SETUP) {
            session.lastGranted = permissions.filterIndexed { i, _ ->
                grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            session.permRound++
        }
    }

    companion object {
        const val EXTRA_INITIAL_STEP = "extra_initial_step"
        const val REQ_BW_SETUP = 9601
    }
}

@Composable
private fun SetupWizard(
    activity: ComponentActivity,
    session: SetupState,
    startStep: Int,
) {
    val app = Fr3kApplication.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { session.stepIndex = startStep }

    fun probeEntered() {
        scope.launch {
            session.probing = true
            val endpoint = session.endpoint.trim().ifBlank { "https://blackwave.local:8878" }
            val temp = BlackwaveBridgeClient(
                endpointProvider = { endpoint },
                credentialProvider = { session.credential.ifBlank { null } },
                clientIdProvider = { session.clientId.trim().ifBlank { "fr3k-hud" } },
            )
            session.probe = probeBridgeWith(
                bridge = temp,
                endpoint = endpoint,
                hasCredential = session.credential.isNotBlank(),
                clientId = session.clientId.trim().ifBlank { "fr3k-hud" },
            )
            session.probing = false
        }
    }

    fun settings() = app.settings.settings.value

    // Persist exactly once when reaching the SAVE_CONFIG step.
    LaunchedEffect(session.stepIndex) {
        val step = WizardStep.entries[session.stepIndex]
        if (step == WizardStep.SAVE_CONFIG) {
            val endpoint = session.endpoint.trim().ifBlank { settings().blackwaveEndpoint }
            val clientId = session.clientId.trim().ifBlank { settings().blackwaveClientId }
            app.settings.update { s -> s.copy(blackwaveEndpoint = endpoint, blackwaveClientId = clientId) }
            if (session.credential.isNotBlank()) {
                app.secureStore.put(settings().blackwaveCredentialKey, session.credential.trim())
            }
            app.settings.update { s -> s.copy(blackwaveEnabledScopes = session.enabledScopes.toList()) }
            session.savedMessage = "saved @ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
        }
        if (step == WizardStep.TEST_CONNECTIVITY || step == WizardStep.CONNECT_HUD ||
            step == WizardStep.WIFI_LAN_DISCOVERY || step == WizardStep.CAPABILITY_DISCOVERY ||
            step == WizardStep.SYSTEM_HEALTH_SUMMARY) {
            probeEntered()
        }
    }

    val step = WizardStep.entries[session.stepIndex]
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
                    Text("▰ BLACKWAVE SETUP", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        "step ${session.stepIndex + 1}/${WizardStep.entries.size} · ${step.title}",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                OutlinedButton(onClick = { activity.finish() }) {
                    Text("EXIT", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            Fr3kPanel(title = step.title) {
                WizardStepBody(
                    step = step,
                    session = session,
                    app = app,
                    context = context,
                    activity = activity,
                    onProbe = ::probeEntered,
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { session.stepIndex = (session.stepIndex - 1).coerceAtLeast(0) },
                    enabled = session.stepIndex > 0,
                ) { Text("PREV", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                if (step == WizardStep.SYSTEM_HEALTH_SUMMARY) {
                    Button(
                        onClick = { activity.finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("DONE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                } else {
                    Button(
                        onClick = { session.stepIndex = (session.stepIndex + 1).coerceAtMost(WizardStep.entries.lastIndex) },
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) {
                        Text(
                            if (step == WizardStep.SAVE_CONFIG) "SUMMARY →" else "NEXT",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun WizardStepBody(
    step: WizardStep,
    session: SetupState,
    app: Fr3kApplication,
    context: Context,
    activity: ComponentActivity,
    onProbe: () -> Unit,
) {
    val probe = session.probe
    when (step) {
        WizardStep.IDENTITY_PROFILE -> {
            Text(
                "This device's BLACKWAVE identity and the fleet bridge it talks to.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("device", BwLevel.PASS, app.identity.deviceId)
            StatusRow("client id", BwLevel.PASS, session.clientId)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = session.endpoint,
                onValueChange = { session.endpoint = it },
                label = { Text("endpoint (https://…:8878)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = session.clientId,
                onValueChange = { session.clientId = it },
                label = { Text("client id", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = session.credential,
                onValueChange = { session.credential = it },
                label = { Text("credential (stored encrypted)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        WizardStep.PERMISSIONS -> {
            Text(
                "Runtime permissions the BLACKWAVE screens need. Missing ones are requested inline.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val btGranted = PermissionRegistry.granted(context, PermissionRegistry.Feature.BLUETOOTH)
            StatusRow(
                "bluetooth",
                if (btGranted) BwLevel.PASS else BwLevel.FAIL,
                if (btGranted) "BLUETOOTH_CONNECT + BLUETOOTH_SCAN granted"
                else "needed for BLE discovery / device pairing",
            )
            if (session.lastGranted.isNotEmpty()) {
                Text(
                    "last grant: ${session.lastGranted.joinToString(", ")}",
                    color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                )
            }
            if (!btGranted) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        session.lastGranted = emptyList()
                        PermissionRegistry.request(activity, PermissionRegistry.Feature.BLUETOOTH, BlackwaveSetupActivity.REQ_BW_SETUP)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                ) { Text("REQUEST BLUETOOTH", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
                session.permRound // observe — recomposes after the OS dialog round-trip
            }
        }

        WizardStep.NETWORKING -> {
            Text(
                "Network reachability for the fleet bridge. INTERNET + ACCESS_NETWORK_STATE are install-time grants.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val onCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            StatusRow("internet", BwLevel.PASS, "declared INTERNET permission")
            StatusRow("transport", BwLevel.PASS, if (onWifi) "wifi" else if (onCell) "cellular" else if (caps != null) "other" else "no active network")
            StatusRow(
                "endpoint reach",
                probe?.level ?: BwLevel.WARN,
                probe?.let { if (it.reachable) it.endpoint else "unreachable (${it.error ?: "no probe"})" } ?: "tap next step to probe",
            )
        }

        WizardStep.BLUETOOTH -> {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            val scanGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            StatusRow(
                "adapter",
                if (adapter != null) BwLevel.PASS else BwLevel.FAIL,
                adapter?.name ?: "no bluetooth adapter on this device",
            )
            StatusRow(
                "adapter state",
                when {
                    adapter == null -> BwLevel.FAIL
                    adapter.isEnabled -> BwLevel.PASS
                    else -> BwLevel.WARN
                },
                if (adapter?.isEnabled == true) "enabled" else "disabled — toggle in system settings",
            )
            StatusRow(
                "scan grant",
                if (scanGranted) BwLevel.PASS else BwLevel.FAIL,
                if (scanGranted) "BLUETOOTH_SCAN granted" else "needed before BLE scanning (see PERMISSIONS step)",
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "BLE transports are scanned from the ADD DEVICE screen.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            )
        }

        WizardStep.WIFI_LAN_DISCOVERY -> {
            Text(
                "Discovery over the local network via the fleet bridge endpoint.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("endpoint", probe?.level ?: BwLevel.WARN, session.endpoint)
            StatusRow(
                "role",
                probe?.role?.let { if (it.isExpired) BwLevel.WARN else BwLevel.PASS } ?: BwLevel.WARN,
                probe?.role?.let { "trust ${it.trust_tier} · scopes ${it.allowed_scopes.size}" } ?: "not fetched",
            )
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "PROBING…" else "PROBE NOW", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.CONNECT_HUD -> {
            Text(
                "The FR3K HUD app is the bridge client for this identity.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("bridge", probe?.level ?: BwLevel.WARN, probe?.let { if (it.reachable) "connected" else it.error ?: "unprobed" } ?: "unprobed")
            StatusRow("hud service", if (hudServiceRunning(context)) BwLevel.PASS else BwLevel.WARN, if (hudServiceRunning(context)) "running" else "not running (orb off)")
            StatusRow("credential", if (session.credential.isNotBlank()) BwLevel.PASS else BwLevel.FAIL, if (session.credential.isNotBlank()) "set" else "missing — enter on step 1")
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "PROBING…" else "RE-CONNECT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.DISCOVER_DEVICES -> {
            val devices = probe?.fleet?.devices.orEmpty()
            StatusRow(
                "fleet",
                if ((probe?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
                probe?.let { "${it.fleetOnline}/${it.fleetAccounted} online" + if (it.fleet?.stale == true) " (stale)" else "" } ?: "unprobed",
            )
            if (devices.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column {
                    devices.sortedBy { it.display_name }.forEach { card ->
                        DiscoveryRow(card)
                    }
                }
            } else {
                Text(
                    "no devices discovered — try the ADD MANUALLY step or ADD DEVICE → BLE",
                    color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                )
            }
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "SCANNING…" else "SCAN FLEET", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.ADD_MANUAL -> {
            Text(
                "Add a device by its BLACKWAVE model id (§7). The bridge looks it up over the network.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = session.manualDeviceId,
                onValueChange = { session.manualDeviceId = it },
                label = { Text("model id (e.g. bw-core-0007)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    val id = session.manualDeviceId.trim()
                    if (id.isEmpty()) return@Button
                    session.manualLookupError = null
                    session.manualLookup = null
                    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val result = runCatching { app.blackwaveBridgeClient.fetchDeviceStatus(id) }.getOrNull()
                        session.manualLookup = result?.getOrNull()
                        if (result?.isFailure == true || session.manualLookup == null) {
                            session.manualLookupError = result?.exceptionOrNull()?.message ?: "lookup failed"
                        }
                    }
                    scope
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("LOOK UP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            session.manualLookup?.let { lookup ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "found: ${(lookup.identity["display_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: session.manualDeviceId} · ${lookup.observation_status}",
                    color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                )
            }
            session.manualLookupError?.let {
                Text(it, color = Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }

        WizardStep.PAIRING_AUTH -> {
            Text(
                "Pairing credential + role authenticity against the bridge.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow(
                "credential",
                if (session.credential.isNotBlank()) BwLevel.PASS else BwLevel.FAIL,
                if (session.credential.isNotBlank()) "set" else "missing — enter on step 1",
            )
            val role = probe?.role
            StatusRow(
                "role manifest",
                when { role == null -> BwLevel.WARN; role.isExpired -> BwLevel.WARN; else -> BwLevel.PASS },
                role?.let { "trust ${it.trust_tier} · issuer ${it.issuer}" + if (it.isExpired) " · EXPIRED" else "" } ?: "not fetched",
            )
            StatusRow(
                "device access",
                if (role == null) BwLevel.WARN else BwLevel.PASS,
                role?.device_access ?: "unknown until role fetched",
            )
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "VERIFYING…" else "VERIFY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.CAPABILITY_DISCOVERY -> {
            Text(
                "Capabilities the role grants, from the role manifest.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val caps = probe?.role?.capabilities_map.orEmpty()
            StatusRow("capabilities", if (caps.isNotEmpty()) BwLevel.PASS else BwLevel.WARN, "${caps.size} mapped")
            if (caps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column {
                    caps.toList().sortedBy { it.first }.take(10).forEach { (scope, capId) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
                            Text(scope, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
                            Text(capId, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                    }
                }
            }
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "DISCOVERING…" else "DISCOVER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.IDENTITY_VERIFICATION -> {
            Text(
                "The role's bound identity should match this device.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val roleIdentity = probe?.role?.identity
            val verified = roleIdentity != null && roleIdentity == session.clientId.trim()
            StatusRow(
                "this device",
                BwLevel.PASS,
                "${app.identity.deviceId} · client ${session.clientId}",
            )
            StatusRow(
                "role identity",
                when { roleIdentity == null -> BwLevel.WARN; verified -> BwLevel.PASS; else -> BwLevel.FAIL },
                roleIdentity ?: "not fetched",
            )
            if (roleIdentity != null && !verified) {
                Text(
                    "mismatch — role binds '${roleIdentity}', app sends client '${session.clientId.trim()}'",
                    color = Fr3kPalette.Warn, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                )
            }
        }

        WizardStep.ENABLE_FEATURES -> {
            Text(
                "Choose which scopes this device may use. Scope list comes from the role manifest.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val scopes = probe?.role?.allowed_scopes?.ifEmpty { null } ?: BwScopes.LABELS.keys.sorted()
            Column {
                scopes.forEach { scope ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { session.toggleScope(scope) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scope in session.enabledScopes,
                            onCheckedChange = { session.toggleScope(scope) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(BwScopes.label(scope), color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            Text(scope, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "enabled: ${session.enabledScopes.size}",
                color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }

        WizardStep.OTA_CONFIG -> {
            val hasOta = probe?.hasScope("ota.apply") == true
            val online = probe?.fleetOnline ?: 0
            StatusRow(
                "ota scope",
                if (hasOta) BwLevel.PASS else BwLevel.FAIL,
                if (hasOta) "ota.apply granted" else "role lacks ota.apply scope",
            )
            StatusRow(
                "fleet",
                if (online > 0) BwLevel.PASS else BwLevel.FAIL,
                "${probe?.fleetOnline ?: 0}/${probe?.fleetAccounted ?: 0} online",
            )
            Text(
                when {
                    hasOta && online > 0 -> "OTA available on $online authenticated device(s)."
                    !hasOta -> "OTA unavailable: role lacks ota.apply scope. Request it from the fleet admin."
                    else -> "OTA unavailable: no authenticated OTA-capable device connected."
                },
                color = if (hasOta && online > 0) Fr3kPalette.Ok else Fr3kPalette.Warn,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }

        WizardStep.LIVE_APPLY_CONFIG -> {
            val hasLive = probe?.hasScope("profile.apply") == true
            val online = probe?.fleetOnline ?: 0
            StatusRow(
                "live apply scope",
                if (hasLive) BwLevel.PASS else BwLevel.FAIL,
                if (hasLive) "profile.apply granted" else "role lacks profile.apply scope",
            )
            StatusRow(
                "fleet",
                if (online > 0) BwLevel.PASS else BwLevel.FAIL,
                "${probe?.fleetOnline ?: 0}/${probe?.fleetAccounted ?: 0} online",
            )
            Text(
                when {
                    hasLive && online > 0 -> "Live Apply available on $online authenticated device(s)."
                    !hasLive -> "Live Apply unavailable: role lacks profile.apply scope. Request it from the fleet admin."
                    else -> "Live Apply unavailable: no authenticated device with profile.apply scope connected."
                },
                color = if (hasLive && online > 0) Fr3kPalette.Ok else Fr3kPalette.Warn,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }

        WizardStep.AGENT_CONTROL_PERMS -> {
            Text(
                "Agent-control permissions follow the role: what agents may do on managed devices.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            val role = probe?.role
            StatusRow(
                "delegation depth",
                BwLevel.PASS,
                role?.delegation_depth?.toString() ?: "unknown until role fetched",
            )
            StatusRow(
                "approval req",
                if (role?.approval_required.isNullOrEmpty()) BwLevel.PASS else BwLevel.WARN,
                role?.approval_required?.joinToString(", ") ?: "none",
            )
        }

        WizardStep.TEST_CONNECTIVITY -> {
            Text(
                "Live end-to-end connectivity against the entered endpoint, credential and client id.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("bridge", probe?.level ?: BwLevel.WARN, probe?.let { if (it.reachable) "connected" else it.error ?: "unprobed" } ?: "unprobed")
            StatusRow(
                "role",
                probe?.role?.let { if (it.isExpired) BwLevel.WARN else BwLevel.PASS } ?: BwLevel.WARN,
                probe?.role?.let { "trust ${it.trust_tier} · scopes ${it.allowed_scopes.size}" } ?: "not fetched",
            )
            StatusRow(
                "fleet",
                if ((probe?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
                probe?.let { "${it.fleetOnline}/${it.fleetAccounted} online" } ?: "not fetched",
            )
            StatusRow(
                "credential",
                if (session.credential.isNotBlank()) BwLevel.PASS else BwLevel.FAIL,
                if (session.credential.isNotBlank()) "set" else "missing",
            )
            Button(
                onClick = onProbe,
                enabled = !session.probing,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (session.probing) "TESTING…" else "RUN TEST", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        }

        WizardStep.SAVE_CONFIG -> {
            Text(
                "Persisting endpoint, credential, client id and enabled scopes.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("endpoint", BwLevel.PASS, session.endpoint.trim().ifBlank { settingsEndpoint(app) })
            StatusRow("client id", BwLevel.PASS, session.clientId.trim().ifBlank { settingsClientId(app) })
            StatusRow("credential", if (session.credential.isBlank()) BwLevel.FAIL else BwLevel.PASS, if (session.credential.isBlank()) "not set — will keep existing" else "stored in encrypted store")
            StatusRow("scopes", if (session.enabledScopes.isEmpty()) BwLevel.WARN else BwLevel.PASS, "${session.enabledScopes.size} enabled")
            Spacer(Modifier.height(6.dp))
            Text(
                session.savedMessage ?: "saving…",
                color = if (session.savedMessage != null) Fr3kPalette.Ok else Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }

        WizardStep.SYSTEM_HEALTH_SUMMARY -> {
            Text(
                "Final health summary. DONE exits the wizard.",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
            StatusRow("bridge", probe?.level ?: BwLevel.WARN, probe?.let { if (it.reachable) "connected" else it.error ?: "unprobed" } ?: "unprobed")
            StatusRow(
                "role",
                probe?.role?.let { if (it.isExpired) BwLevel.WARN else BwLevel.PASS } ?: BwLevel.WARN,
                probe?.role?.let { "trust ${it.trust_tier} · scopes ${it.allowed_scopes.size}" } ?: "not fetched",
            )
            StatusRow(
                "fleet",
                if ((probe?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
                probe?.let { "${it.fleetOnline}/${it.fleetAccounted} online" } ?: "not fetched",
            )
            StatusRow(
                "features",
                if (probe?.hasScope("ota.apply") == true || probe?.hasScope("profile.apply") == true) BwLevel.PASS else BwLevel.WARN,
                buildString {
                    append("ota=${if (probe?.hasScope("ota.apply") == true) "on" else "off"} ")
                    append("live=${if (probe?.hasScope("profile.apply") == true) "on" else "off"}")
                },
            )
            StatusRow("credential", if (session.credential.isNotBlank()) BwLevel.PASS else BwLevel.FAIL, if (session.credential.isNotBlank()) "stored" else "missing")
            Spacer(Modifier.height(6.dp))
            Text(
                app.settings.settings.value.blackwaveEnabledScopes.takeIf { it.isNotEmpty() }?.let { "enabled scopes: ${it.size}" } ?: "no scopes enabled",
                color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            )
        }
    }
}

private fun settingsEndpoint(app: Fr3kApplication): String = app.settings.settings.value.blackwaveEndpoint
private fun settingsClientId(app: Fr3kApplication): String = app.settings.settings.value.blackwaveClientId

@Composable
private fun DiscoveryRow(card: FleetDeviceCard) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("›", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(card.display_name.ifBlank { card.model_id }, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(card.model_id, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        Text(card.online, color = if (card.online == "online") Fr3kPalette.Ok else Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}