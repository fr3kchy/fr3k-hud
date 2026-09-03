package com.mcpintelligence.fr3k.core

/**
 * Device handoff (§19, §38). Pure transformation logic: given a Fr3kContext and a target
 * device manifest, produce the payload that should travel to that device.
 *
 * Per-device adapters transform the payload to suit the recipient. A laptop gets the
 * full URL + full text; an embedded device gets a short text snippet + waypoint format.
 */
class DeviceHandoffAdapter {

    data class HandoffPayload(
        val targetDeviceId: String,
        val representation: String,
        val content: String,
        val mime: String = "text/plain",
        val metadata: Map<String, String> = emptyMap(),
    )

    fun adapt(
        target: com.mcpintelligence.fr3k.protocol.DeviceManifest,
        ctx: Fr3kContext,
        prompt: String? = null,
    ): HandoffPayload {
        val representation = inferRepresentation(target)
        val content = when (representation) {
            Representation.URL -> ctx.currentUrl ?: ctx.fullText ?: ctx.selectedText ?: prompt ?: ""
            Representation.FULL_TEXT -> ctx.fullText ?: ctx.currentUrl ?: ctx.selectedText ?: prompt ?: ""
            Representation.SHORT_TEXT -> (ctx.selectedText ?: ctx.currentUrl ?: ctx.fullText ?: prompt ?: "").take(140)
            Representation.COMMAND -> (prompt ?: ctx.selectedText ?: "").take(140)
            Representation.WAYPOINT -> formatWaypoint(ctx)
        }
        return HandoffPayload(
            targetDeviceId = target.deviceId,
            representation = representation.name,
            content = content,
            metadata = mapOf(
                "sourcePackage" to (ctx.foregroundPackage ?: ""),
                "deviceClass" to target.metadata["device_class"].orEmpty(),
                "consentedProfile" to ctx.consentLevel.name,
            ),
        )
    }

    enum class Representation { URL, FULL_TEXT, SHORT_TEXT, COMMAND, WAYPOINT }

    private fun inferRepresentation(target: com.mcpintelligence.fr3k.protocol.DeviceManifest): Representation {
        val deviceClass = target.metadata["device_class"].orEmpty().lowercase()
        val platform = target.platform.lowercase()
        return when {
            deviceClass.contains("laptop") || platform in setOf("linux", "darwin", "windows") -> Representation.URL
            deviceClass.contains("embedded") || deviceClass.contains("mesh") -> Representation.SHORT_TEXT
            deviceClass.contains("waypoint") -> Representation.WAYPOINT
            else -> Representation.URL
        }
    }

    private fun formatWaypoint(ctx: Fr3kContext): String {
        // Best-effort: use location from context engine if present; otherwise fall back
        // to empty. Concrete location read lives in V2 GPS plugin.
        return ""
    }
}