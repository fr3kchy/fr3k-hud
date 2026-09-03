package com.mcpintelligence.fr3k.core

/**
 * Automation engine (§7, §31). Lightweight rule-based engine: events trigger actions,
 * actions run as commands. Every automation has an owner id, so a failing automation
 * plugin doesn't take down the rest.
 *
 * V1 ships:
 *   - manual trigger (run by name)
 *   - foreground app change (debounced)
 *   - share received
 *   - share received with URL
 *   - network changed (registered by automation engine itself, V2 wired)
 *
 *   - command palette
 *   - send to mesh
 *   - send to device
 *   - open palette
 *   - open ask-about-this
 *   - run hermes
 *   - send to notification
 */
class AutomationEngine {

    enum class TriggerType {
        MANUAL,                          // user-invoked
        FOREGROUND_APP,                  // activity task change
        SHARE_RECEIVED,                  // share intent processed
        URL_SHARED,                      // share received with URL
        NOTIFICATION_MATCHED,            // notification listener (V2)
        WIFI_CHANGED,                    // network broadcast (V2)
        BT_DEVICE_CONNECTED,             // bluetooth device connected (V2)
        SCHEDULED,                       // scheduled time (V2)
    }

    sealed interface Action {
        data class RunCommand(val commandId: String, val args: Map<String, String> = emptyMap()) : Action
        data class OpenPalette(val query: String? = null) : Action
        data class OpenAskAboutThis(val prompt: String? = null) : Action
        data class SendToMesh(val content: String) : Action
        data class SendToDevice(val deviceId: String, val content: String) : Action
        data class Notify(val title: String, val text: String) : Action
    }

    data class Automation(
        val id: String,
        val ownerId: String,
        val title: String,
        val trigger: Trigger,
        val action: Action,
        val enabled: Boolean = true,
        val lastFired: Long? = null,
        val fireCount: Int = 0,
    ) {
        data class Trigger(
            val type: TriggerType,
            val packageMatch: String? = null,
            val urlMatch: String? = null,
            val debounceMs: Long = 0L,
        )
    }

    private val automations = LinkedHashMap<String, Automation>()
    private val logs = ArrayDeque<LogEntry>()
    private val maxLogs = 500

    data class LogEntry(
        val timestamp: Long,
        val automationId: String,
        val title: String,
        val trigger: TriggerType,
        val action: Action,
        val outcome: Outcome,
        val message: String? = null,
    )

    enum class Outcome { FIRED, SKIPPED_DISABLED, SKIPPED_DEBOUNCE, SKIPPED_NO_MATCH, FAILED }

    @Synchronized
    fun upsert(automation: Automation) {
        automations[automation.id] = automation
    }

    @Synchronized
    fun removeById(id: String) {
        automations.remove(id)
    }

    @Synchronized
    fun removeByOwner(ownerId: String) {
        val toRemove = automations.values.filter { it.ownerId == ownerId }.map { it.id }
        toRemove.forEach { automations.remove(it) }
    }

    @Synchronized
    fun all(): List<Automation> = automations.values.toList()

    @Synchronized
    fun byOwner(ownerId: String): List<Automation> =
        automations.values.filter { it.ownerId == ownerId }

    fun logs(): List<LogEntry> = synchronized(logs) { logs.toList() }

    @Synchronized
    fun fire(automation: Automation, ctx: Fr3kContext, executor: ActionExecutor): LogEntry {
        if (!automation.enabled) return log(automation, Outcome.SKIPPED_DISABLED, "disabled")
        val now = System.currentTimeMillis()
        if (automation.trigger.debounceMs > 0 && automation.lastFired != null &&
            now - automation.lastFired!! < automation.trigger.debounceMs) {
            return log(automation, Outcome.SKIPPED_DEBOUNCE, "debounce")
        }
        return try {
            val outcome = executor.execute(automation.action, ctx)
            automations[automation.id] = automation.copy(lastFired = now, fireCount = automation.fireCount + 1)
            log(automation, outcome, null)
        } catch (t: Throwable) {
            log(automation, Outcome.FAILED, t.message)
        }
    }

    fun fireManual(id: String, ctx: Fr3kContext, executor: ActionExecutor): LogEntry? {
        val a = automations[id] ?: return null
        return fire(a, ctx, executor)
    }

    fun matchAndFire(trigger: TriggerType, ctx: Fr3kContext, executor: ActionExecutor, packageName: String? = null, url: String? = null): List<LogEntry> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<LogEntry>()
        automations.values
            .filter { it.enabled && it.trigger.type == trigger }
            .filter {
                val pkgOk = it.trigger.packageMatch == null || it.trigger.packageMatch == packageName
                val urlOk = it.trigger.urlMatch == null || (url != null && url.contains(it.trigger.urlMatch, ignoreCase = true))
                pkgOk && urlOk
            }
            .forEach { a ->
                if (a.trigger.debounceMs > 0 && a.lastFired != null && now - a.lastFired!! < a.trigger.debounceMs) {
                    out += log(a, Outcome.SKIPPED_DEBOUNCE, "debounce")
                } else {
                    out += fire(a, ctx, executor)
                }
            }
        return out
    }

    private fun log(automation: Automation, outcome: Outcome, message: String?): LogEntry {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            automationId = automation.id,
            title = automation.title,
            trigger = automation.trigger.type,
            action = automation.action,
            outcome = outcome,
            message = message,
        )
        synchronized(logs) {
            logs.addFirst(entry)
            while (logs.size > maxLogs) logs.removeLast()
        }
        return entry
    }

    /** Action executor interface; concrete implementation lives in :app. */
    interface ActionExecutor {
        fun execute(action: Action, ctx: Fr3kContext): Outcome
    }
}