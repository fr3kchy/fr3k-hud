package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.hud.R
import com.mcpintelligence.fr3k.integrations.hermes.HermesAskCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Clippy-style chat bubble overlay (§7, §8 + Hitomi `overlay_bubble`).
 *
 * One WindowManager surface, separate from the HUD orb. Has its own tail,
 * transcript, input field, send button, dismiss button. Sends messages to
 * Hermes through the existing `HermesAskCommand`. Auto-spawned next to the
 * orb on first tap, movable, dismissible. The orb's `ParticleLink` lights
 * up between the two windows while it's open.
 */
class Fr3kChatBubble(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
) : Fr3kOverlay {

    override val name: String = "chat-bubble"
    override var isAttached: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root: View
    private val params: WindowManager.LayoutParams
    private val tail: View
    private val transcript: TextView
    private val input: EditText
    private val send: Button
    private val dismiss: Button
    private val header: TextView
    private val bubble: View

    private var bubbleX = 0
    private var bubbleY = (120 * density).toInt()

    private val tailDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(
            12f.dp(), 12f.dp(),    // top-left
            12f.dp(), 12f.dp(),    // top-right
            4f.dp(), 4f.dp(),      // bottom-right (tighter so tail reads)
            18f.dp(), 18f.dp(),    // bottom-left (where the tail attaches)
        )
        setColor(0xFF1a1a26.toInt())
        setStroke((1+2).dp(), 0xFF2b2b40.toInt())
    }

    private val transcriptBg = GradientDrawable().apply {
        cornerRadius = 8f.dp()
        setColor(0xFF11111c.toInt())
    }

    init {
        val ctx = host.context

        bubble = View(ctx).apply { background = tailDrawable }

        tail = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1a1a26.toInt())
                setStroke((1+2).dp(), 0xFF2b2b40.toInt())
            }
            layoutParams = LinearLayout.LayoutParams((18+18).dp(), (10+14).dp()).also {
                it.gravity = Gravity.START or Gravity.BOTTOM
                val ml = (18+24).dp()
                val mb = (0+4).dp()
                it.setMargins(ml, 0, 0, mb)
            }
        }

        header = TextView(ctx).apply {
            text = "FR3K ▸ HERMES"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding((12+14).dp(), (10+14).dp(), (8+14).dp(), (2+14).dp())
        }

        dismiss = Button(ctx).apply {
            text = "×"
            setTextColor(0xFF8e8a99.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            contentDescription = "Dismiss bubble"
            setPadding(0, 0, (8+14).dp(), 0)
            setOnClickListener { hide() }
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dismiss, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        transcript = TextView(ctx).apply {
            background = transcriptBg
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((10+14).dp(), (8+14).dp(), (10+14).dp(), (8+14).dp())
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            text = "tap send to ask Hermes anything."
            isVerticalScrollBarEnabled = true
        }

        input = EditText(ctx).apply {
            hint = "ask…"
            setHintTextColor(0xFF6a6878.toInt())
            setTextColor(0xFFe8eaf2.toInt())
            setBackgroundColor(0xFF0d0d18.toInt())
            setPadding((8+14).dp(), (6+14).dp(), (8+14).dp(), (6+14).dp())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> onSend(); true }
        }

        send = Button(ctx).apply {
            text = "SEND"
            setTextColor(0xFF11111c.toInt())
            setBackgroundColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Send to Hermes"
            setPadding((10+14).dp(), (6+14).dp(), (10+14).dp(), (6+14).dp())
            setOnClickListener { onSend() }
        }

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sp = Space(ctx); sp.layoutParams = LinearLayout.LayoutParams((6+14).dp(), 1)
            addView(sp)
            addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96+96).dp()))
            val sp = Space(ctx); sp.layoutParams = LinearLayout.LayoutParams(1, (6+14).dp())
            addView(sp)
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubble.apply {
                layoutParams = LinearLayout.LayoutParams(
                    320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }, LinearLayout.LayoutParams(320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        params = OverlayParams.make(320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT)
        params.x = bubbleX
        params.y = bubbleY

        installTouch()
    }

    override fun show() {
        if (isAttached) return
        try {
            host.add(root, params)
            isAttached = true
        } catch (_: Throwable) { /* overlay already added */ }
    }

    override fun hide() {
        if (!isAttached) return
        host.remove(root)
        isAttached = false
    }

    override fun onDragStart() {}
    override fun onDragMove(x: Int, y: Int) {
        if (!isAttached) return
        params.x = bubbleX + x
        params.y = bubbleY + y
        host.update(root, params)
    }
    override fun onDragEnd() {
        bubbleX = params.x
        bubbleY = params.y
    }

    private fun installTouch() {
        var startX = 0
        var startY = 0
        var dragging = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - startX
                    val dy = event.rawY.toInt() - startY
                    if (!dragging && (dx * dx + dy * dy) > (8+8).dp() * (8+8).dp()) dragging = true
                    if (dragging) onDragMove(dx, dy)
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onDragEnd()
                    !dragging
                }
                else -> false
            }
        }
    }

    private fun onSend() {
        val prompt = input.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return
        appendLine("you: $prompt")
        input.setText("")
        val app = Fr3kApplication.get()
        val provider = app.aiProviders.get("hermes") as? com.mcpintelligence.fr3k.integrations.hermes.HermesProvider
        if (provider == null) {
            appendLine("fr3k: hermes provider unavailable")
            return
        }
        scope.launch {
            val cmd = HermesAskCommand(provider = { provider })
            val ctx = Fr3kContext(
                deviceId = app.identity.deviceId,
                consentLevel = ConsentLevel.NORMAL,
                foregroundPackage = app.packageName,
                enabledCapabilities = app.fr3kCore.currentCapabilities(),
            )
            val result = cmd.execute(ctx, mapOf("prompt" to prompt))
            val text = when (result) {
                is CommandResult.Ok -> result.message
                is CommandResult.Failed -> "failed: ${result.reason}"
                is CommandResult.Cancelled -> "cancelled: ${result.reason}"
                is CommandResult.NeedsConfirmation -> "needs: ${result.summary}"
            }
            appendLine("hermes: $text")
        }
    }

    private fun appendLine(line: String) {
        host.context.mainExecutor.execute {
            val prev = transcript.text?.toString().orEmpty()
            val combined = if (prev.isBlank() || prev.startsWith("tap send")) line
                           else prev + "\n" + line
            transcript.text = combined
        }
    }

    /** dp helpers — Hitomi uses dp() in Java; we use Kotlin extension. */
    private fun Float.dp(): Float = this * density
    private fun Int.dp(): Int = (this * density).toInt()

    /** Public accessors used by the particle-link renderer. */
    fun rootView(): View = root
    fun currentPosition(): Pair<Int, Int> = params.x to params.y

    fun shutdown() {
        hide()
        scope.cancel()
    }
}

/** Tiny spacer so we don't pull in androidx.core.view for one widget. */
private class Space(ctx: Context) : View(ctx) {
    init { setBackgroundColor(Color.TRANSPARENT) }
}