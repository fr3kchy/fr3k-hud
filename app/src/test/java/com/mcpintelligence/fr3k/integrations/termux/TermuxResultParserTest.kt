package com.mcpintelligence.fr3k.integrations.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Locks the behaviour of [TermuxResultParser.parseFields] (pure) and the
 * result-delivery invariants of [TermuxBridge].
 *
 * The Android-bundle marshalling layer above ([TermuxResultParser.parseIntent])
 * is trivial enough that code review is sufficient; the JVM tests pin the
 * classification rules that drive every caller-visible field.
 */
class TermuxResultParserTest {

    // ---------- parser ----------

    @Test fun parsesHappyPath() {
        val parsed = TermuxResultParser.parseFields(
            executionId = 7,
            innerBundlePresent = true,
            stdout = "hello\n",
            stderr = "",
            exitCode = 0,
            errorMessage = "",
        )
        assertEquals(7, parsed.executionId)
        assertEquals("hello\n", parsed.stdout)
        assertEquals("", parsed.stderr)
        assertEquals(0, parsed.exitCode)
        assertFalse(parsed.truncated)
        assertEquals("", parsed.errorMessage)
        assertEquals(TermuxResultParser.Outcome.OK, parsed.outcome)
    }

    @Test fun parsesNonZeroExitCode() {
        val parsed = TermuxResultParser.parseFields(
            executionId = 8,
            innerBundlePresent = true,
            stdout = "partial",
            stderr = "fail",
            exitCode = 2,
            errorMessage = "",
        )
        assertEquals(2, parsed.exitCode)
        assertEquals("partial", parsed.stdout)
        assertEquals("fail", parsed.stderr)
        assertEquals(TermuxResultParser.Outcome.FAILED, parsed.outcome)
    }

    @Test fun parsesTruncatedFlag() {
        // Termux sets `errmsg` to "stdout length exceeded ..." when it
        // caps the bundle size. Surface that as an explicit truncation flag
        // instead of pretending the result is complete.
        val parsed = TermuxResultParser.parseFields(
            executionId = 9,
            innerBundlePresent = true,
            stdout = "...truncated",
            stderr = "",
            exitCode = 0,
            errorMessage = "stdout length exceeded 50000",
        )
        assertTrue(parsed.truncated)
        assertEquals(TermuxResultParser.Outcome.TRUNCATED, parsed.outcome)
        assertEquals("stdout length exceeded 50000", parsed.errorMessage)
    }

    @Test fun fallsBackToErrorMessageWhenStderrBlank() {
        // RunCommandService sometimes populates errmsg instead of stderr.
        // The receiver historically did this fallback; keep the behaviour
        // so callers can treat them uniformly.
        val parsed = TermuxResultParser.parseFields(
            executionId = 10,
            innerBundlePresent = true,
            stdout = "",
            stderr = "",
            exitCode = 127,
            errorMessage = "executable not found",
        )
        assertEquals("executable not found", parsed.stderr)
        assertEquals(TermuxResultParser.Outcome.FAILED, parsed.outcome)
    }

    @Test fun handlesMissingResultBundle() {
        val parsed = TermuxResultParser.parseFields(
            executionId = 12,
            innerBundlePresent = false,
            stdout = "",
            stderr = "",
            exitCode = 0,
            errorMessage = "",
        )
        // Defensive: a malformed result should not crash. Return a
        // structured FAILED with empty stdout/stderr.
        assertEquals(TermuxResultParser.Outcome.FAILED, parsed.outcome)
        assertEquals("", parsed.stdout)
        assertEquals("", parsed.stderr)
        assertEquals(-1, parsed.exitCode)
    }

    @Test fun truncationTrumpsNonZeroExit() {
        // If Termux both truncated and exited non-zero, surface the
        // truncation as the more actionable outcome — the caller likely
        // wants to retry with a smaller output target, not chase the
        // exit code.
        val parsed = TermuxResultParser.parseFields(
            executionId = 13,
            innerBundlePresent = true,
            stdout = "...",
            stderr = "",
            exitCode = 2,
            errorMessage = "stdout length exceeded 50000",
        )
        assertTrue(parsed.truncated)
        assertEquals(TermuxResultParser.Outcome.TRUNCATED, parsed.outcome)
    }

    @Test fun exitCodeZeroWithErrorMessageIsFailedNotOk() {
        // Some Termux builds write to errmsg but still report exit 0 when
        // the command completed with a warning. Classify that as FAILED
        // so callers do not silently swallow the warning.
        val parsed = TermuxResultParser.parseFields(
            executionId = 14,
            innerBundlePresent = true,
            stdout = "",
            stderr = "",
            exitCode = 0,
            errorMessage = "warning: deprecated flag",
        )
        assertEquals(TermuxResultParser.Outcome.FAILED, parsed.outcome)
        assertFalse(parsed.truncated)
    }

    // ---------- duplicate delivery ----------

    @Test fun duplicateDeliveryIsNoOp() {
        // Register a result slot, complete it once, then try to complete it
        // again with different data. The second call must not overwrite
        // the first.
        val slot = TermuxBridge.ResultSlot()
        slot.complete(TermuxBridge.Result("first", "", 0))
        slot.complete(TermuxBridge.Result("second", "", 1))
        val first = slot.firstResult
        assertNotNull("first result must be set after complete()", first)
        assertEquals("first", first!!.stdout)
        assertEquals(0, first.exitCode)
        // A listener registered for re-deliveries fires for every ignored
        // duplicate; we use it only for logging, never for state mutation.
        var secondInvocations = 0
        slot.onSecondDelivery { secondInvocations++ }
        slot.complete(TermuxBridge.Result("third", "", 2))
        assertEquals(1, secondInvocations)
        // And the first result is still untouched.
        assertEquals("first", slot.firstResult!!.stdout)
        assertEquals(0, slot.firstResult!!.exitCode)
    }

    // ---------- source lint ----------

    @Test fun bridgeDoesNotBlockOnCountDownLatch() {
        // The plan mandates that TermuxBridge.kt never call
        // CountDownLatch.await — it must be cooperative-cancellation
        // (suspend + withTimeoutOrNull) so the UI thread is never wedged.
        val source = readBridgeSource()
        assertFalse(
            "TermuxBridge.kt must not call CountDownLatch.await — use " +
                "suspend + withTimeoutOrNull instead",
            Regex("""CountDownLatch\s*\(.*\)\.await""").containsMatchIn(source),
        )
    }

    @Test fun bridgeUsesContractConstants() {
        // After Task 5 the bridge references the typed contract instead
        // of typed literals. Lint this so a regression (typed-literal
        // extras re-introduced) fails the build.
        val source = readBridgeSource()
        val typedLiteral = Regex("""putExtra\(\s*"com\.termux\.RUN_COMMAND_""")
        assertFalse(
            "TermuxBridge.kt must use TermuxCommandContract.* constants " +
                "instead of typed \"com.termux.RUN_COMMAND_*\" literals",
            typedLiteral.containsMatchIn(source),
        )
    }

    // ---------- helpers ----------

    private fun readBridgeSource(): String {
        val path = "/home/parrot/repos/fr3k-hud/app/src/main/java/" +
            "com/mcpintelligence/fr3k/integrations/termux/TermuxBridge.kt"
        val f = java.io.File(path)
        if (!f.exists()) error("TermuxBridge.kt not found at $path")
        return f.readText()
    }
}