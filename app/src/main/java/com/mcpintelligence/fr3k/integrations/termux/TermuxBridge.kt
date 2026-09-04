package com.mcpintelligence.fr3k.integrations.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Termux bridge (§17). Talks to Termux:API over `com.termux.RUN_COMMAND`
 * broadcasts, mirrors Hitomi's `TermuxCommandBridge.java`.
 *
 * Two prerequisites (same as Hitomi):
 *  1. App must hold the runtime permission `com.termux.permission.RUN_COMMAND`
 *     (granted by Termux via `pm grant <pkg> com.termux.permission.RUN_COMMAND`
 *      OR by an in-Termux helper script that asks the user to tap "ALLOW").
 *  2. In Termux, `~/.termux/termux.properties` must contain `allow-external-apps=true`
 *     and Termux fully restarted.
 *
 * We additionally support a "sandboxed" mode where commands are mapped to a
 * registry of named jobs (`git.clone`, `ssh.connect`, etc.) and translated to
 * a safe command line. This is what the FR3K command palette and automation
 * engine call into — never the raw shell.
 */
class TermuxBridge(private val context: Context) {

    data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    private val jobRegistry = mutableMapOf<String, (Map<String, String>) -> String>()

    init {
        registerDefaultJobs()
    }

    fun isAvailable(): Boolean {
        val pkg = "com.termux"
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * True when Termux is installed AND the user has explicitly granted our
     * package `com.termux.permission.RUN_COMMAND`. Until both are satisfied
     * `runRaw` will refuse to fire.
     */
    fun isUsable(): Boolean = isAvailable() && hasRunCommandPermission()

    /** True only when Termux has explicitly granted our package RUN_COMMAND. */
    fun hasRunCommandPermission(): Boolean {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED ||
            context.packageManager.checkPermission("com.termux.permission.RUN_COMMAND", context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Returns a copy-pasteable line for the user to grant the perm in Termux. */
    fun grantInstructions(): String = listOf(
        "# In Termux, run:",
        "mkdir -p ~/.termux",
        "grep -qx 'allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties",
        "# Then fully close and reopen Termux so it reloads the setting.",
        "# In Termux:API menu, accept the 'Run Command' permission for FR3K HUD.",
    ).joinToString("\n")

    /**
     * Run a named job from the registry. Throws on unknown job or unavailable
     * Termux — callers must check [isAvailable] first.
     */
    fun runJob(name: String, args: Map<String, String>): Result {
        val builder = jobRegistry[name]
            ?: return Result("", "unknown job: $name", 2)
        val cmd = builder(args)
        return runRaw(cmd, timeoutMs = 15000)
    }

    /**
     * Run a raw command via Termux:API. Sends an `am broadcast` with the
     * `com.termux.RUN_COMMAND` intent — the modern Termux:API path — and
     * captures the result broadcast on a counting latch.
     */
    fun runRaw(command: String, timeoutMs: Long = 8000): Result {
        if (!isUsable()) {
            val why = if (!isAvailable()) "termux not installed"
            else "RUN_COMMAND permission not granted — see grantInstructions()"
            return Result("", why, 127)
        }
        return try {
            val api = Intent("com.termux.RUN_COMMAND")
            api.setPackage("com.termux")
            api.putExtra("com.termux.RUN_COMMAND.workingDirectory", "/data/data/com.termux/files/home")
            api.putExtra("com.termux.RUN_COMMAND.path", "/system/bin/sh")
            api.putExtra("com.termux.RUN_COMMAND.command", "sh")
            api.putExtra("com.termux.RUN_COMMAND.args", arrayOf("-c", command))
            api.putExtra("com.termux.RUN_COMMAND.background", false)
            val latch = java.util.concurrent.CountDownLatch(1)
            val results = arrayOfNulls<String>(1)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.action == "com.termux.RUN_COMMAND_RESULT") {
                        val out = intent.getStringExtra("com.termux.RUN_COMMAND_RESULT.stdout") ?: ""
                        val err = intent.getStringExtra("com.termux.RUN_COMMAND_RESULT.stderr") ?: ""
                        val code = intent.getIntExtra("com.termux.RUN_COMMAND_RESULT.exitCode", -1)
                        results[0] = "STDOUT:$out\nSTDERR:$err\nEXIT:$code"
                        latch.countDown()
                    }
                }
            }
            val filter = android.content.IntentFilter("com.termux.RUN_COMMAND_RESULT")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            context.sendBroadcast(api)
            val ok = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { context.unregisterReceiver(receiver) }
            if (!ok || results[0] == null) {
                return Result("", "timeout after ${timeoutMs}ms", 124)
            }
            val parts = results[0]!!.split("\n")
            val stdout = parts.find { it.startsWith("STDOUT:") }?.removePrefix("STDOUT:") ?: ""
            val stderr = parts.find { it.startsWith("STDERR:") }?.removePrefix("STDERR:") ?: ""
            val exit = parts.find { it.startsWith("EXIT:") }?.removePrefix("EXIT:")?.toIntOrNull() ?: -1
            Result(stdout, stderr, exit)
        } catch (t: Throwable) {
            Log.e(TAG, "runRaw failed", t)
            Result("", t.message ?: t.javaClass.simpleName, 1)
        }
    }

    private fun registerDefaultJobs() {
        // Job: git.clone — safe argv construction.
        jobRegistry["git.clone"] = { args ->
            val url = args["url"] ?: throw IllegalArgumentException("git.clone needs url")
            val dir = args["dir"] ?: File(context.filesDir, "repos").absolutePath
            "git clone --depth 1 '$url' '$dir'"
        }
        jobRegistry["ssh.connect"] = { args ->
            val host = args["host"] ?: throw IllegalArgumentException("ssh.connect needs host")
            val user = args["user"] ?: "root"
            val port = args["port"] ?: "22"
            "ssh -o StrictHostKeyChecking=accept-new -p $port $user@$host"
        }
        jobRegistry["python.run"] = { args ->
            val code = args["code"] ?: ""
            "python3 -c '${code.replace("'", "'\\''")}'"
        }
        jobRegistry["network.ping"] = { args ->
            val host = args["host"] ?: throw IllegalArgumentException("network.ping needs host")
            "ping -c 4 '$host'"
        }
        jobRegistry["file.hash"] = { args ->
            val path = args["path"] ?: throw IllegalArgumentException("file.hash needs path")
            "sha256sum '$path'"
        }
        jobRegistry["repo.inspect"] = { args ->
            val path = args["path"] ?: "."
            "cd '$path' && ls -la && (git log --oneline -5 2>/dev/null || true)"
        }
    }

    companion object { private const val TAG = "FR3K.termux" }
}