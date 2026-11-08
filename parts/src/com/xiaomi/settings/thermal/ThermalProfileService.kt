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
                val delay = if (isScreenInteractive()) {
                    MONITOR_INTERVAL
                } else {
                    IN_CALL_MONITOR_INTERVAL
                }
                monitorHandler?.postDelayed(this, delay)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    monitorHandler?.removeCallbacks(monitorRunnable)
                    if (isCallActive()) {
                        scheduleMonitoring()
                    } else {
                        ThermalProfileController.restore(this@ThermalProfileService)
                    }
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
        val enabled = prefs.getBoolean(ThermalProfileController.PREF_ENABLED, true)
        if (!enabled) {
            ThermalProfileController.restore(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun updateThermalProfile(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(ThermalProfileController.PREF_ENABLED, true)) {
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

    private fun isScreenInteractive(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun isCallActive(): Boolean =
        getSystemService(android.telecom.TelecomManager::class.java)?.isInCall == true

    private fun canMonitor(): Boolean {
        val userUnlocked = getSystemService(UserManager::class.java)?.isUserUnlocked == true
        return userUnlocked && (isScreenInteractive() || isCallActive())
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
            val shouldRun = prefs.getBoolean(ThermalProfileController.PREF_ENABLED, true)
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
        private const val IN_CALL_MONITOR_INTERVAL = 10_000L
        private const val UNKNOWN_PACKAGE = "Unknown"
    }
}
