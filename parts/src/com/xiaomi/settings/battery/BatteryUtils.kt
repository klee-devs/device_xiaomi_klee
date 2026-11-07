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

    // smart_chg encodes function type 1 (SMART_CHG_NAVIGATION) in bit 1,
    // and bit 0 enables it. The kernel uses this function for its SOC cutoff.
    private const val SMART_CHG_SOC_LIMIT_TYPE = 1 shl 1
    private const val SMART_CHG_SOC_LIMIT_ENABLE = SMART_CHG_SOC_LIMIT_TYPE or 1
    private const val SMART_CHG_SOC_LIMIT_DISABLE = SMART_CHG_SOC_LIMIT_TYPE

    fun setSmartChargeMode(bypassEnabled: Boolean, limitEnabled: Boolean, limit: Int): Boolean {
        val file = File(NODE_SMART_CHG)
        if (!file.exists()) {
            Log.w(TAG, "Charging control unavailable: $NODE_SMART_CHG does not exist")
            return false
        }

        return try {
            // A zero-percent SOC cutoff stops charging while leaving the input
            // power path enabled; input_suspend is deliberately untouched.
            val chargeLimit = when {
                bypassEnabled -> 0
                limitEnabled -> limit.coerceIn(10, 100)
                else -> null
            }
            val command = chargeLimit?.let {
                (it shl 16) or SMART_CHG_SOC_LIMIT_ENABLE
            } ?: SMART_CHG_SOC_LIMIT_DISABLE
            val readback = writeSmartChargeCommand(file, command)
            val expectedEnabled = chargeLimit != null
            if (readback != null &&
                ((readback and SMART_CHG_SOC_LIMIT_TYPE) != 0) != expectedEnabled
            ) {
                Log.e(
                    TAG,
                    "Kernel did not apply charging mode (bypass=$bypassEnabled, " +
                        "limitEnabled=$limitEnabled, readback=$readback)"
                )
                return false
            }
            Log.i(
                TAG,
                "Charging mode applied (bypass=$bypassEnabled, limitEnabled=$limitEnabled, " +
                    "limit=$chargeLimit, kernelState=$readback)"
            )
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to update $NODE_SMART_CHG", e)
            false
        }
    }

    private fun writeSmartChargeCommand(file: File, command: Int): Int? {
        val value = "0x${command.toString(16)}"
        FileOutputStream(file).use { fos ->
            fos.write(value.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            try {
                fos.fd.sync()
            } catch (ignored: IOException) {}
        }
        val rawReadback = try {
            file.readText(StandardCharsets.UTF_8).trim()
        } catch (e: IOException) {
            Log.w(TAG, "Could not read back $NODE_SMART_CHG", e)
            return null
        }
        val readback = if (rawReadback.startsWith("0x", ignoreCase = true)) {
            rawReadback.substring(2).toIntOrNull(16)
        } else {
            rawReadback.toIntOrNull()
        }
        Log.i(TAG, "Wrote $value to $NODE_SMART_CHG; kernel readback=$rawReadback")
        if (readback == null) {
            Log.w(TAG, "Unrecognized smart_chg readback: $rawReadback")
        }
        return readback
    }
}
