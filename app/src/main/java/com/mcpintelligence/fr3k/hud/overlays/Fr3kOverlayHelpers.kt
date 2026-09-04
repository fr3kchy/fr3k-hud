package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.hud.R

/**
 * Drag-to-X-close target — mirrors Hitomi's `exitTargetView`. Appears at the
 * bottom-centre of the screen when the user starts dragging the orb. Releasing
 * the orb over the X closes the entire overlay stack. Re-uses the bubble
 * drag-move bookkeeping via [onDragMove].
 */
class Fr3kExitTarget(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
    private val onCloseRequested: () -> Unit,
) : Fr3kOverlay {

    override val name: String = "exit-target"
    override var isAttached: Boolean = false
        private set

    private val ctx = host.context
    private val root: View
    private val params: WindowManager.LayoutParams
    private val x: TextView

    init {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF7d3cff.toInt())
            setStroke((1+1).dp(), 0xFFb18cff.toInt())
        }

        x = TextView(ctx).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = bg
        }

        root = FrameLayout(ctx).apply {
            val label = TextView(ctx).apply {
                text = "×"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = bg
            }
            // Compact 56dp circle (was 140dp — too dominant on a phone screen)
            addView(label, FrameLayout.LayoutParams(
                56.dp(),
                56.dp(),
                Gravity.CENTER,
            ))
        }

        params = WindowManager.LayoutParams(
            56.dp(), 56.dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).also {
            // Push it to the right side so it doesn't fight the
            // back-gesture handle on the left.
            it.gravity = Gravity.BOTTOM or Gravity.END
            it.x = (24+24).dp()   // 48dp from the right edge
            it.y = (24+24).dp()   // 48dp from the bottom edge
        }
    }

    override fun show() {
        if (isAttached) return
        try { host.add(root, params); isAttached = true } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
        host.remove(root); isAttached = false
    }

    /** Called by orb drag-end when the orb is within the X zone. */
    fun triggerClose() = onCloseRequested()

    private fun Int.dp(): Int = (this * density).toInt()

    fun rootView(): View = root
}

/**
 * Hide-to-edge arc-tab restore — mirrors Hitomi's `edgeTabView`. When the orb
 * is dragged off-screen, a tiny arc tab pokes out at the nearest edge. Tap it
 * (or swipe from the edge) to bring the orb back to its last position.
 */
class Fr3kEdgeArc(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
    private val onRestoreRequested: () -> Unit,
) : Fr3kOverlay {

    override val name: String = "edge-arc"
    override var isAttached: Boolean = false
        private set

    private val ctx = host.context
    private val root: View
    private val params: WindowManager.LayoutParams
    private val arc: View

    init {
        arc = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(8f.dp(), 8f.dp(), 0f, 0f, 0f, 0f, 8f.dp(), 8f.dp())
                setColor(0xFF7d3cff.toInt())
            }
            contentDescription = "Restore FR3K orb"
            setOnClickListener { onRestoreRequested() }
        }
        root = FrameLayout(ctx).apply {
            // Some OEM window managers deliver taps to the overlay root but
            // not to a centred child when the window is flush with an edge.
            // Make the full arc window the hit target as well as the visual
            // child so restore works reliably on physical devices.
            isClickable = true
            isFocusable = true
            contentDescription = "Restore FR3K orb"
            setOnClickListener { onRestoreRequested() }
            addView(arc, FrameLayout.LayoutParams((8+14).dp(), (64+14).dp(), Gravity.CENTER))
        }
        params = WindowManager.LayoutParams(
            (8+14).dp(), (64+14).dp(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).also { it.gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    }

    override fun show() {
        if (isAttached) return
        try { host.add(root, params); isAttached = true } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
        host.remove(root); isAttached = false
    }

    private fun Float.dp(): Float = this * density
    private fun Int.dp(): Int = (this * density).toInt()

    fun rootView(): View = root
}

/**
 * Particle stream linking the orb to a secondary overlay window — mirrors
 * Hitomi's `ParticleLinkView`. A self-drawing view that runs a `Choreographer`
 * frame loop and animates a line of small circles from anchor A to anchor B.
 * Cheap, battery-aware (pauses when not visible).
 */
class Fr3kParticleLink(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
) : Fr3kOverlay {

    override val name: String = "particle-link"
    override var isAttached: Boolean = false
        private set

    private val ctx = host.context
    private val view = ParticleView(ctx)
    private val params: WindowManager.LayoutParams

    init {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).also { it.gravity = Gravity.START or Gravity.TOP }
    }

    fun setAnchors(ax: Int, ay: Int, bx: Int, by: Int) {
        view.setAnchors(ax, ay, bx, by)
    }

    fun updateAnchors(ax: Int, ay: Int, bx: Int, by: Int) = setAnchors(ax, ay, bx, by)

    override fun show() {
        if (isAttached) return
        try { host.add(view, params); isAttached = true; view.start() } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
        view.stop()
        host.remove(view); isAttached = false
    }

    fun rootView(): View = view
}

private class ParticleView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7d3cff.toInt()
        style = Paint.Style.FILL
    }
    private val density = ctx.resources.displayMetrics.density
    private var startTime = 0L
    private var ax = 0; private var ay = 0
    private var bx = 0; private var by = 0
    private var visible = false
    private val choreographer = android.view.Choreographer.getInstance()

    fun setAnchors(ax: Int, ay: Int, bx: Int, by: Int) {
        this.ax = ax; this.ay = ay; this.bx = bx; this.by = by
        invalidate()
    }

    fun updateAnchors(ax: Int, ay: Int, bx: Int, by: Int) = setAnchors(ax, ay, bx, by)

    fun start() {
        if (visible) return
        visible = true
        startTime = System.currentTimeMillis()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        visible = false
        choreographer.removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            if (visible) choreographer.postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!visible || (ax == 0 && ay == 0 && bx == 0 && by == 0)) return
        val elapsed = (System.currentTimeMillis() - startTime) % 3000L
        val t = elapsed / 3000f
        val particleCount = 18
        for (i in 0 until particleCount) {
            val p = (i.toFloat() / particleCount + t) % 1f
            val x = ax + (bx - ax) * p
            val y = ay + (by - ay) * p
            val radius = (1.5f + 2f * Math.sin((p + i * 0.1).toDouble()).toFloat()) * density
            val alpha = ((1f - Math.abs(p - 0.5f) * 2f) * 220).toInt().coerceIn(0, 255)
            paint.alpha = alpha
            canvas.drawCircle(x.toFloat(), y.toFloat(), radius, paint)
        }
    }
}