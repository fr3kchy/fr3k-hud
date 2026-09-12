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
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.core.CommandResult
import com.mcpintelligence.fr3k.core.ConsentLevel
import com.mcpintelligence.fr3k.core.Fr3kContext
import com.mcpintelligence.fr3k.integrations.hermes.HermesAskCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mini browser overlay window — mirrors Hitomi's `overlay_browser` (BeOS-style
 * mini browser that pops up when the assistant triggers a `{{tool:browser_open:url}}`
 * in its reply). This is the standalone version: opened by Ask About This, by
 * command palette's "Open URL", or by sharing a URL into FR3K.
 */
class Fr3kMiniBrowserOverlay(
    private val host: OverlayHost,
    private val density: Float = host.context.resources.displayMetrics.density,
    private val session: com.mcpintelligence.fr3k.core.tools.SharedAgentSession = Fr3kApplication.get().agentSession,
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
    private val chatToggle: Button
    private val chatPanel: LinearLayout
    private val chatTranscript: TextView
    private val chatInput: EditText
    private val chatSend: Button

    private val sessionPanel = AgentSessionPanel(host.context, session)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Published page state — the agent API reads WebView facts back from here,
    // never fabricated. Updated in the WebViewClient callbacks.
    private var currentUrl = ""
    private var currentTitle = ""
    private var pageLoading = false
    private var pageError: String? = null
    private var navigationResult: kotlinx.coroutines.CompletableDeferred<String>? = null
    private val agentMutex = kotlinx.coroutines.sync.Mutex()

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
            addView(close)
        }

        // Compact chat strip — toggled by the chat button, hidden by default.
        chatToggle = Button(ctx).apply {
            text = "◨"
            setTextColor(0xFF8e8a99.toInt())
            setBackgroundColor(0x00000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            contentDescription = "Toggle inline chat"
            setPadding(0, 0, 4.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(24.dp(), 24.dp())
            isAllCaps = false
            setOnClickListener { toggleChat() }
        }
        titleRow.addView(chatToggle)

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
            addView(back)
            addView(reload)
            addView(urlField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(go)
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
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // Allow on-page pinch-to-zoom of the rendered content (not just
            // window resize) so the user can zoom into text/images.
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank()) currentUrl = url
                    pageLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    urlField.setText(url ?: "")
                    if (!url.isNullOrBlank()) currentUrl = url
                    pageLoading = false
                    navigationResult?.complete(currentUrl)
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        pageError = "${error?.errorCode}: ${error?.description}"
                        pageLoading = false
                        navigationResult?.completeExceptionally(IllegalStateException(pageError))
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        pageError = "HTTP ${response?.statusCode}: ${response?.reasonPhrase}"
                        navigationResult?.completeExceptionally(IllegalStateException(pageError))
                    }
                }
            }
            // onPageFinished delivers the location, but the page TITLE only
            // arrives via the WebChromeClient — that is the canonical place
            // to observe it.
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    currentTitle = title ?: ""
                }
            }
        }

        // Compact collapsible chat strip — GONE until the chat button opens it.
        chatTranscript = TextView(ctx).apply {
            setTextColor(0xFFcdd1e0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        chatInput = EditText(ctx).apply {
            hint = "ask about this page…"
            setHintTextColor(0xFF6a6878.toInt())
            setTextColor(0xFFe8eaf2.toInt())
            setBackgroundColor(0xFF0d0d18.toInt())
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> onChatSend(); true }
        }
        chatSend = Button(ctx).apply {
            text = "→"
            setTextColor(0xFF11111c.toInt())
            setBackgroundColor(0xFF7d3cff.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            contentDescription = "Send chat message"
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(32.dp(), 28.dp())
            isAllCaps = false
            setOnClickListener { onChatSend() }
        }
        chatPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val inputRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chatSend)
            }
            addView(chatTranscript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sessionPanel)
            addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            addView(dragHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8.dp()))
            addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(chatPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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

        params = OverlayParams.forBrowser(300.dp(), 380.dp())
        params.x = viewX
        params.y = viewY

        installTouch()
        installResizeTouch()
    }

    private val sessionObserver = scope.launch(Dispatchers.Main.immediate) {
        session.lines.collect { lines ->
            chatTranscript.text = lines.takeLast(4).joinToString("\n") { "${it.speaker}: ${it.text}" }
        }
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

    /** Public entry — auto-scheme the URL, then load; returns the loaded page. */
    fun openUrl(rawUrl: String): String {
        val normalised = normaliseUrl(rawUrl)
        urlField.setText(normalised)
        webView.loadUrl(normalised)
        show()
        return normalised
    }

    /** §3 agent API — drives the REAL overlay; results read back from WebView state. */
    fun agentOpenUrl(rawUrl: String): String = openUrl(rawUrl)

    fun agentGoBack(): Boolean {
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun agentGoForward(): Boolean {
        if (!webView.canGoForward()) return false
        webView.goForward()
        return true
    }

    fun agentReload(): String {
        webView.reload()
        return currentUrl
    }

    fun agentGetUrl(): String = currentUrl

    fun agentGetTitle(): String = currentTitle

    /** All tool calls are serialized and WebView access stays on the UI thread. */
    suspend fun performAgentAction(args: com.mcpintelligence.fr3k.core.tools.ToolArgs): com.mcpintelligence.fr3k.core.tools.ToolResult =
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            agentMutex.lock()
            try {
                val action = args.get("action") ?: "open"
                when (action) {
                    "open", "navigate", "back", "forward", "reload" -> {
                        val pending = kotlinx.coroutines.CompletableDeferred<String>()
                        navigationResult = pending
                        pageError = null
                        try {
                            when (action) {
                                "open", "navigate" -> {
                                    val url = normaliseUrl(args.require("url"))
                                    require(Uri.parse(url).scheme in listOf("http", "https")) { "Agent navigation requires an HTTP or HTTPS URL" }
                                    openUrl(url)
                                }
                                "back" -> check(agentGoBack()) { "No back history" }
                                "forward" -> check(agentGoForward()) { "No forward history" }
                                else -> { check(currentUrl.isNotBlank()) { "No page to reload" }; agentReload() }
                            }
                            val loaded = kotlinx.coroutines.withTimeout(20_000) { pending.await() }
                            com.mcpintelligence.fr3k.core.tools.ToolResult.Success("$action completed: $loaded", mapOf("url" to loaded))
                        } finally {
                            navigationResult = null
                        }
                    }
                    "getUrl" -> com.mcpintelligence.fr3k.core.tools.ToolResult.Success(currentUrl)
                    "getTitle" -> com.mcpintelligence.fr3k.core.tools.ToolResult.Success(currentTitle)
                    "inspect", "text", "click", "scroll", "type", "submit" -> {
                        val script = com.mcpintelligence.fr3k.integrations.browser.BrowserPageScript.build(action, args.values)
                        val raw = kotlinx.coroutines.withTimeout(5_000) {
                            kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                                webView.evaluateJavascript(script) { value ->
                                    if (continuation.isActive) continuation.resumeWith(Result.success(value ?: "null"))
                                }
                            }
                        }
                        val result = org.json.JSONObject(raw)
                        if (!result.optBoolean("ok")) {
                            com.mcpintelligence.fr3k.core.tools.ToolResult.Failure(result.optString("error", "Page action failed"), "browser.page")
                        } else {
                            result.put("loading", pageLoading)
                            pageError?.let { result.put("lastError", it) }
                            com.mcpintelligence.fr3k.core.tools.ToolResult.Success(result.toString())
                        }
                    }
                    else -> com.mcpintelligence.fr3k.core.tools.ToolResult.Failure("Unknown browser action: $action", "browser.action")
                }
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                com.mcpintelligence.fr3k.core.tools.ToolResult.Failure("Browser action timed out; current URL: $currentUrl", "browser.timeout")
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Exception) {
                com.mcpintelligence.fr3k.core.tools.ToolResult.Failure(t.message ?: "Browser action failed", "browser.action")
            } finally {
                agentMutex.unlock()
            }
        }

    private fun onGo() {
        val text = urlField.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        val normalised = normaliseUrl(text)
        urlField.setText(normalised)
        webView.loadUrl(normalised)
    }

    private fun toggleChat() {
        chatPanel.visibility = if (chatPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun onChatSend() {
        val prompt = chatInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty() || session.busy.value) return
        chatInput.setText("")
        session.submit(prompt)
    }

    private fun appendChatLine(line: String) {
        host.context.mainExecutor.execute {
            val prev = chatTranscript.text?.toString().orEmpty()
            chatTranscript.text = if (prev.isBlank()) line else prev + "\n" + line
        }
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

    private fun resizeBounds(): ResizeBounds {
        val bounds = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        val maxW = bounds.width()
        val maxH = (bounds.height() - 48.dp()).coerceAtLeast(160.dp())
        return ResizeBounds(minOf(240.dp(), maxW), maxW, minOf(200.dp(), maxH), maxH)
    }

    /**
     * Resize grip touch handler. 16dp square in the bottom-right of
     * the WebView; dragging changes params.width and params.height
     * (clamped 240..720 x 320..960 dp) and updates the root.
     */
    private fun installResizeTouch() {
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
                    val b = resizeBounds()
                    val newW = (startW + dx).coerceIn(b.minW, b.maxW)
                    val newH = (startH + dy).coerceIn(b.minH, b.maxH)
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

    fun shutdown() {
        hide()
        sessionPanel.shutdown()
        scope.cancel()
        navigationResult?.cancel()
        webView.destroy()
    }
}