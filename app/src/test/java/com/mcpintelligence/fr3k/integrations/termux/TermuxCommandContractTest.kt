package com.mcpintelligence.fr3k.integrations.termux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the official Termux `RunCommandService` contract in one place.
 *
 * Every magic string the rest of the codebase needs to talk to Termux must
 * come from [TermuxCommandContract]. A typo or guessed-lowercase key silently
 * breaks the integration, so this contract test makes the change explicit.
 *
 * If the installed Termux build changes its keys, bump both the constant and
 * this test in the same commit and prove it on a physical device.
 */
class TermuxCommandContractTest {

    @Test fun packageIsComTermux() {
        assertEquals("com.termux", TermuxCommandContract.PACKAGE)
    }

    @Test fun serviceIsRunCommandService() {
        assertEquals(
            "com.termux.app.RunCommandService",
            TermuxCommandContract.SERVICE,
        )
    }

    @Test fun actionIsRunCommand() {
        assertEquals("com.termux.RUN_COMMAND", TermuxCommandContract.ACTION)
    }

    @Test fun allExtrasAreUppercase() {
        // Termux's official result bundle keys are case-sensitive. Lowercase
        // or camelCase variants are silently ignored.
        val keys = listOf(
            TermuxCommandContract.EXTRA_PATH,
            TermuxCommandContract.EXTRA_ARGUMENTS,
            TermuxCommandContract.EXTRA_WORKDIR,
            TermuxCommandContract.EXTRA_BACKGROUND,
            TermuxCommandContract.EXTRA_SESSION_ACTION,
            TermuxCommandContract.EXTRA_PENDING_INTENT,
        )
        for (k in keys) {
            assertTrue(
                "extra key '$k' must start with com.termux.RUN_COMMAND_",
                k.startsWith("com.termux.RUN_COMMAND_"),
            )
            // No lowercase-only segments, no camelCase word boundaries.
            assertFalse(
                "extra key '$k' must not contain lowercase word boundaries",
                Regex("[a-z][A-Z]").containsMatchIn(k),
            )
        }
    }

    @Test fun noLegacyLowercaseCommandExtra() {
        // The historical, non-official "com.termux.RUN_COMMAND.command"
        // key must not be referenced. Tests asserting absence of the typo
        // are the cheapest way to keep it from creeping back in.
        val all = listOf(
            TermuxCommandContract.PACKAGE,
            TermuxCommandContract.SERVICE,
            TermuxCommandContract.ACTION,
            TermuxCommandContract.EXTRA_PATH,
            TermuxCommandContract.EXTRA_ARGUMENTS,
            TermuxCommandContract.EXTRA_WORKDIR,
            TermuxCommandContract.EXTRA_BACKGROUND,
            TermuxCommandContract.EXTRA_SESSION_ACTION,
            TermuxCommandContract.EXTRA_PENDING_INTENT,
        ).joinToString("\n")
        assertFalse(
            "RUN_COMMAND.command lowercase extra must not be in the contract",
            all.contains("RUN_COMMAND.command") ||
                all.contains("run_command.command"),
        )
    }

    @Test fun extrasMatchOfficialUppercaseSet() {
        assertEquals(
            "com.termux.RUN_COMMAND_PATH",
            TermuxCommandContract.EXTRA_PATH,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            TermuxCommandContract.EXTRA_ARGUMENTS,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_WORKDIR",
            TermuxCommandContract.EXTRA_WORKDIR,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_BACKGROUND",
            TermuxCommandContract.EXTRA_BACKGROUND,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_SESSION_ACTION",
            TermuxCommandContract.EXTRA_SESSION_ACTION,
        )
        assertEquals(
            "com.termux.RUN_COMMAND_PENDING_INTENT",
            TermuxCommandContract.EXTRA_PENDING_INTENT,
        )
    }

    @Test fun commandSpecBuilderPreservesArguments() {
        val spec = TermuxCommandContract.CommandSpec(
            path = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("-lc", "echo hi"),
            workDir = "/data/data/com.termux/files/home",
            background = false,
            sessionAction = "0",
        )
        // The arguments array is what gets serialised into EXTRA_ARGUMENTS.
        // Reordering or coalescing would silently change what Termux runs.
        assertArrayEquals(
            arrayOf("-lc", "echo hi"),
            spec.arguments,
        )
        assertEquals("/data/data/com.termux/files/usr/bin/bash", spec.path)
        assertEquals("/data/data/com.termux/files/home", spec.workDir)
        assertEquals(false, spec.background)
        assertEquals("0", spec.sessionAction)
    }

    @Test fun commandSpecRejectsEmptyArguments() {
        // An empty arguments array is meaningless to Termux and almost
        // always indicates a bug at the call site. Refuse it loudly.
        val ex = kotlin.runCatching {
            TermuxCommandContract.CommandSpec(
                path = "/data/data/com.termux/files/usr/bin/bash",
                arguments = emptyArray(),
                workDir = "/data/data/com.termux/files/home",
                background = false,
                sessionAction = "0",
            )
        }.exceptionOrNull()
        assertNotNull("empty arguments must throw", ex)
    }

    @Test fun commandSpecRejectsBlankPath() {
        val ex = kotlin.runCatching {
            TermuxCommandContract.CommandSpec(
                path = "   ",
                arguments = arrayOf("-c", "true"),
                workDir = "/data/data/com.termux/files/home",
                background = false,
                sessionAction = "0",
            )
        }.exceptionOrNull()
        assertNotNull("blank path must throw", ex)
    }
}