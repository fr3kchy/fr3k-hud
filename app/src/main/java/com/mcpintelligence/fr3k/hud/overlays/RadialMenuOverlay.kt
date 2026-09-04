package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.hud.HudOverlayService
import com.mcpintelligence.fr3k.ui.MainActivity

/**
 * Hitomi-style long-press radial menu, implemented as a `TYPE_APPLICATION_OVERLAY`
 * window so it pops up ON TOP of whatever's currently on screen without bringing
 * the FR3K HUD activity to the foreground.
 *
 * Replaces the legacy [com.mcpintelligence.fr3k.hud.LongPressRadialActivity]
 * which was a fullscreen translucent activity. The activity approach made the
 * FR3K HUD appear under the popup, which is wrong for an overlay HUD: the
 * popup should float over the user's current app, not over our own app.
 *
 * Rows:
 *   1. OPEN CHAT BUBBLE
 *   2. OPEN MINI BROWSER
 *   3. OPEN TERMINAL
 *   4. ASK ABOUT THIS (full activity, but the radial stays as a popup)
 *   5. INTEGRATIONS (full activity)
 *   6. DIAGNOSTICS
 *   7. DEV OVERLAY
 *   8. SETTINGS
 *   9. CANCEL
 *
 * For rows 1-3 the row clicks the HUD service with the relevant ACTION_* so
 * the existing service-side overlay windows open. For rows 4-8 we DO need to
 * launch a fullscreen activity (those screens aren't overlays), so we use a
 * context-bound start activity with NEW_TASK and then dismiss the radial.
 */
class RadialMenuOverlay(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
) : Fr3kOverlay {

    override val name: String = "radial-menu"
    override var isAttached: Boolean = false
        private set

    private val root: View
    private val params: WindowManager.LayoutParams

    init {
        val ctx = host.context
        val rows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(0xF0111126.toInt())
                setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
            }
        }
        val title = TextView(ctx).apply {
            text = "FR3K ▸ RADIAL"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        }
        rows.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Row 1: open chat bubble (overlay)
        addOverlayRow(rows, "OPEN CHAT BUBBLE", HudOverlayService.ACTION_OPEN_CHAT)
        // Row 2: open mini browser (overlay)
        addOverlayRow(rows, "OPEN MINI BROWSER", HudOverlayService.ACTION_OPEN_BROWSER)
        // Row 3: open terminal (overlay)
        addOverlayRow(rows, "OPEN TERMINAL", HudOverlayService.ACTION_OPEN_TERMINAL)
        // Row 4: ask about this (full activity — needed for the share sheet flow)
        addActivityRow(rows, "ASK ABOUT THIS", com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
        // Row 5: integrations
        addActivityRow(rows, "INTEGRATIONS · TERMUX/LSPATCH/MORPHE",
            com.mcpintelligence.fr3k.ui.integrations.IntegrationsActivity::class.java)
        // Row 6: diagnostics
        addActivityRow(rows, "DIAGNOSTICS",
            com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity::class.java)
        // Row 7: dev overlay
        addActivityRow(rows, "DEV OVERLAY",
            com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity::class.java)
        // Row 8: settings
        addActivityRow(rows, "SETTINGS",
            com.mcpintelligence.fr3k.ui.settings.SettingsActivity::class.java)
        // Row 9: open the main app launcher
        addActivityRow(rows, "FR3K MAIN",
            MainActivity::class.java)
        // Row 10: cancel
        addOverlayRow(rows, "CANCEL", "", onClick = { hide() })

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        container.addView(
            rows,
            LinearLayout.LayoutParams((340 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        // Background tap dismisses the radial.
        container.setOnClickListener { hide() }

        root = container

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun addOverlayRow(parent: LinearLayout, label: String, action: String, onClick: (() -> Unit)? = null) {
        parent.addView(makeRow(label) {
            hide()
            if (onClick != null) {
                onClick()
            } else {
                host.context.sendBroadcast(
                    Intent(action).setPackage(host.context.packageName)
                )
            }
        })
        parent.addView(spacer(6))
    }

    private fun addActivityRow(
        parent: LinearLayout,
        label: String,
        activity: Class<*>,
    ) {
        parent.addView(makeRow(label) {
            hide()
            // NEW_TASK + from a service/overlay context, this launches the
            // activity as its own task so we don't bring the wrong task
            // forward. The radial stays as a popup; the new activity opens
            // on top of it.
            val i = Intent(host.context, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                host.context.startActivity(i)
            } catch (_: Throwable) {
                // Activity not present (e.g. stripped from a release variant).
            }
        })
        parent.addView(spacer(6))
    }

    private fun makeRow(label: String, onClick: () -> Unit): View {
        val ctx = host.context
        val bg = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(0xFF11111c.toInt())
            setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
        }
        return TextView(ctx).apply {
            text = label
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt(),
            )
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(height: Int): View = View(host.context).apply {
        layoutParams = LinearLayout.LayoutParams(1, (height * density).toInt())
    }

    override fun show() {
        if (isAttached) return
        try {
            host.add(root, params)
            isAttached = true
        } catch (_: Throwable) { /* already added */ }
    }

    override fun hide() {
        if (!isAttached) return
        host.remove(root)
        isAttached = false
    }
}
