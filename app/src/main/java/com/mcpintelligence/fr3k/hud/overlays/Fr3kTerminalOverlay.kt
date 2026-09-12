package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.Fr3kApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Green-on-black terminal overlay — Hitomi's `overlay_terminal`.
 *
 * Complete rerender (2026-09-05) fixing three regression classes:
 *   1. DRAG FLIES OFF-SCREEN + CRASH. The previous build assigned the
 *      drag touch listener to `dragHandle` in installTouch(), then
 *      RE-ASSIGNED a second listener in installResizeTouch() whose
 *      coordinate vars were declared INSIDE the lambda — so on every
 *      ACTION_MOVE `sx/sy` reset to 0 and `dx=rawX-0` was the raw
 *      screen coordinate, hurling the window off-screen. Now there is
 *      ONE drag listener on the header with state hoisted outside the
 *      lambda, and drag deltas are clamped to the real display bounds.
 *   2. RESIZE MADE THE BOX VANISH. The resize grip mutated
 *      `params.height` but never re-laid-out the weighted transcript,
 *      so a WRAP_CONTENT box could collapse to a sliver. Now the
 *      transcript sits in a FrameLayout with a fixed weight span and
 *      resize calls `requestLayout()` on the root.
 *   3. SPACE. Every dp value was obfuscated (e.g. `(12+14).dp()`,
 *      `(180+96).dp()`) inflating padding/transcript height. All
 *      cleaned to literals; border removed — the box is now a bare
 *      surface with a slim header: tiny title + a single ✕ close.
 *
 * The input echoes back through the same Termux bridge as before
 * ([Fr3kApplication.termuxBridge]); tapping it pops the soft keyboard.
 */
