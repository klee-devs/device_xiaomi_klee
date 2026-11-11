/*
 * Copyright (C) 2025 kenway214
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

package com.xiaomi.settings.gamebar

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import androidx.preference.PreferenceManager
import com.xiaomi.settings.utils.ForegroundAppDetector
import java.util.HashSet

class GameBarMonitorService : Service() {

    private var mHandler: Handler? = null
    private var mMonitorRunnable: Runnable? = null
    private var mIsScreenInteractive = false

    private val mScreenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    mIsScreenInteractive = false
                    mMonitorRunnable?.let { mHandler?.removeCallbacks(it) }
                    GameBar.getInstance(this@GameBarMonitorService).hide()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    mIsScreenInteractive = true
                    scheduleMonitoring()
                }
            }
        }
    }

    companion object {
        private const val MONITOR_INTERVAL = 2000L // 2 seconds
    }

    override fun onCreate() {
        super.onCreate()
        mHandler = Handler()
        mMonitorRunnable = object : Runnable {
            override fun run() {
                if (!mIsScreenInteractive) return
                monitorForegroundApp()
                if (shouldMonitorForegroundApp()) {
                    mHandler?.postDelayed(this, MONITOR_INTERVAL)
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(mScreenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        mIsScreenInteractive = getSystemService(PowerManager::class.java)?.isInteractive == true
        scheduleMonitoring()
    }

    private fun shouldMonitorForegroundApp(): Boolean {
        if (!mIsScreenInteractive) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return !prefs.getBoolean("game_bar_enable", false) &&
            prefs.getBoolean("game_bar_auto_enable", false)
    }

    private fun scheduleMonitoring() {
        if (!mIsScreenInteractive) return
        val handler = mHandler ?: return
        val runnable = mMonitorRunnable ?: return
        handler.removeCallbacks(runnable)
        handler.post(runnable)
    }

    private fun monitorForegroundApp() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val masterEnabled = prefs.getBoolean("game_bar_enable", false)
        if (masterEnabled) {
            GameBar.getInstance(this).applyPreferences()
            GameBar.getInstance(this).show()
            return
        }

        val autoEnabled = prefs.getBoolean("game_bar_auto_enable", false)
        if (!autoEnabled) {
            GameBar.getInstance(this).hide()
            return
        }

        val foreground = ForegroundAppDetector.getForegroundPackageName(this)
        val autoApps = prefs.getStringSet(GameBarAppSelectorFragment.PREF_AUTO_APPS, HashSet())!!
        if (autoApps.contains(foreground)) {
            GameBar.getInstance(this).applyPreferences()
            GameBar.getInstance(this).show()
        } else {
            GameBar.getInstance(this).hide()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        mMonitorRunnable?.let { mHandler?.removeCallbacks(it) }
        runCatching { unregisterReceiver(mScreenStateReceiver) }
        mHandler = null
        super.onDestroy()
    }
}
