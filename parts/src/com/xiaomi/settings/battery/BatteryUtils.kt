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

    // Function type 32 (0x40/0x41) is the kernel SOC-limit control.
    // The old 0x11 command selects type 8 and does not encode an SOC target.
    private const val SMART_CHG_SOC_LIMIT_ENABLE = 0x41
    private const val SMART_CHG_SOC_LIMIT_RESET = 0x640040

    fun setSmartChargeMode(bypassEnabled: Boolean, limitEnabled: Boolean, limit: Int): Boolean {
        val file = File(NODE_SMART_CHG)
        if (!file.exists()) {
            Log.w(TAG, "Charging control unavailable: $NODE_SMART_CHG does not exist")
            return false
        }

        return try {
            if (!bypassEnabled) {
                // Clear a previously triggered limit before applying another mode.
                writeSmartChargeCommand(file, SMART_CHG_SOC_LIMIT_RESET)
            }
            if (bypassEnabled || limitEnabled) {
                // A 0% SOC cutoff stops battery charging while keeping the input
                // power path enabled, unlike input_suspend.
                val chargeLimit = if (bypassEnabled) 0 else limit.coerceIn(10, 100)
                val value = (chargeLimit shl 16) or SMART_CHG_SOC_LIMIT_ENABLE
                writeSmartChargeCommand(file, value)
            }
            Log.d(
                TAG,
                "Successfully updated charging mode (bypass=$bypassEnabled, limitEnabled=$limitEnabled)"
            )
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to update $NODE_SMART_CHG", e)
            false
        }
    }

    private fun writeSmartChargeCommand(file: File, command: Int) {
        val value = "0x${command.toString(16)}"
        FileOutputStream(file).use { fos ->
            fos.write(value.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            try {
                fos.fd.sync()
            } catch (ignored: IOException) {}
        }
        Log.d(TAG, "Successfully wrote $value to $NODE_SMART_CHG")
    }
}
