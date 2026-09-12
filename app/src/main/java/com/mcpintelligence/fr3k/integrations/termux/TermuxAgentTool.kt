package com.mcpintelligence.fr3k.integrations.termux

import com.mcpintelligence.fr3k.core.tools.AgentTool
import com.mcpintelligence.fr3k.core.tools.ToolArgs
import com.mcpintelligence.fr3k.core.tools.ToolContext
import com.mcpintelligence.fr3k.core.tools.ToolResult

/**
 * §3 agent tool — `termux.exec`. Runs a command in the REAL Termux
 * environment via [TermuxBridge] and returns the actual result
 * (stdout / stderr / exit code). Never simulates output: a command
 * that could not be executed is a [ToolResult.Failure]; a command that
 * ran but exited non-zero is still a [ToolResult.Success] carrying the
 * real output so the agent can reason about what actually happened.
 */
class TermuxAgentTool(
    private val bridge: TermuxBridge,
    private val onExecuted: suspend (cmd: String, cwd: String?) -> Unit = { _, _ -> },
    private val onOutput: suspend (String) -> Unit = {},
) : AgentTool {

    override val id = "termux.exec"
    override val displayName = "Termux"
    override val description =
        "Run a command inside the Termux environment on this device and return its real stdout, stderr and exit code."
    override val capability = "termux"
    override val requiresNetwork = false

    override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
        // Explicit gate — an unauthorised or absent Termux can never run.
        if (!bridge.isUsable()) {
            return ToolResult.Failure(
                if (!bridge.isAvailable()) "Termux is not installed on this device"
                else "Termux RUN_COMMAND permission not granted",
                failureCode = "termux.unusable",
            )
        }
        val command = args.get("command")
            ?: return ToolResult.Failure("missing required arg: command", failureCode = "termux.missing_command")
        val cwd = args.get("cwd").orEmpty()
        val effective = if (cwd.isBlank()) command else "cd ${quoteShell(cwd)} && $command"

        // Hook for surfaces that want to mirror the execution in a transcript
        // (fires BEFORE execution starts, with the args the tool got).
        onExecuted(command, cwd.ifBlank { null })

        return try {
            val result = bridge.runRaw(effective, 30_000)
            val text = buildString {
                append(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("stderr: ${result.stderr.trimEnd()}")
                }
                append("\nexit ${result.exitCode}")
            }.trim()
            onOutput(text)
            ToolResult.Success(
                output = text.ifBlank { "(no output)" },
                structured = mapOf(
                    "exitCode" to result.exitCode.toString(),
                    "stdout" to result.stdout,
                    "stderr" to result.stderr,
                ),
            )
        } catch (t: kotlinx.coroutines.CancellationException) {
            onOutput("Stopped waiting; the command may still be running in Termux.")
            throw t
        } catch (t: Exception) {
            onOutput("Termux execution error: ${t.message ?: t.javaClass.simpleName}")
            ToolResult.Failure(
                "termux exec failed: ${t.message ?: t.javaClass.simpleName}",
                failureCode = "termux.invoke",
            )
        }
    }

    private fun quoteShell(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}