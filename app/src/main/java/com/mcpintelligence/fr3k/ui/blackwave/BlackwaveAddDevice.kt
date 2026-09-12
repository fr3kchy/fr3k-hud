package com.mcpintelligence.fr3k.ui.blackwave

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.mcpintelligence.fr3k.integrations.blackwave.BlackwaveBridgeClient
import com.mcpintelligence.fr3k.integrations.blackwave.DeviceStatusResponse
import com.mcpintelligence.fr3k.integrations.blackwave.FleetDeviceCard
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.ui.Fr3kBadge
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kPanel
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/**
 * §7 BLACKWAVE add-device screen — the full transport inventory.
 *
 * Capability reporting is honest per the pass requirements: BLE (real scan),
 * Local Wi-Fi/LAN (real fleet probe), USB (real host enumeration), Manual
 * IP/hostname (real bridge probe) and Manual device ID (real /devices/{id}
 * lookup) are functional. Device AP, Serial and QR provisioning are not
 * implemented in this build and are surfaced as such, each with a direct
 * **Configure →** action into the matching setup-wizard step — never as
 * unexplained disabled buttons.
 */
enum class BwTransport(val title: String, val desc: String, val tag: String) {
    BLE("bluetooth low energy", "scan for advertising BLACKWAVE devices", "scan"),
    LOCAL_LAN("local wi-fi/lan", "discover devices via the fleet bridge", "bridge"),
    DEVICE_AP("device access point", "connect to a device-hosted access point", "n/a"),
    USB("usb", "enumerate USB-attached hardware", "host"),
    SERIAL("serial", "UART / serial console transport", "n/a"),
    QR("qr provisioning", "scan a provisioning QR code", "n/a"),
    MANUAL_IP("manual ip/hostname", "probe a bridge endpoint by address", "probe"),
    MANUAL_ID("manual device id", "look up a device by model id", "lookup"),
}

/** One discovered BLE advertiser. */
data class BleResult(val address: String, val name: String, val rssi: Int)

/**
 * Add-device session state — survives permission round-trips, probe fetches
 * and recomposition. Owns per-transport probe results.
 */
class AddDeviceState(initialModelId: String?) {
    var transport by mutableStateOf<BwTransport?>(null)
    var permRound by mutableStateOf(0)
    var lastGranted by mutableStateOf<List<String>>(emptyList())

    var lanProbing by mutableStateOf(false)
    var lanProbe by mutableStateOf<BridgeProbe?>(null)

    var ipHost by mutableStateOf("")
    var ipProbing by mutableStateOf(false)
    var ipProbe by mutableStateOf<BridgeProbe?>(null)
    var ipError by mutableStateOf<String?>(null)
    var savedBridgeMessage by mutableStateOf<String?>(null)

    var manualId by mutableStateOf(initialModelId ?: "")
    var manualLookup by mutableStateOf<DeviceStatusResponse?>(null)
    var manualLookupError by mutableStateOf<String?>(null)
    var manualProbing by mutableStateOf(false)

    var detailModelId by mutableStateOf("")
    var detail by mutableStateOf<DeviceStatusResponse?>(null)
    var detailError by mutableStateOf<String?>(null)
    var detailProbing by mutableStateOf(false)
}

/**
 * Passive BLE scanner with its own state so the scan survives recomposition
 * and is torn down on dispose. Callbacks arrive on the main thread.
 */
class BleScanner(private val adapter: BluetoothAdapter?) {
    val results = mutableStateOf<List<BleResult>>(emptyList())
    val scanning = mutableStateOf(false)
    val error = mutableStateOf<String?>(null)

    private var callback: ScanCallback? = null

    fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            error.value = "no BLE scanner (adapter off?)"
            return
        }
        if (scanning.value) return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = device.name
                if (name.isNullOrBlank()) return
                val entry = BleResult(device.address, name, result.rssi)
                results.value = (results.value.filterNot { it.address == entry.address } + entry)
                    .sortedByDescending { it.rssi }
            }

            override fun onScanFailed(errorCode: Int) {
                error.value = "scan failed (code $errorCode)"
                scanning.value = false
            }
        }
        callback = cb
        scanning.value = true
        try {
            scanner.startScan(cb)
        } catch (e: Throwable) {
            error.value = "scan failed: ${e.message}"
            callback = null
            scanning.value = false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        scanning.value = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Throwable) {
            // adapter already gone — nothing to stop
        }
    }
}

