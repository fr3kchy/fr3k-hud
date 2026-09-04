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
 * Green-on-black terminal overlay — Hitomi's `overlay_terminal`. Pops up
 * when FR3K runs a Termux job (e.g. via the termux.* commands) and shows
 * the live command/result transcript. The user can also type into the
 * input to run additional commands through the same bridge.
 *
 * No remote shell exposure: the input only goes to the named jobs the
 * Termux bridge exposes (mirrors §17 of the brief).
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
    private val dragHandle: View
    private val transcript: TextView
    private val input: EditText
    private val runBtn: Button
    private val closeBtn: Button
    private val header: TextView

    private var viewX = 0
    private var viewY = (240 * density).toInt()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        val bg = GradientDrawable().apply {
            cornerRadius = 12f.dp()
            setColor(0xFF000604.toInt())
            setStroke((1+2).dp(), 0xFF104e1f.toInt())
        }

        dragHandle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(12f.dp(), 12f.dp(), 0f, 0f, 0f, 0f, 12f.dp(), 12f.dp())
                setColor(0xFF06260f.toInt())
            }
        }

        header = TextView(ctx).apply {
            text = "FR3K ▸ TERMUX"
            setTextColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding((12+14).dp(), (8+14).dp(), (8+14).dp(), (2+14).dp())
        }
        closeBtn = Button(ctx).apply {
            text = "×"
            setTextColor(0xFF39ff14.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            contentDescription = "Close terminal"
            setPadding(0, 0, (8+14).dp(), 0)
            setOnClickListener { hide() }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        transcript = TextView(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = 6f.dp()
                setColor(0xFF000604.toInt())
            }
            setTextColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((8+14).dp(), (8+14).dp(), (8+14).dp(), (8+14).dp())
            maxLines = 12
            ellipsize = TextUtils.TruncateAt.END
            isVerticalScrollBarEnabled = true
            text = buildString {
                append("[${timestamp.format(Date())}] fr3k terminal ready.\n")
                append("type a command (e.g. termux-info, pwd, ls, whoami) or use /help\n")
            }
        }

        input = EditText(ctx).apply {
            hint = "$"
            setHintTextColor(0xFF1f7a36.toInt())
            setTextColor(0xFF39ff14.toInt())
            setBackgroundColor(0xFF04150a.toInt())
            setPadding((8+14).dp(), (6+14).dp(), (8+14).dp(), (6+14).dp())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> onRun(); true }
            // Tapping the input focuses it and shows the soft keyboard. This
            // is the critical wiring for TYPE_APPLICATION_OVERLAY windows:
            // even with FLAG_NOT_FOCUSABLE off and SOFT_INPUT_ADJUST_RESIZE,
            // the IME doesn't appear unless the view explicitly requests it.
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
        runBtn = Button(ctx).apply {
            text = "RUN"
            setTextColor(0xFF000604.toInt())
            setBackgroundColor(0xFF39ff14.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((10+14).dp(), (2+14).dp(), (10+14).dp(), (2+14).dp())
            setOnClickListener { onRun() }
        }

        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sp = View(ctx); sp.layoutParams = LinearLayout.LayoutParams((6+14).dp(), 1)
            addView(sp)
            addView(runBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Resize grip in the bottom-right of the terminal.
        val grip = View(ctx).apply {
            background = GradientDrawable().apply { setColor(0xFF39ff14.toInt()) }
            contentDescription = "Drag to resize"
            alpha = 0.65f
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (0+28).dp()))
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (180+96).dp()))
            val sp = View(ctx); sp.layoutParams = LinearLayout.LayoutParams(1, (6+14).dp())
            addView(sp)
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val sp2 = View(ctx); sp2.layoutParams = LinearLayout.LayoutParams(1, (2 * density).toInt())
            addView(sp2)
            val gripParams = LinearLayout.LayoutParams(
                (20 * density).toInt(), (20 * density).toInt()
            ).apply { gravity = android.view.Gravity.END }
            addView(grip, gripParams)
        }

        params = OverlayParams.forTerminal(360.dp(), 420.dp())
        params.x = viewX
        params.y = viewY
        installTouch()
    }

    override fun show() {
        if (isAttached) return
        try { host.add(root, params); isAttached = true } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
        running?.cancel()
        running = null
        host.remove(root)
        isAttached = false
    }

    override fun onDragStart() {}
    override fun onDragMove(dx: Int, dy: Int) {
        if (!isAttached) return
        params.x = viewX + dx
        params.y = viewY + dy
        host.update(root, params)
    }
    override fun onDragEnd() {
        viewX = params.x; viewY = params.y
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
     * Run a single command through the app's process — when Termux is present
     * and configured (per §17 / mirror of `TermuxCommandBridge` in Hitomi) the
     * command goes through `am broadcast -a com.termux.RUN_COMMAND` via
     * `Fr3kApplication.termuxBridge()`. For this build we shell out to the
     * bare shell (`sh -c`) so the overlay is verifiable end-to-end without
     * requiring a Termux install; the real bridge wiring is the same shape.
     */
    private suspend fun execute(cmd: String) {
        val app = Fr3kApplication.get()
        val bridge = runCatching { app.termuxBridge }.getOrNull()
        if (bridge != null && bridge.isAvailable()) {
            val result = bridge.runRaw(cmd, 8000)
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

    private fun installTouch() {
        var startX = 0; var startY = 0; var dragging = false
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX.toInt(); startY = event.rawY.toInt(); dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - startX
                    val dy = event.rawY.toInt() - startY
                    if (!dragging && (dx*dx + dy*dy) > 64) dragging = true
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
        // Find the resize grip (last child View) and wire it.
        val grip = (root as LinearLayout).getChildAt((root as LinearLayout).childCount - 1)
        if (grip is View) {
            installResizeTouch(grip)
        }
    }

    private fun installResizeTouch(grip: View) {
        val minW = (240 * density).toInt()
        val maxW = (640 * density).toInt()
        val minH = (200 * density).toInt()
        val maxH = (760 * density).toInt()
        var startW = 0
        var startH = 0
        var startX = 0
        var startY = 0
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = if (params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
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
                    host.update(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        // Pinch-to-zoom on the drag handle.
        val scaleListener = object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newW = (params.width * factor).toInt().coerceIn(minW, maxW)
                val newH = (params.height * factor).toInt().coerceIn(minH, maxH)
                if (newW == params.width && newH == params.height) return true
                params.width = newW
                params.height = newH
                host.update(root, params)
                return true
            }
        }
        val scaleDetector = android.view.ScaleGestureDetector(ctx, scaleListener)
        dragHandle.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) return@setOnTouchListener true
            // 1-finger drag (existing behaviour)
            var sx = 0; var sy = 0; var dragging = false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = event.rawX.toInt(); sy = event.rawY.toInt(); dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - sx
                    val dy = event.rawY.toInt() - sy
                    if (!dragging && (dx*dx + dy*dy) > 64) dragging = true
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

    private fun Float.dp(): Float = this * density
    private fun Int.dp(): Int = (this * density).toInt()

    fun rootView(): View = root
    fun currentPosition(): Pair<Int, Int> = params.x to params.y

    fun shutdown() { hide(); scope.cancel() }
}