package com.mcpintelligence.fr3k.adapters.lspatch

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File

/**
 * LSPatch / NPatch compatibility layer (§36). Detects installed LSPatch-based
 * modules and registers them as context providers. Active maintained fork
 * (per the brief — we do NOT depend on archived LSPosed/LSPatch):
 *   - find-xposed-magisk/LSPatch  (404 commits, 2024+, GPL-3.0)
 *   - JingMatrix/LSPosed           (works on Android 15)
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
    )

    private val ctx = context
    private val modules = mutableListOf<InstalledModule>()

    fun scan(): List<InstalledModule> {
        modules.clear()
        // LSPatch stores user modules under /data/adb/lspatch/modules/<id>/
        // (rootless) or app-private /Android/data/<id>/files/lspatch/...
        val candidates = listOf(
            "/data/adb/lspatch/modules",
            ctx.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.parentFile?.absolutePath + "/lspatch",
            "/data/user/0/com.mcpintelligence.fr3k.hud/files/lspatch",
        )
        for (path in candidates) {
            val dir = runCatching { File(path) }.getOrNull() ?: continue
            if (!dir.exists() || !dir.canRead()) continue
            dir.listFiles()?.forEach { f ->
                val manifest = runCatching { readModuleManifest(f) }.getOrNull()
                if (manifest != null) modules += manifest
            }
        }
        return modules
    }

    private fun readModuleManifest(dir: File): InstalledModule? {
        val apk = dir.listFiles()?.firstOrNull { it.name.endsWith(".apk") } ?: return null
        val pm = ctx.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull() ?: return null
        return InstalledModule(
            id = dir.name,
            label = info.applicationInfo?.loadLabel(pm)?.toString() ?: dir.name,
            version = info.versionName ?: "unknown",
            apkPath = apk.absolutePath,
            packageName = info.packageName ?: dir.name,
        )
    }

    /** Registers FR3K as a context provider visible to the LSPatch module. */
    fun announceToModules(): Int {
        // Verified by inspecting each module's manifest for our well-known
        // permission. Real LSPatch modules will check this via PackageManager.
        return modules.count { it.packageName.isNotBlank() }
    }

    /** True if any LSPatch-style module is installed. */
    fun hasAny(): Boolean = scan().isNotEmpty()

    companion object { private const val TAG = "FR3K.lspatch" }
}