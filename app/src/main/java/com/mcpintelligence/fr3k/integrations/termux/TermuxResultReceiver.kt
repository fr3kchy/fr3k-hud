package com.mcpintelligence.fr3k.integrations.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the result bundle returned by Termux `RunCommandService` via
 * the PendingIntent registered in [TermuxBridge.runRaw].
 *
 * Routing contract:
 *  - The PendingIntent carries `fr3k.termux.request_id` (a UUID string
 *    minted by the bridge).
 *  - The result bundle uses the keys defined in [TermuxResultParser]
 *    (these are the keys Termux's documented `RUN_COMMAND_PENDING_INTENT`
 *    contract writes).
 *
 * The receiver delegates parsing to [TermuxResultParser] so the parser
 * remains the single source of truth for bundle shape. Duplicate
 * deliveries are tolerated via [TermuxBridge.ResultSlot] — the first
 * delivery wins.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId.isNullOrBlank()) return

        // Parse via the shared parser. We need to look up the bridge
        // singleton; the parser only deals with the intent shape and
        // returns a ParsedResult the bridge can convert to its own Result.
        val parsed = TermuxResultParser.parseIntent(intent, executionId = -1)
        val result = TermuxBridge.Result(
            stdout = parsed.stdout,
            stderr = parsed.stderr,
            exitCode = parsed.exitCode,
        )

        TermuxBridgeSingleton.deliver(requestId, result)
    }

    companion object {
        /**
         * PendingIntent extra key carrying the UUID minted by
         * [TermuxBridge.runRaw]. The receiver uses it to look up the
         * matching slot / continuation.
         */
        const val EXTRA_REQUEST_ID = "fr3k.termux.request_id"

        /**
         * Back-compat alias kept so older code paths that still emit an
         * integer id (none in the current codebase, but receivers can
         * be re-delivered after process death and might arrive with
         * the legacy key) don't silently no-op.
         */
        @Suppress("unused")
        const val EXTRA_EXECUTION_ID = "fr3k.termux.execution_id"
    }
}

/**
 * Tiny singleton holder so the [BroadcastReceiver] can reach the bridge
 * without DI. The bridge itself registers itself on construction; this
 * is a process-wide lookup.
 */
internal object TermuxBridgeSingleton {
    @Volatile
    private var current: TermuxBridge? = null

    fun register(bridge: TermuxBridge) {
        current = bridge
    }

    fun unregister(bridge: TermuxBridge) {
        if (current === bridge) current = null
    }

    fun deliver(requestId: String, result: TermuxBridge.Result) {
        val b = current ?: return
        b.deliver(requestId, result)
    }
}