package com.mcpintelligence.fr3k.hud

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mcpintelligence.fr3k.core.AutomationEngine

/**
 * Action executor for [AutomationEngine]. Each action delegates to the right
 * subsystem in the :app layer (broadcast intents, share intents, command
 * invocation). Failures bubble back as AutomationEngine.Outcome.
 */
class AutomationActionExecutor(
    private val context: Context,
    private val commandExecutor: (String, Map<String, String>) -> AutomationEngine.Outcome,
) : AutomationEngine.ActionExecutor {

    override fun execute(action: AutomationEngine.Action, ctx: com.mcpintelligence.fr3k.core.Fr3kContext): AutomationEngine.Outcome {
        return try {
            when (action) {
                is AutomationEngine.Action.RunCommand -> commandExecutor(action.commandId, action.args)
                is AutomationEngine.Action.OpenPalette -> {
                    context.startActivity(Intent(context, com.mcpintelligence.fr3k.ui.palette.CommandPaletteActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    AutomationEngine.Outcome.FIRED
                }
                is AutomationEngine.Action.OpenAskAboutThis -> {
                    val intent = Intent(context, com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    action.prompt?.let { intent.putExtra("android.intent.extra.TEXT", it) }
                    context.startActivity(intent)
                    AutomationEngine.Outcome.FIRED
                }
                is AutomationEngine.Action.SendToMesh -> {
                    val intent = Intent(context, com.mcpintelligence.fr3k.ui.ask.AskAboutThisActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("android.intent.extra.TEXT", action.content)
                    context.startActivity(intent)
                    AutomationEngine.Outcome.FIRED
                }
                is AutomationEngine.Action.SendToDevice -> {
                    val intent = Intent(context, com.mcpintelligence.fr3k.ui.handoff.DeviceHandoffActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("deviceId", action.deviceId)
                        .putExtra("content", action.content)
                    context.startActivity(intent)
                    AutomationEngine.Outcome.FIRED
                }
                is AutomationEngine.Action.Notify -> {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        android.app.Notification.Builder(context, "fr3k_automation")
                    } else {
                        @Suppress("DEPRECATION") android.app.Notification.Builder(context)
                    }
                    nm.notify(System.currentTimeMillis().toInt(),
                        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(action.title)
                            .setContentText(action.text)
                            .setAutoCancel(true)
                            .build()
                    )
                    AutomationEngine.Outcome.FIRED
                }
            }
        } catch (t: Throwable) {
            AutomationEngine.Outcome.FAILED
        }
    }
}