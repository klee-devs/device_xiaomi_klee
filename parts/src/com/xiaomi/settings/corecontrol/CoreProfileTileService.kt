/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.xiaomi.settings.R

/**
 * Quick settings tile that cycles Power saving -> Balanced -> Performance.
 * Long-press opens Core Control.
 */
class CoreProfileTileService : TileService() {

    private var listening = false

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        updateTile(CoreProfileController.currentProfile())
    }

    override fun onStopListening() {
        listening = false
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        CoreProfileController.cycleAsync(this) { profile ->
            if (listening) updateTile(profile)
        }
    }

    private fun updateTile(profile: CoreProfile?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.core_profile_tile_label)
        tile.subtitle = getString(profile?.titleRes ?: R.string.core_profile_custom)
        // Lit whenever some cores are off; Performance is the stock all-cores state.
        tile.state = if (profile == CoreProfile.PERFORMANCE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
