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
import com.mcpintelligence.fr3k.core.UrlSanitiser

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

    private var viewX = 0
    private var viewY = (160 * density).toInt()

    init {
        val bg = GradientDrawable().apply {
            cornerRadius = 12f.dp()
            setColor(0xFF11111c.toInt())
            setStroke((1+2).dp(), 0xFF2b2b40.toInt())
        }

        val header = TextView(ctx).apply {
            text = "FR3K ▸ BROWSER"
            setTextColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
            setPadding((12+14).dp(), (8+14).dp(), (8+14).dp(), (4+14).dp())
        }
        dragHandle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(12f.dp(), 12f.dp(), 0f, 0f, 0f, 0f, 12f.dp(), 12f.dp())
                setColor(0xFF1a1a26.toInt())
                setStroke((1+2).dp(), 0xFF2b2b40.toInt())
            }
        }
        close = Button(ctx).apply {
            text = "×"
            setTextColor(0xFF8e8a99.toInt())
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            contentDescription = "Close browser"
            setPadding(0, 0, (6 * density).toInt(), 0)
            // Compact icon button — fixed 32dp so the title row stays slim.
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (28 * density).toInt())
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
        // 32dp square icon buttons — the same width as the close button
        // so the address row stays a tidy strip.
        fun iconBtn(text: String, desc: String, onClick: () -> Unit): Button = Button(ctx).apply {
            this.text = text
            setTextColor(0xFFcdd1e0.toInt())
            setBackgroundColor(0xFF1a1a26.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            contentDescription = desc
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (28 * density).toInt())
            setOnClickListener { onClick() }
        }
        back = iconBtn("←", "Back") { if (webView.canGoBack()) webView.goBack() }
        reload = iconBtn("↻", "Reload") { webView.reload() }
        val go = iconBtn("GO", "Go to address") { onGo() }
        // The GO button is a little wider to fit the text comfortably.
        (go.layoutParams as LinearLayout.LayoutParams).width = (44 * density).toInt()

        val addressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(reload, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        webView = WebView(ctx).apply {
            setBackgroundColor(0xFF0d0d18.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    urlField.setText(url ?: "")
                }
            }
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (0+28).dp()))
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(addressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        params = OverlayParams.forBrowser(360.dp(), 420.dp())
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

    /** Public entry — clean URL via the sanitiser, then load. */
    fun openUrl(rawUrl: String) {
        val cleaned = UrlSanitiser().clean(rawUrl).clean
        urlField.setText(cleaned)
        webView.loadUrl(cleaned)
        show()
    }

    private fun onGo() {
        val text = urlField.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val cleaned = UrlSanitiser().clean(text).clean
        urlField.setText(cleaned)
        webView.loadUrl(cleaned)
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
    }

    private fun Float.dp(): Float = this * density
    private fun Int.dp(): Int = (this * density).toInt()

    fun rootView(): View = root
    fun currentPosition(): Pair<Int, Int> = params.x to params.y

    fun shutdown() { hide() }
}