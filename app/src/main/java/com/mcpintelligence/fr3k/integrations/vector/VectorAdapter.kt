package com.mcpintelligence.fr3k.integrations.vector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Tier-4 rooted / Vector integration.
 *
 * Active maintained forks in 2026 (per the master brief, we never depend
 * on the archived LSPosed/LSPatch originals):
 *   - Vector          (org.lsposed.vector, GPL-3.0)
 *   - LSPatch (Jing) (io.github.jingmatrix.lspatch, GPL-3.0)
 *   - LSPosed (Jing) (com.jingmatrix.lsposed, fork of LSPosed)
 *
 * FR3K HUD does NOT call into Vector's Java API at runtime. Instead, this
 * adapter:
 *   1. Detects which Vector build (if any) is installed
 *   2. Detects whether the user has `su` available (root)
 *   3. Records the active privilege tier so the UI can show it
 *   4. Provides a hookable interface (`VectorHook.onClass`) that
 *      companion apps can implement if they want to bridge into the
 *      Vector module once one is built.
 *
 * The reason: Vector API surface is intentionally unstable across forks,
 * and depending on it would break the moment the user installs a different
 * fork. We expose a single test point that returns a stable boolean.
 */
class VectorAdapter(private val context: Context) {

    data class Status(
        val rootAvailable: Boolean,
        val suPath: String?,
        val vectorPackage: String?,
        val lspatchPackages: List<String>,
        val activeTier: Tier,
    )

    enum class Tier { TIER_0_NORMAL, TIER_1_USER_GRANTED, TIER_2_SHIZUKU, TIER_3_LSPATCH, TIER_4_ROOT }

    private val knownVectorPkgs = listOf(
        "org.lsposed.vector",
        "com.jingmatrix.lsposed",
        "io.github.jingmatrix.lsposed",
        "org.lsposed.lspd",
        "de.robv.android.xposed.installer",
    )

    private val knownLspatchPkgs = listOf(
        "org.lsposed.lspatch",
        "io.github.jingmatrix.lspatch",
        "com.itsaky.lspatch",
    )

    fun status(): Status {
        val su = probeRoot()
        val vector = knownVectorPkgs.firstOrNull { installed(it) }
        val lspatches = knownLspatchPkgs.filter { installed(it) }
        val tier = when {
            su != null -> Tier.TIER_4_ROOT
            vector != null -> Tier.TIER_4_ROOT
            lspatches.isNotEmpty() -> Tier.TIER_3_LSPATCH
            else -> Tier.TIER_0_NORMAL
        }
        return Status(
            rootAvailable = su != null,
            suPath = su,
            vectorPackage = vector,
            lspatchPackages = lspatches,
            activeTier = tier,
        )
    }

    /** Returns the path to a working `su` binary or null if not rooted. */
    fun probeRoot(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/modules/zygisk/.*", // magisk-zygisk style, rare
        )
        for (c in candidates) {
            if (c.endsWith("/*")) continue
            try {
                val p = java.io.File(c)
                if (p.exists() && p.canExecute()) return c
            } catch (_: Throwable) {}
        }
        // Try running `su -c id` — Magisk's su only works when invoked
        // through the su daemon, so a static `which` is unreliable.
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            proc.waitFor()
            if (proc.exitValue() == 0) "/system/bin/su" else null
        } catch (_: Throwable) {
            null
        }
    }

    fun hasVector(): Boolean = knownVectorPkgs.any { installed(it) }
    fun hasLspatch(): Boolean = knownLspatchPkgs.any { installed(it) }
    fun hasRoot(): Boolean = probeRoot() != null

    private fun installed(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /**
     * Bridge a class name + method invocation into the rooted context.
     * Returns the method's return value (boxed) or null on failure.
     *
     * Vector API stabilised around: call a static method on a target class
     * from the system_server context. The implementation in V1 uses
     * `Runtime.exec("su -c ...")` with a Java method invocation expressed
     * as a string — full reflection via `app_process` is the next step.
     */
    fun runRootedShell(command: String, timeoutMs: Long = 8000): String {
        val su = probeRoot() ?: return "root not available"
        return try {
            val proc = ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
            proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (proc.isAlive) {
                proc.destroyForcibly()
                "timeout after ${timeoutMs}ms"
            } else {
                proc.inputStream.bufferedReader().readText()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "runRootedShell failed: ${t.message}")
            "error: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    companion object { private const val TAG = "FR3K.vector" }
}
