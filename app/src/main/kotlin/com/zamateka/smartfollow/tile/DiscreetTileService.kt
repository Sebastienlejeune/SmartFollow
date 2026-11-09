package com.zamateka.smartfollow.tile

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.alarm.AlarmProfiles
import com.zamateka.smartfollow.container

/**
 * Quick settings tile switching to the "Discreet" profile and back to the previous one, without
 * opening the app. A long press opens the app (MainActivity handles QS_TILE_PREFERENCES). On a
 * locked phone the switch waits for the phone to be unlocked, so that nobody can mute the alarms
 * from the lock screen.
 */
class DiscreetTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val toggle = {
            container.toggleDiscreet()
            refresh()
        }
        if (isLocked) unlockAndRun(toggle) else toggle()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val profiles = container.profiles.value
        val discreet = profiles[AlarmProfiles.DISCREET_ID]
        val active = profiles.activeId == AlarmProfiles.DISCREET_ID
        tile.state = when {
            discreet == null -> Tile.STATE_UNAVAILABLE
            active -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = discreet?.name ?: getString(R.string.profile_discreet)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                discreet == null -> getString(R.string.tile_unavailable)
                active -> profiles.active.preferences.mutedAlarms.size.let { resources.getQuantityString(R.plurals.tile_muted, it, it) }
                else -> profiles.active.name
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "DiscreetTile"

        /** Asks the system to refresh the tile, after a change of profile. */
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, DiscreetTileService::class.java)) }
                .onFailure { Log.w(TAG, "Cannot refresh the tile", it) }
        }
    }
}
