/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.preference.PreferenceManager
import com.xiaomi.settings.R

class BypassChargingTileService : TileService() {
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updateTile()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!batteryReceiverRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryReceiverRegistered = true
        }
        updateTile()
    }

    override fun onStopListening() {
        unregisterBatteryReceiver()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()

        if (!isPlugged(currentBatteryStatus())) {
            updateTile()
            return
        }

        val storageContext = applicationContext.createDeviceProtectedStorageContext()
        val preferences = PreferenceManager.getDefaultSharedPreferences(storageContext)
        val bypassEnabled = !preferences.getBoolean(BatteryUtils.PREF_BYPASS_CHARGING, false)
        val limitEnabled = preferences.getBoolean(BatteryUtils.PREF_CHARGING_CTRL, false)
        val limit = preferences.getInt(BatteryUtils.PREF_CHARGING_LIMIT, 80)

        // Persist immediately so the tile reflects rapid consecutive taps correctly.
        preferences.edit()
            .putBoolean(BatteryUtils.PREF_BYPASS_CHARGING, bypassEnabled)
            .apply()

        startService(Intent(this, ChargingLimitService::class.java).apply {
            putExtra(ChargingLimitService.EXTRA_BYPASS_ENABLED, bypassEnabled)
            putExtra(ChargingLimitService.EXTRA_LIMIT_ENABLED, limitEnabled)
            putExtra(ChargingLimitService.EXTRA_LIMIT, limit)
        })

        updateTile(plugged = true, bypassEnabled = bypassEnabled)
    }

    override fun onDestroy() {
        unregisterBatteryReceiver()
        super.onDestroy()
    }

    private fun updateTile() {
        val plugged = isPlugged(currentBatteryStatus())
        val storageContext = applicationContext.createDeviceProtectedStorageContext()
        val preferences = PreferenceManager.getDefaultSharedPreferences(storageContext)
        val bypassEnabled = plugged &&
            preferences.getBoolean(BatteryUtils.PREF_BYPASS_CHARGING, false)

        updateTile(plugged, bypassEnabled)
    }

    private fun updateTile(plugged: Boolean, bypassEnabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            !plugged -> Tile.STATE_UNAVAILABLE
            bypassEnabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.bypass_charging_title)
        tile.subtitle = if (plugged) {
            null
        } else {
            getString(R.string.bypass_charging_tile_unavailable)
        }
        tile.contentDescription = if (plugged) {
            getString(R.string.bypass_charging_tile_description)
        } else {
            getString(R.string.bypass_charging_tile_unavailable)
        }
        tile.updateTile()
    }

    private fun currentBatteryStatus(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun isPlugged(status: Intent?): Boolean =
        status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

    private fun unregisterBatteryReceiver() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
    }
}
