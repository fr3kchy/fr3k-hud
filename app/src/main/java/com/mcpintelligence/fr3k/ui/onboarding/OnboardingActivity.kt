package com.mcpintelligence.fr3k.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.permissions.PermissionRegistry
import com.mcpintelligence.fr3k.permissions.SpecialPermissionLauncher
import com.mcpintelligence.fr3k.ui.Fr3kPalette
import com.mcpintelligence.fr3k.ui.Fr3kTheme
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

private const val REQ_NOTIF = 7009

/**
 * First-run onboarding (§9): WELCOME → PERMISSIONS → READY.
 *
 * The step order is fixed (welcome, permissions, ready); every step is
 * skippable and honours the §34 doctrine by showing the REAL permission
 * state (never a papered-over "all good") with one-tap GRANT actions that
 * reuse PermissionRegistry / SpecialPermissionLauncher. Completion writes
 * `onboardingDone = true` to AppSettings and starts the HUD if the overlay
 * + notification perms are now granted (same rule as MainActivity's
 * auto-start). Skipping behaves identically to completing.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Fr3kTheme {
                OnboardingScreen(
                    activity = this@OnboardingActivity,
                    onDone = { completeOnboarding() },
                    onSkip = { completeOnboarding() },
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Permission state is recomputed on the next recomposition; nothing
        // to do here beyond letting the OS dialog close.
    }

    /** Finish/skip both write the flag so the gate disappears for ever. */
    private fun completeOnboarding() {
        val app = Fr3kApplication.get()
        val alreadyDone = app.settings.settings.value.onboardingDone
        if (!alreadyDone) {
            app.settings.update { it.copy(onboardingDone = true) }
        }
        maybeStartHudAfterGrant()
        finish()
    }

    /**
     * Onboarding completion is exactly the moment the HUD may become
     * grantable — mirrors MainActivity's auto-start rule: foreground
     * service only when overlay + notifications are BOTH granted.
     */
    private fun maybeStartHudAfterGrant() {
        val canOverlay = SpecialPermissionLauncher.canDrawOverlays(this)
        val canNotif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canOverlay && canNotif) {
            startForegroundService(Intent(this, HudOverlayService::class.java))
        }
    }
}

private enum class OnboardingStep(val title: String) {
    WELCOME("FR3K HUD · ONE-TIME SETUP"),
    PERMISSIONS("GRANT WHAT FR3K NEEDS"),
    READY("READY · FR3K HUD"),
}

@Composable
private fun OnboardingScreen(
    activity: OnboardingActivity,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val app = Fr3kApplication.get()
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    // Bumped when we return from the overlay Settings round-trip so the
    // PERMISSIONS rows re-read their real grant state.
    var overlayReturnCheck by remember { mutableIntStateOf(0) }
    val canOverlay = remember(overlayReturnCheck) { SpecialPermissionLauncher.canDrawOverlays(activity) }
    val canNotif = remember(overlayReturnCheck) {
        Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    Box(modifier = Modifier.fillMaxSize().background(Fr3kPalette.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = step.title,
                color = Fr3kPalette.Accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))

            when (step) {
                OnboardingStep.WELCOME -> {
                    Text(
                        text = "▰",
                        color = Fr3kPalette.Magenta,
                        fontSize = 56.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "An adaptive Android agent interface. Floating HUD orb, terminal + browser overlays, and a BLACKWAVE fleet bridge.",
                        color = Fr3kPalette.Text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "v${app.identity.appVersion} · ${app.identity.platform} · ${app.identity.deviceId.take(12)}…",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { step = OnboardingStep.PERMISSIONS },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("BEGIN SETUP", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }

                OnboardingStep.PERMISSIONS -> {
                    PermissionRow(
                        label = "FLOATING HUD · SYSTEM_ALERT_WINDOW",
                        detail = if (canOverlay) "granted" else "required to draw the orb over other apps",
                        granted = canOverlay,
                        onGrant = {
                            activity.startActivity(SpecialPermissionLauncher.overlayIntent(activity))
                            overlayReturnCheck++
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    PermissionRow(
                        label = "NOTIFICATIONS · POST_NOTIFICATIONS",
                        detail = if (Build.VERSION.SDK_INT < 33) "granted (SDK < 33)" else if (canNotif) "granted" else "required for the foreground HUD service",
                        granted = canNotif,
                        onGrant = {
                            PermissionRegistry.request(
                                activity,
                                PermissionRegistry.Feature.HUD_ORB,
                                REQ_NOTIF,
                            )
                        },
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { step = OnboardingStep.READY },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("CONTINUE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "skip for now — permission state stays visible in diagnostics",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onSkip() }
                            .padding(6.dp),
                    )
                }

                OnboardingStep.READY -> {
                    val missing = buildList {
                        if (!canOverlay) add("SYSTEM_ALERT_WINDOW")
                        if (!canNotif) add("POST_NOTIFICATIONS")
                    }
                    if (missing.isEmpty()) {
                        Text(
                            text = "all permissions granted ✓",
                            color = Fr3kPalette.Ok,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    } else {
                        Text(
                            text = "permissions pending: ${missing.joinToString(", ")}",
                            color = Fr3kPalette.Warn,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                    Text(
                        text = "you can also manage everything from Settings",
                        color = Fr3kPalette.TextDim,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                activity.startActivity(Intent(activity, SettingsActivity::class.java))
                            }
                            .padding(top = 16.dp),
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
                    ) { Text("GET STARTED", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SKIP SETUP", fontFamily = FontFamily.Monospace) }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    detail: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Fr3kPalette.Surface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = Fr3kPalette.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = Fr3kPalette.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        if (granted) {
            Text("GRANTED", color = Fr3kPalette.Ok, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Fr3kPalette.Accent, contentColor = Fr3kPalette.Bg),
            ) { Text("GRANT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
        }
    }
}