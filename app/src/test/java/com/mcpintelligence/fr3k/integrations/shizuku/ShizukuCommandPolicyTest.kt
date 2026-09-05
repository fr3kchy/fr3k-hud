package com.mcpintelligence.fr3k.integrations.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks [ShizukuCommandExecutor] against a small, typed, policy-scoped
 * set of operations — never arbitrary `sh -c` strings.
 *
 * The plan §8: "Reject raw shell strings; allow only typed operations
 * initially: GetPackageInfo, ListPackageSplits, InstallApprovedApk,
 * UninstallTestFixture, ReadSystemSetting. Tests must deny package
 * deletion, security-setting changes, arbitrary sh -c, and every
 * restricted target category."
 */
class ShizukuCommandPolicyTest {

    // ---------- allowed operations ----------

    @Test fun allowsGetPackageInfo() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "GetPackageInfo",
            arg = "com.mcpintelligence.fr3k.hud",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Allowed)
        assertTrue((op as ShizukuCommandExecutor.Operation.Allowed).safe)
    }

    @Test fun allowsListPackageSplits() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "ListPackageSplits",
            arg = "com.termux",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Allowed)
    }

    @Test fun allowsInstallApprovedApk() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "InstallApprovedApk",
            arg = "/data/user/0/com.mcpintelligence.fr3k.hud/files/approved.apk",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Allowed)
    }

    @Test fun allowsReadSystemSetting() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "ReadSystemSetting",
            arg = "settings:global:airplane_mode_on",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Allowed)
    }

    // ---------- denied operations ----------

    @Test fun deniesArbitraryShell() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "Shell",
            arg = "id",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Denied)
    }

    @Test fun deniesPackageDeletion() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "UninstallTestFixture",
            arg = "com.shady.bank",
        )
        // UninstallTestFixture is allowed — but only for the owned fixture
        // package. Any other target is denied by the policy check.
        assertTrue(op is ShizukuCommandExecutor.Operation.Denied)
    }

    @Test fun deniesSecuritySettingChange() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "WriteSystemSetting",
            arg = "settings:secure:mock_location",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Denied)
    }

    @Test fun deniesRestrictedTargetCategories() {
        // Banking / payment / credential / DRM / security / system-critical
        // must be denied for InstallApprovedApk and every op that touches a
        // target package.
        val deniedTargets = listOf(
            "com.shady.bank",              // banking
            "com.apple.payment",           // payment
            "com.resurrection.passwordmanager", // password manager
            "com.google.android.gms",      // system-critical / integrity
            "com.google.android.apps.authenticator2", // authenticator
            "com.drmprotector.licence",    // DRM
        )
        for (t in deniedTargets) {
            val op = ShizukuCommandExecutor.Operation.from("InstallApprovedApk", "/data/approved.apk")
            assertTrue(
                "target $t must be denied",
                !ShizukuCommandExecutor.policyTargetsApproved(t),
            )
        }
    }

    @Test fun allowsOwnedFixtureTarget() {
        // The owned fixture package is the only one InstallApprovedApk may
        // hit — repository-owned, allowlisted.
        assertTrue(
            "owned fixture must be approved",
            ShizukuCommandExecutor.policyTargetsApproved(ShizukuCommandExecutor.FIXTURE_PACKAGE),
        )
    }

    // ---------- typed operation enum ----------

    @Test fun unknownRequestIsDenied() {
        val op = ShizukuCommandExecutor.Operation.from(
            request = "rm -rf /",
            arg = "",
        )
        assertTrue(op is ShizukuCommandExecutor.Operation.Denied)
    }

    @Test fun deniedOperationCarriesReason() {
        val op = ShizukuCommandExecutor.Operation.from("Shell", "id") as ShizukuCommandExecutor.Operation.Denied
        assertFalse(op.reason.isBlank())
        assertTrue(op.reason.isNotBlank())
    }
}