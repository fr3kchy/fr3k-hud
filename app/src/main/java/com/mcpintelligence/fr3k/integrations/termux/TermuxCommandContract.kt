package com.mcpintelligence.fr3k.integrations.termux

/**
 * Authoritative contract for the official Termux `RunCommandService` API.
 *
 * Every magic string the rest of the codebase needs to talk to Termux must
 * come from this object. A typo or guessed-lowercase key silently breaks the
 * integration because Termux ignores extras it does not recognise.
 *
 * The keys here match the documented `RUN_COMMAND_PATH / RUN_COMMAND_ARGUMENTS
 * / RUN_COMMAND_WORKDIR / RUN_COMMAND_BACKGROUND / RUN_COMMAND_SESSION_ACTION
 * / RUN_COMMAND_PENDING_INTENT` set that ships with the Termux:API AAR. They
 * are case-sensitive — using a lowercase or camelCase variant will fail
 * silently and return no result.
 *
 * If the installed Termux build changes these keys, bump both the constants
 * and the contract test in the same commit and prove the change on a
 * physical device.
 */
object TermuxCommandContract {

    /** Termux main package — the APK that owns `RunCommandService`. */
    const val PACKAGE = "com.termux"

    /** Fully-qualified component name of the service that actually runs the command. */
    const val SERVICE = "com.termux.app.RunCommandService"

    /** Intent action used to address the service. */
    const val ACTION = "com.termux.RUN_COMMAND"

    /** Extra key for the absolute path of the executable to invoke. */
    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"

    /** Extra key for the argv array passed to the executable. */
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"

    /** Extra key for the working directory of the spawned process. */
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"

    /** Extra key for the boolean background flag. */
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    /** Extra key for the session action (e.g. `"0"` for no session). */
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    /** Extra key for the PendingIntent that receives the result bundle. */
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /**
     * Pure value type describing one Termux invocation. Construction validates
     * invariants that, if violated, would silently no-op or surface as a
     * TimeoutException downstream.
     */
    data class CommandSpec(
        val path: String,
        val arguments: Array<String>,
        val workDir: String,
        val background: Boolean,
        val sessionAction: String,
    ) {
        init {
            require(path.isNotBlank()) { "path must not be blank" }
            require(arguments.isNotEmpty()) { "arguments must not be empty" }
            require(arguments.none { it.contains('\n') }) {
                "arguments must not contain newlines"
            }
            require(workDir.isNotBlank()) { "workDir must not be blank" }
            require(sessionAction.isNotBlank()) { "sessionAction must not be blank" }
        }

        // Generated data-class equals/hashCode would not consider arrays
        // structurally. We override only because tests rely on the structural
        // compare via assertArrayEquals elsewhere; this keeps the contract
        // explicit.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommandSpec) return false
            return path == other.path &&
                arguments.contentEquals(other.arguments) &&
                workDir == other.workDir &&
                background == other.background &&
                sessionAction == other.sessionAction
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + arguments.contentHashCode()
            result = 31 * result + workDir.hashCode()
            result = 31 * result + background.hashCode()
            result = 31 * result + sessionAction.hashCode()
            return result
        }
    }
}