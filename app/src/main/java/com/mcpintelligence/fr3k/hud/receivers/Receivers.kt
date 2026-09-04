package com.mcpintelligence.fr3k.hud.receivers

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.AutomationEngine
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.hud.AutomationActionExecutor

/**
 * Tracks the foreground task / activity so the context engine and the
 * application profiles always reflect the current app. Updates [ContextEngine]
 * and fires any FOREGROUND_APP automations (§31).
 */
class ForegroundAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pkg = intent?.data?.schemeSpecificPart ?: return
        Log.i(TAG, "foreground app changed: $pkg")
        val app = Fr3kApplication.get()
        app.fr3kCore.contextEngine.setForeground(packageName = pkg, activityName = null)
        val ctx = app.fr3kCore.contextEngine.toCommandContext(
            deviceId = app.identity.deviceId,
            capabilities = app.fr3kCore.currentCapabilities(),
            consentLevel = ConsentLevel.NORMAL,
        )
        val executor = AutomationActionExecutor(context) { id, args ->
            val c = app.fr3kCore.commandRegistry.get(id)
                ?: return@AutomationActionExecutor AutomationEngine.Outcome.FAILED
            when (val r = kotlinx.coroutines.runBlocking { c.execute(ctx, args) }) {
                is com.mcpintelligence.fr3k.core.CommandResult.Ok,
                is com.mcpintelligence.fr3k.core.CommandResult.NeedsConfirmation -> AutomationEngine.Outcome.FIRED
                is com.mcpintelligence.fr3k.core.CommandResult.Failed -> AutomationEngine.Outcome.FAILED
                is com.mcpintelligence.fr3k.core.CommandResult.Cancelled -> AutomationEngine.Outcome.SKIPPED_DEBOUNCE
            }
        }
        app.fr3kCore.automationEngine.matchAndFire(
            trigger = AutomationEngine.TriggerType.FOREGROUND_APP,
            ctx = ctx,
            executor = executor,
            packageName = pkg,
        )
    }

    companion object { private const val TAG = "FR3K.receiver" }
}

/** Boot receiver — restarts the core foreground service after device reboot
 *  and refreshes the partner-app install state for the integrations panel.
 *
 *  We deliberately do NOT start the HUD service on its own — Android since
 *  8.0 prevents background services from starting, and the user is supposed
 *  to be in control of when the orb is on screen. But the install-state
 *  cache is fair game: it just walks the partner-app package list and
 *  writes a small JSON the IntegrationsActivity can read on first open
 *  after a reboot, so the panel comes up with accurate status without
 *  making the user open each section. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i(TAG, "boot/replace — refreshing partner-app install state")
        try {
            InstallStateProbe.refresh(context)
        } catch (t: Throwable) {
            Log.w(TAG, "InstallStateProbe.refresh failed: ${t.message}")
        }
    }
    companion object { private const val TAG = "FR3K.boot" }
}

/** Network connectivity changes — fires WIFI_CHANGED automations. */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = Fr3kApplication.get()
        val ctx = app.fr3kCore.contextEngine.toCommandContext(
            deviceId = app.identity.deviceId,
            capabilities = app.fr3kCore.currentCapabilities(),
            consentLevel = ConsentLevel.NORMAL,
        )
        val executor = AutomationActionExecutor(context) { _, _ -> AutomationEngine.Outcome.FIRED }
        app.fr3kCore.automationEngine.matchAndFire(
            trigger = AutomationEngine.TriggerType.WIFI_CHANGED,
            ctx = ctx,
            executor = executor,
        )
    }
    companion object { private const val TAG = "FR3K.net" }
}

/** Bluetooth adapter state changes — fires BT_DEVICE_CONNECTED automations. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = Fr3kApplication.get()
        val ctx = app.fr3kCore.contextEngine.toCommandContext(
            deviceId = app.identity.deviceId,
            capabilities = app.fr3kCore.currentCapabilities(),
            consentLevel = ConsentLevel.NORMAL,
        )
        val executor = AutomationActionExecutor(context) { _, _ -> AutomationEngine.Outcome.FIRED }
        app.fr3kCore.automationEngine.matchAndFire(
            trigger = AutomationEngine.TriggerType.BT_DEVICE_CONNECTED,
            ctx = ctx,
            executor = executor,
        )
    }
    companion object { private const val TAG = "FR3K.bt" }
}

/** Share intent receiver — fires SHARE_RECEIVED / URL_SHARED automations. */
class ShareReceiverTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val app = Fr3kApplication.get()
        app.fr3kCore.contextEngine.update {
            it.copy(
                selectedText = text,
                fullText = text,
                url = if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) text else it.url,
            )
        }
        val ctx = app.fr3kCore.contextEngine.toCommandContext(
            deviceId = app.identity.deviceId,
            capabilities = app.fr3kCore.currentCapabilities(),
            consentLevel = ConsentLevel.NORMAL,
        )
        val executor = AutomationActionExecutor(context) { _, _ -> AutomationEngine.Outcome.FIRED }
        val isUrl = text?.startsWith("http://") == true || text?.startsWith("https://") == true
        app.fr3kCore.automationEngine.matchAndFire(
            trigger = if (isUrl) AutomationEngine.TriggerType.URL_SHARED else AutomationEngine.TriggerType.SHARE_RECEIVED,
            ctx = ctx,
            executor = executor,
            url = text,
        )
    }
    companion object { private const val TAG = "FR3K.share.trigger" }
}