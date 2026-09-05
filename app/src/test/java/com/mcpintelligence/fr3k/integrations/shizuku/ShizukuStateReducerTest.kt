package com.mcpintelligence.fr3k.integrations.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the [ShizukuState] model and the pure reducer that derives it
 * from observed events. The reducer is the only piece that can be JVM-
 * tested without booting Android or the Shizuku AAR — everything else
 * (the listener wiring, the state-flow exposure) is exercised on a
 * physical device.
 *
 * The plan §7 enumerates six states and rules out a recurring bug
 * where "package installed + OS server process alive + binder callback
 * pending" was being rendered as "manager not installed". This test
 * pins that the reducer never collapses to MISSING while a binder is
 * still being awaited.
 */
class ShizukuStateReducerTest {

    @Test fun missingWhenNothingInstalled() {
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = false),
        )
        assertEquals(ShizukuState.Missing, state)
    }

    @Test fun serverStartingWhenPackageInstalledButBinderAbsent() {
        // The plan rule: package installed + OS server process alive +
        // binder callback pending must NOT render "manager not installed".
        val state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun serverStartingAfterInstallCheckThenOsProcessSeen() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.OsProcessSeen(running = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun binderLivePermissionRequiredAfterBinderReceived() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Unknown,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.BinderLivePermissionRequired, state)
    }

    @Test fun binderLiveWhenPermissionAlreadyGranted() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.ServerStarting,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        assertEquals(ShizukuState.Ready, state)
    }

    @Test fun deniedAfterPermissionRejection() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.BinderLivePermissionRequired,
            event = ShizukuEvent.BinderReceived,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.PermissionResult(granted = false),
        )
        assertEquals(ShizukuState.Denied, state)
    }

    @Test fun deadAfterBinderDies() {
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderDied,
        )
        assertEquals(ShizukuState.Dead, state)
    }

    @Test fun restartTransitionsDeadBackToServerStarting() {
        // After binder death, a new install check should not jump back
        // to Ready — the user still has to wait for the binder.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.BinderDied,
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.InstallCheck(present = true),
        )
        assertEquals(ShizukuState.ServerStarting, state)
    }

    @Test fun binderReceivedIsIdempotent() {
        // The OnBinderReceivedListener can fire more than once (e.g. when
        // the Shizuku service restarts). Receiving twice must NOT change
        // a Ready state back to BinderLivePermissionRequired.
        var state = ShizukuStateReducer.reduce(
            current = ShizukuState.Ready,
            event = ShizukuEvent.PermissionResult(granted = true),
        )
        state = ShizukuStateReducer.reduce(
            current = state,
            event = ShizukuEvent.BinderReceived,
        )
        assertEquals(ShizukuState.Ready, state)
    }

    // ---------- source lint ----------

    @Test fun adapterDoesNotCallActivityRequestPermissionsForShizuku() {
        // The plan §7: remove the Android-version branch that calls
        // Activity.requestPermissions() for Shizuku. Shizuku grants must
        // only go through Shizuku.requestPermission(code) after binder
        // received. A future regression that re-adds an OS permission
        // grant path will fail this lint.
        val source = readAdapterSource()
        val callsOsGrant = source.contains("activity.requestPermissions(") ||
            source.contains(".requestPermissions(arrayOf(\"moe.shizuku")
        assertFalse(
            "ShizukuAdapter must not call Activity.requestPermissions " +
                "for the Shizuku permission — grant via " +
                "Shizuku.requestPermission(code) only",
            callsOsGrant,
        )
    }

    @Test fun bridgeRegistersAllThreeListeners() {
        // The plan §7: application-scoped OnBinderReceivedListener +
        // OnBinderDeadListener + OnRequestPermissionResultListener. The
        // bridge file must register all three at start() time.
        val source = readBridgeSource()
        assertTrue(
            "ShizukuBridge must register OnBinderReceivedListener",
            source.contains("addBinderReceivedListener"),
        )
        assertTrue(
            "ShizukuBridge must register OnBinderDeadListener",
            source.contains("addBinderDeadListener"),
        )
        assertTrue(
            "ShizukuBridge must register OnRequestPermissionResultListener",
            source.contains("addRequestPermissionResultListener"),
        )
    }

    @Test fun applicationStartsShizukuBridge() {
        // The plan §7: ShizukuBridge is started from Fr3kApplication.
        // Without that, the listeners never register and the binder
        // callback never fires for the activity.
        val source = readApplicationSource()
        assertTrue(
            "Fr3kApplication must call ShizukuBridge.start() so the " +
                "listeners register at process entry",
            source.contains("ShizukuBridge.start") ||
                source.contains("ShizukuBridge.get") ||
                source.contains("shizukuBridge.start"),
        )
    }

    // ---------- helpers ----------

    private fun readAdapterSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/integrations/shizuku/ShizukuAdapter.kt")

    private fun readBridgeSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/integrations/shizuku/ShizukuBridge.kt")

    private fun readApplicationSource(): String =
        readFile("app/src/main/java/com/mcpintelligence/fr3k/Fr3kApplication.kt")

    private fun readFile(relativePath: String): String {
        val f = java.io.File("/home/parrot/repos/fr3k-hud/$relativePath")
        if (!f.exists()) error("missing file: ${f.absolutePath}")
        return f.readText()
    }
}