package com.mcpintelligence.fr3k.protocol

import kotlinx.serialization.Serializable

/**
 * Hermes / FR3K agent request payload. Carried inside an envelope of type `agent.ask`.
 */
@Serializable
data class AgentAskRequest(
    val prompt: String,
    val model: String? = null,
    val profile: AgentProfile = AgentProfile.NORMAL,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val context: AgentContext? = null,
    val attachments: List<AgentAttachment> = emptyList(),
)

@Serializable
enum class AgentProfile {
    /** Default — use Hermes routing. */
    NORMAL,
    /** Speed-optimised — short replies. */
    FAST,
    /** No data leaves the device — local AI only. */
    PRIVATE,
    /** No cloud dependency — local first, offline-tolerant. */
    OFFLINE,
    /** Wider search; web tools allowed. */
    RESEARCH,
    /** Code-focused — favours tools and code interpreters. */
    CODE,
    /** Cheap models only. */
    CHEAP,
}

@Serializable
data class AgentContext(
    val sourcePackage: String? = null,
    val sourceActivity: String? = null,
    val url: String? = null,
    val selectedText: String? = null,
    val fullText: String? = null,
    val location: Fr3kLocation? = null,
    val screenshotUri: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentAttachment(
    val name: String,
    val mimeType: String,
    val uri: String,
    val sizeBytes: Long? = null,
)

/**
 * Agent response payload. Hermes may return plain text, structured actions,
 * follow-up commands, or even file URIs for the HUD to deliver.
 */
@Serializable
data class AgentAskResponse(
    val text: String,
    val format: ResponseFormat = ResponseFormat.PLAIN,
    val actions: List<AgentAction> = emptyList(),
    val followUps: List<String> = emptyList(),
    val tokensUsed: Int? = null,
    val model: String? = null,
)

@Serializable
enum class ResponseFormat { PLAIN, MARKDOWN, JSON }

/**
 * Agent-suggested follow-up action. These are PROPOSALS — FR3K core runs policy checks
 * before execution.
 */
@Serializable
data class AgentAction(
    val id: String,
    val title: String,
    val command: String,
    val arguments: Map<String, String> = emptyMap(),
    val requiresCapabilities: List<String> = emptyList(),
    val requiresConfirmation: Boolean = true,
)

/** Standardised location representation. Mirrors the §25 brief. */
@Serializable
data class Fr3kLocation(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val accuracyM: Float? = null,
    val altitudeM: Double? = null,
    val bearingDeg: Float? = null,
    val speedMps: Float? = null,
    val provider: String = "unknown",
    val timestampMs: Long = 0L,
) {
    val hasGpsLock: Boolean get() = accuracyM != null && accuracyM <= 25f
}