class BlackwaveAddDeviceActivity : ComponentActivity() {

    private lateinit var session: AddDeviceState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = AddDeviceState(initialModelId = intent?.getStringExtra(EXTRA_MODEL_ID))
        setContent {
            Fr3kTheme { AddDeviceScreen(activity = this, session = session) }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BW_ADD) {
            session.lastGranted = permissions.filterIndexed { i, _ ->
                grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            }
            session.permRound++
        }
    }

    companion object {
        const val EXTRA_MODEL_ID = "extra_model_id"
        const val REQ_BW_ADD = 9602
    }
}

@Composable
private fun AddDeviceScreen(activity: ComponentActivity, session: AddDeviceState) {
    val app = Fr3kApplication.get()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val bleScanner = remember(adapter) { BleScanner(adapter) }
    val scanGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED

    fun settings() = app.settings.settings.value

    fun probeLan() {
        scope.launch {
            session.lanProbing = true
            session.lanProbe = probeBridge(app)
            session.lanProbing = false
        }
    }

    fun probeIp() {
        val host = session.ipHost.trim()
        if (host.isEmpty()) return
        val endpoint = when {
            host.startsWith("http://") || host.startsWith("https://") -> host
            host.matches(Regex(".*:\\d+$")) -> "https://$host"
            else -> "https://$host:8878"
        }
        scope.launch {
            session.ipProbing = true
            session.ipError = null
            session.ipProbe = probeBridgeWith(
                bridge = BlackwaveBridgeClient(
                    endpointProvider = { endpoint },
                    credentialProvider = { app.secureStore.get(settings().blackwaveCredentialKey) },
                    clientIdProvider = { settings().blackwaveClientId.ifBlank { "fr3k-hud" } },
                ),
                endpoint = endpoint,
                hasCredential = !app.secureStore.get(settings().blackwaveCredentialKey).isNullOrBlank(),
                clientId = settings().blackwaveClientId.ifBlank { "fr3k-hud" },
            )
            session.ipProbing = false
        }
    }

    fun saveBridge(endpoint: String) {
        app.settings.update { s -> s.copy(blackwaveEndpoint = endpoint) }
        session.savedBridgeMessage =
            "bridge endpoint saved @ ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}"
    }

    fun lookUp() {
        val id = session.manualId.trim()
        if (id.isEmpty()) return
        scope.launch {
            session.manualProbing = true
            session.manualLookupError = null
            session.manualLookup = null
            val result = withContext(Dispatchers.IO) {
                runCatching { app.blackwaveBridgeClient.fetchDeviceStatus(id) }
            }.getOrNull()
            val status = result?.getOrNull()
            session.manualLookup = status
            if (status != null) {
                session.detailModelId = id
                session.detail = status
                session.detailError = null
            } else {
                session.manualLookupError = result?.exceptionOrNull()?.message ?: "lookup failed"
            }
            session.manualProbing = false
        }
    }

    fun fetchDetail(id: String) {
        scope.launch {
            session.detailProbing = true
            session.detailError = null
            session.detailModelId = id
            val result = withContext(Dispatchers.IO) {
                runCatching { app.blackwaveBridgeClient.fetchDeviceStatus(id) }
            }.getOrNull()
            val status = result?.getOrNull()
            session.detail = status
            if (status == null) {
                session.detailError = result?.exceptionOrNull()?.message ?: "status fetch failed"
            }
            session.detailProbing = false
        }
    }

    // Auto-fetch detail when opened with a fleet EXTRA_MODEL_ID.
    LaunchedEffect(Unit) {
        val id = session.manualId
        if (id.isNotBlank()) fetchDetail(id)
    }

    // Keep the BLE scan alive only while the BLE transport is open and granted.
    LaunchedEffect(session.transport, scanGranted, session.permRound) {
        if (session.transport == BwTransport.BLE && scanGranted) {
            bleScanner.start()
        } else {
            bleScanner.stop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { bleScanner.stop() }
    }

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
                    Text("▰ BLACKWAVE ADD DEVICE", color = Fr3kPalette.Accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        "§7 transports · honest capability report",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                }
                OutlinedButton(onClick = { activity.finish() }) {
                    Text("BACK", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            Fr3kPanel(title = "transport") {
                Column {
                    BwTransport.entries.forEach { t ->
                        TransportRow(
                            t = t,
                            selected = session.transport == t,
                            onClick = { session.transport = if (session.transport == t) null else t },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            when (session.transport) {
                BwTransport.BLE -> BleTransportBlock(activity, session, adapter, scanGranted, bleScanner)
                BwTransport.LOCAL_LAN -> LanTransportBlock(session, onProbe = ::probeLan, onOpen = ::fetchDetail)
                BwTransport.DEVICE_AP -> UnavailableBlock(
                    title = BwTransport.DEVICE_AP.title,
                    reason = "device-hosted access-point provisioning is not implemented in this build — Wi‑Fi/LAN discovery over the bridge is the supported path.",
                    wizardStep = WizardStep.WIFI_LAN_DISCOVERY,
                    context = context,
                )
                BwTransport.USB -> UsbTransportBlock(context)
                BwTransport.SERIAL -> UnavailableBlock(
                    title = BwTransport.SERIAL.title,
                    reason = "serial (UART) provisioning requires a USB-serial shim not present in this build — add the device by its model id instead.",
                    wizardStep = WizardStep.ADD_MANUAL,
                    context = context,
                )
                BwTransport.QR -> UnavailableBlock(
                    title = BwTransport.QR.title,
                    reason = "camera QR provisioning is not implemented in this build — paste the model id from the QR instead.",
                    wizardStep = WizardStep.ADD_MANUAL,
                    context = context,
                )
                BwTransport.MANUAL_IP -> ManualIpTransportBlock(session, onProbe = ::probeIp, onSave = ::saveBridge)
                BwTransport.MANUAL_ID -> ManualIdTransportBlock(session, onLookUp = ::lookUp)
                null -> Text(
                    "tap a transport above to add a device",
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }

            if (session.detailProbing && session.detail == null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "fetching device status…",
                    color = Fr3kPalette.Accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
            session.detail?.let { detail ->
                Spacer(Modifier.height(12.dp))
                DeviceDetailPanel(session.detailModelId, detail)
            }
            session.detailError?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    "detail error: $it",
                    color = Fr3kPalette.Err,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TransportRow(t: BwTransport, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) Fr3kPalette.Surface else Fr3kPalette.Bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "●" else "○",
            color = if (selected) Fr3kPalette.Accent else Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = t.title,
                color = if (selected) Fr3kPalette.Accent else Fr3kPalette.Text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = t.desc,
                color = Fr3kPalette.TextDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
            )
        }
        Fr3kBadge(text = t.tag, color = if (selected) Fr3kPalette.AccentDim else Fr3kPalette.TextDim)
    }
}

@Composable
private fun BleTransportBlock(
    activity: ComponentActivity,
    session: AddDeviceState,
    adapter: BluetoothAdapter?,
    scanGranted: Boolean,
    scanner: BleScanner,
) {
    Fr3kPanel(title = BwTransport.BLE.title) {
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
            if (scanGranted) "BLUETOOTH_SCAN granted" else "needed before scanning",
        )
        if (!scanGranted) {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    session.lastGranted = emptyList()
                    PermissionRegistry.request(activity, PermissionRegistry.Feature.BLUETOOTH, BlackwaveAddDeviceActivity.REQ_BW_ADD)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("REQUEST BLUETOOTH", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            session.permRound // observe — recomposes after the OS dialog round-trip
            if (session.lastGranted.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "last grant: ${session.lastGranted.joinToString(", ")}",
                    color = Fr3kPalette.Ok,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { if (scanner.scanning.value) scanner.stop() else scanner.start() },
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text(if (scanner.scanning.value) "STOP SCAN" else "START SCAN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            scanner.error.value?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (scanner.results.value.isEmpty()) {
                Text(
                    "no advertisers seen yet — scanning is passive BLE discovery. Tap an advertiser to add it via its advertised model id.",
                    color = Fr3kPalette.TextDim,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                )
            } else {
                Column {
                    scanner.results.value.forEach { result ->
                        BleResultRow(result) {
                            session.manualId = result.name
                            session.manualLookupError = null
                            session.manualLookup = null
                            session.transport = BwTransport.MANUAL_ID
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BleResultRow(result: BleResult, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(result.name, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(result.address, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        Fr3kBadge(text = "${result.rssi} dBm", color = Fr3kPalette.AccentDim)
    }
}

@Composable
private fun LanTransportBlock(session: AddDeviceState, onProbe: () -> Unit, onOpen: (String) -> Unit) {
    Fr3kPanel(title = BwTransport.LOCAL_LAN.title) {
        Text(
            "Discovery over the local network through the configured fleet bridge.",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(6.dp))
        val probe = session.lanProbe
        StatusRow(
            "endpoint",
            probe?.level ?: BwLevel.WARN,
            probe?.endpoint ?: "unprobed",
        )
        StatusRow(
            "fleet",
            if ((probe?.fleetOnline ?: 0) > 0) BwLevel.PASS else BwLevel.WARN,
            probe?.let { "${it.fleetOnline}/${it.fleetAccounted} online" } ?: "not fetched",
        )
        Button(
            onClick = onProbe,
            enabled = !session.lanProbing,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
        ) { Text(if (session.lanProbing) "SCANNING…" else "SCAN LAN", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        val devices = probe?.fleet?.devices.orEmpty()
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column {
                devices.sortedBy { it.display_name }.forEach { card ->
                    LanDeviceRow(card) { onOpen(card.device_id ?: card.model_id) }
                }
            }
        }
    }
}

@Composable
private fun LanDeviceRow(card: FleetDeviceCard, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(card.display_name.ifBlank { card.model_id }, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(card.model_id, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        Text(card.online, color = if (card.online == "online") Fr3kPalette.Ok else Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}

@Composable
private fun UsbTransportBlock(context: Context) {
    Fr3kPanel(title = BwTransport.USB.title) {
        Text(
            "USB-attached hardware enumeration. The bridge does not yet expose a USB provisioning transport.",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(6.dp))
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = manager?.deviceList.orEmpty()
        StatusRow(
            "usb host",
            if (manager != null) BwLevel.PASS else BwLevel.FAIL,
            if (manager != null) "UsbManager available" else "no USB service",
        )
        StatusRow(
            "attached",
            if (devices.isNotEmpty()) BwLevel.PASS else BwLevel.WARN,
            "${devices.size} device(s)",
        )
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column {
                devices.values.forEach { d ->
                    Text(
                        "${d.deviceName} · ${String.format(java.util.Locale.US, "%04x:%04x", d.vendorId, d.productId)}",
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "USB devices are enumerated here, but BLACKWAVE provisioning over USB is not implemented in this build.",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ManualIpTransportBlock(session: AddDeviceState, onProbe: () -> Unit, onSave: (String) -> Unit) {
    Fr3kPanel(title = BwTransport.MANUAL_IP.title) {
        Text(
            "Probe a specific bridge endpoint (hostname or IP) before adding devices from it.",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = session.ipHost,
            onValueChange = { session.ipHost = it },
            label = { Text("bridge host (e.g. 192.168.1.50 or https://host:8878)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onProbe,
            enabled = session.ipHost.isNotBlank() && !session.ipProbing,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
        ) { Text(if (session.ipProbing) "PROBING…" else "PROBE BRIDGE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        session.ipError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
        val p = session.ipProbe
        if (p != null) {
            Spacer(Modifier.height(6.dp))
            StatusRow("reachable", p.level, if (p.reachable) p.endpoint else p.error ?: "unreachable")
            StatusRow(
                "role",
                if (p.role != null && !p.role.isExpired) BwLevel.PASS else BwLevel.WARN,
                p.role?.let { "trust ${it.trust_tier} · scopes ${it.allowed_scopes.size}" } ?: "not fetched",
            )
            StatusRow(
                "fleet",
                if (p.fleetOnline > 0) BwLevel.PASS else BwLevel.WARN,
                "${p.fleetOnline}/${p.fleetAccounted} online",
            )
            if (p.reachable) {
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { onSave(p.endpoint) },
                    colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                ) { Text("USE THIS BRIDGE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
            }
        }
        session.savedBridgeMessage?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ManualIdTransportBlock(session: AddDeviceState, onLookUp: () -> Unit) {
    Fr3kPanel(title = BwTransport.MANUAL_ID.title) {
        Text(
            "Look up a device by its BLACKWAVE model id (e.g. bw-core-0007).",
            color = Fr3kPalette.TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = session.manualId,
            onValueChange = { session.manualId = it },
            label = { Text("model id", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onLookUp,
            enabled = session.manualId.isNotBlank() && !session.manualProbing,
            colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
        ) { Text(if (session.manualProbing) "LOOKING UP…" else "LOOK UP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
        session.manualLookup?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "found: ${(it.identity["display_name"] as? JsonPrimitive)?.content ?: session.manualId} · ${it.observation_status}",
                color = Fr3kPalette.Ok,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }
        session.manualLookupError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Fr3kPalette.Err, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        }
    }
}

/**
 * Honest unavailable transport: explains why and routes to the nearest
 * feasible setup-wizard step instead of showing a dead button.
 */
@Composable
private fun UnavailableBlock(title: String, reason: String, wizardStep: WizardStep, context: Context) {
    Fr3kPanel(title = title) {
        Text(reason, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Spacer(Modifier.height(6.dp))
        StatusRow("status", BwLevel.WARN, "not implemented in this build")
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(context, BlackwaveSetupActivity::class.java)
                        .putExtra(BlackwaveSetupActivity.EXTRA_INITIAL_STEP, wizardStep.ordinal)
                )
            },
        ) { Text("CONFIGURE →", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp) }
    }
}

/** §7 device detail panel — the fields listed in the pass spec. */
@Composable
private fun DeviceDetailPanel(modelId: String, status: DeviceStatusResponse) {
    fun level(value: String): BwLevel = if (status.live && !status.cached && value.isNotBlank() &&
        listOf("UNKNOWN", "NOT REPORTED", "N/P", "null").none { value.contains(it, ignoreCase = true) }) BwLevel.PASS else BwLevel.WARN
    Fr3kPanel(title = "device detail · $modelId") {
        StatusRow("observation", if (status.live && !status.cached) BwLevel.PASS else BwLevel.WARN,
            "${status.observation_status} · observed ${status.observed_at ?: "—"}" + if (status.cached) " · cached" else "")
        val deviceId = (status.identity["device_id"] as? JsonPrimitive)?.content ?: "not enrolled"
        StatusRow("identity", if (status.device_identity_verified) BwLevel.PASS else BwLevel.WARN,
            "$deviceId · ${status.identity_evidence}")
        val name = (status.identity["display_name"] as? JsonPrimitive)?.content ?: "unknown"
        StatusRow("display name", level(name), name)
        val firmware = status.software["installed_firmware"] ?: status.software["firmware_version"] ?: status.software["firmware"] ?: "NOT REPORTED"
        StatusRow("firmware", level(firmware), firmware)
        val fields = listOf("connectivity" to status.connectivity, "battery" to status.battery, "verification" to status.verification)
        fields.forEach { (label, values) ->
            if (values.isNotEmpty()) {
                val text = values.entries.joinToString(", ") { "${it.key}=${it.value}" }
                StatusRow(label, level(text), text)
            }
        }
        StatusRow("capabilities", level(status.hardware.joinToString()),
            status.hardware.joinToString().ifBlank { "none reported" } + " · ${status.capability_evidence}")
        if (status.catalog_capabilities.isNotEmpty()) {
            StatusRow("model claims", BwLevel.WARN, status.catalog_capabilities.joinToString())
        }
        listOf("power" to status.power, "validation" to status.validation).forEach { (label, values) ->
            if (values.isNotEmpty()) {
                val text = values.entries.joinToString(", ") { (k,v) -> "$k=${(v as? JsonPrimitive)?.content ?: v}" }
                StatusRow(label, level(text), text)
            }
        }
    }
}
