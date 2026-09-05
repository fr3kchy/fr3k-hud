package com.mcpintelligence.fr3k.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured application settings. Non-sensitive data lives in regular preferences,
 * sensitive data lives in SecureStore. This is the typed read of the regular prefs.
 */
class AppSettings {

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        _settings.value = transform(_settings.value)
    }

    /** Plain settings that are safe to log in diagnostics. */
    data class Settings(
        val hudEnabled: Boolean = false,
        val hudEdgeMarginDp: Int = 16,
        val hudPosition: Int = 0,
        val consentProfile: ConsentLevel = ConsentLevel.NORMAL,
        val hermesEndpoint: String = "https://hermes.local/api/v1/agent",
        val hermesAuthTokenKey: String = "hermes.auth.token",
        val blackwaveEndpoint: String = "https://blackwave.local:8878",
        val blackwaveCredentialKey: String = "blackwave.credential",
        val blackwaveClientId: String = "fr3k-hud",
        val termuxPackage: String = "com.termux",
        val autoShareTargets: List<String> = emptyList(),
        val telemetryEnabled: Boolean = true,
        val experimentalFeatures: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("hudEnabled", hudEnabled)
            put("hudEdgeMarginDp", hudEdgeMarginDp)
            put("hudPosition", hudPosition)
            put("consentProfile", consentProfile.name)
            put("hermesEndpoint", hermesEndpoint)
            put("blackwaveEndpoint", blackwaveEndpoint)
            put("termuxPackage", termuxPackage)
            put("autoShareTargets", JSONArray(autoShareTargets))
            put("telemetryEnabled", telemetryEnabled)
            put("experimentalFeatures", JSONArray(experimentalFeatures))
        }
    }
}