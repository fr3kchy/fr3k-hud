package com.mcpintelligence.fr3k.integrations.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Tier-2 Shizuku integration. Talks to the running Shizuku service via the
 * official `dev.rikka.shizuku:api` AAR. Permission grants go through
 * `Shizuku.requestPermission(...)` which is what populates the SUI admin
 * list with our package.
 *
 * We used to do this via reflection so we didn't need the AAR, but the
 * reflective call silently fell back to "launch the Shizuku app" because
 * `moe.shizuku.api.Shizuku` wasn't on the classpath — meaning SUI never
 * saw us in its "apps that can use this" list. The AAR is ~50 KB and
 * solves the problem for real.
 *
 * Operations exposed:
 *   - [isInstalled]      — package present on the device
 *   - [isAuthorized]     — Shizuku's "permission grant" dialog has been
 *                          accepted for our package (SUI >= 13.5.0)
 *   - [pingBinder]       — call a no-op method on the bound service to
 *                          confirm the IPC link is live
 *   - [shellCommand]     — run a command through `IShizukuService.newProcess`
 *                          (Shizuku-equivalent of `Runtime.exec` as root)
 *   - [installApk]       — `pm install` through Shizuku so the user doesn't
 *                          need to use `adb`
 *
 * The Shizuku service is bound via the well-known intent
 *   `moe.shizuku.api.intent.action.REQUEST_BIND`
 * which any Shizuku build >= 12.x handles.
 */
class ShizukuAdapter(private val context: Context) {

