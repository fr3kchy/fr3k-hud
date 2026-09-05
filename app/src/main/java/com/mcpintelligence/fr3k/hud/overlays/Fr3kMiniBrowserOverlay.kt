package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mini browser overlay window — mirrors Hitomi's `overlay_browser` (BeOS-style
 * mini browser that pops up when the assistant triggers a `{{tool:browser_open:url}}`
 * in its reply). This is the standalone version: opened by Ask About This, by
 * command palette's "Open URL", or by sharing a URL into FR3K.
 */
class Fr3kMiniBrowserOverlay(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
) : Fr3kOverlay {

    override val name: String = "mini-browser"
    override var isAttached: Boolean = false
        private set

    private val ctx = host.context
    private val root: View
    private val params: WindowManager.LayoutParams
    private val dragHandle: View
    private val webView: WebView
    private val urlField: EditText
    private val back: Button
    private val close: Button
    private val reload: Button
    private val resizeGrip: View

    private var viewX = 0
    private var viewY = (160 * density).toInt()

    init {
        val bg = GradientDrawable().apply {
            cornerRadius = 10f.dp()
            setColor(0xF211111c.toInt())
            setStroke(0.dp(), 0x13000000.toInt()) // hairline
        }

        val header = TextView(ctx).apply {
            text = "BROWSER"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.16f
            setPadding(10.dp(), 6.dp(), 6.dp(), 2.dp())
        }
        dragHandle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(10f.dp(), 10f.dp(), 0f, 0f, 0f, 0f, 10f.dp(), 10f.dp())
                setColor(0x0011111c.toInt()) // transparent but a touch target
            }
        }
        close = Button(ctx).apply {
            text = "×"
            setTextColor(0xFF8e8a99.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            contentDescription = "Close browser"
            setPadding(0, 0, 8.dp(), 0)
            // Compact icon button — fixed 28dp so the title row stays slim.
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
            isAllCaps = false
            setOnClickListener { hide() }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        urlField = EditText(ctx).apply {
            hint = "https://…"
            setHintTextColor(0xFF6a6878.toInt())
            setTextColor(0xFFe8eaf2.toInt())
            setBackgroundColor(0xFF0d0d18.toInt())
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> onGo(); true }
            // Tapping the address bar should always focus the field and pop
            // the soft keyboard, even if the address row's touch listener
            // already returned true. requestFocus() is enough because the
            // window has FLAG_NOT_FOCUSABLE off + SOFT_INPUT_STATE_VISIBLE
            // on it now.
            setOnClickListener { requestFocus() }
        }
        // Compact icon buttons — 28dp wide so the address row stays tidy.
        fun iconBtn(text: String, desc: String, onClick: () -> Unit): Button = Button(ctx).apply {
            this.text = text
            setTextColor(0xFFcdd1e0.toInt())
            setBackgroundColor(0x1A1a1a26.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            contentDescription = desc
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(28.dp(), 28.dp())
            setOnClickListener { onClick() }
        }
        back = iconBtn("←", "Back") { if (webView.canGoBack()) webView.goBack() }
        reload = iconBtn("↻", "Reload") { webView.reload() }
        val go = iconBtn("GO", "Go to address") { onGo() }
        // The GO button is a little wider to fit the text.
        (go.layoutParams as LinearLayout.LayoutParams).width = 40.dp()

        val addressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(reload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Resize grip — anchored bottom-right of the WebView, drag it
        // to grow / shrink the browser window. 16dp square so it
        // doesn't visually compete with the close button.
        resizeGrip = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(0xFF7d3cff.toInt())
            }
            contentDescription = "Drag to resize"
            alpha = 0.7f
        }

        webView = WebView(ctx).apply {
            setBackgroundColor(0xFF0d0d18.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Allow on-page pinch-to-zoom of the rendered content (not just
            // window resize) so the user can zoom into text/images.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlField.setText(url ?: "")
                }
            }
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 18.dp()))
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(addressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // WebView + resize grip in a FrameLayout so the grip can
            // sit in the bottom-right corner without being part of
            // the vertical flow.
            val webContainer = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                addView(webView, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ))
                addView(resizeGrip, android.widget.FrameLayout.LayoutParams(
                    (14 * density).toInt(),
                    (14 * density).toInt(),
                    android.view.Gravity.END or android.view.Gravity.BOTTOM,
                ))
            }
            addView(webContainer)
        }

        params = OverlayParams.forBrowser(360.dp(), 420.dp())
        params.x = viewX
        params.y = viewY

        installTouch()
        installResizeTouch()
    }

    override fun show() {
        if (isAttached) return
        try { host.add(root, params); isAttached = true } catch (_: Throwable) {}
    }

    override fun hide() {
        if (!isAttached) return
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
        val (cx, cy) = clampToDisplay(params.x, params.y)
        params.x = cx; params.y = cy; viewX = cx; viewY = cy
        host.update(root, params)
    }

    /** Clamp a window position to on-screen bounds so drags can't fling it off. */
    private fun clampToDisplay(x: Int, y: Int): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val out = android.graphics.Point()
        wm.defaultDisplay.getSize(out)
        val w = params.width.takeIf { it in 1..out.x } ?: (320 * density).toInt()
        val h = params.height.takeIf { it in 1..out.y } ?: (200 * density).toInt()
        val maxX = (out.x - w).coerceAtLeast(0)
        val maxY = (out.y - h - (28 * density).toInt()).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * Normalise what the user typed in the address bar into something
     * WebView will actually load. Without a scheme `webView.loadUrl`
     * rejects the input ("net::ERR_UNKNOWN_URL_SCHEME") and Android
     * blocks cleartext HTTP on API 28+ by default, so the user gets
     * "cleartext not permitted" the moment they type `http://foo`.
     *
     * Rules:
     *   - empty / whitespace -> do nothing
     *   - "localhost[:port][/...]"            -> http://
     *   - "127.0.0.1[:port][/...]" / RFC1918  -> http://
     *   - "foo" or "foo.com[/...]" (no scheme) -> https://
     *   - anything with a scheme              -> leave alone
     *   - "javascript:" / "file:" / "data:"  -> leave alone (caller's job)
     */
    private fun normaliseUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        val lower = t.lowercase()
        // Already has a recognised scheme — leave alone.
        if (listOf("http://", "https://", "file://", "javascript:", "data:", "content://", "about:")
                .any { lower.startsWith(it) }) return t
        // Local / private network — use http so plain-HTTP routers, dev
        // servers, and localhost services just work.
        val isLocal = lower.startsWith("localhost") ||
            lower.startsWith("127.") ||
            lower.startsWith("10.") ||
            lower.startsWith("192.168.") ||
            lower.startsWith("169.254.") ||
            lower.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*"))
        val scheme = if (isLocal) "http://" else "https://"
        // If the user already wrote a host:port with a path, drop the
        // leading "http(s)://" we just added (none there) — they're
        // effectively just "host[:port][/path]" which we prefix.
        // Also handle "user@host" by leaving as-is after prefixing.
        return scheme + t
    }

    /** Public entry — auto-scheme the URL, then load. */
    fun openUrl(rawUrl: String) {
        val normalised = normaliseUrl(rawUrl)
        urlField.setText(normalised)
        webView.loadUrl(normalised)
        show()
    }

    private fun onGo() {
        val text = urlField.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val normalised = normaliseUrl(text)
        urlField.setText(normalised)
        webView.loadUrl(normalised)
    }

    private fun installTouch() {
        // One drag listener on the header + pinch-zoom anywhere.
        val resizeDetector = android.view.ScaleGestureDetector(
            ctx,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val b = resizeBounds()
                    val factor = detector.scaleFactor
                    val newW = (params.width * factor).toInt().coerceIn(b.minW, b.maxW)
                    val newH = (params.height * factor).toInt().coerceIn(b.minH, b.maxH)
                    if (newW == params.width && newH == params.height) return true
                    params.width = newW
                    params.height = newH
                    root.requestLayout()
                    host.update(root, params)
                    return true
                }
            },
        )
        var startX = 0; var startY = 0; var dragging = false
        dragHandle.setOnTouchListener { _, event ->
            resizeDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) return@setOnTouchListener true
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
    }

    /** Shared min/max bounds for both pinch-zoom and the resize grip. */
    private data class ResizeBounds(val minW: Int, val maxW: Int, val minH: Int, val maxH: Int)

    private fun resizeBounds(): ResizeBounds = ResizeBounds(
        minW = (240 * density).toInt(),
        maxW = (720 * density).toInt(),
        minH = (160 * density).toInt(),
        maxH = (960 * density).toInt(),
    )

    /**
     * Resize grip touch handler. 16dp square in the bottom-right of
     * the WebView; dragging changes params.width and params.height
     * (clamped 240..720 x 320..960 dp) and updates the root.
     */
    private fun installResizeTouch() {
        val b = resizeBounds()
        var startW = 0
        var startH = 0
        var startX = 0
        var startY = 0
        resizeGrip.setOnTouchListener { _, event ->
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
                    val baseH = if (params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                        root.height.takeIf { it > 0 } ?: b.minH
                    } else params.height
                    val newW = (startW + dx).coerceIn(b.minW, b.maxW)
                    val newH = (baseH + dy).coerceIn(b.minH, b.maxH)
                    params.width = newW
                    params.height = newH
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

    fun rootView(): View = root
    fun currentPosition(): Pair<Int, Int> = params.x to params.y

    fun shutdown() { hide() }
}