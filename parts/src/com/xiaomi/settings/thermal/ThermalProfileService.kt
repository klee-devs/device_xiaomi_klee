/*
 * Copyright (C) 2026 The VoltageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.utils.ForegroundAppDetector

class ThermalProfileService : Service() {

    private var monitorThread: HandlerThread? = null
    private var monitorHandler: Handler? = null

    private val monitorRunnable = object : Runnable {
        override fun run() {
            val shouldContinue = try {
                updateThermalProfile()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update app thermal profile", e)
                canMonitor()
            }
            if (shouldContinue) {
                monitorHandler?.postDelayed(this, MONITOR_INTERVAL)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    monitorHandler?.removeCallbacks(monitorRunnable)
                    ThermalProfileController.restore(this@ThermalProfileService)
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> scheduleMonitoring()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("ThermalProfileMonitor")
        thread.start()
        monitorThread = thread
        monitorHandler = Handler(thread.looper)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        scheduleMonitoring()
    }

    private fun scheduleMonitoring() {
        monitorHandler?.removeCallbacks(monitorRunnable)
        monitorHandler?.post(monitorRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(ThermalProfileController.PREF_ENABLED, false)
        if (!enabled || !ThermalProfileController.hasAppProfiles(this)) {
            ThermalProfileController.restore(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun updateThermalProfile(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(ThermalProfileController.PREF_ENABLED, false) ||
            !ThermalProfileController.hasAppProfiles(this)
        ) {
            ThermalProfileController.restore(this)
            stopSelf()
            return false
        }

        if (!canMonitor()) {
            ThermalProfileController.restore(this)
            return false
        }

        val foregroundPackage = ForegroundAppDetector.getForegroundPackageName(this)
            .takeUnless { it == UNKNOWN_PACKAGE }
        ThermalProfileController.update(this, foregroundPackage)
        return true
    }

    private fun canMonitor(): Boolean {
        val screenInteractive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val userUnlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        return screenInteractive && userUnlocked
    }

    override fun onDestroy() {
        monitorHandler?.removeCallbacks(monitorRunnable)
        runCatching { unregisterReceiver(screenReceiver) }
        ThermalProfileController.restore(this)
        monitorThread?.quitSafely()
        monitorHandler = null
        monitorThread = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun syncService(context: Context) {
            val appContext = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            val shouldRun =
                prefs.getBoolean(ThermalProfileController.PREF_ENABLED, false) &&
                    ThermalProfileController.hasAppProfiles(appContext)
            val intent = Intent(appContext, ThermalProfileService::class.java)
            if (shouldRun) {
                appContext.startService(intent)
            } else {
                ThermalProfileController.restore(appContext)
                appContext.stopService(intent)
            }
        }

        private const val TAG = "ThermalProfileService"
        private const val MONITOR_INTERVAL = 2000L
        private const val UNKNOWN_PACKAGE = "Unknown"
    }
}
