package com.mcpintelligence.fr3k.integrations.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Authoritative "can we run a command" check. Sends a
     * `com.termux.permission_check` probe broadcast and waits for the
     * `com.termux.permission_check_result` answer. Termux only answers
     * `granted=true` if the user has actively approved our package.
     * The static `checkSelfPermission` path can lag behind the actual
     * state (e.g. on first install, or when the perm was just toggled
     * in Settings), so this probe is the ground truth.
     */
    @Volatile private var lastProbeOk: Boolean = false
    @Volatile private var lastProbeAt: Long = 0
    private val probeCacheMs = 5_000L

    fun probeAuthorisation(timeoutMs: Long = 3000): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < probeCacheMs) return lastProbeOk
        if (!isAvailable()) {
            lastProbeOk = false
            lastProbeAt = now
            return false
        }
        val ok = try {
            val probe = Intent("com.termux.permission_check")
                .setPackage("com.termux")
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = arrayOfNulls<Boolean>(1)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val granted = intent?.getBooleanExtra("granted", false) ?: false
                    result[0] = granted
                    latch.countDown()
                }
            }
            val filter = android.content.IntentFilter("com.termux.permission_check_result")
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            context.sendBroadcast(probe)
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            runCatching { context.unregisterReceiver(receiver) }
            result[0] == true
        } catch (t: Throwable) {
            Log.w(TAG, "probeAuthorisation failed: ${t.message}")
            false
        }
        lastProbeOk = ok
        lastProbeAt = now
        return ok
    }

    /** True only when Termux has explicitly granted our package RUN_COMMAND. */
    fun hasRunCommandPermission(): Boolean {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED ||
            context.packageManager.checkPermission("com.termux.permission.RUN_COMMAND", context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Steps the user must follow to grant FR3K HUD the ability to send
     * `com.termux.RUN_COMMAND` intents. The check in
     * [hasRunCommandPermission] looks at the Android permission
     * `com.termux.permission.RUN_COMMAND`. The grant lives in the
     * system Settings under our app's "Additional permissions" list,
     * NOT inside Termux or Termux:API themselves.
     */
    fun grantInstructions(): String = listOf(
        "Termux blocks other apps from sending RUN_COMMAND intents unless the",
        "RUN_COMMAND permission is explicitly granted. The toggle lives in the",
        "system Settings, not in Termux.",
        "",
        "Fastest path from this app: open the Integrations panel and tap",
        "'OPEN FR3K APP PERMISSIONS → RUN COMMANDS'. Then:",
        "  - Tap 'Permissions' (or 'Additional permissions' on older Android)",
        "  - Find 'Run commands in Termux environment' and toggle it on",
        "  - Press back to return to FR3K HUD and tap REFRESH",
        "",
        "Manual path:",
        "  Android Settings -> Apps -> FR3K HUD -> Permissions",
        "  -> Additional permissions -> Run commands in Termux environment",
        "",
        "Termux also requires the 'allow-external-apps' flag to be set inside",
        "Termux itself. Run this once inside Termux:",
        "  echo 'allow-external-apps = true' >> ~/.termux/termux.properties",
        "  termux-reload-settings",
        "",
        "Termux:API must also be installed (it ships the com.termux.api service).",
        "If you only installed 'Termux' (the main app), grab 'Termux:API' from",
        "F-Droid: https://f-droid.org/packages/com.termux.api/.",
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

    /** Run a command through Termux's documented RunCommandService contract. */
    fun runRaw(command: String, timeoutMs: Long = 30_000): Result {
        if (!isUsable()) {
            val why = if (!isAvailable()) "termux not installed"
            else "RUN_COMMAND permission not granted — see grantInstructions()"
            return Result("", why, 127)
        }
        val id = NEXT_EXECUTION_ID.incrementAndGet()
        val future = CompletableFuture<Result>()
        pending[id] = future
        return try {
            val callback = Intent(context, TermuxResultReceiver::class.java)
                .putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, id)
            val callbackIntent = android.app.PendingIntent.getBroadcast(
                context,
                id,
                callback,
                android.app.PendingIntent.FLAG_ONE_SHOT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        android.app.PendingIntent.FLAG_MUTABLE else 0
            )
            val run = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callbackIntent)
            }
            context.startService(run)
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Result("", "timeout after ${timeoutMs}ms", 124)
        } catch (t: Throwable) {
            Log.e(TAG, "runRaw failed", t)
            Result("", t.message ?: t.javaClass.simpleName, 1)
        } finally {
            pending.remove(id)
        }
    }

    companion object {
        private const val TAG = "FR3K.termux"
        private val NEXT_EXECUTION_ID = AtomicInteger(1000)
        private val pending = ConcurrentHashMap<Int, CompletableFuture<Result>>()

        internal fun complete(id: Int, result: Result) {
            pending.remove(id)?.complete(result)
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

}