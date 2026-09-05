package com.mcpintelligence.fr3k.integrations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lint-style contract that pins how integration adapters may be invoked
 * from the UI layer.
 *
 * Rule: any call to a blocking integration adapter (`runRaw`,
 * `pingBinder`, `probeRoot`, filesystem hashing) inside a click-listener
 * body OR inside the activity's `onCreate` build phase is a UI-freeze
 * bug. The fix is always the same: dispatch on `Dispatchers.IO` via
 * `lifecycleScope` and either show a RUNNING state or disable the
 * clicked control.
 *
 * The plan §6 enumerates the four canonical offenders; we enforce that
 * `IntegrationsActivity.kt` and `Fr3kTerminalOverlay.kt` never invoke
 * them on the main thread.
 */
class NoBlockingUiContractTest {

    private val offenders = listOf(
        "shizuku.pingBinder()",
        "shizuku.probeRoot()",
        "termux.runRaw(",
        "termux.runBlocking(",
        "lspatch.hash",
        "morphe.hash",
    )

    @Test fun integrationsActivityDoesNotBlockInClickListeners() {
        val source = readFile("app/src/main/java/com/mcpintelligence/fr3k/ui/integrations/IntegrationsActivity.kt")
        // Find every `setOnClickListener { ... }` block (depth-aware via
        // simple `{`/`}` counter that tolerates nested braces) and check
        // none of them contain a blocking adapter call.
        val blockBodies = extractClickHandlerBodies(source)
        assertTrue(
            "IntegrationsActivity must define at least one setOnClickListener " +
                "for the lint to be meaningful",
            blockBodies.isNotEmpty(),
        )
        for (body in blockBodies) {
            for (needle in offenders) {
                assertFalse(
                    "IntegrationsActivity setOnClickListener body must not " +
                        "call $needle inline — wrap in lifecycleScope.launch",
                    body.contains(needle),
                )
            }
        }
    }

    @Test fun integrationsActivityHasLifecycleScopeOrRunOnIo() {
        val source = readFile("app/src/main/java/com/mcpintelligence/fr3k/ui/integrations/IntegrationsActivity.kt")
        val uses = source.contains("lifecycleScope") ||
            source.contains("withContext(Dispatchers.IO")
        assertTrue(
            "IntegrationsActivity must use lifecycleScope / " +
                "withContext(Dispatchers.IO) for integration adapter calls",
            uses,
        )
    }

    @Test fun noBlockingAdapterCallInActivityBuildPhase() {
        // The activity's onCreate / body / layout phase runs on the main
        // thread. A `shizuku.pingBinder()` here freezes the UI even
        // without a click. Move all probe calls into a coroutine.
        val source = readFile("app/src/main/java/com/mcpintelligence/fr3k/ui/integrations/IntegrationsActivity.kt")
        // Allowed contexts: inside lifecycleScope.launch { ... } or
        // withContext(Dispatchers.IO) { ... } or a click-handler coroutine.
        // Lint that no blocking call appears at the top level of the file
        // outside those scopes.
        val stripped = source
            .replace(Regex("""lifecycleScope\.launch\s*\{[\s\S]*?\}\s*\)"""), "/* in scope */")
            .replace(Regex("""withContext\(Dispatchers\.[A-Za-z]+\)\s*\{[\s\S]*?\}\s*\)"""), "/* in scope */")
        for (needle in offenders) {
            assertFalse(
                "IntegrationsActivity must not call $needle outside a " +
                    "lifecycleScope.launch or withContext(Dispatchers.IO) — " +
                    "the onCreate / build phase runs on the main thread",
                stripped.contains(needle),
            )
        }
    }

    @Test fun fr3kTerminalOverlayDispatchesOnIoScope() {
        val source = readFile("app/src/main/java/com/mcpintelligence/fr3k/hud/overlays/Fr3kTerminalOverlay.kt")
        // The overlay already owns a SupervisorJob+IO scope; the test
        // pins that contract so a future refactor that drops the IO
        // dispatcher fails the build.
        val usesIo = source.contains("Dispatchers.IO") &&
            (source.contains("scope.launch") || source.contains("SupervisorJob"))
        assertTrue(
            "Fr3kTerminalOverlay must dispatch integration work onto " +
                "Dispatchers.IO via its existing SupervisorJob scope",
            usesIo,
        )
    }

    // ---------- helpers ----------

    /**
     * Extract every `setOnClickListener { ... }` body as a separate
     * string. Uses a depth-counting brace scanner so nested blocks don't
     * confuse the boundaries.
     */
    private fun extractClickHandlerBodies(source: String): List<String> {
        val out = mutableListOf<String>()
        val regex = Regex("""setOnClickListener\s*\{""")
        for (match in regex.findAll(source)) {
            val start = match.range.last + 1
            var depth = 1
            var i = start
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            if (depth == 0) {
                out.add(source.substring(start, i - 1))
            }
        }
        return out
    }

    private fun readFile(relativePath: String): String {
        val f = java.io.File("/home/parrot/repos/fr3k-hud/$relativePath")
        if (!f.exists()) error("missing file: ${f.absolutePath}")
        return f.readText()
    }
}