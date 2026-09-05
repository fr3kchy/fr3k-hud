package com.mcpintelligence.fr3k.integrations.shizuku

import android.os.Parcel
import android.os.RemoteException
import android.util.Log

/**
 * Policy-scoped Shizuku executor — replaces the raw `Parcel.transact(0x0A)`
 * path in [ShizukuAdapter.shellCommand] with a narrow, typed set of
 * operations. The plan §8: "Reject raw shell strings; allow only typed
 * operations initially." No arbitrary `sh -c`, no package deletion, no
 * security-setting changes, no restricted targets.
 *
 * This keeps Shizuku's elevated privilege strictly bounded: the only way
 * to reach system-level capability is through an [Allowed] operation whose
 * target passes [policyTargetsApproved]. Everything a plugin or the UI
 * needs must be expressed as one of these typed requests.
 */
object ShizukuCommandExecutor {

    /** The single repository-owned package `InstallApprovedApk` may hit. */
    const val FIXTURE_PACKAGE = "com.mcpintelligence.fr3k.patchfixture"

    /**
     * Outcome of a policy check for one operation. [Allowed] carries a
     * prototype command we can safely hand to Shizuku; [Denied] carries a
     * human-readable reason and is never executed.
     */
    sealed class Operation {
        val safe: Boolean get() = this is Allowed
        data class Allowed(val kind: Kind, val target: String) : Operation()
        data class Denied(val reason: String) : Operation()

        enum class Kind {
            GetPackageInfo,
            ListPackageSplits,
            InstallApprovedApk,
            UninstallTestFixture,
            ReadSystemSetting,
        }

        companion object {
            /** Map a raw request + arg to a policy decision. */
            fun from(request: String, arg: String): Operation =
                when (request) {
                    "GetPackageInfo" -> Allowed(Kind.GetPackageInfo, arg)
                    "ListPackageSplits" -> Allowed(Kind.ListPackageSplits, arg)
                    "InstallApprovedApk" ->
                        if (policyTargetsApproved(arg)) {
                            Allowed(Kind.InstallApprovedApk, arg)
                        } else {
                            Denied("restricted target for InstallApprovedApk: $arg")
                        }
                    "UninstallTestFixture" ->
                        if (arg == FIXTURE_PACKAGE) {
                            Allowed(Kind.UninstallTestFixture, arg)
                        } else {
                            Denied("UninstallTestFixture only allowed on owned fixture $FIXTURE_PACKAGE")
                        }
                    "ReadSystemSetting" ->
                        if (isReadableSystemSetting(arg)) {
                            Allowed(Kind.ReadSystemSetting, arg)
                        } else {
                            Denied("non-readable system setting: $arg")
                        }
                    else -> Denied("unknown or shell operation rejected: '$request'")
                }
        }
    }

    /** True if [pkg] is the owned patch fixture or a benign target. */
    fun policyTargetsApproved(pkg: String): Boolean {
        if (pkg == FIXTURE_PACKAGE) return true
        // Deny banking/payment/credential/authenticator/security/DRM/system.
        val deniedAny = DENIED_TARGET_MARKERS.any { marker -> pkg.contains(marker, ignoreCase = true) }
            || pkg in SYSTEM_CRITICAL_PACKAGES
        return !deniedAny && pkg.isNotBlank()
    }

    private val DENIED_TARGET_MARKERS = listOf(
        "bank", "pay", "wallet", "password", "browser",
        "authenticator", "2fa", "otp", "credential", "drm",
        "vpn", "security", "devicepolicy", "devicepolicycontroller",
        "play", "gms", "integrity", "samsung", "miui",
    )

    private val SYSTEM_CRITICAL_PACKAGES = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.launcher3", "com.google.android.gms",
        "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.authenticator2",
    )

    /** Read-only settings only (global/secure/system). Never a writer. */
    private fun isReadableSystemSetting(arg: String): Boolean =
        arg.startsWith("settings:") && !arg.contains("=") &&
            listOf("global:", "secure:", "system:").any { arg.contains(it) }

    // ---------- executor (uses typed operations only) ----------

    /**
     * Run a [Operation.Allowed] against the live Shizuku binder. Returns
     * a small provenance object: stdout/stderr version of what the
     * operation observed, exit code, duration ms, and the operation kind.
     * Never accepts a raw shell string.
     */
    fun execute(binder: android.os.IBinder, op: Operation.Allowed): ShResult {
        val start = System.currentTimeMillis()
        return try {
            // Parcel-based call to IShizukuService for typed ops.
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("moe.shizuku.api.IShizukuService")
                writeTypedRequest(data, op)
                binder.transact(OP_EXEC, data, reply, 0)
                reply.readException()
                val result = reply.readString().orEmpty()
                ShResult(ensureMetadata(result, op), "", 0, System.currentTimeMillis() - start, op.kind.name)
            } finally {
                reply.recycle(); data.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "execute ${op.kind} failed: ${t.message}")
            ShResult("", t.message ?: t.javaClass.simpleName, 1, System.currentTimeMillis() - start, op.kind.name)
        }
    }

    private fun writeTypedRequest(data: Parcel, op: Operation.Allowed) {
        when (op.kind) {
            Operation.Kind.GetPackageInfo -> {
                data.writeString("package-info")
                data.writeString(op.target)
            }
            Operation.Kind.ListPackageSplits -> {
                data.writeString("list-splits")
                data.writeString(op.target)
            }
            Operation.Kind.InstallApprovedApk -> {
                data.writeString("install")
                data.writeString(op.target)
            }
            Operation.Kind.UninstallTestFixture -> {
                data.writeString("uninstall-fixture")
                data.writeString(op.target)
            }
            Operation.Kind.ReadSystemSetting -> {
                data.writeString("read-setting")
                data.writeString(op.target)
            }
        }
    }

    private fun ensureMetadata(raw: String, op: Operation.Allowed): String =
        raw.ifBlank { "op=${op.kind.name} target=${op.target}" }

    data class ShResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val provenance: String,
    )

    private const val TAG = "FR3K.shizuku-exec"
    private const val OP_EXEC = 0x0B // distinct from the old raw 0x0A
}