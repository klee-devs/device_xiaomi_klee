/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChargingLimitService : Service() {
    private var receiverRegistered = false
    private var lastRequestedMode: String? = null
    private var writeGeneration = 0
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val writeMutex = Mutex()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updateChargingState(context.createDeviceProtectedStorageContext(), intent)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storageContext = applicationContext.createDeviceProtectedStorageContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(storageContext)
        if (intent?.hasExtra(EXTRA_BYPASS_ENABLED) == true) {
            prefs.edit()
                .putBoolean(
                    BatteryUtils.PREF_BYPASS_CHARGING,
                    intent.getBooleanExtra(EXTRA_BYPASS_ENABLED, false)
                )
                .putBoolean(
                    BatteryUtils.PREF_CHARGING_CTRL,
                    intent.getBooleanExtra(EXTRA_LIMIT_ENABLED, false)
                )
                .putInt(
                    BatteryUtils.PREF_CHARGING_LIMIT,
                    intent.getIntExtra(EXTRA_LIMIT, 80)
                )
                .apply()
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (!receiverRegistered) {
            applicationContext.registerReceiver(batteryReceiver, filter)
            receiverRegistered = true
        }
        val batteryStatus = applicationContext.registerReceiver(null, filter)
        updateChargingState(storageContext, batteryStatus)
        return START_STICKY
    }

    private fun updateChargingState(context: Context, intent: Intent?) {
        intent ?: return
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        var bypassEnabled = prefs.getBoolean(BatteryUtils.PREF_BYPASS_CHARGING, false)

        if (bypassEnabled && !plugged) {
            prefs.edit().putBoolean(BatteryUtils.PREF_BYPASS_CHARGING, false).apply()
            bypassEnabled = false
        }

        val limitEnabled = prefs.getBoolean(BatteryUtils.PREF_CHARGING_CTRL, false)
        val limit = prefs.getInt(BatteryUtils.PREF_CHARGING_LIMIT, 80)
        val effectiveBypass = bypassEnabled && plugged
        val mode = when {
            effectiveBypass -> "bypass"
            limitEnabled -> "limit:${limit.coerceIn(10, 100)}"
            else -> "normal"
        }
        val modeKey = if (plugged) "$mode:plugged" else "$mode:unplugged"
        if (modeKey == lastRequestedMode) return

        lastRequestedMode = modeKey
        val generation = ++writeGeneration
        serviceScope.launch {
            val success = writeMutex.withLock {
                BatteryUtils.setSmartChargeMode(effectiveBypass, limitEnabled, limit)
            }
            if (!success) {
                withContext(Dispatchers.Main) {
                    if (writeGeneration == generation) lastRequestedMode = null
                }
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            applicationContext.unregisterReceiver(batteryReceiver)
            receiverRegistered = false
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_BYPASS_ENABLED = "com.xiaomi.settings.extra.BYPASS_ENABLED"
        const val EXTRA_LIMIT_ENABLED = "com.xiaomi.settings.extra.LIMIT_ENABLED"
        const val EXTRA_LIMIT = "com.xiaomi.settings.extra.CHARGING_LIMIT"
    }
}
