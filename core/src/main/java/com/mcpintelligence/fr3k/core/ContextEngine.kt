package com.mcpintelligence.fr3k.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Context engine (§10). The single in-memory source of the *current* FR3K context.
 *
 * Components (HUD, share receiver, Ask About This, accessibility service, automation)
 * push into this engine. Commands read from it when they execute. The UI shows the
 * current state through [current]; the context firewall reads it before any send.
 *
 * Empty fields are still represented (as null) so the firewall can render a truthful
 * manifest. Nothing leaves the device without an explicit command execution.
 */
class ContextEngine {

    data class Snapshot(
        val sourcePackage: String? = null,
        val sourceActivity: String? = null,
        val url: String? = null,
        val selectedText: String? = null,
        val fullText: String? = null,
        val screenshotUri: String? = null,
        val locationLabel: String? = null,
        val locationLat: Double? = null,
        val locationLon: Double? = null,
        val clipboardText: String? = null,
        val userPrompt: String? = null,
        val updatedAt: Long = 0L,
    ) {
        fun isEmpty(): Boolean =
            sourcePackage == null && url == null && selectedText == null &&
                fullText == null && screenshotUri == null && clipboardText == null &&
                locationLat == null && userPrompt == null

        fun summary(): String = buildList {
            sourcePackage?.let { add("app:$it") }
            url?.let { add("url:${it.take(60)}") }
            selectedText?.let { add("text:${it.take(40)}…") }
            screenshotUri?.let { add("screenshot") }
            locationLat?.let { add("loc:$it,$locationLon") }
            clipboardText?.let { add("clipboard") }
        }.joinToString(" · ")
    }

    private val _current = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    @Synchronized
    fun update(transform: (Snapshot) -> Snapshot) {
        _current.value = transform(_current.value).copy(updatedAt = System.currentTimeMillis())
    }

    fun setForeground(packageName: String?, activityName: String?) = update {
        it.copy(sourcePackage = packageName, sourceActivity = activityName)
    }

    fun setUrl(url: String?) = update { it.copy(url = url) }
    fun setSelectedText(text: String?) = update { it.copy(selectedText = text) }
    fun setFullText(text: String?) = update { it.copy(fullText = text) }
    fun setScreenshot(uri: String?) = update { it.copy(screenshotUri = uri) }
    fun setClipboard(text: String?) = update { it.copy(clipboardText = text) }
    fun setUserPrompt(prompt: String?) = update { it.copy(userPrompt = prompt) }
    fun setLocation(lat: Double?, lon: Double?, label: String?) = update {
        it.copy(locationLat = lat, locationLon = lon, locationLabel = label)
    }

    fun clear() {
        _current.value = Snapshot()
    }

    /** Build a [Fr3kContext] suitable for [Fr3kCommand.execute]. */
    fun toCommandContext(
        deviceId: String,
        capabilities: Set<String>,
        consentLevel: ConsentLevel,
    ): Fr3kContext = with(_current.value) {
        Fr3kContext(
            deviceId = deviceId,
            consentLevel = consentLevel,
            foregroundPackage = sourcePackage,
            foregroundActivity = sourceActivity,
            currentUrl = url,
            selectedText = selectedText,
            fullText = fullText,
            screenshotUri = screenshotUri,
            enabledCapabilities = capabilities,
        )
    }

    /** Render the firewall manifest (§11) — every field with its send-status. */
    fun firewallManifest(): List<FirewallRow> {
        val s = _current.value
        return listOf(
            FirewallRow(label = "APPLICATION", value = s.sourcePackage ?: "—", enabled = s.sourcePackage != null),
            FirewallRow(label = "URL", value = s.url ?: "—", enabled = s.url != null),
            FirewallRow(label = "SELECTED TEXT", value = s.selectedText?.let { it.take(40) + "…" } ?: "—", enabled = s.selectedText != null),
            FirewallRow(label = "FULL TEXT", value = if (s.fullText != null) "${s.fullText.length} chars" else "—", enabled = s.fullText != null),
            FirewallRow(label = "SCREENSHOT", value = s.screenshotUri ?: "—", enabled = s.screenshotUri != null),
            FirewallRow(label = "LOCATION", value = if (s.locationLat != null) "$s.locationLat,$s.locationLon" else "—", enabled = s.locationLat != null),
            FirewallRow(label = "CLIPBOARD", value = s.clipboardText?.let { it.take(40) + "…" } ?: "—", enabled = s.clipboardText != null),
        )
    }

    data class FirewallRow(val label: String, val value: String, val enabled: Boolean)
}