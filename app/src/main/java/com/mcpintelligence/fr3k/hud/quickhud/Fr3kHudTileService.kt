package com.mcpintelligence.fr3k.hud.quickhud

import android.content.ComponentName
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.mcpintelligence.fr3k.Fr3kApplication
import com.mcpintelligence.fr3k.hud.R

/**
 * Quick Settings tile (§32). One tile for FR3K HUD; toggles the floating
 * orb on/off. Tile state reflects whether the overlay is currently attached.
 *
 * Higher-API devices use the icon resource; lower-API fall back to a
 * monochrome tile label only.
 */
class Fr3kHudTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val intent = android.content.Intent(this, QuickHudActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        } else {
            startLegacyTileActivity(intent)
        }
    }

    // API 31–33 do not have the PendingIntent overload. The caller guards this
    // compatibility path; API 34+ always uses the overload above.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startLegacyTileActivity(intent: android.content.Intent) {
        startActivityAndCollapse(intent)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tile.label = "FR3K"
            tile.icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Icon.createWithResource(this, R.drawable.fr3k_orb)
            } else {
                @Suppress("DEPRECATION") Icon.createWithResource(this, R.drawable.fr3k_orb)
            }
        }
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