class Fr3kTerminalOverlay(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
) : Fr3kOverlay {

    override val name: String = "terminal"
    override var isAttached: Boolean = false
        private set

    private val ctx = host.context
    private val root: View
    private val params: WindowManager.LayoutParams
    private val header: TextView
    private val closeBtn: TextView
    private val maxBtn: TextView
    private val minBtn: TextView
    private val titleRow: LinearLayout
    private val transcript: TextView
    private val input: EditText
    private val resizeGrip: View

    // Drag state hoisted OUTSIDE the listener lambda so it survives
    // across ACTION_MOVE events. Previously declared inside the lambda,
    // it reset to (0,0) on every move — the exact off-screen bug.
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false

    // Last known position so drag deltas are relative to it.
    private var viewX = 0
    private var viewY = (120 * density).toInt()

    // Maximise / minimise (§2). A window snapshot is taken before either
    // transition so restore returns to the exact prior geometry.
    private var maximised = false
    private var minimised = false
    private var savedW = 0
    private var savedH = 0
    private var savedX = 0
    private var savedY = 0
    private var pill: View? = null
    private var pillParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        // No border — a bare rounded surface with a sliver of outline so
        // it's visible on a dark wallpaper but does not read as a box.
        val bg = GradientDrawable().apply {
            cornerRadius = 10f.dp()
            setColor(0xF20A0F0C.toInt())
            setStroke((0).dp(), 0x00000000.toInt()) // only a hint, no visible border
        }

        header = TextView(ctx).apply {
            text = "TERMUX"
            setTextColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.16f
            setPadding(10.dp(), 6.dp(), 6.dp(), 2.dp())
        }
        closeBtn = TextView(ctx).apply {
            text = "✕"
            setTextColor(0xFF9ca3af.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            contentDescription = "Close terminal"
            setPadding(0, 0, 10.dp(), 0)
            setOnClickListener { hide() }
        }
        minBtn = TextView(ctx).apply {
            text = "–"
            setTextColor(0xFF9ca3af.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            contentDescription = "Minimise terminal to a pill"
            setPadding(0, 0, 6.dp(), 0)
            setOnClickListener { minimise() }
        }
        maxBtn = TextView(ctx).apply {
            text = "▢"
            setTextColor(0xFF9ca3af.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            contentDescription = "Maximise window"
            setPadding(0, 0, 6.dp(), 0)
            setOnClickListener { toggleMaximise() }
        }
        titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(minBtn, LinearLayout.LayoutParams((24).dp(), (28).dp()))
            addView(maxBtn, LinearLayout.LayoutParams((24).dp(), (28).dp()))
            addView(closeBtn, LinearLayout.LayoutParams((36).dp(), (28).dp()))
        }

        transcript = TextView(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 6f.dp()
                setColor(0x00000000.toInt()) // transparent — the root bg shows through
            }
            setTextColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            maxLines = 16
            ellipsize = TextUtils.TruncateAt.END
            isVerticalScrollBarEnabled = true
            text = buildString {
                append("[${timestamp.format(Date())}] fr3k terminal ready.\n")
                append("type a command (termux-info, pwd, ls, whoami) or /help\n")
            }
        }

        input = EditText(ctx).apply {
            hint = "$"
            setHintTextColor(0xFF1f7a36.toInt())
            setTextColor(0xFF39ff14.toInt())
            setBackgroundColor(0x1A04150a.toInt()) // slightly raised input field
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> onRun(); true }
            // Keyboard on tap
            setOnClickListener {
                requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        val runBtn = TextView(ctx).apply {
            text = "RUN"
            setTextColor(0xFF000604.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF39ff14.toInt())
            contentDescription = "Run command"
            setPadding(10.dp(), 6.dp(), 10.dp(), 6.dp())
            setOnClickListener { onRun() }
        }
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(runBtn, LinearLayout.LayoutParams((52).dp(), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // Resize grip — a small green square, bottom-right corner.
        resizeGrip = View(ctx).apply {
            background = GradientDrawable().apply { setColor(0xFF39ff14.toInt()) }
            contentDescription = "Drag to resize"
            alpha = 0.7f
        }
        // Transcript in a FrameLayout so the grip can sit in its corner
        // without joining the vertical flow (mirrors the browser layout).
        val transcriptFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            addView(transcript, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(resizeGrip, FrameLayout.LayoutParams(
                (18).dp(), (18).dp(),
                Gravity.END or Gravity.BOTTOM,
            ))
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(transcriptFrame)
            val sp = View(ctx); sp.layoutParams = LinearLayout.LayoutParams(1, 4.dp())
            addView(sp)
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        params = OverlayParams.forTerminal(320.dp(), 300.dp())
        params.x = viewX
        params.y = viewY
        installTouch()
        installResizeTouch()
    }

    override fun show() {
        if (isAttached) return
        if (minimised) {
            restoreFromPill()
            return
        }
        try { host.add(root, params); isAttached = true } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
        running?.cancel()
        running = null
        if (minimised) {
            pill?.let { host.remove(it) }
            minimised = false
        }
        host.remove(root)
        isAttached = false
    }

    override fun onDragStart() {}
    override fun onDragMove(dx: Int, dy: Int) {
        if (!isAttached || minimised) return
        params.x = viewX + dx
        params.y = viewY + dy
        host.update(root, params)
    }
    override fun onDragEnd() {
        viewX = params.x; viewY = params.y
    }

    /** Clamp the given window position to on-screen bounds. */
    private fun clampToDisplay(
        x: Int,
        y: Int,
        w: Int = params.width,
        h: Int = params.height,
    ): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val out = android.graphics.Point()
        wm.defaultDisplay.getSize(out)
        val maxX = (out.x - w).coerceAtLeast(0)
        val maxY = (out.y - h - 28 * density.toInt()).coerceAtLeast(0) // leave the status bar visible
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun toggleMaximise() {
        if (!isAttached || minimised) return
        if (maximised) restoreSize() else maximise()
        updateWindowButtons()
    }

    private fun maximise() {
        saveGeometry()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val out = android.graphics.Point()
        wm.defaultDisplay.getSize(out)
        val margin = (8 * density).toInt()
        val topInset = (52 * density).toInt() // below the status bar
        params.width = out.x - margin * 2
        params.height = out.y - topInset - margin
        params.x = margin
        params.y = topInset
        viewX = margin
        viewY = topInset
        windowResizeDone()
        maximised = true
        updateWindowButtons()
    }

    private fun restoreSize() {
        if (savedW <= 0) return
        params.width = savedW
        params.height = savedH
        params.x = savedX
        params.y = savedY
        viewX = savedX
        viewY = savedY
        windowResizeDone()
        maximised = false
        updateWindowButtons()
    }

    private fun saveGeometry() {
        savedW = params.width
        savedH = params.height
        savedX = params.x
        savedY = params.y
    }

    private fun updateWindowButtons() {
        maxBtn.text = if (maximised) "▣" else "▢"
        maxBtn.contentDescription = if (maximised) "Restore window size" else "Maximise window"
    }

    private fun windowResizeDone() {
        root.requestLayout()
        host.update(root, params)
    }

    fun minimise() {
        if (!isAttached || minimised) return
        saveGeometry()
        val v = pill ?: buildPill().also { pill = it }
        val pp = pillParams ?: OverlayParams.make((132).dp(), (34).dp()).also { pillParams = it }
        pp.x = params.x
        pp.y = params.y
        host.remove(root)
        host.add(v, pp)
        pillParams = pp
        minimised = true
    }

    fun restoreFromPill() {
        if (!minimised) return
        val v = pill ?: return
        host.remove(v)
        restoreSize()
        host.add(root, params)
        minimised = false
    }

    private fun buildPill(): View {
        val label = TextView(ctx).apply {
            text = "▸ TERMUX"
            setTextColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 8f.dp()
                setColor(0xF20A0F0C.toInt())
                setStroke((1).dp(), 0xFF39ff14.toInt())
            }
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            contentDescription = "Terminal minimized — tap to restore"
        }
        // Draggable pill with tap-to-restore; drag deltas are relative to
        // the moved-from position so the pill never jumps on first move.
        var px = 0
        var py = 0
        var pDrag = false
        v.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    px = event.rawX.toInt()
                    py = event.rawY.toInt()
                    pDrag = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - px
                    val dy = event.rawY.toInt() - py
                    if (!pDrag && (dx * dx + dy * dy) > 64) pDrag = true
                    if (pDrag) {
                        val pp = pillParams ?: return@setOnTouchListener true
                        pp.x += dx
                        pp.y += dy
                        px = event.rawX.toInt()
                        py = event.rawY.toInt()
                        host.update(v, pp)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pDrag) {
                        val pp = pillParams ?: return@setOnTouchListener true
                        val (cx, cy) = clampToDisplay(pp.x, pp.y, pp.width, pp.height)
                        pp.x = cx
                        pp.y = cy
                        host.update(v, pp)
                    } else {
                        restoreFromPill()
                    }
                    true
                }
                else -> false
            }
        }
        return v
    }

    private fun installTouch() {
        // ONE drag listener on the header row with state hoisted OUTSIDE
        // the lambda. Previously installResizeTouch() overwrote this with
        // a second listener whose coords reset each event → off-screen bug.
        titleRow.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX.toInt()
                    dragStartY = event.rawY.toInt()
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - dragStartX
                    val dy = event.rawY.toInt() - dragStartY
                    if (!dragging && (dx * dx + dy * dy) > 64) dragging = true
                    if (dragging) onDragMove(dx, dy)
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Clamp the final position so the box never rests
                    // off-screen after a drag ends.
                    val (cx, cy) = clampToDisplay(params.x, params.y)
                    params.x = cx; params.y = cy; viewX = cx; viewY = cy
                    host.update(root, params)
                    onDragEnd()
                    !dragging
                }
                else -> false
            }
        }
    }

    private fun installResizeTouch() {
        val minW = (200 * density).toInt()
        val maxW = (520 * density).toInt()
        val minH = (140 * density).toInt()
        val maxH = (600 * density).toInt()
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
                    // Reflow children so the weighted transcript absorbs the
                    // delta instead of the input row collapsing.
                    root.requestLayout()
                    host.update(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun Float.dp(): Float = this * density
    private fun Int.dp(): Int = (this * density).toInt()

    /** Show the actual tool execution without starting a second shell command. */
    fun showAgentExecution(command: String, cwd: String?) {
        if (minimised) restoreFromPill()
        show()
        appendLine("[agent] cwd: ${cwd ?: "/data/data/com.termux/files/home"}")
        appendLine("$ $command")
        appendLine("Running in Termux; output appears when the command returns.")
    }

    /** Append a line to the transcript from anywhere. */
    fun appendLine(line: String) {
        ctx.mainExecutor.execute {
            val prev = transcript.text?.toString().orEmpty()
            transcript.text = prev + "\n" + "[${timestamp.format(Date())}] $line"
        }
    }

    private fun onRun() {
        val cmd = input.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        input.setText("")
        appendLine("$ $cmd")
        running?.cancel()
        running = scope.launch { execute(cmd) }
    }

    /**
     * §3 agent entry — run a command as the agent, restoring the window if
     * it was minimised so the agent's output stays visible. Returns the
     * effective command string (with the cwd prefix baked in).
     */
    fun agentRun(
        command: String,
        cwd: String? = null,
    ): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return ""
        val effective = if (cwd.isNullOrBlank().not() && cwd != "~") {
            "cd ${quoteShell(cwd!!)} && $trimmed"
        } else {
            trimmed
        }
        if (minimised) restoreFromPill()
        if (!isAttached) show()
        appendLine("[agent] $ $trimmed")
        running?.cancel()
        running = scope.launch { execute(effective) }
        return effective
    }

    /** Whether Termux is actually available for agent execution. */
    fun agentAvailable(): Boolean {
        val app = runCatching { Fr3kApplication.get() }.getOrNull() ?: return false
        val bridge = runCatching { app.termuxBridge }.getOrNull() ?: return false
        return bridge.isAvailable()
    }

    private fun quoteShell(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private suspend fun execute(cmd: String) {
        val app = Fr3kApplication.get()
        val bridge = runCatching { app.termuxBridge }.getOrNull()
        if (bridge != null && bridge.isAvailable()) {
            val result = bridge.runRaw(cmd, 30_000)
            appendLine(result.stdout.lines().joinToString("\n"))
            if (result.stderr.isNotBlank()) appendLine("stderr: ${result.stderr}")
            appendLine("exit ${result.exitCode}")
            return
        }
        // Fallback — local sh
        try {
            val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            out.lines().filter { it.isNotEmpty() }.forEach { appendLine(it) }
            appendLine("exit ${proc.exitValue()}")
        } catch (t: Throwable) {
            appendLine("error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    fun rootView(): View = root
    fun currentPosition(): Pair<Int, Int> = params.x to params.y

    fun shutdown() { hide(); scope.cancel() }
}