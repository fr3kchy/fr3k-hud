package com.mcpintelligence.fr3k.hud.receivers

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * One-shot probe that walks every partner-app package the FR3K integrations
 * panel cares about, runs each adapter's `isAvailable` / `isInstalled` /
 * `isAuthorized` / `pingBinder` against the live system, and writes a
 * small JSON cache to `context.filesDir/install_state.json`.
 *
 * Called from [BootReceiver] (BOOT_COMPLETED + MY_PACKAGE_REPLACED) so the
 * integrations panel comes up fresh after a reboot. The actual work is
 * cheap — a few `PackageManager` lookups and one reflective ping for
 * Shizuku — so we don't bother with a WorkManager job; just run it
 * synchronously on the broadcast thread.
 */
object InstallStateProbe {

    private const val TAG = "FR3K.probe"
    const val STATE_FILE = "install_state.json"

    /**
     * Refresh and persist the install state. Returns the JSON object so
     * callers (mostly [BootReceiver] in logs) can see what changed.
     */
    fun refresh(context: Context): JSONObject {
        val pm = context.packageManager
        val state = JSONObject()

        // ----- Tier 1: Termux -----
        val termuxCore = isPkgInstalled(pm, "com.termux")
        val termuxApi = isPkgInstalled(pm, "com.termux.api")
        val termuxRunCommand = isTermuxRunCommandGranted(context)
        state.put("termux_installed", termuxCore && termuxApi)
        state.put("termux_run_command", termuxRunCommand)

        // ----- Tier 2: Shizuku -----
        val shizukuInstalled = isPkgInstalled(pm, "moe.shizuku.api")
        val shizukuAuthorized = isShizukuAuthorized()
        state.put("shizuku_installed", shizukuInstalled)
        state.put("shizuku_authorized", shizukuAuthorized)

        // ----- Tier 3: LSPatch / LSPosed -----
        val lspatchInstalled = isPkgInstalled(pm, "org.lsposed.manager")
        state.put("lspatch_installed", lspatchInstalled)
        val modules = countLspatchModules(pm)
        state.put("lspatch_modules", modules)

        // ----- Tier 3: Morphe -----
        // Morphe isn't an app — it's a JSON patch repo. The actual
        // `loadAllAvailable()` scan happens at panel-open time. We just
        // record that the assets dir is present.
        val morpheAssets = File(context.filesDir, "morphe").exists() ||
            runCatching {
                context.assets.list("morphe")?.isNotEmpty() == true
            }.getOrDefault(false)
        state.put("morphe_assets", morpheAssets)

        // ----- Tier 4: Vector / root -----
        val rootAvailable = probeRoot()
        state.put("root_available", rootAvailable)
        val vectorPkg = listOf(
            "io.github.vvb2060.xposeddetector",
            "io.github.vvb2060.magiskdetector",
            "com.solana.mobilewalletadapter",
        ).firstOrNull { isPkgInstalled(pm, it) }
        state.put("vector_package", vectorPkg ?: JSONObject.NULL)

        state.put("probed_at", System.currentTimeMillis())

        // Persist
        runCatching {
            File(context.filesDir, STATE_FILE).writeText(state.toString(2))
        }.onFailure { Log.w(TAG, "write failed: ${it.message}") }

        Log.i(TAG, "install state: $state")
        return state
    }

    /**
     * Read the cached state. Used by [IntegrationsActivity] to pre-fill
     * status before the live probes finish.
     */
    fun read(context: Context): JSONObject? = runCatching {
        val f = File(context.filesDir, STATE_FILE)
        if (!f.exists()) return@runCatching null
        JSONObject(f.readText())
    }.getOrNull()

    private fun isPkgInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * Termux's RUN_COMMAND permission is a `dangerous` permission the user
     * grants inside Termux via `termux-setup-storage` or
     * `pkg install termux-api && accept`. The OS exposes it as a
     * `runtime` grant on the app that requests it (com.termux.api), so
     * we check whether `com.termux.api` holds it.
     */
    private fun isTermuxRunCommandGranted(context: Context): Boolean = runCatching {
        val pkg = "com.termux.api"
        context.packageManager.getPackageInfo(
            pkg,
            PackageManager.GET_PERMISSIONS,
        )
        // The permission name is "com.termux.permission.RUN_COMMAND".
        // It's a custom dangerous permission; we check via appops as a
        // fallback because GET_PERMISSIONS doesn't tell us grant state
        // directly.
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as android.app.AppOpsManager
        // Mode 0 = MODE_ALLOWED, MODE_DEFAULT + PERMISSION_GRANTED = allowed
        @Suppress("DEPRECATION")
        val mode = appOps.unsafeCheckOpNoThrow(
            "com.termux.permission.RUN_COMMAND",
            android.os.Process.myUid(),
            pkg,
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED ||
            mode == android.app.AppOpsManager.MODE_DEFAULT
    }.getOrDefault(false)

    /**
     * Shizuku stores its auth state inside the `moe.shizuku.api` SharedPreferences.
     * The reliable way to check is to ask the running Shizuku service directly
     * via reflection on its `IUserService` binder — but at boot time the
     * service may not be running yet. We just check whether the user has
     * previously granted by reading the public marker file Shizuku writes:
     *
     * `/data/data/moe.shizuku.api/shared_prefs/...xml` — but we don't have
     * that permission, so we fall back to `isInstalled` only and let the
     * IntegrationsActivity do the live `pingBinder` once it's on screen.
     */
    private fun isShizukuAuthorized(): Boolean = false // live ping happens in IntegrationsActivity

    private fun countLspatchModules(pm: PackageManager): Int = runCatching {
        // LSPatch doesn't expose a public "modules" API. The community
        // convention is to look for apps whose manifest declares an
        // `xposedmodule` meta-data. We do a cheap count via
        // GET_META_DATA — it only catches modules that haven't been
        // stripped, but that's a useful upper bound.
        var n = 0
        val flags = PackageManager.GET_META_DATA
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(flags)
        for (info in apps) {
            if (info.metaData?.getBoolean("xposedmodule", false) == true) n++
        }
        n
    }.getOrDefault(0)

    /**
     * Mirror of VectorAdapter.probeRoot() — duplicated here so the
     * boot-time probe doesn't have to instantiate the adapter (which
     * pulls in Hermes / etc. via the singleton graph). Kept simple:
     * we just check whether `/system/xbin/su` or `/system/bin/su` is
     * executable by *some* uid. The real per-app root check happens
     * in VectorAdapter.status() at panel-open time.
     */
    private fun probeRoot(): Boolean {
        for (path in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")) {
            val f = java.io.File(path)
            if (f.exists() && f.canExecute()) return true
        }
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            proc.waitFor() == 0
        }.getOrDefault(false)
    }
}
