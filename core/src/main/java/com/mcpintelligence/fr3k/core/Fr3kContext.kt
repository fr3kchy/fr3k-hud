package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.AgentContext

/**
 * Explicit, minimal execution context handed to every command.
 *
 * Anything potentially sensitive is opt-in — commands must declare what they
 * use, the registry enforces policy.
 */
data class Fr3kContext(
    val deviceId: String,
    val now: Long = System.currentTimeMillis(),
    val consentLevel: ConsentLevel = ConsentLevel.NORMAL,
    val foregroundPackage: String? = null,
    val foregroundActivity: String? = null,
    val currentUrl: String? = null,
    val selectedText: String? = null,
    val fullText: String? = null,
    val location: FrappeLocationRef_unused = FrappeLocationRef_unused.NONE,
    val screenshotUri: String? = null,
    val enabledCapabilities: Set<String> = emptySet(),
    val requestId: String = java.util.UUID.randomUUID().toString(),
) {
    fun has(capabilityId: String): Boolean = enabledCapabilities.contains(capabilityId)

    fun hasAll(capabilityIds: Collection<String>): Boolean =
        capabilityIds.all { has(it) }

    fun asAgentContext(): AgentContext = AgentContext(
        sourcePackage = foregroundPackage,
        sourceActivity = foregroundActivity,
        url = currentUrl,
        selectedText = selectedText,
        fullText = fullText,
        location = null,
        screenshotUri = screenshotUri,
    )
}

/** V1 placeholder for location reference; V2 wires real Fr3kLocation. */
enum class FrappeLocationRef_unused { NONE }