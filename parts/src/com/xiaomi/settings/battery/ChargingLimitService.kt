/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChargingLimitService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val writeMutex = Mutex()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val storageContext = applicationContext.createDeviceProtectedStorageContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(storageContext)
        val enabled = if (intent?.hasExtra(EXTRA_ENABLED) == true) {
            intent.getBooleanExtra(EXTRA_ENABLED, false)
        } else {
            prefs.getBoolean(BatteryUtils.PREF_CHARGING_CTRL, false)
        }
        val limit = if (intent?.hasExtra(EXTRA_LIMIT) == true) {
            intent.getIntExtra(EXTRA_LIMIT, 80)
        } else {
            prefs.getInt(BatteryUtils.PREF_CHARGING_LIMIT, 80)
        }

        serviceScope.launch {
            writeMutex.withLock {
                BatteryUtils.setSmartChargeLimit(enabled, limit)
            }
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_ENABLED = "com.xiaomi.settings.extra.CHARGING_ENABLED"
        const val EXTRA_LIMIT = "com.xiaomi.settings.extra.CHARGING_LIMIT"
    }
}
