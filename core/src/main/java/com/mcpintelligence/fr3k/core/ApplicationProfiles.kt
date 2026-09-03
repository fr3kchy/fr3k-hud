package com.mcpintelligence.fr3k.core

import com.mcpintelligence.fr3k.protocol.Capability
import com.mcpintelligence.fr3k.protocol.Capabilities

/**
 * Application profiles (§39) — data-driven command suggestions per package.
 * Built-in profiles cover common categories; users can add more in V2 via JSON.
 *
 * Resolution: match by package name (exact), then by category prefix
 * (`browser`, `maps`, `messaging`, `github`, `mail`), then default.
 */
object ApplicationProfiles {

    data class Profile(
        val id: String,
        val title: String,
        val matchPackages: Set<String> = emptySet(),
        val matchCategory: String? = null,
        val suggestedCommands: List<CommandSuggestion>,
        val contextHints: ContextHints = ContextHints(),
    )

    data class CommandSuggestion(
        val commandId: String,
        val title: String,
        val keywords: Set<String> = emptySet(),
        val requiresCapabilities: Set<String> = emptySet(),
        val requiresConfirmation: Boolean = true,
        val priority: Int = 50,
    )

    /** What context fields this app category normally exposes. */
    data class ContextHints(
        val expectUrl: Boolean = false,
        val expectSelectedText: Boolean = false,
        val expectFullText: Boolean = false,
        val expectLocation: Boolean = false,
        val expectScreenshot: Boolean = false,
    )

