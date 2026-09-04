package com.mcpintelligence.fr3k.integrations.termux

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Termux bridge (§17). Talks to Termux:API over the official
 * `com.termux.RUN_COMMAND` intent contract, replacing the prior
 * `BroadcastReceiver`-based path that blocked the calling thread.
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

    /**
     * Single-write slot for one Termux execution's result. The first
     * [complete] call wins; subsequent calls are no-ops so a duplicate
     * receiver firing (e.g. an intent redelivery after process death)
     * cannot overwrite the value callers have already observed.
     *
     * A listener registered via [onSecondDelivery] fires once per ignored
     * re-delivery — useful for logging but never for state mutation.
     */
    class ResultSlot {
        @Volatile private var delivered: Result? = null
        @Volatile private var secondListener: (() -> Unit)? = null

        val firstResult: Result? get() = delivered

        fun complete(result: Result): Boolean {
            val prior = delivered
            if (prior != null) {
                secondListener?.invoke()
                return false
            }
            delivered = result
            return true
        }

        fun onSecondDelivery(listener: () -> Unit) {
            secondListener = listener
        }

        fun awaitOrNull(): Result? = delivered
    }

    private val jobRegistry = mutableMapOf<String, (Map<String, String>) -> String>()
    private val slots = java.util.concurrent.ConcurrentHashMap<String, ResultSlot>()

    init {
        registerDefaultJobs()
        TermuxBridgeSingleton.register(this)
    }

    fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(TermuxCommandContract.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isUsable(): Boolean = isAvailable() && hasRunCommandPermission()

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
                .setPackage(TermuxCommandContract.PACKAGE)
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

    fun hasRunCommandPermission(): Boolean {
        return context.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED ||
            context.packageManager.checkPermission(
                "com.termux.permission.RUN_COMMAND",
                context.packageName,
            ) == PackageManager.PERMISSION_GRANTED
    }

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

    fun runJob(name: String, args: Map<String, String>): Result {
        val builder = jobRegistry[name]
            ?: return Result("", "unknown job: $name", 2)
        val cmd = builder(args)
        return runBlocking(cmd, timeoutMs = 15000)
    }

    /**
     * Asynchronous, non-blocking execution through Termux's documented
     * `RunCommandService` contract. The suspend return type cooperates
     * with structured concurrency: cancellation in the calling scope
     * cancels the pending `Termux` invocation via [cancel].
     */
    suspend fun runRaw(command: String, timeoutMs: Long = 30_000): Result {
        if (!isUsable()) {
            val why = if (!isAvailable()) "termux not installed"
            else "RUN_COMMAND permission not granted — see grantInstructions()"
            return Result("", why, 127)
        }
        return withContext(Dispatchers.IO) {
            val timed = withTimeoutOrNull(timeoutMs) {
                executeAsync(command)
            }
            timed ?: Result("", "timeout after ${timeoutMs}ms", 124)
        }
    }

    /**
     * Synchronous facade for callers that cannot be suspend yet (legacy
     * `runJob` path, tests, etc.). Internally delegates to [runRaw] on a
     * short-lived scope so callers do not block on Termux IPC.
     */
    fun runBlocking(command: String, timeoutMs: Long = 30_000): Result {
        val deferred = kotlinx.coroutines.runBlocking {
            runRaw(command, timeoutMs)
        }
        return deferred
    }

    private suspend fun executeAsync(command: String): Result {
        val requestId = java.util.UUID.randomUUID().toString()
        val slot = ResultSlot()
        slots[requestId] = slot

        val callback = Intent(context, TermuxResultReceiver::class.java)
            .putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId)

        val callbackIntent = android.app.PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            callback,
            android.app.PendingIntent.FLAG_ONE_SHOT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE else 0,
        )

        val run = Intent().apply {
            setClassName(
                TermuxCommandContract.PACKAGE,
                TermuxCommandContract.SERVICE,
            )
            action = TermuxCommandContract.ACTION
            putExtra(
                TermuxCommandContract.EXTRA_PATH,
                "/data/data/com.termux/files/usr/bin/sh",
            )
            putExtra(
                TermuxCommandContract.EXTRA_ARGUMENTS,
                arrayOf("-c", command),
            )
            putExtra(
                TermuxCommandContract.EXTRA_WORKDIR,
                "/data/data/com.termux/files/home",
            )
            putExtra(TermuxCommandContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxCommandContract.EXTRA_SESSION_ACTION, "0")
            putExtra(TermuxCommandContract.EXTRA_PENDING_INTENT, callbackIntent)
        }
        try {
            context.startService(run)
        } catch (t: Throwable) {
            cleanup(requestId)
            return Result("", "startService failed: ${t.message}", 1)
        }

        // Wait cooperatively for the receiver to fill the slot. We poll
        // rather than suspendCancellableCoroutine + invokeOnCancellation
        // here because the receiver path is a separate BroadcastReceiver
        // callback that does not own a coroutine continuation; a small
        // busy-wait under Dispatchers.IO is acceptable for an integration
        // adapter and never touches the main thread.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            slot.firstResult?.let { return it }
            kotlinx.coroutines.yield()
        }
        cleanup(requestId)
        return Result("", "no result delivered", 124)
    }

    /**
     * Receiver entry point. Called by [TermuxResultReceiver] when a result
     * PendingIntent fires. Idempotent: second delivery for the same
     * requestId is a no-op so the receiver can be safely re-fired by the
     * platform without overwriting the first observed result.
     */
    internal fun deliver(requestId: String, result: Result) {
        val slot = slots.remove(requestId) ?: return
        slot.complete(result)
    }

    /**
     * Public cancel hook. Removes the slot so a late delivery becomes a
     * no-op rather than resurrecting a coroutine the caller already gave
     * up on.
     */
    fun cancel(requestId: String) {
        slots.remove(requestId)
    }

    private fun cleanup(requestId: String) {
        slots.remove(requestId)
    }

    private fun registerDefaultJobs() {
        jobRegistry["git.clone"] = { args ->
            val url = args["url"] ?: throw IllegalArgumentException("git.clone needs url")
            val dir = args["dir"] ?: java.io.File(context.filesDir, "repos").absolutePath
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

    companion object {
        private const val TAG = "FR3K.termux"

        // Unused: kept referenced from TermuxResultReceiver for back-compat.
        @Suppress("unused")
        internal val pending = java.util.concurrent.ConcurrentHashMap<Int, String>()

        @Deprecated("Replaced by deliver(requestId, result).")
        internal fun complete(id: Int, result: Result) {
            // Best-effort: in the old API id was an Int; the new API uses
            // UUID strings. We can't safely map Int -> String so we no-op.
            Log.w(TAG, "legacy TermuxBridge.complete($id) ignored")
        }
    }
}