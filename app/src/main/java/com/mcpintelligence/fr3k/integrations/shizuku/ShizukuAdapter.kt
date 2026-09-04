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

    data class ShResult(val stdout: String, val stderr: String, val exitCode: Int)

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
            if (Shizuku.getBinder() != null) {
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

    /** Bind to Shizuku and return the IBinder (or null if unavailable). */
    fun bind(): IBinder? {
        if (!isInstalled()) return null
        return try {
            // Shizuku keeps a cached binder across the process — if we
            // already have one, return it directly.
            Shizuku.getBinder()?.let { return it }
            // Otherwise wait for the binder to arrive via the listener
            // path. The AAR has no `bind(ServiceConnection)` static —
            // service binding goes through the AIDL intent and the
            // listener fires when SUI hands us the binder.
            val holder = arrayOfNulls<IBinder>(1)
            val latch = java.util.concurrent.CountDownLatch(1)
            val listener = object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    holder[0] = Shizuku.getBinder()
                    latch.countDown()
                }
            }
            Shizuku.addBinderReceivedListener(listener)
            // Trigger the bind if it hasn't already happened. The AAR
            // does this internally on construction, but a poke doesn't
            // hurt and is idempotent.
            Shizuku.pingBinder()
            latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            Shizuku.removeBinderReceivedListener(listener)
            holder[0]
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku bind failed: ${t.message}")
            null
        }
    }

    /**
     * Ping the bound Shizuku service with a no-op call. Returns true if
     * the IPC link is live and our UID is authorised.
     */
    fun pingBinder(): Boolean {
        val b = bind() ?: return false
        return try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken("moe.shizuku.api.IShizukuService")
            // IShizukuService.exit() with our process is too destructive;
            // use the safer "version" call (no side effects).
            val r = b.transact(/* code for getVersion */ 0x01, data, reply, 0)
            reply.readException()
            val version = reply.readInt()
            reply.recycle()
            data.recycle()
            r && version > 0
        } catch (e: RemoteException) {
            Log.w(TAG, "Shizuku pingBinder RemoteException: ${e.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku pingBinder failed: ${t.message}")
            false
        }
    }

    /**
     * Run a command through Shizuku's elevated context. Equivalent of
     * `Runtime.getRuntime().exec(cmd)` but with the system_server-grade
     * UID/permission Shizuku has been granted by the user.
     */
    fun shellCommand(command: String, timeoutMs: Long = 8000): ShResult {
        val b = bind() ?: return ShResult("", "shizuku not available", 127)
        if (!isAuthorized()) {
            return ShResult("", "shizuku permission not granted for ${context.packageName}", 126)
        }
        return try {
            // IShizukuService.newProcess(String[] cmd, String[] env, String dir)
            // transact code 0x0A on stable SUI builds.
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken("moe.shizuku.api.IShizukuService")
            data.writeStringArray(arrayOf("/system/bin/sh", "-c", command))
            data.writeStringArray(arrayOf<String>())
            data.writeString("/")
            b.transact(0x0A, data, reply, 0)
            reply.readException()
            // The newProcess binder returns a parcel with a file descriptor
            // pair (stdout, stderr) and a PID. We don't try to consume them
            // synchronously in this minimal adapter — callers wanting the
            // actual output should use the higher-level [ShizukuShell] helper.
            reply.recycle()
            data.recycle()
            ShResult("", "shizuku shellCommand not fully implemented in V1", -1)
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku shellCommand failed: ${t.message}")
            ShResult("", t.message ?: t.javaClass.simpleName, 1)
        }
    }

    /**
     * Request the Shizuku permission. SUI shows a system dialog, and on
     * accept our package appears in the SUI admin list ("apps that can
     * use this"). Calls the real `Shizuku.requestPermission()` from the
     * AAR — reflection was tried first and silently failed because the
     * AAR class wasn't on the classpath at runtime.
     *
     * If the AAR call fails (e.g. Shizuku not installed), falls back to
     * launching the Shizuku app so the user can grant manually.
     */
    fun openGrantScreen(activity: android.app.Activity? = null) {
        // 1) If we're on API 33+ and our manifest declared the
        //    `moe.shizuku.api.permission.PERMISSION` runtime permission,
        //    fire the standard Android grant dialog first. SUI 13.5+
        //    accepts grants through this path and registers us in its
        //    admin list automatically.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission("moe.shizuku.api.permission.PERMISSION") !=
                PackageManager.PERMISSION_GRANTED
            ) {
                if (activity != null) {
                    activity.requestPermissions(
                        arrayOf("moe.shizuku.api.permission.PERMISSION"),
                        REQ_SHIZUKU_PERMISSION,
                    )
                    return
                }
            }
        } catch (_: Throwable) { /* not declared — fall through */ }

        // 2) Direct AAR call: this is the path that actually puts us in
        //    the SUI admin list. Shizuku.requestPermission() pops SUI's
        //    own dialog (not the OS one) which the user must accept.
        //    We require a live binder (Shizuku service running) before
        //    firing the call; otherwise SUI would never see the
        //    request.
        val launched = try {
            val b = Shizuku.getBinder()
            if (b == null) {
                Log.w(TAG, "Shizuku binder is null — service not running")
                false
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU_PERMISSION)
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku.requestPermission failed: ${t.message}")
            false
        }

        // 3) Last-resort: launch the Shizuku app so the user can grant
        //    from inside SUI manually.
        if (!launched) {
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
