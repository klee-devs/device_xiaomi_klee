/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import android.os.Bundle
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import com.xiaomi.settings.corecontrol.CoreProfileController.CoreResult

class CoreControlFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val KEY_PROFILE = "core_profile"
    }

    private val corePrefs = arrayOfNulls<SwitchPreferenceCompat>(CoreProfile.NUM_CORES)
    private var profilePref: ListPreference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.core_control_settings, rootKey)

        profilePref = findPreference<ListPreference>(KEY_PROFILE)?.apply {
            onPreferenceChangeListener = this@CoreControlFragment
        }
        for (i in 0 until CoreProfile.NUM_CORES) {
            corePrefs[i] = findPreference<SwitchPreferenceCompat>("core_$i")?.apply {
                onPreferenceChangeListener = this@CoreControlFragment
            }
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        // The quick settings tile may have switched cores while we were away.
        refreshState()
    }

    // Changes are applied on the controller's worker; the UI updates from sysfs once it is done.
    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (preference === profilePref) {
            val profile = CoreProfile.fromKey(newValue as? String) ?: return false
            setControlsEnabled(false)
            CoreProfileController.applyAsync(requireContext(), profile) { success ->
                onSwitched(if (success) null else R.string.core_profile_apply_failed)
            }
            return false
        }

        val core = corePrefs.indexOfFirst { it === preference }
        if (core < 0) return false

        setControlsEnabled(false)
        CoreProfileController.setCoreAsync(requireContext(), core, newValue as Boolean) { result ->
            onSwitched(
                when (result) {
                    CoreResult.DONE -> null
                    CoreResult.MIN_LITTLE_CORES -> R.string.core_control_min_little_cores
                    CoreResult.FAILED -> R.string.core_profile_apply_failed
                }
            )
        }
        return false
    }

    private fun onSwitched(errorMessage: Int?) {
        if (!isAdded) return
        errorMessage?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        setControlsEnabled(true)
        refreshState()
    }

    private fun refreshState() {
        for (i in 0 until CoreProfile.NUM_CORES) {
            corePrefs[i]?.isChecked = CoreProfileController.isCoreOnline(i)
        }
        val profile = CoreProfileController.currentProfile()
        profilePref?.apply {
            value = profile?.key
            summary = getString(profile?.summaryRes ?: R.string.core_profile_custom_summary)
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        profilePref?.isEnabled = enabled
        corePrefs.forEach { it?.isEnabled = enabled }
    }
}
