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
    private val modelButton: Button
    private val ttsButton: Button
    private val resizeGrip: View

    private var bubbleX = 0
    private var bubbleY = (120 * density).toInt()
    private var hasSeededWelcome = false

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

        // The chat bubble shape is a drawable applied to the root view
        // directly. We used to keep a separate sibling `bubble` View for
        // the shape and a `content` LinearLayout for the buttons, but
        // uiautomator and some accessibility paths only saw the empty
        // shape and the dismiss/send/model buttons never reached the
        // user. Folding everything into one root view fixes both: the
        // shape is the background, and every header / button / transcript
        // child is a real view under root.
        bubble = View(ctx).apply { background = tailDrawable }
        tail = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF1a1a26.toInt())
                setStroke((1+2).dp(), 0xFF2b2b40.toInt())
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
            setTextColor(0xFF7d3cff.toInt())
            setBackgroundColor(0xFF1a1a26.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Dismiss bubble"
            // Big square close button — 36dp x 36dp — so it's a real target
            // and not the tiny ✕ that gets lost in the header.
            setPadding(0, 0, 0, 0)
            val closeSize = (36 * density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(closeSize, closeSize)
            setOnClickListener {
                hide()
                TtsPreference.shutdown()
            }
        }

        // Small "M" button to open the model picker (long-press cycles models).
        modelButton = Button(ctx).apply {
            text = "M"
            setTextColor(0xFFcdd1e0.toInt())
            setBackgroundColor(0xFF2b2b40.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Cycle AI model (long-press for picker)"
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            isAllCaps = false
            setOnClickListener { cycleModel() }
            setOnLongClickListener { showModelPicker(); true }
        }

        // Small "🔊" toggle for spoken responses. Default Android renderer
        // doesn't always have the speaker glyph, so we use "TTS" text.
        ttsButton = Button(ctx).apply {
            text = if (TtsPreference.isEnabled(ctx)) "TTS●" else "TTS"
            setTextColor(if (TtsPreference.isEnabled(ctx)) 0xFF7d3cff.toInt() else 0xFF8e8a99.toInt())
            setBackgroundColor(0xFF2b2b40.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = "Toggle spoken responses"
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            isAllCaps = false
            setOnClickListener {
                val now = !TtsPreference.isEnabled(ctx)
                TtsPreference.setEnabled(ctx, now)
                text = if (now) "TTS●" else "TTS"
                setTextColor(if (now) 0xFF7d3cff.toInt() else 0xFF8e8a99.toInt())
            }
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(modelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ttsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            // Tapping the input focuses it and shows the soft keyboard.
            setOnClickListener {
                requestFocus()
                val imm = host.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val imm = host.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
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

        // Resize grip — anchored bottom-right so the user can drag it
        // to grow / shrink the chat bubble. Small (16dp square) and
        // accent-coloured so it doesn't look like a hitomi bottom X.
        resizeGrip = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xFF7d3cff.toInt())
            }
            contentDescription = "Drag to resize"
            alpha = 0.7f
        }

        // The root view is now the chat bubble itself: tailDrawable
        // is its background, headerRow / transcript / inputRow / grip
        // are direct children. The old `content` LinearLayout wrapper
        // is removed — it caused headerRow / transcript / inputRow to
        // have a parent already when we tried to attach them to root.
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = tailDrawable
            // Explicit width on the root so the layout never collapses to
            // 0px even if the inner children report weird measured sizes.
            layoutParams = ViewGroup.LayoutParams(
                320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            // Header row: title + model picker + TTS toggle + dismiss.
            addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Transcript body.
            // Let the transcript absorb resize deltas. The input row and grip
            // stay measured at the bottom instead of being clipped by an exact
            // window height.
            val transcriptLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(transcript, transcriptLp)
            val sp = Space(ctx); sp.layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            addView(sp)
            // Input row: text field + send button.
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val sp2 = Space(ctx); sp2.layoutParams = LinearLayout.LayoutParams(1, (2 * density).toInt())
            addView(sp2)
            // Resize grip — bottom-right so the user can drag to grow/shrink.
            val gripParams = LinearLayout.LayoutParams(
                (16 * density).toInt(), (16 * density).toInt()
            ).apply { gravity = android.view.Gravity.END }
            addView(resizeGrip, gripParams)
        }

        // Resizable params: keep the width dynamic so the user can grow the
        // bubble. Initial height is WRAP_CONTENT; once the user drags the
        // grip, both width and height are persisted in [params] until next
        // resize. Use forChat() so the EditText inside can receive focus
        // and the soft keyboard appears on tap.
        params = OverlayParams.forChat(320.dp(), ViewGroup.LayoutParams.WRAP_CONTENT)
        params.x = bubbleX
        params.y = bubbleY

        installTouch()
        installResizeTouch()
    }

    override fun show() {
        if (isAttached) return
        try {
            host.add(root, params)
            isAttached = true
            android.util.Log.i("FR3K_HUD", "chat bubble shown at (${params.x}, ${params.y}) size ${params.width}x${params.height}")
            // First-show welcome so the transcript isn't empty. We only
            // seed once per process so reopens don't spam the user.
            if (!hasSeededWelcome) {
                hasSeededWelcome = true
                appendLine("fr3k: HUD online. termux / shizuku / lspatch / morphe detected from the integrations panel.")
                appendLine("fr3k: tap the M key to pick a model, or long-press the orb for the radial menu.")
            }
        } catch (t: Throwable) {
            android.util.Log.e("FR3K_HUD", "chat bubble addView failed", t)
        }
    }

    override fun hide() {
        if (!isAttached) return
        try {
            host.remove(root)
            isAttached = false
        } catch (_: Throwable) {}
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
        // Drag-from-header only. The chat bubble is a fixed-size popup —
        // the user explicitly asked for "not resizable", so the
        // ScaleGestureDetector / resize grip / pinch-zoom code path is
        // gone. Drag still works: header drag = move the window.
        fun listener(): View.OnTouchListener {
            var startX = 0
            var startY = 0
            var dragging = false
            return View.OnTouchListener { _, event ->
                if (event.pointerCount >= 2) return@OnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX.toInt()
                        startY = event.rawY.toInt()
                        dragging = false
                        // Returning false on DOWN lets the touch continue to
                        // child views (dismiss/send/model buttons) so they
                        // receive their click events. We only claim the
                        // gesture once the user actually moves.
                        false
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
                        // On a true tap (no drag), return false so the
                        // child view (header / bubble) can still receive
                        // their click events. The dismiss/send buttons
                        // are wired via setOnClickListener and only fire
                        // when the parent's touch returns false.
                        !dragging
                    }
                    else -> false
                }
            }
        }
        // Touch listener on root (drag from anywhere except interactive
        // children like EditText / buttons) and on the header text.
        root.setOnTouchListener(listener())
        header.setOnTouchListener(listener())
    }

    /**
     * Resize grip touch handler. The grip is a 16dp square in the
     * bottom-right corner; dragging it changes the chat bubble's
     * [params.width] and [params.height], clamped to reasonable limits.
     * Updates the root container's layout params so the LinearLayout
     * reflows.
     */
    private fun installResizeTouch() {
        val minW = (180 * density).toInt()
        val maxW = (560 * density).toInt()
        val minH = (320 * density).toInt()
        val maxH = (720 * density).toInt()
        var startW = 0
        var startH = 0
        var startX = 0
        var startY = 0
        resizeGrip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = if (params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        root.height.takeIf { it > 0 } ?: minH
                    } else params.height
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - startX
                    val dy = event.rawY.toInt() - startY
                    val newW = (startW + dx).coerceIn(minW, maxW)
                    val newH = (startH + dy).coerceIn(minH, maxH)
                    params.width = newW
                    params.height = newH
                    // The children already use MATCH_PARENT/weight. Rewriting
                    // their widths here used to collapse the weighted EditText
                    // and could hide the entire input row after a resize.
                    root.layoutParams = root.layoutParams.apply { width = newW }
                    root.requestLayout()
                    host.update(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * Cycle the active model through the OpenCode Zen free-list. Each tap
     * moves to the next free model. Long-press opens a text-prompt picker.
     */
    private fun cycleModel() {
        val app = Fr3kApplication.get()
        val provider = app.aiProviders.get("opencode-zen")
            as? com.mcpintelligence.fr3k.integrations.opencode.OpenCodeZenProvider
        if (provider == null) {
            appendLine("fr3k: opencode not registered")
            return
        }
        val current = provider.selectedModel()
        val free = provider.availableFreeModels()
        if (free.isEmpty()) {
            appendLine("fr3k: no free models cached; refresh")
            return
        }
        val idx = free.indexOfFirst { it.id == current }
        val next = free[(idx + 1).mod(free.size)]
        provider.setModel(next.id)
        header.text = "FR3K ▸ ${next.id}"
        appendLine("fr3k: switched → ${next.id}")
    }

    private fun showModelPicker() {
        val app = Fr3kApplication.get()
        val provider = app.aiProviders.get("opencode-zen")
            as? com.mcpintelligence.fr3k.integrations.opencode.OpenCodeZenProvider
        if (provider == null) {
            appendLine("fr3k: opencode not registered")
            return
        }
        val models = provider.availableFreeModels()
        if (models.isEmpty()) {
            appendLine("fr3k: no free models cached — refreshing from provider")
            // Kick off a background refresh so the next open of the
            // picker has a full list. Don't block here.
            scope.launch {
                try {
                    val r = provider.refreshFreeModels()
                    val count = r.getOrNull()?.size ?: 0
                    appendLine("fr3k: refresh got $count models")
                } catch (t: Throwable) {
                    appendLine("fr3k: refresh failed: ${t.message}")
                }
            }
            return
        }
        // Build a popup menu listing every free model. Tapping one
        // switches the provider to it and dismisses the popup.
        val popup = android.widget.PopupMenu(host.context, input)
        models.forEachIndexed { idx, m ->
            val label = if (m.id == provider.selectedModel()) "✓ ${m.id}" else m.id
            popup.menu.add(0, idx, idx, label)
        }
        // Add a refresh action at the bottom of the menu so the user
        // can force a re-fetch of the free-models list without leaving
        // the chat.
        popup.menu.add(0, -1, models.size, "↻ refresh free models")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == -1) {
                appendLine("fr3k: refreshing free models…")
                scope.launch {
                    try {
                        val r = provider.refreshFreeModels()
                        val count = r.getOrNull()?.size ?: 0
                        appendLine("fr3k: refresh got $count models")
                    } catch (t: Throwable) {
                        appendLine("fr3k: refresh failed: ${t.message}")
                    }
                }
                return@setOnMenuItemClickListener true
            }
            val chosen = models[item.itemId]
            provider.setModel(chosen.id)
            header.text = "FR3K ▸ ${chosen.id}"
            appendLine("fr3k: model → ${chosen.id}")
            true
        }
        popup.setOnDismissListener {
            // No state to restore — the input box was never touched.
        }
        popup.show()
    }

    private fun onSend() {
        val prompt = input.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return
        appendLine("you: $prompt")
        input.setText("")
        val app = Fr3kApplication.get()
        // Default provider: OpenCode Zen (free, no API key, big-pickle model).
        // Fallback to Hermes if OpenCode isn't registered.
        val opencode = app.aiProviders.get("opencode-zen")
            as? com.mcpintelligence.fr3k.integrations.opencode.OpenCodeZenProvider
        val hermes = app.aiProviders.get("hermes")
            as? com.mcpintelligence.fr3k.integrations.hermes.HermesProvider
        val (providerId, askCmd, providerRef) = when {
            opencode != null -> Triple(
                "opencode",
                { com.mcpintelligence.fr3k.integrations.opencode.AskOpenCodeCommand(provider = { opencode }) },
                opencode
            )
            hermes != null -> Triple(
                "hermes",
                { HermesAskCommand(provider = { hermes }) },
                hermes
            )
            else -> {
                appendLine("fr3k: no AI provider registered")
                android.util.Log.e("FR3K_HUD", "onSend: no AI provider registered")
                return
            }
        }
        val modelId = if (opencode != null) opencode.selectedModel() else "hermes"
        android.util.Log.i("FR3K_HUD", "onSend: provider=$providerId model=$modelId promptLen=${prompt.length}")
        appendLine("fr3k: $providerId → $modelId …")
        scope.launch {
            try {
                val cmd = askCmd()
                val ctx = Fr3kContext(
                    deviceId = app.identity.deviceId,
                    consentLevel = ConsentLevel.NORMAL,
                    foregroundPackage = app.packageName,
                    enabledCapabilities = app.fr3kCore.currentCapabilities(),
                )
                val result = cmd.execute(ctx, mapOf("prompt" to prompt))
                android.util.Log.i("FR3K_HUD", "onSend: result=$result")
                val text = when (result) {
                    is CommandResult.Ok -> result.message
                    is CommandResult.Failed -> "failed: ${result.reason}"
                    is CommandResult.Cancelled -> "cancelled: ${result.reason}"
                    is CommandResult.NeedsConfirmation -> "needs: ${result.summary}"
                }
                appendLine("$providerId: $text")
                // Speak the response if TTS is enabled.
                TtsPreference.speakIfEnabled(host.context, text)
            } catch (t: Throwable) {
                android.util.Log.e("FR3K_HUD", "onSend crashed", t)
                appendLine("fr3k: ${t.javaClass.simpleName}: ${t.message}")
            }
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