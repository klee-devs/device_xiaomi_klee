/*
 * Copyright (C) 2026 The VoltageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

class ThermalProfileSettingsFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.thermal_profile_preferences, rootKey)

        findPreference<SwitchPreferenceCompat>(ThermalProfileController.PREF_ENABLED)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue !is Boolean) {
                    return@setOnPreferenceChangeListener false
                }
                val context = requireContext()
                Handler(Looper.getMainLooper()).post {
                    ThermalProfileService.syncService(context)
                }
                true
            }

        findPreference<Preference>(KEY_APP_PROFILES)?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ThermalProfileAppSelectorActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        ThermalProfileService.syncService(requireContext())
    }

    companion object {
        private const val KEY_APP_PROFILES = "thermal_profile_app_profiles"
    }
}
