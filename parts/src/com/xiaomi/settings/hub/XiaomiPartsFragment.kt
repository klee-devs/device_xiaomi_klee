/*
 * Copyright (C) 2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xiaomi.settings.hub

import android.content.Intent
import android.os.Bundle
import android.os.SystemProperties
import android.util.Log
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import com.xiaomi.settings.battery.ChargingControlActivity
import com.xiaomi.settings.corecontrol.CoreControlActivity
import com.xiaomi.settings.gamebar.GameBarSettingsActivity
import com.xiaomi.settings.light.LightSettingsActivity
import com.xiaomi.settings.touchsampling.TouchSamplingSettingsActivity
import com.xiaomi.settings.thermal.ThermalProfileSettingsActivity

class XiaomiPartsFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.xiaomi_parts, rootKey)

        findPreference<Preference>(KEY_BACKLIGHT)?.setOnPreferenceClickListener {
            startActivity(Intent(context, LightSettingsActivity::class.java))
            true
        }

        findPreference<Preference>(KEY_GAME_BAR)?.setOnPreferenceClickListener {
            startActivity(Intent(context, GameBarSettingsActivity::class.java))
            true
        }

        findPreference<Preference>(KEY_THERMAL_PROFILES)?.setOnPreferenceClickListener {
            startActivity(Intent(context, ThermalProfileSettingsActivity::class.java))
            true
        }

        findPreference<Preference>(KEY_TOUCH_SAMPLING)?.setOnPreferenceClickListener {
            startActivity(Intent(context, TouchSamplingSettingsActivity::class.java))
            true
        }

        findPreference<Preference>(KEY_CORE_CONTROL)?.setOnPreferenceClickListener {
            startActivity(Intent(context, CoreControlActivity::class.java))
            true
        }

        findPreference<Preference>(KEY_CHARGING_CONTROL)?.setOnPreferenceClickListener {
            startActivity(Intent(context, ChargingControlActivity::class.java))
            true
        }

        findPreference<SwitchPreferenceCompat>(KEY_LOW_LIGHT_FOD)?.apply {
            isChecked = SystemProperties.getBoolean(PROP_LOW_LIGHT_FOD, false)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    SystemProperties.set(
                        PROP_LOW_LIGHT_FOD,
                        if (newValue as Boolean) "1" else "0",
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update low-light fingerprint unlock", e)
                    false
                }
            }
        }

        findPreference<SwitchPreferenceCompat>(KEY_ZRAM_ENABLED)?.apply {
            isChecked = SystemProperties.getBoolean(PROP_ZRAM_ENABLED, false)
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean
                    ?: return@setOnPreferenceChangeListener false
                try {
                    SystemProperties.set(PROP_ZRAM_ENABLED, if (enabled) "1" else "0")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update ZRAM preference", e)
                    false
                }
            }
        }
    }

    companion object {
        private const val KEY_ZRAM_ENABLED = "parts_zram_enabled"
        private const val PROP_ZRAM_ENABLED = "persist.sys.xiaomi.zram_enabled"
        private const val KEY_BACKLIGHT = "parts_backlight"
        private const val KEY_GAME_BAR = "parts_game_bar"
        private const val KEY_THERMAL_PROFILES = "parts_thermal_profiles"
        private const val KEY_TOUCH_SAMPLING = "parts_touch_sampling"
        private const val KEY_CORE_CONTROL = "parts_core_control"
        private const val KEY_CHARGING_CONTROL = "parts_charging_control"
        private const val KEY_LOW_LIGHT_FOD = "parts_low_light_fingerprint"
        private const val PROP_LOW_LIGHT_FOD = "persist.sys.xiaomi.udfps_low_light"
        private const val TAG = "XiaomiParts"
    }
}
