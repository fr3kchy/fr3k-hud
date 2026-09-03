package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability

/**
 * FR3K command contract.
 *
 * Commands are first-class: every action (send to Hermes, share to mesh, query
 * location) is a command. The command palette is the canonical UI, but the same
 * commands are reachable from share sheets, the HUD, automation triggers,
 * and agent-suggested actions.
 */
interface Fr3kCommand {
    /** Stable id, e.g. "share.mesh.send". */
    val id: String

    /** Display title for the palette / share sheet. */
    val title: String

    /** Long description shown in help / dev panel. */
    val description: String

    /** Capability IDs the command requires to execute. */
    val requiredCapabilities: Set<String>

    /** Optional keywords to improve fuzzy search (e.g. "termux", "mesh"). */
    val keywords: Set<String> get() = emptySet()

    /** Whether this command can be invoked without user confirmation. */
    val isDestructive: Boolean get() = false

    /** Plugin id this command is owned by — used for permission / routing. */
    val pluginId: String

    /**
     * Execute the command. Implementations MUST:
     *  - check capabilities before doing work (defence in depth)
     *  - never throw across plugin boundaries (return CommandResult.Failed)
     *  - respect [Fr3kContext.consentLevel] for sensitive operations
     *  - never perform silent network IO — return what they did
     */
    suspend fun execute(context: Fr3kContext, args: Map<String, String>): CommandResult
}

/** Categorised result returned by every command execution. */
sealed interface CommandResult {
    data class Ok(val message: String, val data: Map<String, String> = emptyMap()) : CommandResult
    data class Failed(val reason: String, val code: Int = 1) : CommandResult
    data class Cancelled(val reason: String = "user cancelled") : CommandResult
    data class NeedsConfirmation(
        val summary: String,
        val capabilitiesUsed: Set<String>,
    ) : CommandResult
}

/** User-supplied consent level for context firewall (§11). */
enum class ConsentLevel {
    /** No information leaves the device. */
    LOCAL_ONLY,
    /** Personally identifiable information stripped before sending. */
    PRIVATE,
    /** Normal usage. */
    NORMAL,
    /** Wider web access, file fetches, etc. */
    RESEARCH,
}