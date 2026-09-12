package com.mcpintelligence.fr3k.core

import android.util.Log
import com.mcpintelligence.fr3k.protocol.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * FR3K plugin contract.
 *
 * A plugin owns capabilities and commands. Plugin failure must be isolated —
 * one plugin crashing must not affect others (§48). The [PluginManager]
 * wraps each plugin in its own supervisor scope and tracks lifecycle.
 */
interface Fr3kPlugin {
    val pluginId: String
    val displayName: String
    val version: String

    fun capabilities(): List<Capability>
    fun commands(): List<Fr3kCommand>

    /** Called once after registration. May suspend for setup. */
    suspend fun start() {}
    /** Called before unregistration. Must release resources. */
    suspend fun stop() {}
}

/**
 * Owns the plugin lifecycle. Failure isolation, hot reload, debug-friendly
 * plugin enumeration.
 */
class PluginManager(
    private val capabilityRegistry: CapabilityRegistry,
    private val commandRegistry: CommandRegistry,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val reportFailure: (String, Throwable) -> Unit = { id, cause ->
        Log.e("FR3K.plugin", "plugin $id lifecycle failed", cause)
    },
) {
    private val plugins = ConcurrentHashMap<String, RegisteredPlugin>()
    private val statusByPlugin = ConcurrentHashMap<String, PluginStatus>()
    private val stopping = HashMap<String, MutableList<Job>>()

    val pluginList: List<Fr3kPlugin> get() = plugins.values.map { it.plugin }
    val statuses: Map<String, PluginStatus> get() = statusByPlugin.toMap()

    @Synchronized
    fun register(plugin: Fr3kPlugin) {
        if (plugins.containsKey(plugin.pluginId)) {
            unregister(plugin.pluginId)
        }
        plugins[plugin.pluginId] = RegisteredPlugin(plugin)
        statusByPlugin[plugin.pluginId] = PluginStatus.REGISTERED
    }

    @Synchronized
    fun start(pluginId: String) {
        val reg = plugins[pluginId] ?: return
        if (reg.startJob != null) return
        val plugin = reg.plugin
        stopping[pluginId]?.removeAll { it.isCompleted }
        val previous = stopping[pluginId]?.toList().orEmpty()
        // Context entries on the right win. Keep a child job, not the parent's job.
        reg.scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        reg.startJob = reg.scope!!.launch(start = CoroutineStart.LAZY) {
            var enteredStart = false
            try {
                // A replacement must not race the old plugin's provider/resource cleanup.
                previous.forEach { it.join() }
                synchronized(this@PluginManager) {
                    if (plugins[pluginId] !== reg) return@launch
                    statusByPlugin[pluginId] = PluginStatus.STARTING
                }
                enteredStart = true
                plugin.start()
                synchronized(this@PluginManager) {
                    if (plugins[pluginId] === reg) {
                        capabilityRegistry.registerAll(pluginId, plugin.capabilities())
                        plugin.commands().forEach { commandRegistry.register(it) }
                        statusByPlugin[pluginId] = PluginStatus.RUNNING
                    }
                }
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                reportFailure(pluginId, cause)
                synchronized(this@PluginManager) {
                    if (plugins[pluginId] === reg) statusByPlugin[pluginId] = PluginStatus.FAILED
                }
            } finally {
                synchronized(this@PluginManager) {
                    if (plugins[pluginId] === reg) {
                        capabilityRegistry.unregisterAllByOwner(pluginId)
                        commandRegistry.unregisterByPlugin(pluginId)
                        if (statusByPlugin[pluginId] != PluginStatus.FAILED) {
                            statusByPlugin[pluginId] = PluginStatus.UNREGISTERED
                        }
                    }
                }
                if (enteredStart) withContext(NonCancellable) {
                    try { plugin.stop() } catch (cause: Exception) { reportFailure(pluginId, cause) }
                }
                reg.scope?.cancel()
            }
        }
        reg.startJob!!.start()
    }

    @Synchronized
    fun unregister(pluginId: String) {
        val reg = plugins.remove(pluginId) ?: return
        reg.startJob?.let { stopping.getOrPut(pluginId) { mutableListOf() }.add(it) }
        reg.startJob?.cancel()
        reg.scope?.cancel()
        capabilityRegistry.unregisterAllByOwner(pluginId)
        commandRegistry.unregisterByPlugin(pluginId)
        statusByPlugin[pluginId] = PluginStatus.UNREGISTERED
    }

    fun startAll() = plugins.keys.toList().forEach { start(it) }
    fun unregisterAll() = plugins.keys.toList().forEach { unregister(it) }

    fun statusOf(pluginId: String): PluginStatus = statusByPlugin[pluginId] ?: PluginStatus.UNREGISTERED

    private class RegisteredPlugin(val plugin: Fr3kPlugin) {
        var scope: CoroutineScope? = null
        var startJob: Job? = null
    }
}

enum class PluginStatus { REGISTERED, STARTING, RUNNING, FAILED, UNREGISTERED }
