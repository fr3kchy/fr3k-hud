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
class AppSettings(
    initial: Settings = Settings(),
    private val persist: (Settings) -> Unit = {},
) {

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        persist(next)
        _settings.value = next
    }

    companion object {
        /** Missing storage is a new installation; malformed storage fails closed for consent. */
        fun open(read: () -> String?, write: (String) -> Unit): AppSettings {
            val restored = try {
                read()?.let { Settings.fromJson(JSONObject(it)) } ?: Settings()
            } catch (_: Exception) {
                Settings(consentProfile = ConsentLevel.LOCAL_ONLY)
            }
            return AppSettings(restored) { write(it.toJson().toString()) }
        }
    }

    /** User configuration; redact endpoints and identifiers in exported diagnostics. */
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
        val openrouterApiKeyKey: String = "openrouter.api.key",
        val openrouterModel: String = "openrouter/free",
        val termuxPackage: String = "com.termux",
        val autoShareTargets: List<String> = emptyList(),
        val telemetryEnabled: Boolean = true,
        val experimentalFeatures: List<String> = emptyList(),
        /** §9 — first-run onboarding gate. False until the user completes or skips it. */
        val onboardingDone: Boolean = false,
        /** §6/§7 — BLACKWAVE feature scopes the user explicitly enabled in the setup wizard. */
        val blackwaveEnabledScopes: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("hudEnabled", hudEnabled)
            put("hudEdgeMarginDp", hudEdgeMarginDp)
            put("hudPosition", hudPosition)
            put("consentProfile", consentProfile.name)
            put("hermesEndpoint", hermesEndpoint)
            put("hermesAuthTokenKey", hermesAuthTokenKey)
            put("blackwaveEndpoint", blackwaveEndpoint)
            put("blackwaveCredentialKey", blackwaveCredentialKey)
            put("blackwaveClientId", blackwaveClientId)
            put("openrouterApiKeyKey", openrouterApiKeyKey)
            put("openrouterModel", openrouterModel)
            put("termuxPackage", termuxPackage)
            put("autoShareTargets", JSONArray(autoShareTargets))
            put("telemetryEnabled", telemetryEnabled)
            put("experimentalFeatures", JSONArray(experimentalFeatures))
            put("onboardingDone", onboardingDone)
            put("blackwaveEnabledScopes", JSONArray(blackwaveEnabledScopes))
        }

        companion object {
            fun fromJson(json: JSONObject): Settings {
                val defaults = Settings()
                fun strings(key: String): List<String> {
                    if (!json.has(key)) return emptyList()
                    val values = json.getJSONArray(key)
                    return (0 until values.length()).map { values.getString(it) }
                }
                val consent = if (!json.has("consentProfile")) defaults.consentProfile else
                    ConsentLevel.entries.find { it.name == json.getString("consentProfile") }
                        ?: ConsentLevel.LOCAL_ONLY
                return Settings(
                    hudEnabled = json.optBoolean("hudEnabled", defaults.hudEnabled),
                    hudEdgeMarginDp = json.optInt("hudEdgeMarginDp", defaults.hudEdgeMarginDp).coerceIn(0, 48),
                    hudPosition = json.optInt("hudPosition", defaults.hudPosition),
                    consentProfile = consent,
                    hermesEndpoint = json.optString("hermesEndpoint", defaults.hermesEndpoint),
                    hermesAuthTokenKey = json.optString("hermesAuthTokenKey", defaults.hermesAuthTokenKey),
                    blackwaveEndpoint = json.optString("blackwaveEndpoint", defaults.blackwaveEndpoint),
                    blackwaveCredentialKey = json.optString("blackwaveCredentialKey", defaults.blackwaveCredentialKey),
                    blackwaveClientId = json.optString("blackwaveClientId", defaults.blackwaveClientId),
                    openrouterApiKeyKey = json.optString("openrouterApiKeyKey", defaults.openrouterApiKeyKey),
                    openrouterModel = json.optString("openrouterModel", defaults.openrouterModel),
                    termuxPackage = json.optString("termuxPackage", defaults.termuxPackage),
                    autoShareTargets = strings("autoShareTargets"),
                    telemetryEnabled = json.optBoolean("telemetryEnabled", defaults.telemetryEnabled),
                    experimentalFeatures = strings("experimentalFeatures"),
                    onboardingDone = json.optBoolean("onboardingDone", defaults.onboardingDone),
                    blackwaveEnabledScopes = strings("blackwaveEnabledScopes"),
                )
            }
        }
    }
}
