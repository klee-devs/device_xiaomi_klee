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
        if (DEBUG) Log.d(TAG, "Received boot completed intent: " + intent.action)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Unit
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> onLockedBootCompleted(context)
        }
        overrideHdrTypes(context)
    }

    private fun onLockedBootCompleted(context: Context) {
        startService(context, ColorService::class.java, "ColorService")

        val touchSamplingEnabled = runCatching {
            val sharedHtsr = context.getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
            )
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val globalEnabled = sharedHtsr.getBoolean(
                TouchSamplingSettingsFragment.HTSR_STATE, false
            )
            val autoEnabled = defaultPrefs.getBoolean(
                TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false
            )
            val autoApps = defaultPrefs.getStringSet(
                TouchSamplingSettingsFragment.HTSR_APPS_PREF, emptySet()
            ).orEmpty()
            globalEnabled || (autoEnabled && autoApps.isNotEmpty())
        }.onFailure {
            Log.e(TAG, "Failed to read touch sampling preferences", it)
        }.getOrDefault(false)

        if (touchSamplingEnabled) {
            startService(context, TouchSamplingService::class.java, "TouchSamplingService")
        }

        val lightEnabled = runCatching {
            PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("light_enable", false)
        }.onFailure {
            Log.e(TAG, "Failed to read light service preference", it)
        }.getOrDefault(false)

        if (lightEnabled) {
            startService(context, LightService::class.java, "LightService")
        }

        startService(context, ChargingLimitService::class.java, "ChargingLimitService")
    }

    private fun overrideHdrTypes(context: Context) {
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
        }.onFailure {
            Log.e(TAG, "Failed to override HDR types", it)
        }
    }

    private fun startService(context: Context, serviceClass: Class<*>, name: String) {
        runCatching {
            context.startServiceAsUser(
                Intent(context, serviceClass),
                UserHandle.CURRENT
            )
        }.onFailure {
            Log.e(TAG, "Failed to start " + name, it)
        }
    }
}
