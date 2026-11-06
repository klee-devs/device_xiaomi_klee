/*
 * Copyright (C) 2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xiaomi.settings.touchsampling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.R

class TouchSamplingTileService : TileService() {

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        setupNotificationChannel()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        Log.d(TAG, "Tile added")
        updateTileState()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Log.d(TAG, "Tile removed")
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "Tile started listening")
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        Log.d(TAG, "Tile clicked")

        toggleTouchSampling()
        updateTileState()
    }

    private fun updateTileState() {
        val enabled = isTouchSamplingEnabled()

        qsTile?.apply {
            state = if (enabled) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }

            updateTile()
        }
    }

    private fun toggleTouchSampling() {
        val newState = !isTouchSamplingEnabled()

        saveTouchSamplingState(newState)

        val serviceIntent = Intent(this, TouchSamplingService::class.java)
        val autoEnable = runCatching {
            PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false)
        }.getOrDefault(false)

        if (newState || autoEnable) {
            startService(serviceIntent)
        } else {
            stopService(serviceIntent)
        }

        if (newState) {
            showTouchSamplingNotification()
        } else {
            cancelTouchSamplingNotification()
        }
    }

    private fun isTouchSamplingEnabled(): Boolean {
        return runCatching {
            val sharedPref = getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR,
                Context.MODE_PRIVATE
            )
            sharedPref.getBoolean(
                TouchSamplingSettingsFragment.HTSR_STATE,
                false
            )
        }.getOrDefault(false)
    }

    private fun saveTouchSamplingState(state: Boolean) {
        runCatching {
            val sharedPref = getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR,
                Context.MODE_PRIVATE
            )
            sharedPref.edit()
                .putBoolean(TouchSamplingSettingsFragment.HTSR_STATE, state)
                .apply()
        }
    }

    private fun setupNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.touch_sampling_mode_title),
            NotificationManager.IMPORTANCE_DEFAULT
        )

        channel.setBlockable(true)

        notificationManager.createNotificationChannel(channel)
    }

    private fun showTouchSamplingNotification() {
        val intent = Intent(this, TouchSamplingSettingsActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(getString(R.string.touch_sampling_mode_title))
            .setContentText(getString(R.string.touch_sampling_mode_notification))
            .setSmallIcon(R.drawable.ic_touch_sampling_tile)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setFlag(Notification.FLAG_NO_CLEAR, true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelTouchSamplingNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "TouchSamplingTileService"

        private const val NOTIFICATION_CHANNEL_ID =
            "touch_sampling_tile_service_channel"

        const val NOTIFICATION_ID = 3
    }
}
