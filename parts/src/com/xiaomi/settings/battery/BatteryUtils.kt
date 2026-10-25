/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object BatteryUtils {
    private const val TAG = "XiaomiBatteryUtils"
    const val PREF_CHARGING_CTRL = "charging_control"
    const val PREF_CHARGING_LIMIT = "charging_limit"
    const val PREF_BYPASS_CHARGING = "bypass_charging"
    private const val NODE_SMART_CHG = "/sys/class/power_supply/battery/smart_chg"
    private const val SMART_CHG_NORMAL = "0x10"
    private const val SMART_CHG_LIMIT_MODE = 0x11

    fun setSmartChargeMode(bypassEnabled: Boolean, limitEnabled: Boolean, limit: Int): Boolean {
        val file = File(NODE_SMART_CHG)
        if (!file.exists()) {
            Log.w(TAG, "Charging control unavailable: $NODE_SMART_CHG does not exist")
            return false
        }

        val chargeLimit = when {
            bypassEnabled -> 0
            limitEnabled -> limit.coerceIn(10, 100)
            else -> null
        }
        val value = chargeLimit?.let {
            "0x${((it shl 16) or SMART_CHG_LIMIT_MODE).toString(16)}"
        } ?: SMART_CHG_NORMAL

        return try {
            FileOutputStream(file).use { fos ->
                fos.write(value.toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (ignored: IOException) {}
            }
            Log.d(TAG, "Successfully wrote $value to $NODE_SMART_CHG")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write to $NODE_SMART_CHG", e)
            false
        }
    }
}
