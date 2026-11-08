/*
 * Copyright (C) 2026 The VoltageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R

class ThermalProfileAppSelectorFragment : SettingsBasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        val appCategory = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.thermal_profile_apps_category)
        }
        screen.addPreference(appCategory)
        loadApps(appCategory)
    }

    private fun loadApps(category: PreferenceCategory) {
        val context = requireContext()
        val packageManager = context.packageManager
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val entries = resources.getTextArray(R.array.thermal_profile_entries)
        val values = resources.getTextArray(R.array.thermal_profile_values)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<ApplicationInfo> {
                    prefs.getString(
                        ThermalProfileController.PREF_APP_PROFILE_PREFIX + it.packageName,
                        NO_PROFILE
                    ) != NO_PROFILE
                }.thenBy {
                    it.loadLabel(packageManager).toString().lowercase()
                }
            )
            .toList()

        for (appInfo in apps) {
            val packageName = appInfo.packageName
            val key = ThermalProfileController.PREF_APP_PROFILE_PREFIX + packageName
            val savedValue = prefs.getString(key, NO_PROFILE) ?: NO_PROFILE
            val selectedValue = savedValue.takeIf { values.contains(it) } ?: NO_PROFILE
            val label = appInfo.loadLabel(packageManager)
            val preference = ListPreference(context).apply {
                this.key = key
                title = label
                dialogTitle = getString(R.string.thermal_profile_dialog_title, label)
                icon = appInfo.loadIcon(packageManager)
                this.entries = entries
                entryValues = values
                isPersistent = true
                value = selectedValue
                summary = profileSummary(entries, values, selectedValue)
                setOnPreferenceChangeListener { changedPreference, newValue ->
                    val nextValue = newValue?.toString() ?: NO_PROFILE
                    changedPreference.summary = profileSummary(entries, values, nextValue)
                    Handler(Looper.getMainLooper()).post {
                        ThermalProfileService.syncService(context)
                    }
                    true
                }
            }
            category.addPreference(preference)
        }
    }

    private fun profileSummary(
        entries: Array<CharSequence>,
        values: Array<CharSequence>,
        value: String
    ): String {
        val index = values.indexOfFirst { it.toString() == value }
        return if (index >= 0) entries[index].toString()
        else getString(R.string.thermal_profile_no_override)
    }

    companion object {
        private const val NO_PROFILE = "none"
    }
}
