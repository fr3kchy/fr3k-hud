package com.mcpintelligence.fr3k.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Central permission registry (§34 doctrine — user must always be able to
 * inspect what will be requested; we auto-request only what a feature needs).
 *
 * The brief's per-feature requirements:
 *  - HUD orb           → SYSTEM_ALERT_WINDOW + POST_NOTIFICATIONS
 *  - Speech-to-text    → RECORD_AUDIO
 *  - Screenshot        → MediaProjection (handled by user gesture, not runtime)
 *  - GPS               → ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION
 *  - Termux bridge     → com.termux.permission.RUN_COMMAND
 *  - Clipboard         → no perm (read-on-invocation)
 *  - Notification ls   → BIND_NOTIFICATION_LISTENER
 *  - Bluetooth scan    → BLUETOOTH_CONNECT / BLUETOOTH_SCAN
 *  - Storage / files   → READ_MEDIA_IMAGES etc on 33+
 *
 * Each feature calls [request] with its id and the activity. The activity
 * implements [Host] to receive the result.
 */
object PermissionRegistry {

    /** Feature → list of Android runtime permissions it needs. */
    fun permissionsFor(feature: Feature): List<String> = when (feature) {
        Feature.HUD_ORB -> listOf(Manifest.permission.POST_NOTIFICATIONS).filter { Build.VERSION.SDK_INT >= 33 }
        Feature.STT -> listOf(Manifest.permission.RECORD_AUDIO)
        Feature.GPS -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        Feature.BLUETOOTH -> {
            val list = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) {
                list += Manifest.permission.BLUETOOTH_CONNECT
                list += Manifest.permission.BLUETOOTH_SCAN
            }
            list
        }
        Feature.STORAGE -> if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        Feature.MIC_PROJECTION -> emptyList() // MediaProjection is gesture-driven
        Feature.NOTIFICATION_LISTENER -> emptyList() // granted via Settings
    }

    fun granted(context: Context, feature: Feature): Boolean =
        permissionsFor(feature).all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Issue a runtime permission request for [feature]. Returns the list of
     * permissions it actually requested (empty if everything was already
     * granted). The activity must be the caller.
     */
    fun request(activity: Activity, feature: Feature, requestCode: Int): List<String> {
        val needed = permissionsFor(feature).filter { perm ->
            ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
        }
        return needed
    }

    enum class Feature {
        HUD_ORB, STT, GPS, BLUETOOTH, STORAGE, MIC_PROJECTION, NOTIFICATION_LISTENER,
    }
}