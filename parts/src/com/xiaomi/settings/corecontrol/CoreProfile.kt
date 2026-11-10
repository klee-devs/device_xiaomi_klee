/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.corecontrol

import com.xiaomi.settings.R

/**
 * Preset core layouts for MT6899.
 *
 * cpu0-3 are the little cores (policy0), cpu4-6 the big cores (policy4)
 * and cpu7 the prime core (policy7).
 */
enum class CoreProfile(
    val key: String,
    val onlineCores: IntRange,
    val titleRes: Int,
    val summaryRes: Int,
) {
    POWER_SAVING(
        "power_saving",
        0..3,
        R.string.core_profile_power_saving,
        R.string.core_profile_power_saving_summary,
    ),
    BALANCED(
        "balanced",
        0..6,
        R.string.core_profile_balanced,
        R.string.core_profile_balanced_summary,
    ),
    PERFORMANCE(
        "performance",
        0..7,
        R.string.core_profile_performance,
        R.string.core_profile_performance_summary,
    );

    fun isOnline(core: Int): Boolean = core in onlineCores

    /** The profile the quick settings tile moves to on the next tap. */
    fun next(): CoreProfile = ALL[(ordinal + 1) % ALL.size]

    companion object {
        const val NUM_CORES = 8
        val LITTLE_CORES = 0..3
        const val MIN_LITTLE_CORES = 2

        private val ALL = values()

        fun fromKey(key: String?): CoreProfile? = ALL.firstOrNull { it.key == key }

        /** The profile whose layout matches [online], or null when the cores are set by hand. */
        fun matching(online: (Int) -> Boolean): CoreProfile? =
            ALL.firstOrNull { profile ->
                (0 until NUM_CORES).all { core -> online(core) == profile.isOnline(core) }
            }

        /** Whether [core] may go offline without leaving fewer than [MIN_LITTLE_CORES] little cores. */
        fun canOffline(core: Int, online: (Int) -> Boolean): Boolean {
            if (core !in LITTLE_CORES) return true
            return LITTLE_CORES.count { it != core && online(it) } >= MIN_LITTLE_CORES
        }
    }
}
