package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagerTest {
    private class Plugin(
        override val pluginId: String,
        val onStart: suspend () -> Unit = {},
        val onStop: suspend () -> Unit = {},
    ) : Fr3kPlugin {
        override val displayName = pluginId
        override val version = "test"
        var starts = 0
        var stops = 0
        override fun capabilities() = listOf(Capability(id = pluginId, displayName = pluginId))
        override fun commands() = emptyList<Fr3kCommand>()
        override suspend fun start() { starts++; onStart() }
        override suspend fun stop() { stops++; onStop() }
    }

    @Test fun removalStopsOnlyItsPluginAndParentRemainsUsable() = runTest {
        val caps = CapabilityRegistry()
        val manager = PluginManager(caps, CommandRegistry(), backgroundScope) { _, _ -> }
        val first = Plugin("first")
        val second = Plugin("second")
        manager.register(first); manager.register(second); manager.startAll(); runCurrent()
        manager.unregister("first"); runCurrent()
        assertEquals(1, first.stops)
        assertEquals(0, second.stops)
        assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
        assertFalse(caps.has("first"))
        assertTrue(caps.has("second"))
        val third = Plugin("third")
        manager.register(third); manager.start("third"); runCurrent()
        assertEquals(PluginStatus.RUNNING, manager.statusOf("third"))
        manager.unregisterAll(); runCurrent()
        assertEquals(1, second.stops)
    }

    @Test fun duplicateStartDoesNotCreateAnotherLifecycle() = runTest {
        val manager = PluginManager(CapabilityRegistry(), CommandRegistry(), backgroundScope) { _, _ -> }
        val plugin = Plugin("one")
        manager.register(plugin); manager.start("one"); manager.start("one"); runCurrent()
        assertEquals(1, plugin.starts)
        manager.unregister("one"); manager.unregister("one"); runCurrent()
        assertEquals(1, plugin.stops)
    }

    @Test fun startupFailureCleansUpWithoutAffectingSibling() = runTest {
        val caps = CapabilityRegistry()
        val errors = mutableListOf<String>()
        val manager = PluginManager(caps, CommandRegistry(), backgroundScope) { id, _ -> errors.add(id) }
        val broken = Plugin("broken", onStart = { error("startup failed") })
        val healthy = Plugin("healthy")
        manager.register(broken); manager.register(healthy); manager.startAll(); runCurrent()
        assertEquals(listOf("broken"), errors)
        assertEquals(PluginStatus.FAILED, manager.statusOf("broken"))
        assertEquals(1, broken.stops)
        assertFalse(caps.has("broken"))
        assertTrue(caps.has("healthy"))
        manager.unregisterAll(); runCurrent()
        assertEquals(1, broken.stops)
    }

    @Test fun replacementWaitsForOldCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val caps = CapabilityRegistry()
        val manager = PluginManager(caps, CommandRegistry(), backgroundScope) { _, _ -> }
        val old = Plugin("shared", onStop = { cleanup.await() })
        val replacement = Plugin("shared")
        manager.register(old); manager.start("shared"); runCurrent()
        manager.register(replacement); manager.start("shared"); runCurrent()
        assertEquals(1, old.stops)
        assertEquals(0, replacement.starts)
        assertFalse(caps.has("shared"))
        cleanup.complete(Unit); runCurrent()
        assertEquals(1, replacement.starts)
        assertTrue(caps.has("shared"))
        manager.unregisterAll(); runCurrent()
    }

    @Test fun cancellationDuringStartupDoesNotPublishCapabilities() = runTest {
        val startup = CompletableDeferred<Unit>()
        val caps = CapabilityRegistry()
        val manager = PluginManager(caps, CommandRegistry(), backgroundScope) { _, _ -> }
        val plugin = Plugin("pending", onStart = { startup.await() })
        manager.register(plugin); manager.start("pending"); runCurrent()
        assertFalse(caps.has("pending"))
        manager.unregister("pending"); runCurrent()
        assertEquals(1, plugin.stops)
        assertFalse(caps.has("pending"))
        assertEquals(PluginStatus.UNREGISTERED, manager.statusOf("pending"))
    }

    @Test fun rapidReplacementCannotSkipAnEarlierCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val manager = PluginManager(CapabilityRegistry(), CommandRegistry(), backgroundScope) { _, _ -> }
        val old = Plugin("shared", onStop = { cleanup.await() })
        manager.register(old); manager.start("shared"); runCurrent()
        val cancelledReplacement = Plugin("shared")
        manager.register(cancelledReplacement); manager.start("shared"); runCurrent()
        val latest = Plugin("shared")
        manager.register(latest); manager.start("shared"); runCurrent()
        assertEquals(0, cancelledReplacement.starts)
        assertEquals(0, latest.starts)
        cleanup.complete(Unit); runCurrent()
        assertEquals(1, latest.starts)
        manager.unregisterAll(); runCurrent()
    }

    @Test fun cleanupFailureDoesNotPreventReplacement() = runTest {
        val manager = PluginManager(CapabilityRegistry(), CommandRegistry(), backgroundScope) { _, _ -> }
        val old = Plugin("same", onStop = { error("cleanup failed") })
        manager.register(old); manager.start("same"); runCurrent()
        val next = Plugin("same")
        manager.register(next); manager.start("same"); runCurrent()
        assertEquals(1, old.stops)
        assertEquals(1, next.starts)
        manager.unregisterAll(); runCurrent()
    }
}
