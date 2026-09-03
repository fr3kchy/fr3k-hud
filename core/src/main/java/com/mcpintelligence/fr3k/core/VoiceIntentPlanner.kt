package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * Voice command intent planner (§30). Translates natural-language commands
 * into a structured plan. V1 ships deterministic pattern matching; V2 wires
 * Hermes for NL understanding.
 *
 * Examples:
 *   "Send this page to the Dell and have the coding agent inspect it"
 *   → [collect url, target Dell 5550, dispatch url, request coding agent]
 *
 *   "Share my location on mesh"
 *   → [collect location, broadcast mesh]
 */
class VoiceIntentPlanner(
    private val deviceRegistryProvider: () -> DeviceRegistry,
) {

    data class Step(
        val action: String,
        val args: Map<String, String> = emptyMap(),
        val description: String,
    )

    data class Plan(
        val steps: List<Step>,
        val requiresConfirmation: Boolean = true,
        val rawUtterance: String,
    )

    fun plan(utterance: String, context: Fr3kContext): Plan {
        val text = utterance.trim().lowercase()
        val steps = mutableListOf<Step>()

        // Step 1: collect context
        if (text.contains("page") || text.contains("url") || text.contains("link")) {
            steps += Step(action = "collect.url", description = "collect current URL")
        }
        if (text.contains("location") || text.contains("gps") || text.contains("coords")) {
            steps += Step(action = "collect.location", description = "collect current location")
        }
        if (text.contains("screenshot") || text.contains("screen")) {
            steps += Step(action = "collect.screenshot", description = "capture current screen")
        }

        // Step 2: target device
        val registry = deviceRegistryProvider()
        val targets = registry.snapshot.value.filter { dev ->
            text.contains(dev.name.lowercase()) || text.contains(dev.deviceId.lowercase())
        }
        targets.forEach { dev ->
            steps += Step(action = "device.handoff", args = mapOf("deviceId" to dev.deviceId), description = "target ${dev.name}")
        }

        // Step 3: action
        when {
            text.contains("coding agent") || text.contains("code agent") || text.contains("inspect") ->
                steps += Step(action = "agent.code", description = "request coding agent")
            text.contains("explain") || text.contains("summaris") || text.contains("what is") ->
                steps += Step(action = "agent.ask", description = "explain via Hermes")
            text.contains("translate") ->
                steps += Step(action = "agent.translate", description = "translate via Hermes")
            text.contains("share") && text.contains("mesh") ->
                steps += Step(action = "mesh.send", description = "share via mesh")
            text.contains("share") ->
                steps += Step(action = "share.url", description = "share URL")
        }

        if (steps.isEmpty()) {
            steps += Step(action = "agent.ask", args = mapOf("prompt" to utterance), description = "ask Hermes")
        }

        return Plan(steps = steps, requiresConfirmation = isDestructive(text), rawUtterance = utterance)
    }

    private fun isDestructive(text: String): Boolean {
        val destructive = listOf("delete", "drop", "reset", "clear", "wipe", "format", "rm ")
        return destructive.any { text.contains(it) }
    }
}