    private val profiles: List<Profile> = listOf(
        Profile(
            id = "browser",
            title = "Browser",
            matchCategory = "browser",
            matchPackages = setOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.brave.browser",
                "com.opera.browser",
                "com.opera.mini.native",
                "com.UCMobile.intl",
                "com.sec.android.app.sbrowser",
                "com.microsoft.emmx",
                "com.duckduckgo.mobile.android",
                "com.bromite.android",
                "org.torproject.torbrowser",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "agent.ask.hermes",
                    title = "Summarise page",
                    keywords = setOf("summarise", "page", "explain", "tldr"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    requiresConfirmation = false,
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "agent.ask.hermes",
                    title = "Research page",
                    keywords = setOf("research", "investigate"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    requiresConfirmation = false,
                    priority = 80,
                ),
                CommandSuggestion(
                    commandId = "share.url.clean",
                    title = "Clean URL",
                    keywords = setOf("clean", "strip", "utm"),
                    requiresCapabilities = setOf(Capabilities.BROWSER_CLEAN_URL),
                    requiresConfirmation = false,
                    priority = 70,
                ),
                CommandSuggestion(
                    commandId = "device.open",
                    title = "Open on…",
                    keywords = setOf("handoff", "send"),
                    requiresCapabilities = setOf(Capabilities.DEVICE_OPEN),
                    priority = 60,
                ),
                CommandSuggestion(
                    commandId = "share.mesh.send",
                    title = "Send to mesh",
                    keywords = setOf("mesh", "broadcast"),
                    requiresCapabilities = setOf(Capabilities.MESH_SEND),
                    priority = 55,
                ),
            ),
            contextHints = ContextHints(expectUrl = true, expectSelectedText = true, expectFullText = true),
        ),
        Profile(
            id = "maps",
            title = "Maps",
            matchCategory = "maps",
            matchPackages = setOf(
                "com.google.android.apps.maps",
                "org.openstreetmap",
                "net.osmand.plus",
                "com.waze",
                "com.here.app.maps",
                "com.mapswithme.mapswithme",
                "app.organicmaps",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "location.copy",
                    title = "Copy coordinates",
                    keywords = setOf("copy", "coords", "latlon"),
                    requiresCapabilities = setOf(Capabilities.LOCATION_CURRENT),
                    requiresConfirmation = false,
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "location.waypoint",
                    title = "Send waypoint",
                    keywords = setOf("waypoint", "location", "gps"),
                    requiresCapabilities = setOf(Capabilities.LOCATION_WAYPOINT),
                    priority = 85,
                ),
                CommandSuggestion(
                    commandId = "share.mesh.location",
                    title = "Share via mesh",
                    keywords = setOf("mesh", "broadcast", "location"),
                    requiresCapabilities = setOf(Capabilities.MESH_LOCATION),
                    priority = 80,
                ),
                CommandSuggestion(
                    commandId = "device.open",
                    title = "Open on…",
                    keywords = setOf("handoff"),
                    requiresCapabilities = setOf(Capabilities.DEVICE_OPEN),
                    priority = 60,
                ),
            ),
            contextHints = ContextHints(expectLocation = true, expectSelectedText = true),
        ),
        Profile(
            id = "messaging",
            title = "Messaging",
            matchCategory = "messaging",
            matchPackages = setOf(
                "org.thoughtcrime.securesms",
                "com.signal.android",
                "com.whatsapp",
                "com.facebook.orca",
                "org.telegram.messenger",
                "im.vector.app",
                "com.google.android.apps.messaging",
                "com.discord",
                "xyz.klinker.messenger",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "share.text.rewrite",
                    title = "Rewrite selected",
                    keywords = setOf("rewrite", "tone", "polish"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    requiresConfirmation = false,
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "share.text.translate",
                    title = "Translate",
                    keywords = setOf("translate", "language"),
                    requiresCapabilities = setOf(Capabilities.AGENT_TRANSLATE),
                    priority = 85,
                ),
                CommandSuggestion(
                    commandId = "share.text.summarise",
                    title = "Summarise",
                    keywords = setOf("summarise"),
                    requiresCapabilities = setOf(Capabilities.AGENT_SUMMARISE),
                    priority = 75,
                ),
                CommandSuggestion(
                    commandId = "share.mesh.send",
                    title = "Send to mesh",
                    keywords = setOf("mesh"),
                    requiresCapabilities = setOf(Capabilities.MESH_SEND),
                    priority = 50,
                ),
            ),
            contextHints = ContextHints(expectSelectedText = true),
        ),
        Profile(
            id = "github",
            title = "GitHub client",
            matchCategory = "github",
            matchPackages = setOf(
                "com.github.android",
                "io.github.android",
                "com.fastaccess.github",
                "org.fdroid.github",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "agent.ask.hermes",
                    title = "Explain repo",
                    keywords = setOf("explain", "repo"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "agent.code.review",
                    title = "Review code",
                    keywords = setOf("review", "audit"),
                    requiresCapabilities = setOf(Capabilities.AGENT_CODE),
                    priority = 85,
                ),
                CommandSuggestion(
                    commandId = "termux.git.clone",
                    title = "Clone to device",
                    keywords = setOf("clone", "git"),
                    requiresCapabilities = setOf(Capabilities.TERMUX_JOB),
                    priority = 80,
                ),
                CommandSuggestion(
                    commandId = "device.open",
                    title = "Open in dev agent",
                    keywords = setOf("dev", "agent"),
                    requiresCapabilities = setOf(Capabilities.DEVICE_OPEN),
                    priority = 70,
                ),
            ),
            contextHints = ContextHints(expectUrl = true, expectFullText = true),
        ),
        Profile(
            id = "terminal",
            title = "Terminal",
            matchCategory = "terminal",
            matchPackages = setOf(
                "com.termux",
                "com.termux.api",
                "com.termux.tasker",
                "jackpal.androidterm",
                "io.github.deweyreed.themeable",
                "com.offsec.nethunter",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "termux.job",
                    title = "Run named job",
                    keywords = setOf("job", "run"),
                    requiresCapabilities = setOf(Capabilities.TERMUX_JOB),
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "termux.script",
                    title = "Run script",
                    keywords = setOf("script", "exec"),
                    requiresCapabilities = setOf(Capabilities.TERMUX_SCRIPT),
                    priority = 85,
                ),
                CommandSuggestion(
                    commandId = "share.text.explain",
                    title = "Explain output",
                    keywords = setOf("explain", "logs"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    priority = 75,
                ),
            ),
            contextHints = ContextHints(expectSelectedText = true, expectFullText = true),
        ),
        Profile(
            id = "media",
            title = "Gallery / Photos",
            matchCategory = "media",
            matchPackages = setOf(
                "com.google.android.apps.photos",
                "com.google.android.gallery3d",
                "com.sec.android.gallery3d",
                "com.miui.gallery",
                "com.oneplus.gallery",
                "com.nick.mowen.gallery2",
                "org.fossify.gallery",
            ),
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "context.screen.analyse",
                    title = "Analyse image",
                    keywords = setOf("analyse", "describe"),
                    requiresCapabilities = setOf(Capabilities.AI_LOCAL_VISION, Capabilities.AGENT_ASK),
                    priority = 90,
                ),
                CommandSuggestion(
                    commandId = "context.screen.text",
                    title = "Extract text",
                    keywords = setOf("ocr", "extract"),
                    requiresCapabilities = setOf(Capabilities.CONTEXT_SCREEN),
                    priority = 80,
                ),
                CommandSuggestion(
                    commandId = "share.file",
                    title = "Share via…",
                    keywords = setOf("share"),
                    requiresCapabilities = setOf(Capabilities.SHARE_FILE),
                    priority = 60,
                ),
            ),
            contextHints = ContextHints(expectScreenshot = true),
        ),
        Profile(
            id = "default",
            title = "Default",
            suggestedCommands = listOf(
                CommandSuggestion(
                    commandId = "agent.ask.hermes",
                    title = "Ask Hermes",
                    keywords = setOf("ask", "explain"),
                    requiresCapabilities = setOf(Capabilities.AGENT_ASK),
                    priority = 70,
                ),
                CommandSuggestion(
                    commandId = "system.battery",
                    title = "Battery status",
                    keywords = setOf("battery", "power"),
                    requiresCapabilities = setOf(Capabilities.SYSTEM_BATTERY),
                    priority = 40,
                ),
                CommandSuggestion(
                    commandId = "system.network",
                    title = "Network status",
                    keywords = setOf("network", "wifi"),
                    requiresCapabilities = setOf(Capabilities.SYSTEM_NETWORK),
                    priority = 40,
                ),
            ),
        ),
    )

    fun resolve(packageName: String?): Profile {
        if (packageName != null) {
            profiles.firstOrNull { packageName in it.matchPackages }?.let { return it }
            val category = inferCategory(packageName)
            profiles.firstOrNull { it.matchCategory == category }?.let { return it }
        }
        return profiles.last()
    }

    fun all(): List<Profile> = profiles

    private fun inferCategory(packageName: String): String? {
            val parts = packageName.split(".")
            if (parts.size < 2) return null
            return when {
                parts.any { it.contains("browser", ignoreCase = true) } -> "browser"
                parts.any { it.contains("map", ignoreCase = true) } -> "maps"
                parts.any { it.contains("messag", ignoreCase = true) || it.contains("chat", ignoreCase = true) || it.contains("signal", ignoreCase = true) } -> "messaging"
                parts.any { it.contains("github", ignoreCase = true) } -> "github"
                parts.any { it.contains("term", ignoreCase = true) } -> "terminal"
                parts.any { it.contains("gallery", ignoreCase = true) || it.contains("photo", ignoreCase = true) } -> "media"
                else -> null
            }
        }
}