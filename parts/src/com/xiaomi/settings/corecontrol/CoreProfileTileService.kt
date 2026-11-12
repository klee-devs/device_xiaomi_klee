/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import android.graphics.drawable.Icon
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
        val titleRes = profile?.titleRes ?: R.string.core_profile_custom
        val iconRes = when (profile) {
            CoreProfile.POWER_SAVING -> R.drawable.ic_core_profile_power_saving
            CoreProfile.BALANCED -> R.drawable.ic_core_profile_balanced
            CoreProfile.PERFORMANCE -> R.drawable.ic_core_profile_performance
            null -> R.drawable.ic_core_profile_tile
        }
        val profileTitle = getString(titleRes)

        tile.icon = Icon.createWithResource(this, iconRes)
        tile.label = getString(R.string.core_profile_tile_label)
        tile.subtitle = profileTitle
        tile.contentDescription = getString(R.string.core_profile_tile_description, profileTitle)
        // Performance is the stock all-cores state, so keep its existing inactive appearance.
        tile.state = if (profile == CoreProfile.PERFORMANCE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
