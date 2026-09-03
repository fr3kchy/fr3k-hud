package com.mcpintelligence.fr3k.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The FR3K core orchestrator — assembled once in [Fr3kApplication] and held as a
 * process-wide singleton. Composes the registries and the plugin manager.
 */
class Fr3kCore(
    val appContext: Context,
    val identity: DeviceIdentity,
    val secureStore: SecureStore,
    val capabilityRegistry: CapabilityRegistry = CapabilityRegistry(),
    val commandRegistry: CommandRegistry = CommandRegistry(),
    val deviceRegistry: DeviceRegistry = DeviceRegistry(),
    val settings: AppSettings = AppSettings(),
    val contextEngine: ContextEngine = ContextEngine(),
    val automationEngine: AutomationEngine = AutomationEngine(),
    val deviceHandoff: DeviceHandoffAdapter = DeviceHandoffAdapter(),
    val pluginManager: PluginManager,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Returns the current capability id set for fast UI filtering. */
    fun currentCapabilities(): Set<String> = capabilityRegistry.snapshot.value.keys

    /** Returns only commands that can actually execute right now. */
    fun availableCommands(query: String = ""): List<Fr3kCommand> =
        commandRegistry.search(query, currentCapabilities())

    /** Suggest commands for the current foreground package via ApplicationProfiles. */
    fun suggestedForCurrent(): List<ApplicationProfiles.CommandSuggestion> {
        val profile = ApplicationProfiles.resolve(contextEngine.current.value.sourcePackage)
        val caps = currentCapabilities()
        return profile.suggestedCommands
            .filter { it.requiresCapabilities.all { c -> c in caps } || it.requiresCapabilities.isEmpty() }
            .sortedByDescending { it.priority }
    }

    /** Profile name for the current foreground package. */
    fun profileForCurrent(): String =
        ApplicationProfiles.resolve(contextEngine.current.value.sourcePackage).title
}