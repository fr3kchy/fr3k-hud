package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BlackwaveRoleManifestTest {
    @Test fun acceptsGatewayNullableExpiry() {
        val role = Json.decodeFromString<BlackwaveRoleManifest>("""{"expires_at":null}""")
        assertFalse(role.isExpiredAt(0))
    }

    @Test fun expiresAtExactBoundary() {
        val role = BlackwaveRoleManifest(expires_at = "2026-09-07T00:00:00Z")
        val boundary = java.time.Instant.parse(role.expires_at!!).toEpochMilli()
        assertFalse(role.isExpiredAt(boundary - 1))
        assertTrue(role.isExpiredAt(boundary))
        assertTrue(role.isExpiredAt(boundary + 1))
    }

    @Test fun rejectsMalformedNonNullExpiry() {
        for (date in listOf("", "invalid", "2026-09-07", "2026-99-99T00:00:00Z")) {
            assertTrue(date, BlackwaveRoleManifest(expires_at = date).isExpiredAt(0))
        }
    }
}
