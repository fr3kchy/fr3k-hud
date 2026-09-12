package com.mcpintelligence.fr3k.core.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAgentSessionTest {
    private fun call(id: String, tool: String, args: Map<String,String>) = JSONObject().put("role", "assistant")
        .put("content", "I will run the requested operation.")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", SharedAgentSession.functionName(tool)).put("arguments", JSONObject(args).toString()))))
    private fun answer(text: String) = JSONObject().put("role", "assistant").put("content", text)
    private class Tool(override val id: String, override val capability: String) : AgentTool {
        override val displayName = id
        override val description = "test tool"
        override val requiresNetwork = false
        val contexts = mutableListOf<ToolContext>()
        override suspend fun invoke(context: ToolContext, args: ToolArgs): ToolResult {
            contexts.add(context)
            return ToolResult.Success("actual-result-7")
        }
    }

    @Test fun nativeCallWaitsForExactApprovalAndReturnsActualResult() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("call1", tool.id, mapOf("command" to "printf hello"))
            else {
                val last = messages.getJSONObject(messages.length()-1)
                assertEquals("tool", last.getString("role"))
                assertEquals("call1", last.getString("tool_call_id"))
                assertEquals("actual-result-7", last.getString("content"))
                answer("Tool returned actual-result-7.")
            }
        }, "phone")
        val job = launch { session.send("run printf") }
        runCurrent()
        val approval = session.approval.value!!
        assertEquals("printf hello", approval.arguments["command"])
        assertTrue(tool.contexts.isEmpty())
        session.decide("wrong-id", true)
        runCurrent()
        assertTrue(tool.contexts.isEmpty())
        session.decide(approval.id, true)
        job.join()
        assertEquals(1, tool.contexts.size)
        assertEquals(session.sessionId, tool.contexts.single().sessionId)
        assertTrue(session.lines.value.any { it.text == "actual-result-7" })
        assertFalse(session.busy.value)
    }

    @Test fun decliningNeverInvokesTool() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("call1", tool.id, mapOf("command" to "anything"))
            else { assertTrue(messages.toString().contains("tool.declined")); answer("Declined.") }
        }, "phone")
        val job = launch { session.send("request") }
        runCurrent()
        session.decide(session.approval.value!!.id, false)
        job.join()
        assertTrue(tool.contexts.isEmpty())
    }

    @Test fun cancellationDoesNotReplayPendingCallAndNextTurnKeepsHistory() = runTest {
        val tool = Tool("termux.exec", "termux")
        val bus = AgentToolBus().apply { register(tool) }
        var turn = 0
        val session = SharedAgentSession(bus, AgentSessionBackend { messages, _ ->
            if (turn++ == 0) call("cancelled-call", tool.id, mapOf("command" to "anything"))
            else {
                assertTrue(messages.toString().contains("Turn interrupted"))
                assertTrue(messages.toString().contains("first request"))
                answer("Second turn retained context.")
            }
        }, "phone")
        val job = launch { session.send("first request") }
        runCurrent()
        job.cancelAndJoin()
        assertNull(session.approval.value)
        assertFalse(session.busy.value)
        session.send("second request")
        assertTrue(tool.contexts.isEmpty())
    }
}
