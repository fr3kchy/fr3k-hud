package com.mcpintelligence.fr3k.hud

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.R
import com.mcpintelligence.fr3k.hud.quickhud.QuickHudActivity
import com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity
import com.mcpintelligence.fr3k.ui.screenshot.ScreenshotActivity
import com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity
import com.mcpintelligence.fr3k.ui.clipboard.SmartClipboardActivity
import com.mcpintelligence.fr3k.ui.automation.AutomationActivity
import com.mcpintelligence.fr3k.ui.devoverlay.DeveloperOverlayActivity
import com.mcpintelligence.fr3k.ui.diagnostics.DiagnosticsActivity
import com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity
import com.mcpintelligence.fr3k.ui.settings.SettingsActivity

/**
 * The orb itself. Holds the touch handling for tap / long-press / double-tap /
 * swipe gestures (§7). All gestures are configurable; defaults below match the
 * brief exactly. Position is persisted in DataStore by the HudOverlayService.
 *
 * Touch slop is generous to avoid accidental triggers; long-press threshold is
 * 450 ms; double-tap window is 250 ms.
 */
class Fr3kHudOrb(
    private val context: Context,
    private val windowManager: WindowManager,
) {

    interface GestureListener {
        fun onTap()
        fun onLongPress()
        fun onDoubleTap()
        fun onSwipeUp()
        fun onSwipeDown()
    }

    private val orbView: View
    private val params: WindowManager.LayoutParams
    private var listener: GestureListener? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var touchDownTime = 0L
    private var lastTapTime = 0L
    private var tapCount = 0
    private var dragStarted = false
    private val touchSlopPx = (16 * context.resources.displayMetrics.density).toInt()
    private val longPressMs = 450L
    private val doubleTapMs = 250L
    private val swipeMinDistPx = (80 * context.resources.displayMetrics.density).toInt()

    init {
        orbView = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.fr3k_orb)
            contentDescription = "FR3K HUD orb"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            isFocusable = true
            minimumWidth = (36 * context.resources.displayMetrics.density).toInt()
            minimumHeight = (36 * context.resources.displayMetrics.density).toInt()
            layoutParams = android.view.ViewGroup.LayoutParams(
                (36 * context.resources.displayMetrics.density).toInt(),
                (36 * context.resources.displayMetrics.density).toInt(),
            )
        }
        val label = TextView(context).apply {
            text = "F3K"
            setTextColor(0xFF05060A.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        orbView.addView(label)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            (36 * context.resources.displayMetrics.density).toInt(),
            (36 * context.resources.displayMetrics.density).toInt(),
            type,
            // NOT_FOCUSABLE    — never steal focus from the activity below.
            // LAYOUT_NO_LIMITS — can extend past screen edges.
            // No WATCH_OUTSIDE_TOUCH — touches outside the orb's 36dp
            // bounds go to the underlying window automatically, so the
            // radial menu and any other UI stay interactive.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 200
        }
        android.util.Log.i("FR3K", "orb constructed, view=$orbView, params=${params.width}x${params.height}")
    }

    fun setGestureListener(l: GestureListener) {
        listener = l
    }

    fun attach() {
        try {
            // Force the view to the params size before adding so its hitbox
            // actually matches the window — without this the view stays 0x0
            // and setOnTouchListener never fires.
            orbView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(params.width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(params.height, android.view.View.MeasureSpec.EXACTLY),
            )
            orbView.layout(0, 0, params.width, params.height)
            windowManager.addView(orbView, params)
            android.util.Log.i("FR3K", "orb attached at ${params.x},${params.y}, view=${orbView.width}x${orbView.height}")
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "could not add overlay", e)
        }
    }

    fun detach() {
        runCatching { windowManager.removeView(orbView) }
    }

    fun setPosition(x: Int, y: Int) {
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(orbView, params) }
    }

    fun currentPosition(): Pair<Int, Int> = params.x to params.y
    fun viewWidthPx(): Int = params.width

    /** Register callbacks for drag lifecycle (used by service to show X-target etc). */
    fun setLifecycleListener(l: OrbLifecycleListener?) { lifecycle = l }

    private var lifecycle: OrbLifecycleListener? = null

    interface OrbLifecycleListener {
        fun onOrbDragStart() {}
        fun onOrbDragging(x: Int, y: Int) {}
        fun onOrbDragEnd(x: Int, y: Int, droppedOnClose: Boolean, droppedOffScreen: Boolean) {}
    }

    fun installTouchHandler() {
        android.util.Log.i("FR3K", "installTouchHandler called, view=${orbView.width}x${orbView.height}, hasListener=${orbView.hasOnClickListeners()}")
        // The orb gesture is handled entirely by setOnTouchListener so we
        // don't add a setOnClickListener — that would double-fire and steal
        // the radial's clicks when the touch ends on the orb.
        //
        // The window has FLAG_NOT_TOUCH_MODAL so touches outside the orb
        // pass through to the underlying activity. We only need to handle
        // touches that actually start *inside* the orb's bounds; outside
        // touches never reach this listener.
        orbView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchDownTime = System.currentTimeMillis()
                    dragStarted = false
                    // NOTE: do NOT fire onOrbDragStart here — taps should
                    // never reveal the drag UI. We only fire it the first
                    // time the finger crosses the touch slop in MOVE.
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!dragStarted && (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx)) {
                        dragStarted = true
                        lifecycle?.onOrbDragStart()
                    }
                    if (kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(orbView, params) }
                        lifecycle?.onOrbDragging(params.x, params.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val elapsed = System.currentTimeMillis() - touchDownTime
                    val moved = kotlin.math.abs(dx) > touchSlopPx || kotlin.math.abs(dy) > touchSlopPx
                    if (dragStarted) {
                        // We only emit dragEnd if dragStart actually fired —
                        // otherwise dragEnd would try to hide UI that was
                        // never shown.
                        magnetToEdge()
                        val metrics = context.resources.displayMetrics
                        val orbCenterX = params.x + params.width / 2
                        val orbCenterY = params.y + params.height / 2
                        // Close-target now sits in the bottom-right corner
                        // (Gravity.BOTTOM | Gravity.END, 48dp inset, 56dp
                        // diameter). Compute the centre of the X-target so
                        // dropping the orb on it actually closes.
                        val closeRadius = (48 * metrics.density)
                        val closeZoneX = metrics.widthPixels - (24 + 24 + 28) * metrics.density
                        val closeZoneY = metrics.heightPixels - (24 + 24 + 28) * metrics.density
                        val droppedOnClose = kotlin.math.abs(orbCenterX - closeZoneX) < closeRadius &&
                            kotlin.math.abs(orbCenterY - closeZoneY) < closeRadius
                        val droppedOffScreen = params.x < 0 || params.y < 0 ||
                            params.x + params.width > metrics.widthPixels ||
                            params.y + params.height > metrics.heightPixels
                        lifecycle?.onOrbDragEnd(params.x, params.y, droppedOnClose, droppedOffScreen)
                    } else if (!moved && elapsed >= longPressMs) {
                        listener?.onLongPress()
                    } else if (!moved && kotlin.math.abs(dy) >= swipeMinDistPx && dy < 0) {
                        listener?.onSwipeUp()
                    } else if (!moved && kotlin.math.abs(dy) >= swipeMinDistPx && dy > 0) {
                        listener?.onSwipeDown()
                    } else if (!moved) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapMs) {
                            tapCount++
                            if (tapCount >= 1) {
                                listener?.onDoubleTap()
                                tapCount = 0
                            }
                        } else {
                            tapCount = 0
                            listener?.onTap()
                        }
                        lastTapTime = now
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun magnetToEdge() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val center = screenWidth / 2
        params.x = if (params.x + params.width / 2 < center) {
            (16 * displayMetrics.density).toInt()
        } else {
            (screenWidth - params.width - (16 * displayMetrics.density).toInt())
        }
        runCatching { windowManager.updateViewLayout(orbView, params) }
    }

    companion object {
        private const val TAG = "FR3K.orb"
    }
}