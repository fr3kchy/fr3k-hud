package com.mcpintelligence.fr3k.core

import android.util.Log
import com.mcpintelligence.fr3k.protocol.Capability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
) {
    private val plugins = ConcurrentHashMap<String, RegisteredPlugin>()
    private val statusByPlugin = ConcurrentHashMap<String, PluginStatus>()
    private val logTag = "FR3K.plugin"

    val pluginList: List<Fr3kPlugin> get() = plugins.values.map { it.plugin }
    val statuses: Map<String, PluginStatus> get() = statusByPlugin.toMap()

    fun register(plugin: Fr3kPlugin) {
        if (plugins.containsKey(plugin.pluginId)) {
            Log.w(logTag, "plugin ${plugin.pluginId} already registered; replacing")
            unregister(plugin.pluginId)
        }
        plugins[plugin.pluginId] = RegisteredPlugin(plugin)
        statusByPlugin[plugin.pluginId] = PluginStatus.REGISTERED
    }

    fun start(pluginId: String) {
        val reg = plugins[pluginId] ?: run {
            Log.w(logTag, "no such plugin: $pluginId"); return
        }
        val plugin = reg.plugin
        reg.scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + parentScope.coroutineContext)
        reg.startJob = reg.scope!!.launch {
            runCatching {
                statusByPlugin[pluginId] = PluginStatus.STARTING
                capabilityRegistry.registerAll(pluginId, plugin.capabilities())
                plugin.commands().forEach { commandRegistry.register(it) }
                plugin.start()
                statusByPlugin[pluginId] = PluginStatus.RUNNING
            }.onFailure {
                Log.e(logTag, "plugin $pluginId start failed", it)
                statusByPlugin[pluginId] = PluginStatus.FAILED
                capabilityRegistry.unregisterAllByOwner(pluginId)
                commandRegistry.unregisterByPlugin(pluginId)
            }
        }
    }

    fun unregister(pluginId: String) {
        val reg = plugins.remove(pluginId) ?: return
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