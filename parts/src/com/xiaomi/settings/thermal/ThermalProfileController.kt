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
 * Applies a mapped Klee thermal profile for the foreground app.
 * mi_thermald continues enforcing temperature-based limits for every profile.
 */
internal object ThermalProfileController {

    const val PREF_ENABLED = "thermal_profile_enabled"
    const val PREF_APP_PROFILE_PREFIX = "thermal_profile_app_"
    const val AUTO_PROFILE = "auto"

    private const val TAG = "ThermalProfileController"
    private const val NO_OWNED_PROFILE = -1
    private const val NORMAL_PROFILE = 0
    private const val LEGACY_AUTO_PROFILE = "none"
    private val ALLOWED_PROFILES = setOf(0, 5, 9, 10, 11, 15, 18, 19, 20)
    private val sconfig = File("/sys/class/thermal/thermal_message/sconfig")
    private var ownedProfile = NO_OWNED_PROFILE
    private var baseProfile = NORMAL_PROFILE
    private var lastWriteFailure: String? = null

    @Synchronized
    fun update(context: Context, foregroundPackage: String?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val currentProfile = readProfile() ?: return

        val savedSelection = foregroundPackage?.let { packageName ->
            prefs.getString(PREF_APP_PROFILE_PREFIX + packageName, AUTO_PROFILE)
        } ?: AUTO_PROFILE
        val customProfile = savedSelection.toIntOrNull()?.takeIf { it in ALLOWED_PROFILES }
        val isCustomProfile = customProfile != null
        val requestedProfile = customProfile ?: when (savedSelection) {
            AUTO_PROFILE, LEGACY_AUTO_PROFILE ->
                ThermalProfilePolicy.automaticProfile(context, foregroundPackage)
            else -> null
        }

        if (ownedProfile != NO_OWNED_PROFILE) {
            // Preserve a profile another component changed after we applied ours.
            if (currentProfile != ownedProfile) {
                clearOwnership()
                return
            }

            if (requestedProfile == null) {
                restoreOwnedProfile()
                return
            }

            if (requestedProfile == currentProfile) return
            if (!writeProfile(requestedProfile)) return
            ownedProfile = requestedProfile
            return
        }

        if (requestedProfile == null || requestedProfile == currentProfile) return

        // Automatic profiles take over the normal baseline. Explicit app choices
        // intentionally override the current profile and restore it when the app exits.
        if (!isCustomProfile && currentProfile != NORMAL_PROFILE) return

        if (!writeProfile(requestedProfile)) return
        baseProfile = currentProfile
        ownedProfile = requestedProfile
    }

    @Synchronized
    fun restore(context: Context) {
        if (ownedProfile == NO_OWNED_PROFILE) return

        val currentProfile = readProfile() ?: return
        if (currentProfile == ownedProfile) {
            restoreOwnedProfile()
        } else {
            clearOwnership()
        }
    }

    private fun restoreOwnedProfile() {
        if (!writeProfile(baseProfile)) return
        clearOwnership()
    }

    private fun clearOwnership() {
        ownedProfile = NO_OWNED_PROFILE
        baseProfile = NORMAL_PROFILE
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
