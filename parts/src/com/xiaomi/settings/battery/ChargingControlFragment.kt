/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import com.android.settingslib.widget.SliderPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

class ChargingControlFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var mLimitPref: SliderPreference? = null
    private var mMasterSwitch: MainSwitchPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.charging_control_settings, rootKey)

        mLimitPref = findPreference<SliderPreference>(BatteryUtils.PREF_CHARGING_LIMIT)?.apply {
            sliderIncrement = 5
            setTickVisible(true)
            onPreferenceChangeListener = this@ChargingControlFragment
            updateSummary(value)
        }

        mMasterSwitch = findPreference<MainSwitchPreference>(BatteryUtils.PREF_CHARGING_CTRL)?.apply {
            onPreferenceChangeListener = this@ChargingControlFragment
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        var isEnabled = mMasterSwitch?.isChecked ?: false
        var limit = mLimitPref?.value ?: 80

        if (preference == mMasterSwitch) {
            isEnabled = newValue as Boolean
        } else if (preference == mLimitPref) {
            limit = newValue as Int
            updateSummary(limit)
        }

        val serviceIntent = Intent(requireContext(), ChargingLimitService::class.java).apply {
            putExtra(ChargingLimitService.EXTRA_ENABLED, isEnabled)
            putExtra(ChargingLimitService.EXTRA_LIMIT, limit)
        }
        requireContext().startService(serviceIntent)
        return true
    }

    private fun updateSummary(value: Int) {
        mLimitPref?.summary = getString(
            R.string.charging_limit_summary,
            value,
            (value - 5).coerceAtLeast(0)
        )
    }

    override fun onDestroyView() {
        mLimitPref?.onPreferenceChangeListener = null
        mMasterSwitch?.onPreferenceChangeListener = null
        mLimitPref = null
        mMasterSwitch = null
        super.onDestroyView()
    }
}
