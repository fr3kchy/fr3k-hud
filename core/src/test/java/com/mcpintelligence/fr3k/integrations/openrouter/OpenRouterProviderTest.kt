package com.mcpintelligence.fr3k.integrations.openrouter

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class OpenRouterProviderTest {
    @Test fun invalidKeyCannotPassUsingPublicCatalogue() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401))
            val provider = OpenRouterProvider(endpoint = server.url("/api/v1").toString(), apiKeyProvider = { "dummy-invalid" })
            val health = provider.health()
            assertFalse(health.online)
            assertEquals("/api/v1/key", server.takeRequest().path)
            assertTrue(health.message.orEmpty().contains("401"))
        } finally { server.shutdown() }
    }

    @Test fun absentKeyDoesNotMakeNetworkRequest() = runBlocking {
        val provider = OpenRouterProvider(endpoint = "http://127.0.0.1:1", apiKeyProvider = { null })
        assertFalse(provider.health().online)
        assertEquals("NOT CONFIGURED", provider.health().message)
        assertEquals("openrouter/free", provider.selectedModel())
    }

    @Test fun echoedKeyIsRedactedFromChatErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val key = "dummy-secret-key"
            server.enqueue(MockResponse().setResponseCode(401).setBody("rejected $key"))
            val provider = OpenRouterProvider(endpoint = server.url("/api/v1").toString(), apiKeyProvider = { key })
            val result = provider.ask(com.mcpintelligence.fr3k.protocol.AgentAskRequest(prompt = "hello"))
            assertFalse(result.text.contains(key))
            assertTrue(result.text.contains("[redacted]"))
        } finally { server.shutdown() }
    }
    @Test fun nativeToolCallsUseSchemaAndPreserveCallIds() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Inspecting the page.","tool_calls":[{"id":"native-call-1","type":"function","function":{"name":"browser__navigate","arguments":"{\"action\":\"inspect\"}"}}]}}]}"""))
            val provider = OpenRouterProvider(endpoint = server.url("/api/v1").toString(), apiKeyProvider = { "dummy-key" })
            val tool = object : com.mcpintelligence.fr3k.core.tools.AgentTool {
                override val id = "browser.navigate"
                override val displayName = "browser"
                override val description = "Read browser"
                override val capability = "browser"
                override val requiresNetwork = false
                override suspend fun invoke(context: com.mcpintelligence.fr3k.core.tools.ToolContext, args: com.mcpintelligence.fr3k.core.tools.ToolArgs) = com.mcpintelligence.fr3k.core.tools.ToolResult.Success("page")
            }
            val messages = org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "inspect"))
            val response = provider.complete(messages, listOf(tool))
            assertEquals("native-call-1", response.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            val request = server.takeRequest()
            val body = org.json.JSONObject(request.body.readUtf8())
            assertEquals("openrouter/free", body.getString("model"))
            assertFalse(body.getBoolean("parallel_tool_calls"))
            assertEquals("browser__navigate", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
            assertEquals("Bearer dummy-key", request.getHeader("Authorization"))
            assertFalse(body.toString().contains("dummy-key"))
        } finally { server.shutdown() }
    }
}
