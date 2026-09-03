package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capabilities
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRegistryTest {

    private class FakeCommand(
        override val id: String,
        override val title: String,
        override val requiredCapabilities: Set<String> = emptySet(),
        override val keywords: Set<String> = emptySet(),
    ) : Fr3kCommand {
        override val description: String = "fake"
        override val pluginId: String = "fake"
        override suspend fun execute(context: Fr3kContext, args: Map<String, String>) = CommandResult.Ok("ok")
    }

    @Test fun registersAndFilters() {
        val r = CommandRegistry()
        r.register(FakeCommand("a", "Apple", requiredCapabilities = setOf(Capabilities.AGENT_ASK)))
        r.register(FakeCommand("b", "Banana", requiredCapabilities = setOf(Capabilities.MESH_SEND)))
        assertEquals(2, r.all().size)
        val available = r.available(setOf(Capabilities.AGENT_ASK))
        assertEquals(1, available.size)
        assertEquals("a", available.first().id)
    }

    @Test fun unregisterByPlugin() {
        val r = CommandRegistry()
        r.register(FakeCommand("a", "Apple"))
        r.register(FakeCommand("b", "Banana"))
        r.unregisterByPlugin("fake")
        assertEquals(0, r.all().size)
    }

    @Test fun searchFuzzy() {
        val r = CommandRegistry()
        r.register(FakeCommand("a", "Open URL", keywords = setOf("url")))
        r.register(FakeCommand("b", "Clean URL"))
        r.register(FakeCommand("c", "Send via Mesh"))
        val results = r.search("url", emptySet())
        assertEquals(2, results.size)
        assertTrue(results.all { it.title.contains("URL") })
    }
}