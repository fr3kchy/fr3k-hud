package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class RegistryProjectionTest {
    @Test fun sameModelUnitsKeepTheirOwnIdsAndCapabilities() {
        val fleet = BlackwaveBridgeClient.json.decodeFromString<FleetStatusResponse>("""{"devices":[{"model_id":"tdeck-plus","device_id":"one","capabilities":["device.describe"],"capability_evidence":"registry-enrollment"},{"model_id":"tdeck-plus","device_id":"two","capabilities":[]}],"live":false,"cached":true}""")
        assertEquals(listOf("one", "two"), fleet.devices.map { it.device_id })
        assertEquals(listOf("device.describe"), fleet.devices.first().capabilities)
        assertFalse(fleet.live)
    }
    @Test fun catalogClaimsDoNotBecomeVerifiedHardware() {
        val detail = BlackwaveBridgeClient.json.decodeFromString<DeviceStatusResponse>("""{"catalog_capabilities":["radio.tx"],"identity_evidence":"model-catalog","live":false,"cached":true}""")
        assertTrue(detail.hardware.isEmpty())
        assertFalse(detail.device_identity_verified)
        assertEquals("model-catalog", detail.identity_evidence)
        assertEquals(listOf("radio.tx"), detail.catalog_capabilities)
    }
}