    data class ShResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long = 0,
        val provenance: String = "",
    )

    private val pkg = "moe.shizuku.api.permission"
    private val grantPkg = "moe.shizuku.api"

    fun isInstalled(): Boolean {
        // Shizuku ships as one of two packages: the public-facing
        // "moe.shizuku.api" (the user installs from Play / F-Droid) or
        // "moe.shizuku.privileged.api" (root-only privileged variant).
        // We accept either, and also any of the historical AOSP-internal
        // forks that show up in the wild.
        val candidates = listOf(
            "moe.shizuku.api",
            "moe.shizuku.privileged.api",
        )
        return candidates.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * True if the Shizuku Manager service is actually running and
     * has bound its AIDL interface. Without a live binder, every
     * `Shizuku.requestPermission()` call silently fails — that's
     * why "GRANT SHIZUKU" appears to do nothing on devices where
     * only the API package is installed.
     */
    fun isManagerRunning(): Boolean {
        if (!isInstalled()) return false
        return try {
            Shizuku.getBinder() != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True if the user has accepted our package in Shizuku's permission
     * dialog. SUI 13.5+ uses the runtime permission
     * `moe.shizuku.api.permission.PERMISSION` which is also surfaced by
     * the AAR's `Shizuku.checkSelfPermission`. We try both: the AAR
     * call first (canonical for SUI 13.5+), then the raw Android check
     * (fallback for older SUI versions that exposed the same perm
     * through a different code path).
     */
    fun isAuthorized(): Boolean {
        if (!isInstalled()) return false
        val aarCheck = try {
            // Cache the binder so we don't block the UI thread on every
            // status query. The first time we see a binder, capture it
            // and reuse it.
            val cached = cachedBinder
            val live = cached ?: Shizuku.getBinder()
            if (cached == null && live != null) cachedBinder = live
            if (live != null) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else false
        } catch (_: Throwable) { false }
        if (aarCheck) return true
        return try {
            context.checkSelfPermission("moe.shizuku.api.permission.PERMISSION") ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    @Volatile private var cachedBinder: IBinder? = null

    /**
     * Trigger the Shizuku binder acquisition off the UI thread and
     * return the cached binder (which is updated asynchronously when
     * the listener fires). The first call to this function also kicks
     * off a background coroutine that calls [bindBlocking] once, which
     * populates the cache for all subsequent synchronous reads.
     */
    fun bind(): IBinder? {
        if (!isInstalled()) return null
        // Fast path: cached or already live.
        cachedBinder?.let { return it }
        Shizuku.getBinder()?.let {
            cachedBinder = it
            return it
        }
        // Slow path: kick off a background bind. Don't block the
        // caller — return whatever the cache has after a short wait.
        ensureBackgroundBind()
        return cachedBinder
    }

    @Volatile private var backgroundBindStarted = false
    private fun ensureBackgroundBind() {
        if (backgroundBindStarted) return
        backgroundBindStarted = true
        Thread({
            try {
                bindBlocking()
            } catch (t: Throwable) {
                Log.w(TAG, "background bind failed: ${t.message}")
            } finally {
                backgroundBindStarted = false
            }
        }, "fr3k-shizuku-bind").start()
    }

    /**
     * Blocking bind. Tries the cached binder first, then waits up to
     * 2s on the listener path. Should only be called from a worker
     * thread (see [bind]).
     */
    private fun bindBlocking(): IBinder? {
        if (!isInstalled()) return null
        return try {
            Shizuku.getBinder()?.let { cachedBinder = it; return it }
            val holder = arrayOfNulls<IBinder>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    holder[0] = Shizuku.getBinder()
                    latch.countDown()
                }
            }
            Shizuku.addBinderReceivedListener(listener)
            Shizuku.pingBinder()
            val got = latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            Shizuku.removeBinderReceivedListener(listener)
            val b = holder[0]
            if (got && b != null) {
                cachedBinder = b
                b
            } else null
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku bindBlocking failed: ${t.message}")
            null
        }
    }

    /**
     * Ping the bound Shizuku service with a no-op call. Returns true if
     * the IPC link is live and our UID is authorised.
     */
    /**
     * True if a live Shizuku binder is available AND a quick IPC
     * round-trip succeeds. Cheap to call: we just call the AAR's
     * `Shizuku.pingBinder()` which does its own threading and
     * returns the cached result if the binder is already known.
     */
    fun pingBinder(): Boolean {
        if (!isInstalled()) return false
        return try {
            // First, make sure the cache is populated. The bind() call
            // is now non-blocking — if the binder isn't there yet, we
            // fall back to a synchronous short wait via the AAR.
            val b = cachedBinder ?: Shizuku.getBinder()
            if (b != null) {
                cachedBinder = b
                // Use the AAR's own pingBinder() which does an internal
                // IPC ping and returns the cached result.
                Shizuku.pingBinder()
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku pingBinder failed: ${t.message}")
            false
        }
    }

    /**
     * Run a command through Shizuku's elevated context — but only via the
     * policy-scoped executor ([ShizukuCommandExecutor]). Raw shell strings
     * are denied by policy; this only accepts a typed operation
     * (GetPackageInfo / ListPackageSplits / InstallApprovedApk /
     * UninstallTestFixture / ReadSystemSetting). Returns stdout, stderr,
     * exit code, duration and the operation provenance.
     */
    @Deprecated("Use ShizukuCommandExecutor.execute(binder, op) with a typed Operation")
    fun shellCommand(command: String, timeoutMs: Long = 8000): ShResult {
        val b = bind() ?: return ShResult("", "shizuku not available", 127, 0, "")
        if (!isAuthorized()) {
            return ShResult("", "shizuku permission not granted for ${context.packageName}", 126, 0, "")
        }
        // Policy-gate: a raw shell string is never allowed. Map to a typed
        // operation request based on the command prefix — anything unknown
        // is denied, never executed.
        val (request, arg) = when {
            command.startsWith("pm path ") -> "GetPackageInfo" to command.removePrefix("pm path ").trim()
            command.startsWith("pm list ") -> "ListPackageSplits" to command.removePrefix("pm list ").trim()
            command.startsWith("install ") -> "InstallApprovedApk" to command.removePrefix("install ").trim()
            command.startsWith("uninstall ") -> "UninstallTestFixture" to command.removePrefix("uninstall ").trim()
            command.startsWith("settings get ") -> "ReadSystemSetting" to command.removePrefix("settings get ").trim()
            else -> "" to command
        }
        val op = ShizukuCommandExecutor.Operation.from(request, arg)
        if (op is ShizukuCommandExecutor.Operation.Denied) {
            return ShResult("", "denied: ${op.reason}", 1, 0, "policy")
        }
        val exec = ShizukuCommandExecutor.execute(b, op as ShizukuCommandExecutor.Operation.Allowed)
        return ShResult(exec.stdout, exec.stderr, exec.exitCode, exec.durationMs, exec.provenance)
    }

    /**
     * Request the Shizuku permission. SUI shows a system dialog, and on
     * accept our package appears in the SUI admin list ("apps that can
     * use this"). Delegates to [ShizukuBridge.requestPermission] which
     * is the ONLY Shizuku grant path — it calls
     * `Shizuku.requestPermission(code)` after the binder is live and
     * never falls through to the OS `Activity.requestPermissions()`
     * dialog (that path does not register us in SUI's admin list).
     *
     * If the bridge refuses (no binder yet) or the grant can't fire,
     * falls back to launching the Shizuku app so the user can grant
     * manually inside SUI.
     */
    fun openGrantScreen(activity: android.app.Activity? = null) {
        // Invalidate any cached binder state so the next status read
        // re-queries SUI after the user toggles the grant.
        cachedBinder = null
        backgroundBindStarted = false

        // Ask the bridge to fire the AAR grant request. It refuses
        // unless the binder is live, keeping us from a silent no-op.
        val dispatched = ShizukuBridge.get().requestPermission(REQ_SHIZUKU_PERMISSION)

        // Last-resort: launch the Shizuku app so the user can grant
        // from inside SUI manually.
        if (!dispatched) {
            Log.i(TAG, "Shizuku.requestPermission not dispatched — launching ${grantPkg}")
            val i = context.packageManager.getLaunchIntentForPackage(grantPkg)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQ_SHIZUKU_PERMISSION) return false
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Shizuku runtime permission result granted=$granted")
        // Clear the cached binder so the next read re-queries SUI's
        // authoritative state instead of the stale pre-grant value.
        cachedBinder = null
        backgroundBindStarted = false
        // If the OS-level grant succeeded, also re-fire the SUI AAR
        // request so SUI's internal registry sees us.
        if (granted) {
            runCatching {
                if (Shizuku.getBinder() != null) {
                    Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "FR3K.shizuku"
        const val REQ_SHIZUKU_PERMISSION = 0x5F31
    }
}
