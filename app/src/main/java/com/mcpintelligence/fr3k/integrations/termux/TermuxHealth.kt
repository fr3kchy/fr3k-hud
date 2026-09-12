package com.mcpintelligence.fr3k.integrations.termux

import android.content.Context
import android.content.pm.PackageManager
import com.mcpintelligence.fr3k.integrations.IntegrationState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Termux health truth (ANDROID-TESTING-PASS.md §4 + §9).
 *
 * ONE probe path is used by every surface (QuickHud, diagnostics, future
 * Integrations panel): package presence → RUN_COMMAND permission → a REAL
 * `echo ok` round-trip through [TermuxBridge]. Surfaces never invent a
 * Termux status from capability-registry strings — a UI state is green
 * only when the returned [IntegrationState.operational] is true.
 *
 * The 10s cache means repeated re-renders (list scroll, recomposition)
 * do not fire Termux IPC. [invalidate] forces a fresh probe (e.g. after
 * the user grants the permission in Settings and comes back).
 */
object TermuxHealth {

    /** Visual severity for a status label — UI maps this to a colour. */
    enum class TermuxStatusLevel { OK, WARN, ERR }

    data class TermuxStatusInfo(val label: String, val level: TermuxStatusLevel)

    private const val CACHE_MS = 10_000L
    private const val PROBE_TIMEOUT_MS = 5_000L

    @Volatile private var last: IntegrationState = IntegrationState.Unknown
    @Volatile private var probedAt = 0L

    /** Last probed state — safe to render before the first probe completes. */
    fun lastKnown(): IntegrationState = last

    /** Drop the cache so the next [probe] re-runs. */
    fun invalidate() {
        probedAt = 0L
    }

    /**
     * Probe Termux health. Suspends up to [PROBE_TIMEOUT_MS]; never throws.
     * The result is cached for [CACHE_MS].
     */
    suspend fun probe(context: Context, bridge: TermuxBridge): IntegrationState {
        val now = System.currentTimeMillis()
        if (now - probedAt < CACHE_MS) return last
        val started = now
        val state = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { doProbe(context, bridge, started) }
        } ?: IntegrationState.Stalled(lastProbeAt = started)
        last = state
        probedAt = System.currentTimeMillis()
        return state
    }

    private suspend fun doProbe(context: Context, bridge: TermuxBridge, started: Long): IntegrationState {
        if (!isInstalled(context)) return IntegrationState.Missing

        // Permission gate — the bridge itself refuses to run without it, but
        // we distinguish "permission missing" from "started but broken" so the
        // UI can point the user at the explicit grant step.
        if (!bridge.hasRunCommandPermission()) {
            return IntegrationState.Partial(
                failureCode = "permission_required",
                installed = true,
                serviceLive = false,
                lastProbeAt = started,
            )
        }

        // REAL round-trip through Termux's RunCommandService. `echo ok` is
        // chosen so stdout parity proves the full path works end-to-end.
        val result = bridge.runRaw("echo ok", 4_000)
        val latency = System.currentTimeMillis() - started
        return when {
            result.exitCode == 124 -> IntegrationState.Stalled(lastProbeAt = started)
            result.exitCode != 0 || result.stdout.trim() != "ok" -> IntegrationState.Partial(
                failureCode = "probe_failed",
                installed = true,
                serviceLive = true,
                latencyMs = latency,
                version = version(context),
                lastProbeAt = started,
            )
            else -> IntegrationState.Healthy(
                installed = true,
                serviceLive = true,
                authorised = true,
                latencyMs = latency,
                version = version(context),
                lastProbeAt = started,
            )
        }
    }

    private fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TermuxCommandContract.PACKAGE, 0)
    }.isSuccess

    private fun version(context: Context): String? = runCatching {
        context.packageManager.getPackageInfo(TermuxCommandContract.PACKAGE, 0).versionName
    }.getOrNull()

    /** Human label + severity for a state — the only label source for Termux UI. */
    fun statusLabel(state: IntegrationState): TermuxStatusInfo = when (state) {
        IntegrationState.Unknown -> TermuxStatusInfo("NOT CHECKED", TermuxStatusLevel.WARN)
        IntegrationState.Missing -> TermuxStatusInfo("NOT INSTALLED", TermuxStatusLevel.ERR)
        is IntegrationState.ServerStarting -> TermuxStatusInfo("STARTING", TermuxStatusLevel.WARN)
        is IntegrationState.Partial -> when (state.failureCode) {
            "permission_required" -> TermuxStatusInfo("PERMISSION REQUIRED", TermuxStatusLevel.WARN)
            "probe_failed" -> TermuxStatusInfo("PROBE FAILED", TermuxStatusLevel.ERR)
            "no_bridge" -> TermuxStatusInfo("NO BRIDGE", TermuxStatusLevel.ERR)
            else -> TermuxStatusInfo(
                if (state.failureCode.isNullOrBlank()) "DEGRADED" else state.failureCode.uppercase(),
                TermuxStatusLevel.WARN,
            )
        }
        is IntegrationState.Healthy -> TermuxStatusInfo("CONNECTED", TermuxStatusLevel.OK)
        is IntegrationState.Stale -> TermuxStatusInfo("SERVICE STOPPED", TermuxStatusLevel.ERR)
        is IntegrationState.Stalled -> TermuxStatusInfo("TIMEOUT", TermuxStatusLevel.ERR)
    }
}