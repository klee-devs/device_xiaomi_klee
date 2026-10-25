/*
 * Copyright (C) 2023-2026 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SliderPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

class ChargingControlFragment : SettingsBasePreferenceFragment(),
    Preference.OnPreferenceChangeListener {

    private var mBypassSwitch: MainSwitchPreference? = null
    private var mLimitSwitch: SwitchPreferenceCompat? = null
    private var mLimitPref: SliderPreference? = null
    private var mBatteryReceiverRegistered = false

    private val mBatteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_BATTERY_CHANGED == intent.action) {
                updateChargerState(intent)
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.charging_control_settings, rootKey)

        mBypassSwitch = findPreference<MainSwitchPreference>(BatteryUtils.PREF_BYPASS_CHARGING)?.apply {
            onPreferenceChangeListener = this@ChargingControlFragment
        }

        mLimitSwitch = findPreference<SwitchPreferenceCompat>(BatteryUtils.PREF_CHARGING_CTRL)?.apply {
            onPreferenceChangeListener = this@ChargingControlFragment
        }

        mLimitPref = findPreference<SliderPreference>(BatteryUtils.PREF_CHARGING_LIMIT)?.apply {
            sliderIncrement = 5
            setTickVisible(true)
            onPreferenceChangeListener = this@ChargingControlFragment
            updateSummary(value)
        }

        updateLimitAvailability()
    }

    override fun onResume() {
        super.onResume()
        val context = requireContext()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        updateChargerState(context.registerReceiver(null, filter))
        if (!mBatteryReceiverRegistered) {
            context.registerReceiver(mBatteryReceiver, filter)
            mBatteryReceiverRegistered = true
        }
        context.startService(Intent(context, ChargingLimitService::class.java))
    }

    override fun onPause() {
        unregisterBatteryReceiver()
        super.onPause()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        var bypassEnabled = mBypassSwitch?.isChecked ?: false
        var limitEnabled = mLimitSwitch?.isChecked ?: false
        var limit = mLimitPref?.value ?: 80

        when (preference) {
            mBypassSwitch -> bypassEnabled = newValue as Boolean
            mLimitSwitch -> limitEnabled = newValue as Boolean
            mLimitPref -> {
                limit = newValue as Int
                updateSummary(limit)
            }
        }

        updateLimitAvailability(bypassEnabled, limitEnabled)

        val serviceIntent = Intent(requireContext(), ChargingLimitService::class.java).apply {
            putExtra(ChargingLimitService.EXTRA_BYPASS_ENABLED, bypassEnabled)
            putExtra(ChargingLimitService.EXTRA_LIMIT_ENABLED, limitEnabled)
            putExtra(ChargingLimitService.EXTRA_LIMIT, limit)
        }
        requireContext().startService(serviceIntent)
        return true
    }

    private fun updateChargerState(intent: Intent?) {
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        mBypassSwitch?.isEnabled = plugged
        if (!plugged && mBypassSwitch?.isChecked == true) {
            mBypassSwitch?.isChecked = false
        }
        updateLimitAvailability()
    }

    private fun updateLimitAvailability(
        bypassEnabled: Boolean = mBypassSwitch?.isChecked ?: false,
        limitEnabled: Boolean = mLimitSwitch?.isChecked ?: false
    ) {
        mLimitSwitch?.isEnabled = !bypassEnabled
        mLimitPref?.isEnabled = !bypassEnabled && limitEnabled
    }

    private fun updateSummary(value: Int) {
        mLimitPref?.summary = getString(
            R.string.charging_limit_summary,
            value,
            (value - 5).coerceAtLeast(0)
        )
    }

    private fun unregisterBatteryReceiver() {
        if (mBatteryReceiverRegistered) {
            context?.unregisterReceiver(mBatteryReceiver)
            mBatteryReceiverRegistered = false
        }
    }

    override fun onDestroyView() {
        unregisterBatteryReceiver()
        mBypassSwitch?.onPreferenceChangeListener = null
        mLimitSwitch?.onPreferenceChangeListener = null
        mLimitPref?.onPreferenceChangeListener = null
        mBypassSwitch = null
        mLimitSwitch = null
        mLimitPref = null
        super.onDestroyView()
    }
}
