package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.CapabilityTier
import com.mcpintelligence.fr3k.protocol.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {

    private fun cap(id: String, tier: CapabilityTier = CapabilityTier.TIER_0) =
        Capability(id = id, displayName = id, tier = tier)

    @Test fun registerAndUnregister() {
        val r = CapabilityRegistry()
        r.register("pluginA", cap(Capabilities.AGENT_ASK))
        assertTrue(r.has(Capabilities.AGENT_ASK))
        r.unregisterAllByOwner("pluginA")
        assertFalse(r.has(Capabilities.AGENT_ASK))
    }

    @Test fun hasAllAndHasAny() {
        val r = CapabilityRegistry()
        r.register("p", cap(Capabilities.AGENT_ASK))
        r.register("p", cap(Capabilities.SHARE_TEXT))
        assertTrue(r.hasAll(listOf(Capabilities.AGENT_ASK, Capabilities.SHARE_TEXT)))
        assertTrue(r.hasAny(listOf(Capabilities.AGENT_ASK, Capabilities.MESH_SEND)))
        assertFalse(r.hasAll(listOf(Capabilities.AGENT_ASK, Capabilities.MESH_SEND)))
    }

    @Test fun tiers() {
        val r = CapabilityRegistry()
        r.register("p", cap(Capabilities.AGENT_ASK, CapabilityTier.TIER_0))
        r.register("p", cap(Capabilities.LOCATION_CURRENT, CapabilityTier.TIER_1))
        assertEquals(1, r.capabilitiesAtTier(CapabilityTier.TIER_1).size)
    }

    @Test fun missingFor() {
        val r = CapabilityRegistry()
        r.register("p", cap(Capabilities.AGENT_ASK))
        assertEquals(
            listOf(Capabilities.LOCATION_CURRENT),
            r.missingFor(listOf(Capabilities.AGENT_ASK, Capabilities.LOCATION_CURRENT)),
        )
    }

    @Test fun removingEitherProviderPreservesTheOtherClaim() {
        for (removed in listOf("hermes", "opencode")) {
            val r = CapabilityRegistry()
            r.register("hermes", Capability(Capabilities.AI_LOCAL_CHAT, "Hermes"))
            r.registerAll("opencode", listOf(Capability(Capabilities.AI_LOCAL_CHAT, "OpenCode")))
            r.unregisterAllByOwner(removed)
            val remaining = if (removed == "hermes") "opencode" else "hermes"
            assertTrue(r.has(Capabilities.AI_LOCAL_CHAT))
            assertEquals(remaining, r.ownerOf(Capabilities.AI_LOCAL_CHAT))
            assertEquals(if (remaining == "hermes") "Hermes" else "OpenCode",
                r.snapshot.value.getValue(Capabilities.AI_LOCAL_CHAT).displayName)
            r.unregisterAllByOwner(remaining)
            assertFalse(r.has(Capabilities.AI_LOCAL_CHAT))
        }
    }

    @Test fun explicitRemovalAndClearCannotResurrectOldClaims() {
        val r = CapabilityRegistry()
        r.register("a", cap("shared")); r.register("b", cap("shared"))
        r.unregister("shared"); r.unregisterAllByOwner("b")
        assertFalse(r.has("shared"))
        r.register("a", cap("shared")); r.clear()
        r.register("b", cap("shared")); r.unregisterAllByOwner("b")
        assertFalse(r.has("shared"))
    }
}
