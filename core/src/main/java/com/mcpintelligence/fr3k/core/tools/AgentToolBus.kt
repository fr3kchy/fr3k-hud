package com.mcpintelligence.fr3k.core.tools

/**
 * Shared agent tool contract (ANDROID-TESTING-PASS.md Section 3).
 *
 * Target architecture: FR3K AGENT -> SHARED TOOL / CAPABILITY LAYER ->
 * BROWSER | TERMUX | BLACKWAVE | HUD -> REAL DEVICES / SERVICES.
 *
 * Every overlay / device capability exposes itself as an [AgentTool]; the
 * agent session dispatches through a single [AgentToolBus]. There is ONE
 * session context (see [ToolContext]) shared by chat, browser, terminal and
 * BLACKWAVE — never one ad-hoc channel per surface.
 */
interface AgentTool {
    /** Stable id used by the agent to address the tool, e.g. "browser.navigate". */
    val id: String

    /** Human-readable name for diagnostics / capability lists. */
    val displayName: String

    /** What the tool does, in terms the agent can route on. */
    val description: String

    /** Authorisation scope prefix — "browser", "termux", "blackwave", "hud". */
    val capability: String

    /** True when this tool requires network connectivity. */
    val requiresNetwork: Boolean

    /**
     * Execute the tool. Implementations MUST return real results only —
     * never simulated output. A command that was not actually executed
     * must return [ToolResult.Failure], never an invented [ToolResult.Success].
     */
    suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult
}

/** One agent session context shared across every tool call. */
data class ToolContext(
    /** Stable session id — same value across chat / browser / terminal / blackwave. */
    val sessionId: String,
    /** Optional task id for the current agent task. */
    val taskId: String? = null,
    /** Physical device identity, so tools can reason about "this device". */
    val deviceId: String,
)

/** Free-form string args; tools parse and validate what they need. */
data class ToolArgs(
    val values: Map<String, String>,
) {
    fun require(key: String): String =
        values[key]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing required arg: $key")
    fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }
}

sealed class ToolResult {
    /** Success carries the REAL output produced by the tool. */
    data class Success(
        val output: String,
        val structured: Map<String, String> = emptyMap(),
    ) : ToolResult()

    /** Failure carries a human-readable error plus an optional short code. */
    data class Failure(
        val error: String,
        val failureCode: String? = null,
    ) : ToolResult()
}

/**
 * Registry + dispatch for all agent tools. Thread-safe. The agent session
 * (chat bubble, AskAboutThis, future command palette) resolves a tool by id
 * and dispatches against the ONE bus; tools never talk to the app directly.
 */
class AgentToolBus {

    private val tools = LinkedHashMap<String, AgentTool>()

    @Synchronized
    fun register(tool: AgentTool) {
        tools[tool.id] = tool
    }

    @Synchronized
    fun unregister(id: String) {
        tools.remove(id)
    }

    @Synchronized
    fun list(): List<AgentTool> = tools.values.toList()

    @Synchronized
    fun get(id: String): AgentTool? = tools[id]

    /** Dispatch a tool call. Unknown tool -> Failure, never silent no-op. */
    suspend fun dispatch(context: ToolContext, toolId: String, args: ToolArgs): ToolResult {
        val tool = get(toolId)
            ?: return ToolResult.Failure("tool '$toolId' is not registered", failureCode = "tool.unregistered")
        return try {
            tool.invoke(context, args)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Exception) {
            ToolResult.Failure(
                "${tool.id} error: ${t.message ?: t.javaClass.simpleName}",
                failureCode = "tool.invoke",
            )
        }
    }
}