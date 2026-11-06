/*
 * SPDX-FileCopyrightText: 2018 The LineageOS Project
 * SPDX-FileCopyrightText: 2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.util.Log
import android.view.Display
import android.view.Display.HdrCapabilities
import androidx.preference.PreferenceManager
import com.xiaomi.settings.battery.ChargingLimitService
import com.xiaomi.settings.corecontrol.CoreControlFragment
import com.xiaomi.settings.display.ColorService
import com.xiaomi.settings.light.LightService
import com.xiaomi.settings.touchsampling.TouchSamplingService
import com.xiaomi.settings.touchsampling.TouchSamplingSettingsFragment

/** Everything begins at boot. */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (DEBUG) Log.d(TAG, "Received boot completed intent: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBootCompleted(context)
        }
    }

    private fun onBootCompleted(context: Context) {
        // Restore MediaTek GED & GPU settings after user unlock
        runCatching { CoreControlFragment.restoreSettings(context) }
            .onFailure { Log.e(TAG, "Failed to restore CoreControl settings", it) }

        // Start TouchSamplingService after CE storage unlock if enabled or per-app auto is active
        runCatching {
            val sharedHtsr = context.getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
            )
            val htsrEnabled = sharedHtsr.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false)
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoEnabled = defaultPrefs.getBoolean(
                TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false
            )
            if (htsrEnabled || autoEnabled) {
                context.startServiceAsUser(
                    Intent(context, TouchSamplingService::class.java),
                    UserHandle.CURRENT
                )
            }
        }.onFailure { Log.e(TAG, "Failed to start TouchSamplingService on boot", it) }

        // Light Service (reads credential-encrypted DefaultSharedPreferences after unlock)
        runCatching {
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (sharedPreferences.getBoolean("light_enable", false)) {
                context.startServiceAsUser(
                    Intent(context, LightService::class.java),
                    UserHandle.CURRENT
                )
            }
        }.onFailure { Log.e(TAG, "Failed to start LightService on boot", it) }

        // Ensure ChargingLimitService re-evaluates user preferences after unlock
        runCatching {
            context.startServiceAsUser(
                Intent(context, ChargingLimitService::class.java),
                UserHandle.CURRENT
            )
        }.onFailure { Log.e(TAG, "Failed to start ChargingLimitService on boot", it) }
    }

    private fun onLockedBootCompleted(context: Context) {
        // Display
        runCatching {
            context.startServiceAsUser(Intent(context, ColorService::class.java), UserHandle.CURRENT)
        }.onFailure { Log.e(TAG, "Failed to start ColorService", it) }

        // Override HDR types to enable Dolby Vision
        runCatching {
            val displayManager = context.getSystemService(DisplayManager::class.java)
            displayManager?.overrideHdrTypes(
                Display.DEFAULT_DISPLAY,
                intArrayOf(
                    HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                    HdrCapabilities.HDR_TYPE_HDR10,
                    HdrCapabilities.HDR_TYPE_HLG,
                    HdrCapabilities.HDR_TYPE_HDR10_PLUS
                )
            )
        }.onFailure { Log.e(TAG, "Failed to override HDR types", it) }

        // Battery
        runCatching {
            context.startServiceAsUser(
                Intent(context, ChargingLimitService::class.java),
                UserHandle.CURRENT
            )
        }.onFailure { Log.e(TAG, "Failed to start ChargingLimitService at locked boot", it) }
    }
}
