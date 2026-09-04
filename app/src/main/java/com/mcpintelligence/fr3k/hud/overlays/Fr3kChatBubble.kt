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

        resizeGrip = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xFF7d3cff.toInt())
            }
            contentDescription = "Drag to resize"
            alpha = 0.65f
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
            val transcriptLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (96+96).dp())
            addView(transcript, transcriptLp)
            val sp = Space(ctx); sp.layoutParams = LinearLayout.LayoutParams(1, (6 * density).toInt())
            addView(sp)
            // Input row: text field + send button.
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val sp2 = Space(ctx); sp2.layoutParams = LinearLayout.LayoutParams(1, (2 * density).toInt())
            addView(sp2)
            // Resize grip — bottom-right so the user can drag to grow/shrink.
            val gripParams = LinearLayout.LayoutParams(
                (20 * density).toInt(), (20 * density).toInt()
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
        // Drag from either the bubble shape or the header row.
        // Pinch-zoom is handled by the same listener via a shared
        // ScaleGestureDetector — two-finger gesture scales the window,
        // single-finger drags. The two are mutually exclusive (drag is
        // suppressed when the scale detector is in-progress).
        val scaleListener = object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                // Hitomi-style small popup: keep the bubble compact even
                // when the user pinches. Larger sizes belong in a real
                // chat surface, not the HUD overlay.
                val minW = (180 * density).toInt()
                val maxW = (360 * density).toInt()
                val minH = (200 * density).toInt()
                val maxH = (520 * density).toInt()
                val factor = detector.scaleFactor
                val newW = (params.width * factor).toInt().coerceIn(minW, maxW)
                val newH = (params.height * factor).toInt().coerceIn(minH, maxH)
                if (newW == params.width && newH == params.height) return true
                params.width = newW
                params.height = newH
                (root as LinearLayout).let { rl ->
                    for (i in 0 until rl.childCount) {
                        val child = rl.getChildAt(i)
                        if (child.layoutParams is LinearLayout.LayoutParams) {
                            child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply {
                                width = newW
                            }
                        }
                    }
                }
                host.update(root, params)
                return true
            }
        }
        val scaleDetector = android.view.ScaleGestureDetector(host.context, scaleListener)

        fun listener(): View.OnTouchListener {
            var startX = 0
            var startY = 0
            var dragging = false
            return View.OnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                // Skip drag when the user is mid-pinch.
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
     * Resize grip touch handler. The grip is a 20dp square in the bottom-
     * right corner; dragging it changes the chat bubble's [params.width]
     * and [params.height], clamped to reasonable limits. Updates the root
     * container's layout params so the LinearLayout reflows.
     */
    private fun installResizeTouch() {
        // The grip is a dedicated 20dp square in the bottom-right corner.
        // Drag it to grow / shrink the chat bubble. Pinch-zoom is wired on
        // the bubble/header in [installTouch] — the grip is single-finger only.
        val minW = (180 * density).toInt()
        val maxW = (360 * density).toInt()
        val minH = (200 * density).toInt()
        val maxH = (520 * density).toInt()
        var startW = 0
        var startH = 0
        var startX = 0
        var startY = 0
        resizeGrip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = if (params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        // First resize: lock to current measured height.
                        root.height.takeIf { it > 0 } ?: startH
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
                    // Update the root LinearLayout's children to match.
                    (root as LinearLayout).let { rl ->
                        for (i in 0 until rl.childCount) {
                            val child = rl.getChildAt(i)
                            child.layoutParams = (child.layoutParams as LinearLayout.LayoutParams).apply {
                                width = newW
                            }
                        }
                    }
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
        // Lazy: ask the user to type a model id into the input box and submit
        // to switch. Simple and reliable; no extra dialog.
        input.hint = "type model id (e.g. big-pickle) and send"
        input.requestFocus()
        // Bring up the soft keyboard.
        val imm = host.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        appendLine("fr3k: type a model id and SEND to switch")
        // Override the next send to be a model switch instead of a prompt.
        send.text = "SWITCH"
        val original = send.tag
        send.setOnClickListener {
            val id = input.text?.toString()?.trim().orEmpty()
            if (id.isNotEmpty()) {
                val app = Fr3kApplication.get()
                val provider = app.aiProviders.get("opencode-zen")
                    as? com.mcpintelligence.fr3k.integrations.opencode.OpenCodeZenProvider
                if (provider != null) {
                    provider.setModel(id)
                    header.text = "FR3K ▸ $id"
                    appendLine("fr3k: model → $id")
                } else {
                    appendLine("fr3k: opencode not registered")
                }
                input.setText("")
            }
            // Restore SEND.
            send.text = "SEND"
            input.hint = "ask…"
            send.setOnClickListener { onSend() }
        }
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
                return
            }
        }
        scope.launch {
            val cmd = askCmd()
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
            appendLine("$providerId: $text")
            // Speak the response if TTS is enabled.
            TtsPreference.speakIfEnabled(host.context, text)
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