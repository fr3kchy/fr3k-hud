package com.mcpintelligence.fr3k.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens Settings for special permissions that aren't runtime-grantable
 * (overlay, notification listener, package usage stats). Each [request]
 * returns the intent to launch, and [canRequest] checks if the user
 * still needs to grant.
 */
object SpecialPermissionLauncher {

    fun canDrawOverlays(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun notificationListenerIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val pkg = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.startsWith("$pkg/") }
    }

    fun usageStatsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}