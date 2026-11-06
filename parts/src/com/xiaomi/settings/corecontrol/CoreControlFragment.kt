/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import com.xiaomi.settings.R
import java.io.File
import java.nio.file.Files

class CoreControlFragment : SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val TAG = "CoreControlFragment"
        private const val NUM_CORES = 8

        const val KEY_GED_BOOST = "ged_boost_enable"
        const val KEY_GED_GAME_MODE = "ged_game_mode"
        const val KEY_GPU_DVFS = "ged_gpu_dvfs_enable"
        const val KEY_GPU_BOOST_LEVEL = "ged_gpu_boost_level"

        const val PATH_GED_BOOST = "/sys/module/ged/parameters/ged_boost_enable"
        const val PATH_GED_GAME_MODE = "/sys/module/ged/parameters/gx_game_mode"
        const val PATH_GPU_DVFS = "/sys/module/ged/parameters/gpu_dvfs_enable"
        const val PATH_GPU_BOOST_LEVEL = "/sys/kernel/ged/hal/gpu_boost_level"

        fun restoreSettings(context: Context) {
            val dpContext = context.createDeviceProtectedStorageContext()
            val prefs = PreferenceManager.getDefaultSharedPreferences(dpContext)
            if (prefs.contains(KEY_GED_BOOST)) {
                writeSysfs(PATH_GED_BOOST, if (prefs.getBoolean(KEY_GED_BOOST, true)) "1" else "0")
            }
            if (prefs.contains(KEY_GED_GAME_MODE)) {
                writeSysfs(PATH_GED_GAME_MODE, if (prefs.getBoolean(KEY_GED_GAME_MODE, false)) "1" else "0")
            }
            if (prefs.contains(KEY_GPU_DVFS)) {
                writeSysfs(PATH_GPU_DVFS, if (prefs.getBoolean(KEY_GPU_DVFS, true)) "1" else "0")
            }
            if (prefs.contains(KEY_GPU_BOOST_LEVEL)) {
                writeSysfs(PATH_GPU_BOOST_LEVEL, if (prefs.getBoolean(KEY_GPU_BOOST_LEVEL, false)) "101" else "0")
            }
        }

        private fun writeSysfs(path: String, value: String) {
            try {
                val file = File(path)
                if (file.exists()) {
                    Files.write(file.toPath(), value.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write $path", e)
            }
        }
    }

    private val mCorePrefs = arrayOfNulls<SwitchPreferenceCompat>(NUM_CORES)
    private var mGedBoostPref: SwitchPreferenceCompat? = null
    private var mGedGameModePref: SwitchPreferenceCompat? = null
    private var mGpuDvfsPref: SwitchPreferenceCompat? = null
    private var mGpuBoostLevelPref: SwitchPreferenceCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.core_control_settings, rootKey)

        for (i in 0 until NUM_CORES) {
            val key = "core_$i"
            mCorePrefs[i] = findPreference(key)
            mCorePrefs[i]?.apply {
                onPreferenceChangeListener = this@CoreControlFragment
                isChecked = isCoreOnline(i)
            }
        }

        mGedBoostPref = findPreference<SwitchPreferenceCompat>(KEY_GED_BOOST)?.apply {
            isEnabled = File(PATH_GED_BOOST).exists()
            isChecked = isSysfsEnabled(PATH_GED_BOOST, true)
            onPreferenceChangeListener = this@CoreControlFragment
        }

        mGedGameModePref = findPreference<SwitchPreferenceCompat>(KEY_GED_GAME_MODE)?.apply {
            isEnabled = File(PATH_GED_GAME_MODE).exists()
            isChecked = isSysfsEnabled(PATH_GED_GAME_MODE, false)
            onPreferenceChangeListener = this@CoreControlFragment
        }

        mGpuDvfsPref = findPreference<SwitchPreferenceCompat>(KEY_GPU_DVFS)?.apply {
            isEnabled = File(PATH_GPU_DVFS).exists()
            isChecked = isSysfsEnabled(PATH_GPU_DVFS, true)
            onPreferenceChangeListener = this@CoreControlFragment
        }

        mGpuBoostLevelPref = findPreference<SwitchPreferenceCompat>(KEY_GPU_BOOST_LEVEL)?.apply {
            isEnabled = File(PATH_GPU_BOOST_LEVEL).exists()
            val curBoost = readFile(PATH_GPU_BOOST_LEVEL).toIntOrNull() ?: 0
            isChecked = curBoost >= 100
            onPreferenceChangeListener = this@CoreControlFragment
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        val requestedState = newValue as Boolean

        when (preference.key) {
            KEY_GED_BOOST -> {
                writeFile(PATH_GED_BOOST, if (requestedState) "1" else "0")
                return true
            }
            KEY_GED_GAME_MODE -> {
                writeFile(PATH_GED_GAME_MODE, if (requestedState) "1" else "0")
                return true
            }
            KEY_GPU_DVFS -> {
                writeFile(PATH_GPU_DVFS, if (requestedState) "1" else "0")
                return true
            }
            KEY_GPU_BOOST_LEVEL -> {
                writeFile(PATH_GPU_BOOST_LEVEL, if (requestedState) "101" else "0")
                return true
            }
        }

        for (i in 0 until NUM_CORES) {
            if (preference === mCorePrefs[i]) {
                if (!requestedState && !canOffline(i)) {
                    Toast.makeText(
                        context,
                        "At least 2 little cores must remain online",
                        Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                setCoreState(i, requestedState)
                return true
            }
        }
        return false
    }

    private fun isCoreOnline(core: Int): Boolean {
        val path = "/sys/devices/system/cpu/cpu$core/online"
        return File(path).exists() && readFile(path) == "1"
    }

    private fun setCoreState(core: Int, online: Boolean) {
        writeFile("/sys/devices/system/cpu/cpu$core/online", if (online) "1" else "0")
    }

    private fun canOffline(core: Int): Boolean {
        if (core in 0..3) {
            var onlineCount = 0
            for (i in 0..3) {
                if (i != core && isCoreOnline(i)) onlineCount++
            }
            return onlineCount >= 2
        }
        return true
    }

    private fun isSysfsEnabled(path: String, defVal: Boolean): Boolean {
        val raw = readFile(path)
        if (raw.isEmpty()) return defVal
        return raw == "1" || raw.equals("Y", ignoreCase = true)
    }

    private fun readFile(path: String): String {
        return try {
            String(Files.readAllBytes(File(path).toPath())).trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $path", e)
            ""
        }
    }

    private fun writeFile(path: String, value: String) {
        try {
            Files.write(File(path).toPath(), value.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $path", e)
        }
    }
}
