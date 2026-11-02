package com.flowchat.app.presentation.assistant

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.flowchat.app.R

class AssistantTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.assistant_tile); updateTile() }
    }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val intent = Intent(this, AssistantPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 211, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            else openLegacy(intent)
        }
    }

    // The PendingIntent overload was added in 34. Older systems require the Intent overload.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openLegacy(intent: Intent) = startActivityAndCollapse(intent)
}
