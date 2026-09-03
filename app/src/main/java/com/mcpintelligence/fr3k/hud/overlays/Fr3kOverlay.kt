package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/**
 * Common contract for every WindowManager overlay window owned by FR3K.
 * Hitomi-style: one foreground service holds many overlays; each one is
 * independently movable, dismissible, and can be opened/closed by gestures
 * on the HUD orb. This mirrors the way `HedgehogOverlayService` manages
 * hedgehog / bubble / browser / solana / terminal in Decentricity/hitomi-android.
 */
interface Fr3kOverlay {

    /** Stable name for diagnostics. */
    val name: String

    /** True when the overlay has been added to the window manager. */
    val isAttached: Boolean

    /** Open (inflate + addView) the overlay. Idempotent. */
    fun show()

    /** Remove the overlay from the window manager. Idempotent. */
    fun hide()

    /**
     * Optional drag handler — overlays that respond to user-drag override this.
     * Returns true if the event was consumed.
     */
    fun onDragStart() {}
    fun onDragMove(x: Int, y: Int) {}
    fun onDragEnd() {}
}

/** Factory for [WindowManager.LayoutParams] configured for our overlay family. */
object OverlayParams {
    fun make(
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags: Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        gravity: Int = android.view.Gravity.TOP or android.view.Gravity.START,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width, height, type, flags, PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    fun forChat(width: Int, height: Int) = make(width, height)
    fun forBrowser(width: Int, height: Int) = make(width, height)
    fun forTerminal(width: Int, height: Int) = make(width, height)
}

/** Window manager reference shared by all overlays. */
class OverlayHost(val context: Context, val windowManager: WindowManager) {
    fun remove(view: View) = runCatching { windowManager.removeView(view) }
    fun add(view: View, params: WindowManager.LayoutParams) {
        // Pre-measure if the view is wrap_content — WindowManager won't
        // measure for us and we'd otherwise render at 0x0.
        if (params.width == WindowManager.LayoutParams.WRAP_CONTENT ||
            params.height == WindowManager.LayoutParams.WRAP_CONTENT) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (params.width == WindowManager.LayoutParams.WRAP_CONTENT) {
                params.width = view.measuredWidth.coerceAtLeast(1)
            }
            if (params.height == WindowManager.LayoutParams.WRAP_CONTENT) {
                params.height = view.measuredHeight.coerceAtLeast(1)
            }
        }
        runCatching { windowManager.addView(view, params) }
    }
    fun update(view: View, params: WindowManager.LayoutParams) =
        runCatching { windowManager.updateViewLayout(view, params) }
}