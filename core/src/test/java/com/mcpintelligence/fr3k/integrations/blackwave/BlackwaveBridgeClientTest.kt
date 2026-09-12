package com.mcpintelligence.fr3k.integrations.blackwave

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BlackwaveBridgeClientTest {
    private fun client(server: MockWebServer) = BlackwaveBridgeClient(
        { server.url("/").toString().trimEnd('/') }, { "fixture-only" }, { "fixture-client" },
    )

    @Test fun checksCanonicalHealthRoute() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"ok","api":"fr3k-blackwave-mobile/1"}"""))
            assertTrue(client(server).isAvailable())
            assertEquals("/mobile/v1/health", server.takeRequest().path)
        }
    }

    @Test fun sendsCanonicalClientHeaderAndAcceptsNullableExpiry() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"role_id":"bw:role:observer","expires_at":null}"""))
            assertEquals("bw:role:observer", client(server).fetchRole().getOrThrow().role_id)
            val request = server.takeRequest()
            assertEquals("/mobile/v1/role", request.path)
            assertEquals("fixture-client", request.getHeader("X-Blackwave-Client"))
            assertEquals("Bearer fixture-only", request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Client-Id"))
        }
    }

    @Test fun preservesPowerValidationAndCachedEvidence() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "identity":{"model_id":"m5stack-tab5"},
                "power":{"battery":"NOT REPORTED","physical_charging_verified":false},
                "validation":{"completion_percent":null,"state":"N/P"},
                "live":false,"cached":true,"observation_status":"cached"
            }"""))
            val status = client(server).fetchDeviceStatus("m5stack-tab5").getOrThrow()
            assertEquals("/mobile/v1/devices/m5stack-tab5", server.takeRequest().path)
            assertEquals(JsonNull, status.validation["completion_percent"])
            assertTrue(status.power.containsKey("physical_charging_verified"))
            assertFalse(status.live)
            assertTrue(status.cached)
        }
    }

    @Test fun authorizationFailureIsNotSuccessfulRole() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
            assertTrue(client(server).fetchRole().isFailure)
        }
    }
}
