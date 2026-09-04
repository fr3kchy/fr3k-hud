package com.mcpintelligence.fr3k.integrations.termux

import android.content.Intent
import android.os.Bundle

/**
 * Pure parser that turns the raw result of Termux's `RunCommandService`
 * into a structured value the rest of the codebase can consume.
 *
 * The parser is split into two layers so the JVM unit tests do not need
 * Android's mocked-Bundle machinery:
 *
 *  - [parseIntent] is the Android-facing entry point used by
 *    [TermuxResultReceiver]; it extracts the primitives and delegates to
 *    [parseFields].
 *  - [parseFields] is the pure logic — given `(executionId, stdout,
 *    stderr, exitCode, errorMessage)` it returns a [ParsedResult]. JVM
 *    tests pin [parseFields] directly; the Android-bundle marshalling is
 *    trivial enough to be exercised by code review.
 *
 * Keeping the rules in one place is the whole point — if Termux changes
 * its keys or marker strings, only this file changes.
 */
object TermuxResultParser {

    /**
     * Result of one Termux execution.
     */
    data class ParsedResult(
        val executionId: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val truncated: Boolean,
        val errorMessage: String,
        val outcome: Outcome,
    )

    enum class Outcome {
        /** We got a result bundle for our execution id. */
        OK,

        /** We got a result but stdout/stderr exceeded Termux's cap. */
        TRUNCATED,

        /** Result present but exit code was non-zero / `errmsg` set. */
        FAILED,

        /** Bundle missing or for a different execution id. */
        UNKNOWN,
    }

    // ---- Android-facing entry point ----

    /**
     * Parse the given [intent] as a Termux result for [executionId].
     *
     * The intent's `result` bundle uses these keys:
     *  - `stdout`   — String, may be empty
     *  - `stderr`   — String, may be empty
     *  - `exitCode` — Int, may be -1
     *  - `errmsg`   — String, may be empty; non-empty means Termux hit
     *                 an internal limit (e.g. output truncated) or the
     *                 service could not even run the command.
     */
    fun parseIntent(intent: Intent?, executionId: Int): ParsedResult {
        if (intent == null) return unknown(executionId)

        val inner = intent.getBundleExtra(RESULT_BUNDLE)
        return parseFields(
            executionId = executionId,
            innerBundlePresent = inner != null,
            stdout = inner?.getString(RESULT_STDOUT).orEmpty(),
            stderr = inner?.getString(RESULT_STDERR).orEmpty(),
            exitCode = inner?.getInt(RESULT_EXIT_CODE, -1) ?: -1,
            errorMessage = inner?.getString(RESULT_ERRMSG).orEmpty(),
        )
    }

    // ---- pure logic (unit-tested) ----

    /**
     * Pure parser used by both [parseIntent] and the JVM-only tests.
     *
     * @param executionId        the request id we sent in
     * @param innerBundlePresent true if the intent had a result bundle at all
     * @param stdout             captured stdout
     * @param stderr             captured stderr
     * @param exitCode           process exit code; -1 if unknown
     * @param errorMessage       raw `errmsg` from Termux, when present
     */
    fun parseFields(
        executionId: Int,
        innerBundlePresent: Boolean,
        stdout: String,
        stderr: String,
        exitCode: Int,
        errorMessage: String,
    ): ParsedResult {
        if (!innerBundlePresent) {
            return ParsedResult(
                executionId = executionId,
                stdout = "",
                stderr = "",
                exitCode = -1,
                truncated = false,
                errorMessage = "",
                outcome = Outcome.FAILED,
            )
        }

        val truncated = errorMessage.contains(TRUNCATION_MARKER, ignoreCase = true)
        // When stderr is empty but Termux reported an error message,
        // surface the error message as stderr so callers can treat them
        // uniformly. The original receiver did this fallback.
        val finalStderr = if (stderr.isBlank() && errorMessage.isNotBlank()) {
            errorMessage
        } else {
            stderr
        }

        // Classification: truncated > any error message > non-zero exit > ok.
        // A non-empty `errmsg` that is not a truncation marker is a
        // warning the command completed with — callers must not treat
        // exit 0 with a warning as success.
        val outcome = when {
            truncated -> Outcome.TRUNCATED
            errorMessage.isNotBlank() -> Outcome.FAILED
            exitCode == 0 -> Outcome.OK
            else -> Outcome.FAILED
        }

        return ParsedResult(
            executionId = executionId,
            stdout = stdout,
            stderr = finalStderr,
            exitCode = exitCode,
            truncated = truncated,
            errorMessage = errorMessage,
            outcome = outcome,
        )
    }

    private fun unknown(executionId: Int): ParsedResult = ParsedResult(
        executionId = executionId,
        stdout = "",
        stderr = "",
        exitCode = -1,
        truncated = false,
        errorMessage = "",
        outcome = Outcome.UNKNOWN,
    )

    // ---- constants (kept in sync with TermuxResultReceiver) ----

    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERRMSG = "errmsg"

    /**
     * Substring match on `errmsg` to detect that Termux truncated the
     * output because it exceeded the bundle cap.
     */
    const val TRUNCATION_MARKER = "stdout length exceeded"
}