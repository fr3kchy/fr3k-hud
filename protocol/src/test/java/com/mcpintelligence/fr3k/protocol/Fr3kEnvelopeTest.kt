package com.mcpintelligence.fr3k.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class Fr3kEnvelopeTest {

    @Test fun roundTrip() {
        val envelope = Fr3kEnvelope(
            id = "01JABC",
            source = "fr3k-phone-01",
            destination = "fr3k-dell-01",
            type = "agent.ask",
            timestamp = 1735862345123,
            payload = kotlinx.serialization.json.buildJsonObject {
                put("prompt", kotlinx.serialization.json.JsonPrimitive("Explain this"))
            },
        )
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(Fr3kEnvelope.serializer(), envelope)
        val decoded = json.decodeFromString(Fr3kEnvelope.serializer(), encoded)
        assertEquals(envelope.id, decoded.id)
        assertEquals(envelope.type, decoded.type)
        assertEquals(envelope.protocol, Fr3kEnvelope.PROTOCOL_VERSION)
    }

    @Test fun agentAskFactory() {
        val env = Fr3kProtocol.agentAsk("fr3k-phone", null, "hello")
        assertEquals("agent.ask", env.type)
        assertEquals(Fr3kEnvelope.PROTOCOL_VERSION, env.protocol)
        assert(env.id.isNotEmpty())
    }
}