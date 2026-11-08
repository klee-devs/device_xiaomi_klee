/*
 * Copyright (C) 2026 The VoltageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Applies a mapped stock thermal profile only while its app is foregrounded.
 * mi_thermald keeps temperature-based throttling active for every profile.
 */
internal object ThermalProfileController {

    const val PREF_ENABLED = "thermal_profile_enabled"
    const val PREF_APP_PROFILE_PREFIX = "thermal_profile_app_"

    private const val TAG = "ThermalProfileController"
    private const val PREF_OWNED_PROFILE = "thermal_profile_owned_profile"
    private const val NO_OWNED_PROFILE = -1
    private const val NORMAL_PROFILE = 0
    private val ALLOWED_PROFILES = setOf(0, 5, 9, 10, 11, 15, 18, 19, 20)
    private val sconfig = File("/sys/class/thermal/thermal_message/sconfig")
    private var lastWriteFailure: String? = null

    fun hasAppProfiles(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.all.any { (key, value) ->
            if (!key.startsWith(PREF_APP_PROFILE_PREFIX)) {
                false
            } else {
                val profile = (value as? String)?.toIntOrNull()
                profile != null && profile != NORMAL_PROFILE && profile in ALLOWED_PROFILES
            }
        }
    }

    @Synchronized
    fun update(context: Context, foregroundPackage: String?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentProfile = readProfile() ?: return
        val ownedProfile = prefs.getInt(PREF_OWNED_PROFILE, NO_OWNED_PROFILE)
        val requestedProfile = foregroundPackage?.let { packageName ->
            prefs.getString(PREF_APP_PROFILE_PREFIX + packageName, null)?.toIntOrNull()
        }?.takeIf { it in ALLOWED_PROFILES }

        if (ownedProfile != NO_OWNED_PROFILE) {
            // Preserve a profile another component changed after we applied ours.
            if (currentProfile != ownedProfile) {
                clearOwnership(prefs)
                return
            }

            if (requestedProfile == null || requestedProfile == NORMAL_PROFILE) {
                if (!writeProfile(NORMAL_PROFILE)) return
                clearOwnership(prefs)
                return
            }

            if (requestedProfile == currentProfile) return
            if (!writeProfile(requestedProfile)) return
            if (!prefs.edit().putInt(PREF_OWNED_PROFILE, requestedProfile).commit()) {
                writeProfile(currentProfile)
                Log.e(TAG, "Could not persist ownership of thermal profile $requestedProfile")
            }
            return
        }

        // Do not override a profile owned by another system component.
        if (currentProfile != NORMAL_PROFILE ||
            requestedProfile == null || requestedProfile == NORMAL_PROFILE
        ) {
            return
        }

        if (!writeProfile(requestedProfile)) return
        if (!prefs.edit().putInt(PREF_OWNED_PROFILE, requestedProfile).commit()) {
            writeProfile(NORMAL_PROFILE)
            Log.e(TAG, "Could not persist ownership of thermal profile $requestedProfile")
        }
    }

    @Synchronized
    fun restore(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val ownedProfile = prefs.getInt(PREF_OWNED_PROFILE, NO_OWNED_PROFILE)
        if (ownedProfile == NO_OWNED_PROFILE) return

        val currentProfile = readProfile() ?: return
        if (currentProfile == ownedProfile && !writeProfile(NORMAL_PROFILE)) return
        clearOwnership(prefs)
    }

    private fun clearOwnership(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(PREF_OWNED_PROFILE).commit()
    }

    private fun readProfile(): Int? = runCatching {
        sconfig.readText().trim().toInt()
    }.getOrNull()

    private fun writeProfile(profile: Int): Boolean {
        return try {
            sconfig.writeText(profile.toString())
            val applied = readProfile() == profile
            if (applied) {
                lastWriteFailure = null
            } else {
                logWriteFailure("sconfig did not accept profile $profile")
            }
            applied
        } catch (e: Exception) {
            logWriteFailure("Could not write thermal profile $profile", e)
            false
        }
    }

    private fun logWriteFailure(message: String, error: Throwable? = null) {
        if (lastWriteFailure == message) return
        lastWriteFailure = message
        if (error == null) {
            Log.w(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
    }
}
