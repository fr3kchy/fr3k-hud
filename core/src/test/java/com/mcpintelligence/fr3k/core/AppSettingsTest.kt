package com.mcpintelligence.fr3k.core

import org.junit.Assert.*
import org.junit.Test

class AppSettingsTest {
    @Test fun allSettingsSurviveReopeningTheStore() {
        var storage: String? = null
        val original = AppSettings.open({ storage }, { storage = it })
        val expected = AppSettings.Settings(
            hudEnabled = true, hudEdgeMarginDp = 24, hudPosition = 2,
            consentProfile = ConsentLevel.LOCAL_ONLY,
            hermesEndpoint = "https://local.example/agent",
            hermesAuthTokenKey = "test.hermes.key.reference",
            blackwaveEndpoint = "https://fleet.example:8878",
            blackwaveCredentialKey = "test.fleet.key.reference", blackwaveClientId = "test-client",
            termuxPackage = "test.termux", autoShareTargets = listOf("local-a", "local-b"),
            telemetryEnabled = false, experimentalFeatures = listOf("test-feature"),
        )
        original.update { expected }
        val reopened = AppSettings.open({ storage }, { storage = it })
        assertEquals(expected, reopened.settings.value)
        assertEquals(expected, original.settings.value)
    }

    @Test fun newInstallationUsesDefaultsWithoutWriting() {
        val settings = AppSettings.open({ null }, { error("must not write on read") })
        assertEquals(AppSettings.Settings(), settings.settings.value)
    }

    @Test fun olderSettingsUseDefaultsForMissingFields() {
        val settings = AppSettings.open({ "{\"hudEnabled\":true}" }, {})
        assertTrue(settings.settings.value.hudEnabled)
        assertEquals(AppSettings.Settings().blackwaveCredentialKey, settings.settings.value.blackwaveCredentialKey)
    }

    @Test fun corruptOrUnrecognizedConsentFailsClosed() {
        for (raw in listOf("not json", "{\"consentProfile\":\"future-profile\"}",
                           "{\"autoShareTargets\":42}")) {
            val settings = AppSettings.open({ raw }, {})
            assertEquals(ConsentLevel.LOCAL_ONLY, settings.settings.value.consentProfile)
        }
    }

    @Test fun failedWriteDoesNotPublishAnUnsavedChange() {
        val settings = AppSettings.open({ null }, { error("storage unavailable") })
        assertThrows(IllegalStateException::class.java) { settings.update { it.copy(hudEnabled = true) } }
        assertFalse(settings.settings.value.hudEnabled)
    }

    @Test fun sequentialUpdatesPreserveEarlierFields() {
        var storage: String? = null
        val settings = AppSettings.open({ storage }, { storage = it })
        settings.update { it.copy(hudEnabled = true) }
        settings.update { it.copy(consentProfile = ConsentLevel.PRIVATE) }
        val reopened = AppSettings.open({ storage }, {})
        assertTrue(reopened.settings.value.hudEnabled)
        assertEquals(ConsentLevel.PRIVATE, reopened.settings.value.consentProfile)
    }
}
