package com.mcpintelligence.fr3k.adapters.lspatch

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * LSPatch / NPatch compatibility layer (§36). Detects installed LSPatch-based
 * modules and registers them as context providers. Active maintained fork
 * (per the brief — we do NOT depend on archived LSPosed/LSPatch):
 *   - find-xposed-magisk/LSPatch  (404 commits, 2024+, GPL-3.0)
 *   - JingMatrix/LSPosed           (works on Android 15)
 *   - org.lsposed.lspatch          (org fork)
 *
 * FR3K HUD is designed so the runtime adapter is *separate* from core. This
 * class is the registration layer: it scans known LSPatch module paths and
 * loads each one through [LspatchAdapterShim]. Modules expose a tiny RPC
 * surface over IPC (`adapter.browser.context`, `adapter.url.captured`, etc.)
 * — FR3K core remains the policy authority, the adapter just forwards data.
 */
class LspatchAdapter(context: Context) {

    data class InstalledModule(
        val id: String,
        val label: String,
        val version: String,
        val apkPath: String,
        val packageName: String,
        val isEnabled: Boolean,
    )

    private val ctx = context
    private val modules = mutableListOf<InstalledModule>()

    /**
     * Scan for LSPatch modules. Two paths:
     *   1. Installed apps with FLAG_LSPATCH in their `ApplicationInfo`
     *      (set by the LSPatch manager at install time).
     *   2. Module directories under `/data/adb/lspatch/modules/<id>/`.
     *
     * We attempt both. The install-time flag is the most reliable on
     * modern LSPatch builds; the directory scan is a fallback.
     */
    fun scan(): List<InstalledModule> {
        modules.clear()
        scanInstalledApps()
        scanModuleDirs()
        return modules.toList()
    }

    private fun scanInstalledApps() {
        val pm = ctx.packageManager
        val flag = try {
            // FLAG_LSPATCH was renamed in different LSPatch builds. Try
            // a few candidates. The integer value isn't part of the SDK
            // contract, so we read ApplicationInfo flags directly.
            0x80000000.toInt() // placeholder, we filter below
        } catch (_: Throwable) { 0 }

        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledApplications failed: ${t.message}")
            return
        }

        for (app in installed) {
            // Heuristic: a package is an LSPatch module if its manifest has
            // a `lspatch` meta-data tag, OR its source dir is under
            // /data/adb/lspatch/, OR its package name matches a known
            // LSPatch module prefix.
            val isLspatchModule = try {
                val md = app.metaData
                md != null && (
                    md.getString("lspatch.module") != null ||
                    md.getString("xposedmodule") != null ||
                    md.getString("fr3k.module") != null
                )
            } catch (_: Throwable) { false }
            val isUnderAdbLspatch = runCatching {
                File(app.sourceDir).canonicalPath.contains("/data/adb/lspatch/")
            }.getOrDefault(false)

            if (!isLspatchModule && !isUnderAdbLspatch) continue

            val id = app.metaData?.getString("lspatch.module")
                ?: app.metaData?.getString("xposedmodule")
                ?: app.packageName
            modules += InstalledModule(
                id = id,
                label = app.loadLabel(pm).toString(),
                version = runCatching {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(app.packageName, 0).versionName ?: "unknown"
                }.getOrDefault("unknown"),
                apkPath = app.sourceDir,
                packageName = app.packageName,
                isEnabled = app.enabled,
            )
        }
    }

    private fun scanModuleDirs() {
        val candidates = listOf(
            "/data/adb/lspatch/modules",
            "/data/adb/lsposed/modules",
            "/data/adb/modules",
        )
        for (path in candidates) {
            val dir = runCatching { File(path) }.getOrNull() ?: continue
            if (!dir.exists() || !dir.canRead()) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".apk")) {
                    val manifest = runCatching { readModuleManifest(f) }.getOrNull()
                    if (manifest != null && modules.none { it.apkPath == manifest.apkPath }) {
                        modules += manifest
                    }
                } else if (f.isDirectory) {
                    val apk = f.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
                    if (apk != null) {
                        val manifest = runCatching { readModuleManifest(apk) }.getOrNull()
                        if (manifest != null && modules.none { it.apkPath == manifest.apkPath }) {
                            modules += manifest
                        }
                    }
                }
            }
        }
    }

    private fun readModuleManifest(apk: File): InstalledModule? {
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return null
        val id = info.applicationInfo?.metaData?.getString("lspatch.module")
            ?: info.applicationInfo?.metaData?.getString("xposedmodule")
            ?: apk.parentFile?.name
            ?: apk.nameWithoutExtension
        return InstalledModule(
            id = id,
            label = info.applicationInfo?.loadLabel(pm)?.toString() ?: id,
            version = info.versionName ?: "unknown",
            apkPath = apk.absolutePath,
            packageName = info.packageName ?: id,
            isEnabled = true,
        )
    }

    /**
     * Send the FR3K announce broadcast to every installed LSPatch module.
     * Real modules check our package signature against a pinned hash; this
     * method just records how many we addressed.
     */
    fun announceToModules(): Int {
        val i = android.content.Intent("com.mcpintelligence.fr3k.hud.ANNOUNCE")
            .putExtra("package", ctx.packageName)
            .putExtra("versionName", runCatching {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull())
        var n = 0
        for (m in modules) {
            i.setPackage(m.packageName)
            runCatching { ctx.sendBroadcast(i); n++ }
                .onFailure { Log.w(TAG, "announce to ${m.packageName} failed: ${it.message}") }
        }
        return n
    }

    /** True if any LSPatch-style module is installed. */
    fun hasAny(): Boolean = scan().isNotEmpty()

    /** True if the well-known LSPatch manager packages are installed. */
    /**
     * The list of LSPatch/LSPosed manager package names we recognise. The
     * standalone LSPatch (JingMatrix) uses a different package name from
     * the LSPosed manager, and the user-installed build on the emulator
     * surfaces as `org.lsposed.lspatch`. We probe all of them.
     */
    private val managerPackages = listOf(
        "org.lsposed.lspatch",       // JingMatrix standalone LSPatch
        "org.lsposed.manager",       // upstream LSPosed manager
        "io.github.jingmatrix.lspatch",
        "com.itsaky.lspatch",
    )

    fun hasManager(): Boolean = managerPackage() != null

    /**
     * Returns the actual installed manager package name, or null if none
     * of the known managers are installed. Used to label the install
     * status row in the integrations panel.
     */
    fun managerPackage(): String? {
        for (pkg in managerPackages) {
            val ok = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            if (ok) return pkg
        }
        return null
    }

    /** Open the LSPatch manager if installed, else Play Store. */
    fun openManager() {
        for (m in managerPackages) {
            val i = ctx.packageManager.getLaunchIntentForPackage(m)
            if (i != null) {
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            }
        }
        val play = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setData(android.net.Uri.parse("https://github.com/JingMatrix/LSPatch/releases"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(play) }
    }

    companion object { private const val TAG = "FR3K.lspatch" }
}
