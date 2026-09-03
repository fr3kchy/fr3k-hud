package com.mcpintelligence.fr3k.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central command registry: every FR3K action is a command. The palette, share
 * sheet, HUD, automation triggers, and agent-suggested actions all hit this
 * registry.
 */
class CommandRegistry {

    private val commands = LinkedHashMap<String, Fr3kCommand>()

    private val _commandsView = MutableStateFlow<List<Fr3kCommand>>(emptyList())
    val commandsFlow: StateFlow<List<Fr3kCommand>> = _commandsView.asStateFlow()

    @Synchronized
    fun register(command: Fr3kCommand) {
        commands[command.id] = command
        publish()
    }

    @Synchronized
    fun unregister(commandId: String) {
        commands.remove(commandId)
        publish()
    }

    @Synchronized
    fun unregisterByPlugin(pluginId: String) {
        val toRemove = commands.values.filter { it.pluginId == pluginId }.map { it.id }
        toRemove.forEach { commands.remove(it) }
        publish()
    }

    fun get(commandId: String): Fr3kCommand? = commands[commandId]

    fun all(): List<Fr3kCommand> = commands.values.toList()

    /** Filter to commands whose required capabilities are satisfied. */
    fun available(capabilityIds: Collection<String>): List<Fr3kCommand> =
        commands.values.filter { it.requiredCapabilities.all { id -> id in capabilityIds } }

    /** Filter to commands missing at least one required capability. */
    fun unavailable(capabilityIds: Collection<String>): List<Fr3kCommand> =
        commands.values.filter { cmd -> cmd.requiredCapabilities.any { it !in capabilityIds } }

    /** Fuzzy search across title + keywords + id. */
    fun search(query: String, capabilityIds: Collection<String>): List<Fr3kCommand> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return available(capabilityIds)
        return available(capabilityIds).filter { cmd ->
            cmd.title.lowercase().contains(q) ||
                cmd.id.lowercase().contains(q) ||
                cmd.keywords.any { it.lowercase().contains(q) }
        }.sortedBy { it.title.lowercase().indexOf(q).coerceAtLeast(0) }
    }

    fun changes(): Flow<List<Fr3kCommand>> = commandsFlow

    @Synchronized
    fun clear() {
        commands.clear()
        publish()
    }

    private fun publish() {
        _commandsView.value = commands.values.toList()
    }
}