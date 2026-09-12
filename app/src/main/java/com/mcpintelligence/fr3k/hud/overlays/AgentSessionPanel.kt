package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mcpintelligence.fr3k.core.tools.SharedAgentSession
import kotlinx.coroutines.*

/** Same pending action and conversation on every chat surface. */
class AgentSessionPanel(context: Context, private val session: SharedAgentSession) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val details = TextView(context).apply { setTextColor(0xffe8eaf2.toInt()); textSize = 11f }
    private val actions = LinearLayout(context)
    init {
        orientation = VERTICAL
        val review = android.widget.ScrollView(context).apply { addView(details) }
        addView(review, LayoutParams(LayoutParams.MATCH_PARENT, 96.dp()))
        actions.addView(Button(context).apply {
            text = "ALLOW"
            setOnClickListener { session.approval.value?.let { session.decide(it.id, true) } }
        }, LayoutParams(0, 40.dp(), 1f))
        actions.addView(Button(context).apply {
            text = "DECLINE"
            setOnClickListener { session.approval.value?.let { session.decide(it.id, false) } }
        }, LayoutParams(0, 40.dp(), 1f))
        addView(actions)
        addView(Button(context).apply {
            text = "STOP AGENT"
            setOnClickListener { session.cancel() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 36.dp()))
        scope.launch { session.approval.collect { request ->
            review.visibility = if (request == null) View.GONE else View.VISIBLE
            actions.visibility = review.visibility
            details.text = request?.let { "Review ${it.toolId}\n" + it.arguments.entries.joinToString("\n") { (k,v) -> "$k: $v" } }.orEmpty()
        } }
        scope.launch { session.busy.collect { visibility = if (it) View.VISIBLE else View.GONE } }
    }
    fun shutdown() = scope.cancel()
    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
