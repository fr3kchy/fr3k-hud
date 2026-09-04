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
        softInputMode: Int = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width, height, type, flags, PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = gravity
        it.softInputMode = softInputMode
    }

    /**
     * Overlay params for an input-aware window (chat, terminal). Drops
     * `FLAG_NOT_FOCUSABLE` so the EditText can receive focus, and sets
     * `SOFT_INPUT_ADJUST_RESIZE` so the system pushes the layout up when
     * the keyboard appears. This is the magic combination that makes
     * overlay-window text inputs show the soft keyboard.
     */
    fun forInput(
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // FLAG_NOT_FOCUSABLE must be off, and FLAG_LAYOUT_NO_LIMITS lets us
        // position without being clipped to the safe area.
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).also {
        it.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        it.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
    }

    fun forChat(width: Int, height: Int) = forInput(width, height)
    fun forBrowser(width: Int, height: Int) = make(width, height)
    fun forTerminal(width: Int, height: Int) = forInput(width, height)
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