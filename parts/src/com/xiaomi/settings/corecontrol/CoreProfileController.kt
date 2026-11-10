/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.utils.fileExists
import com.xiaomi.settings.utils.readOneLine
import com.xiaomi.settings.utils.writeLine
import java.util.concurrent.Executors

/**
 * Reads and switches CPU cores, and keeps the chosen [CoreProfile] across reboots.
 *
 * Every core write goes through one worker thread, so the settings screen, the
 * quick settings tile and the boot restore never switch cores at the same time.
 */
object CoreProfileController {

    private const val TAG = "CoreProfile"

    /** The profile chosen by the user; re-applied at boot. Absent when the cores are set by hand. */
    private const val PREF_SAVED_PROFILE = "core_profile_saved"

    private const val CPU_PATH = "/sys/devices/system/cpu/cpu"
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 25L

    enum class CoreResult { DONE, MIN_LITTLE_CORES, FAILED }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isCoreOnline(core: Int): Boolean {
        val path = onlinePath(core)
        // A core without an online node can't be hotplugged, so it is always on.
        if (!fileExists(path)) return fileExists("$CPU_PATH$core")
        return readOneLine(path)?.trim() == "1"
    }

    /** The profile matching the cores' real state, or null for a hand-set layout. */
    fun currentProfile(): CoreProfile? = CoreProfile.matching(::isCoreOnline)

    /**
     * Switches the cores to [profile] and saves it if every core switched.
     * [onDone] runs on the main thread with whether it succeeded.
     */
    fun applyAsync(context: Context, profile: CoreProfile, onDone: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        executor.execute {
            val success = applyAndSave(appContext, profile)
            mainHandler.post { onDone(success) }
        }
    }

    /**
     * Moves to the profile after the current one; a hand-set layout moves to Balanced.
     * The next profile is picked on the worker, so quick repeated taps each advance one step.
     * [onDone] runs on the main thread with the resulting profile, or null for a custom layout.
     */
    fun cycleAsync(context: Context, onDone: (CoreProfile?) -> Unit = {}) {
        val appContext = context.applicationContext
        executor.execute {
            val next = currentProfile()?.next() ?: CoreProfile.BALANCED
            applyAndSave(appContext, next)
            val result = currentProfile()
            mainHandler.post { onDone(result) }
        }
    }

    /**
     * Switches one core, keeping at least [CoreProfile.MIN_LITTLE_CORES] little cores online.
     * Saves the profile the new layout matches, or clears it for a custom layout.
     */
    fun setCoreAsync(context: Context, core: Int, online: Boolean, onDone: (CoreResult) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val result = when {
                !online && !CoreProfile.canOffline(core, ::isCoreOnline) -> CoreResult.MIN_LITTLE_CORES
                setCoreOnline(core, online) -> CoreResult.DONE
                else -> CoreResult.FAILED
            }
            if (result == CoreResult.DONE) saveProfile(appContext, currentProfile())
            mainHandler.post { onDone(result) }
        }
    }

    /** Re-applies the profile saved before the reboot. Does nothing if none was chosen. */
    fun restoreSaved(context: Context) {
        val saved = CoreProfile.fromKey(prefs(context).getString(PREF_SAVED_PROFILE, null))
        saved?.let { applyAsync(context, it) }
    }

    private fun applyAndSave(context: Context, profile: CoreProfile): Boolean {
        val success = applyWith(profile, ::setCoreOnline)
        if (success) {
            saveProfile(context, profile)
        } else {
            Log.w(TAG, "Not every core reached the ${profile.key} layout")
        }
        return success
    }

    /**
     * Brings the profile's cores online first, then takes the rest offline from the
     * highest core down. If a core fails to come online, nothing is taken offline,
     * so a failed switch never leaves fewer cores up than before.
     */
    internal fun applyWith(profile: CoreProfile, setCore: (Int, Boolean) -> Boolean): Boolean {
        val allUp = profile.onlineCores.map { core -> setCore(core, true) }.all { it }
        if (!allUp) return false
        return (CoreProfile.NUM_CORES - 1 downTo 0)
            .filterNot(profile::isOnline)
            .map { core -> setCore(core, false) }
            .all { it }
    }

    private fun setCoreOnline(core: Int, online: Boolean): Boolean {
        val path = onlinePath(core)
        if (!fileExists(path)) return online && isCoreOnline(core)

        val target = if (online) "1" else "0"
        repeat(MAX_ATTEMPTS) {
            if (readOneLine(path)?.trim() == target) return true
            writeLine(path, target)
            SystemClock.sleep(RETRY_DELAY_MS)
        }
        return readOneLine(path)?.trim() == target
    }

    private fun saveProfile(context: Context, profile: CoreProfile?) {
        val editor = prefs(context).edit()
        if (profile == null) {
            editor.remove(PREF_SAVED_PROFILE)
        } else {
            editor.putString(PREF_SAVED_PROFILE, profile.key)
        }
        editor.apply()
    }

    private fun onlinePath(core: Int) = "$CPU_PATH$core/online"

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)
}
