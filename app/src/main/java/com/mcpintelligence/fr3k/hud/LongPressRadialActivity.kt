package com.mcpintelligence.fr3k.hud

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity
import com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity
import com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

/**
 * Hitomi-style long-press radial menu. Renders as a translucent floating
 * panel that the orb summons when the user holds the orb. Six quick actions:
 *
 *   [open chat]  [open browser]  [open terminal]
 *   [ask]        [diagnostics]   [settings]
 *
 * The dialog uses a `dialog`-style theme so it floats, dismisses on outside
 * tap, and does not block the orb.
 */
class LongPressRadialActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(0x88000000.toInt()) })
        setContentView(buildContent())
    }

    private fun buildContent(): View {
        val density = resources.displayMetrics.density
        val rowBg = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(0xFF11111c.toInt())
            setStroke((1.5f * density).toInt(), 0xFF2b2b40.toInt())
        }

        fun makeRow(label: String, action: () -> Unit): View {
            val tv = TextView(this).apply {
                text = label
                setTextColor(0xFFcdd1e0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                    (16 * density).toInt(),
                    (14 * density).toInt(),
                )
                background = rowBg
                setOnClickListener { action() }
            }
            return tv
        }

        val title = TextView(this).apply {
            text = "FR3K ▸ RADIAL"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
        }

        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val gap = View(this@LongPressRadialActivity)
            gap.layoutParams = LinearLayout.LayoutParams(1, (8 * density).toInt())
            addView(gap)
            addView(makeRow("OPEN CHAT BUBBLE") {
                sendBroadcast(Intent("com.mcpintelligence.fr3k.hud.OPEN_CHAT_OVERLAY")
                    .setPackage(packageName))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("OPEN MINI BROWSER") {
                sendBroadcast(Intent("com.mcpintelligence.fr3k.hud.OPEN_BROWSER_OVERLAY")
                    .setPackage(packageName))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("OPEN TERMINAL") {
                sendBroadcast(Intent("com.mcpintelligence.fr3k.hud.OPEN_TERMINAL_OVERLAY")
                    .setPackage(packageName))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("ASK ABOUT THIS") {
                startActivity(Intent(this@LongPressRadialActivity, AskAboutThisActivity::class.java))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("DIAGNOSTICS") {
                startActivity(Intent(this@LongPressRadialActivity, DiagnosticsActivity::class.java))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("SETTINGS") {
                startActivity(Intent(this@LongPressRadialActivity, SettingsActivity::class.java))
                finish()
            })
            addView(View(this@LongPressRadialActivity).apply {
                layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            })
            addView(makeRow("CANCEL") { finish() })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((24 * density).toInt(), (0 * density).toInt(), (24 * density).toInt(), (0 * density).toInt())
            val params = LinearLayout.LayoutParams(
                (300 * density).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(rows, params)
            setOnClickListener { finish() }
        }
    }
}