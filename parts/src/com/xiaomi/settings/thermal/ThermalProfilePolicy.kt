/*
 * Copyright (C) 2026 The VoltageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.telecom.TelecomManager

/**
 * Conservative automatic profile selection using Android app categories and system roles.
 * Users can override this mapping for any launchable app in Xiaomi Parts.
 */
internal object ThermalProfilePolicy {

    private const val PROFILE_PHONE = 5
    private const val PROFILE_NAVIGATION = 10
    private const val PROFILE_VIDEO = 11
    private const val PROFILE_CAMERA = 15
    // Klee's stock scenario table uses profile 19 for generic games.
    private const val PROFILE_GAME = 19

    @Volatile
    private var cachedCameraPackages: Set<String>? = null

    fun automaticProfile(context: Context, packageName: String?): Int? {
        if (packageName == null) return null

        val telecomManager = context.getSystemService(TelecomManager::class.java)
        if (packageName == telecomManager?.defaultDialerPackage) {
            return PROFILE_PHONE
        }

        if (packageName in cameraPackages(context)) {
            return PROFILE_CAMERA
        }

        val category = try {
            context.packageManager.getApplicationInfo(packageName, 0).category
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        return when (category) {
            ApplicationInfo.CATEGORY_GAME -> PROFILE_GAME
            ApplicationInfo.CATEGORY_MAPS -> PROFILE_NAVIGATION
            ApplicationInfo.CATEGORY_VIDEO -> PROFILE_VIDEO
            else -> null
        }
    }

    private fun cameraPackages(context: Context): Set<String> {
        cachedCameraPackages?.let { return it }

        return synchronized(this) {
            cachedCameraPackages?.let { return@synchronized it }
            val packageManager = context.packageManager
            val packages = mutableSetOf<String>()
            for (action in listOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE)) {
                try {
                    packageManager.queryIntentActivities(
                        Intent(action),
                        PackageManager.MATCH_ALL
                    ).forEach { resolveInfo ->
                        resolveInfo.activityInfo?.packageName?.let(packages::add)
                    }
                } catch (_: Exception) {
                    // Missing visibility for a camera intent must not block other app categories.
                }
            }
            packages.toSet().also { cachedCameraPackages = it }
        }
    }
}
