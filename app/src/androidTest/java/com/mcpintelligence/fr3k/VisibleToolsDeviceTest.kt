package com.mcpintelligence.fr3k

import android.content.Context
import android.view.WindowManager
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mcpintelligence.fr3k.core.tools.*
import com.mcpintelligence.fr3k.hud.overlays.*
import com.mcpintelligence.fr3k.integrations.browser.BrowserAgentTool
import com.mcpintelligence.fr3k.integrations.termux.TermuxAgentTool
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real WebView / real Termux on the attached phone. No provider key or device data changes. */
@RunWith(AndroidJUnit4::class)
class VisibleToolsDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Fr3kApplication
    private fun host() = OverlayHost(app, app.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    private val context = ToolContext("device-regression", deviceId = "physical-phone")

    @Test fun browserControlsActualPageAndReportsFailures() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/missing") == true -> MockResponse().setResponseCode(404).setBody("missing")
                else -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <!doctype html><html><head><title>FR3K browser fixture</title><meta name="viewport" content="width=device-width"></head>
                    <body><h1>Real page fixture</h1><form id="form" onsubmit="event.preventDefault();document.querySelector('#result').textContent='submitted:'+document.querySelector('#name').value">
                    <input id="name" required><button id="button" type="button" onclick="document.querySelector('#result').textContent='clicked'">Click fixture</button>
                    <button type="submit">Submit</button></form><p id="result">waiting</p><div style="height:2000px">Scroll fixture</div><p>Bottom</p></body></html>
                """.trimIndent())
            }
        }
        server.start()
        val browser = withContext(Dispatchers.Main) { Fr3kMiniBrowserOverlay(host()) }
        val tool = BrowserAgentTool(browser)
        suspend fun call(action: String, vararg args: Pair<String,String>) = tool.invoke(context, ToolArgs(mapOf("action" to action, *args)))
        fun success(result: ToolResult): String {
            assertTrue(result.toString(), result is ToolResult.Success)
            return (result as ToolResult.Success).output
        }
        try {
            success(call("open", "url" to server.url("/one").toString()))
            assertTrue(withContext(Dispatchers.Main) { browser.isAttached })
            assertTrue(success(call("inspect")).contains("#name"))
            assertTrue(success(call("getTitle")).contains("FR3K browser fixture"))
            success(call("click", "selector" to "#button"))
            assertTrue(success(call("text")).contains("clicked"))
            val value = "single' double\" newline\n </script>"
            success(call("type", "selector" to "#name", "text" to value))
            success(call("submit", "selector" to "#form"))
            assertTrue(success(call("text")).contains("submitted:"))
            assertTrue(call("click", "selector" to "#does-not-exist") is ToolResult.Failure)
            assertTrue(call("click", "selector" to "button") is ToolResult.Failure)
            assertTrue(call("click", "selector" to "[") is ToolResult.Failure)
            success(call("scroll", "y" to "400"))
            success(call("open", "url" to server.url("/two").toString()))
            assertTrue(success(call("back")).contains("/one"))
            assertTrue(success(call("forward")).contains("/two"))
            success(call("reload"))
            assertTrue(call("open", "url" to server.url("/missing").toString()) is ToolResult.Failure)
            assertTrue(call("open", "url" to "javascript:alert(1)") is ToolResult.Failure)
        } finally {
            withContext(Dispatchers.Main) { browser.shutdown() }
            server.shutdown()
        }
    }

    @Test fun termuxToolMirrorsRealOutputAndExitInVisibleConsole() = runBlocking {
        val terminal = withContext(Dispatchers.Main) { Fr3kTerminalOverlay(host()) }
        val tool = TermuxAgentTool(app.termuxBridge,
            onExecuted = { command, cwd -> withContext(Dispatchers.Main) { terminal.showAgentExecution(command, cwd) } },
            onOutput = { terminal.appendLine(it) })
        try {
            val marker = "fr3k-real-${System.nanoTime()}"
            val result = tool.invoke(context, ToolArgs(mapOf("command" to "printf '$marker\\n'; printf 'real-stderr\\n' >&2; exit 7")))
            assertTrue(result.toString(), result is ToolResult.Success)
            result as ToolResult.Success
            assertEquals("7", result.structured["exitCode"])
            assertTrue(result.structured["stdout"].orEmpty().contains(marker))
            assertTrue(result.structured["stderr"].orEmpty().contains("real-stderr"))
            withContext(Dispatchers.Main) {
                assertTrue(terminal.isAttached)
                fun text(v: View): String = (if (v is TextView) v.text.toString() else "") +
                    (if (v is ViewGroup) (0 until v.childCount).joinToString("\n") { text(v.getChildAt(it)) } else "")
                val visible = text(terminal.rootView())
                assertTrue(visible.contains(marker))
                assertTrue(visible.contains("real-stderr"))
                assertTrue(visible.contains("exit 7"))
            }
        } finally { withContext(Dispatchers.Main) { terminal.shutdown() } }
    }

    @Test fun nativeSessionHandsBrowserResultToTerminalAndSharesChat() = runBlocking {
        val server = MockWebServer()
        val requests = java.util.concurrent.CopyOnWriteArrayList<org.json.JSONObject>()
        server.start()
        val marker = "native-session-${System.nanoTime()}"
        fun toolResponse(id: String, name: String, args: Map<String,String>): String {
            val call = org.json.JSONObject().put("id", id).put("type", "function")
                .put("function", org.json.JSONObject().put("name", name).put("arguments", org.json.JSONObject(args).toString()))
            return org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("message",
                org.json.JSONObject().put("role", "assistant").put("content", "Fixture requests a real tool operation.").put("tool_calls", org.json.JSONArray().put(call))))).toString()
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/page") return MockResponse().setHeader("Content-Type", "text/html").setBody("<html><title>Native session fixture</title><body>Browser step</body></html>")
                if (request.path != "/api/v1/chat/completions") return MockResponse().setResponseCode(404)
                requests.add(org.json.JSONObject(request.body.readUtf8()))
                val body = when (requests.size) {
                    1 -> toolResponse("browse", "browser__navigate", mapOf("action" to "open", "url" to server.url("/page").toString()))
                    2 -> toolResponse("shell", "termux__exec", mapOf("command" to "printf '$marker\\n'"))
                    else -> """{"choices":[{"message":{"role":"assistant","content":"Fixture workflow complete."}}]}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        val provider = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider(
            endpoint = server.url("/api/v1").toString(), apiKeyProvider = { "dummy-test-key" })
        val bus = AgentToolBus()
        val session = SharedAgentSession(bus, provider, "phone")
        val browser = withContext(Dispatchers.Main) { Fr3kMiniBrowserOverlay(host(), session = session) }
        val chat = withContext(Dispatchers.Main) { Fr3kChatBubble(host(), session = session) }
        val terminal = withContext(Dispatchers.Main) { Fr3kTerminalOverlay(host()) }
        bus.register(BrowserAgentTool(browser))
        bus.register(TermuxAgentTool(app.termuxBridge,
            onExecuted = { command, cwd -> withContext(Dispatchers.Main) { terminal.showAgentExecution(command, cwd) } },
            onOutput = { terminal.appendLine(it) }))
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        fun text(v: View) = views(v).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        try {
            withContext(Dispatchers.Main) { chat.show() }
            val turn = async { session.send("Exercise the browser then terminal") }
            withTimeout(30000) { while (session.approval.value == null) delay(25) }
            withContext(Dispatchers.Main) {
                assertFalse(terminal.isAttached)
                assertTrue(text(chat.rootView()).contains(marker)) // Exact command visible before execution.
                views(chat.rootView()).filterIsInstance<android.widget.Button>().single { it.text == "ALLOW" }.performClick()
            }
            withTimeout(30000) { turn.await() }
            assertEquals(3, requests.size)
            assertTrue(requests[1].getJSONArray("messages").toString().contains("open completed"))
            assertTrue(requests[2].getJSONArray("messages").toString().contains(marker))
            assertTrue(requests[2].getJSONArray("messages").toString().contains("exit 0"))
            withContext(Dispatchers.Main) {
                assertTrue(text(chat.rootView()).contains("Fixture workflow complete."))
                assertTrue(text(browser.rootView()).contains("Fixture workflow complete."))
                assertTrue(text(terminal.rootView()).contains(marker))
            }
        } finally {
            session.cancel()
            withContext(Dispatchers.Main) { browser.shutdown(); chat.shutdown(); terminal.shutdown() }
            server.shutdown()
        }
    }

    @Test fun termuxHealthMatchesCurrentPermissionAndRealProbe() = runBlocking {
        val expected = InstrumentationRegistry.getArguments().getString("termuxExpected", "CONNECTED")
        com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.invalidate()
        val state = com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.probe(app, app.termuxBridge)
        val label = com.mcpintelligence.fr3k.integrations.termux.TermuxHealth.statusLabel(state).label
        assertEquals(expected, label)
        assertEquals(expected == "CONNECTED", state.operational)
    }

    @Test fun configuredOpenRouterAuthenticatesWithoutExposingKey() = runBlocking {
        val hasKey = !app.secureStore.get(app.settings.settings.value.openrouterApiKeyKey).isNullOrBlank()
        org.junit.Assume.assumeTrue("OpenRouter key is not configured; live provider verification remains pending", hasKey)
        val health = app.openRouterProvider.health()
        assertTrue("OpenRouter validation: ${health.message}", health.online)
    }

    @Test fun liveFreeRouterUsesActualBrowserTool() = runBlocking {
        val keyName = app.settings.settings.value.openrouterApiKeyKey
        org.junit.Assume.assumeTrue("Live model test needs a configured key", !app.secureStore.get(keyName).isNullOrBlank())
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><title>FR3K live tool test</title><body>Harmless local browser fixture</body></html>"))
        server.start()
        val page = server.url("/fr3k-live-${System.nanoTime()}").toString()
        val provider = com.mcpintelligence.fr3k.integrations.openrouter.OpenRouterProvider(apiKeyProvider = { app.secureStore.get(keyName) })
        val bus = AgentToolBus()
        val session = SharedAgentSession(bus, provider, "phone-test", maxToolRounds = 4)
        val browser = withContext(Dispatchers.Main) { Fr3kMiniBrowserOverlay(host(), session = session) }
        val delegate = BrowserAgentTool(browser)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        bus.register(object : AgentTool {
            override val id = "browser.navigate"
            override val displayName = "Browser"
            override val description = "Read the current browser URL. Use action getUrl. The browser already contains a local test page."
            override val capability = "browser"
            override val requiresNetwork = false
            override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
                if (args.get("action") != "getUrl") return ToolResult.Failure("This test allows only getUrl")
                calls.incrementAndGet()
                return delegate.invoke(context, args)
            }
        })
        try {
            val opened = delegate.invoke(context, ToolArgs(mapOf("action" to "open", "url" to page)))
            assertTrue(opened.toString(), opened is ToolResult.Success)
            withTimeout(120000) { session.send("Use the browser tool to read the current URL, then report that exact URL. Do not navigate or perform any other operation.") }
            assertTrue("No real tool invocation. " + session.lines.value.filter { it.speaker == "agent" }.joinToString { it.text }, calls.get() > 0)
            assertTrue("Model did not report observed URL", session.lines.value.any { it.speaker == "agent" && it.text.contains(page) })
        } finally {
            withContext(Dispatchers.Main) { browser.shutdown() }
            server.shutdown()
        }
    }
}
