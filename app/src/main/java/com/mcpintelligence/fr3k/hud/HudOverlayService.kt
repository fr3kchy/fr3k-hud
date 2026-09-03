package com.mcpintelligence.fr3k.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.R
import com.mcpintelligence.fr3k.hud.overlays.OverlayManager
import com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity
import com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity
import com.mcpintelligence.fr3k.ui.automation.AutomationActivity
import com.mcpintelligence.fr3k.ui.clipboard.SmartClipboardActivity
import com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity
import com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity
import com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity
import com.mcpintelligence.fr3k.ui.screenshot.ScreenshotActivity
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The HUD overlay service — single foreground service that owns the floating
 * orb and every overlay window (chat bubble, mini browser, terminal, X-target,
 * edge-arc, particle link). Mirrors Hitomi's single-service-multiple-windows
 * pattern.
 *
 * Gestures on the orb:
 *   - tap                  → quick HUD panel
 *   - long-press           → radial menu (settings / mic / open chat / open browser / open terminal)
 *   - double-tap           → command palette
 *   - swipe up             → devices (fleet)
 *   - swipe down           → screenshot
 *   - drag                 → move (edge-magnet on release)
 *   - drag to bottom X     → close all overlays
 *   - drag off-screen edge → hide to edge arc-tab; tap arc to restore
 */
class HudOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var orb: Fr3kHudOrb
    private lateinit var overlays: OverlayManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var radialOpen = false
    private var edgeHidden = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlays = OverlayManager(this)
        orb = Fr3kHudOrb(this, windowManager)
        startInForeground()
        orb.attach()
        orb.installTouchHandler()
        orb.setGestureListener(gestureListener)
        orb.setLifecycleListener(lifecycleListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OPEN_CHAT -> overlays.openChat()
            ACTION_OPEN_BROWSER -> overlays.openBrowser(intent.getStringExtra(EXTRA_URL))
            ACTION_OPEN_TERMINAL -> overlays.openTerminal()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(overlayReceiver) } catch (_: Exception) {}
        scope.cancel()
        orb.detach()
        overlays.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FR3K HUD",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Floating overlay service" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuickHudActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fr3k_notification)
            .setContentTitle("FR3K HUD")
            .setContentText("tap to open quick HUD · drag to move")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, notif)
        }
        // Register an internal receiver so external adb broadcasts (or
        // extension packages) can drive overlay state.
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_OPEN_CHAT)
            addAction(ACTION_OPEN_BROWSER)
            addAction(ACTION_OPEN_TERMINAL)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(overlayReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(overlayReceiver, filter)
        }
    }

    private val overlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_OPEN_CHAT -> overlays.openChat()
                ACTION_OPEN_BROWSER -> overlays.openBrowser(intent.getStringExtra(EXTRA_URL))
                ACTION_OPEN_TERMINAL -> overlays.openTerminal()
                ACTION_STOP -> stopSelf()
            }
        }
    }

    private val gestureListener = object : Fr3kHudOrb.GestureListener {
        override fun onTap() {
            Log.i("FR3K_HUD", "orb tap")
            val i = Intent(this@HudOverlayService, QuickHudActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }
        override fun onDoubleTap() {
            Log.i("FR3K_HUD", "orb double tap")
            startActivity(
                Intent(this@HudOverlayService, CommandPaletteActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        override fun onLongPress() {
            Log.i("FR3K_HUD", "orb long press")
            scope.launch(Dispatchers.Main) { showRadialMenu() }
        }
        override fun onSwipeUp() {
            startActivity(
                Intent(this@HudOverlayService, DeviceHandoffActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        override fun onSwipeDown() {
            startActivity(
                Intent(this@HudOverlayService, ScreenshotActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val lifecycleListener = object : Fr3kHudOrb.OrbLifecycleListener {
        override fun onOrbDragStart() {
            overlays.exitTarget.show()
            overlays.edgeArc.hide()
        }
        override fun onOrbDragging(x: Int, y: Int) {
            overlays.exitTarget.let { /* keep showing */ }
            val bubbleRoot = overlays.chatBubble.rootView()
            val loc = IntArray(2)
            bubbleRoot.getLocationOnScreen(loc)
            val bubbleCenterX = loc[0] + bubbleRoot.width / 2
            val bubbleCenterY = loc[1] + bubbleRoot.height / 2
            overlays.particle.updateAnchors(
                x + orb.viewWidthPx() / 2,
                y + orb.viewWidthPx() / 2,
                bubbleCenterX,
                bubbleCenterY,
            )
        }
        override fun onOrbDragEnd(x: Int, y: Int, droppedOnClose: Boolean, droppedOffScreen: Boolean) {
            overlays.exitTarget.hide()
            overlays.particle.hide()
            if (droppedOnClose) {
                Toast.makeText(this@HudOverlayService, "FR3K HUD closed", Toast.LENGTH_SHORT).show()
                stopSelf()
                return
            }
            if (droppedOffScreen) {
                edgeHidden = true
                orb.detach()
                overlays.edgeArc.show()
            }
        }
    }

    /**
     * Long-press radial menu — Hitomi's long-press radial. Pops 5 quick
     * actions around the orb's last known position.
     */
    private fun showRadialMenu() {
        if (radialOpen) return
        radialOpen = true
        // The actual radial menu is part of the chat bubble (hit "FR3K ▸ HERMES")
        // — keeping the orb as a tap target. The radial menu is launched as a
        // visible activity dialog instead.
        val i = Intent(this, LongPressRadialActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        // Reset flag when activity finishes.
        scope.launch(Dispatchers.Main) {
            // simple debounce — re-enable after 1500ms
            kotlinx.coroutines.delay(1500)
            radialOpen = false
        }
    }

    fun restoreOrbFromEdge() {
        if (!edgeHidden) return
        edgeHidden = false
        overlays.edgeArc.hide()
        orb.attach()
    }

    companion object {
        const val ACTION_OPEN_CHAT = "com.mcpintelligence.fr3k.hud.OPEN_CHAT"
        const val ACTION_OPEN_BROWSER = "com.mcpintelligence.fr3k.hud.OPEN_BROWSER"
        const val ACTION_OPEN_TERMINAL = "com.mcpintelligence.fr3k.hud.OPEN_TERMINAL"
        const val ACTION_STOP = "com.mcpintelligence.fr3k.hud.STOP"
        const val EXTRA_URL = "url"
        private const val NID = 101
        private const val CHANNEL_ID = "fr3k-hud-overlay"
    }
}