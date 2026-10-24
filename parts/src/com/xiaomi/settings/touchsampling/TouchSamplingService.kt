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

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.xiaomi.settings.gamebar.ForegroundAppDetector
import vendor.xiaomi.hw.touchfeature.ITouchFeature

class TouchSamplingService : Service() {

    private var mTouchFeature: ITouchFeature? = null
    private var mScreenUnlockReceiver: BroadcastReceiver? = null
    private var mPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mDefaultPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mHandler: Handler? = null
    private var mPollingRunnable: Runnable? = null
    private var mLastAppliedState = -1

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TouchSamplingService started")

        // Initialize the touchfeature
        initTouchFeature()

        // Initialize and register the broadcast receiver
        registerScreenUnlockReceiver()

        // Register the preference listeners and apply the configured mode.
        registerPreferenceChangeListener()
        registerDefaultPreferenceChangeListener()
        updateTouchSamplingState()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (DEBUG) Log.d(TAG, "onStartCommand")
        updateTouchSamplingState()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "TouchSamplingService stopped")

        stopForegroundPolling()
        applyTouchSamplingRate(0)

        if (mScreenUnlockReceiver != null) {
            unregisterReceiver(mScreenUnlockReceiver)
        }

        val sharedPref = getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
        )
        if (mPreferenceChangeListener != null) {
            sharedPref.unregisterOnSharedPreferenceChangeListener(mPreferenceChangeListener)
        }

        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
        if (mDefaultPreferenceChangeListener != null) {
            defaultPref.unregisterOnSharedPreferenceChangeListener(mDefaultPreferenceChangeListener)
        }

        mHandler?.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun initTouchFeature() {
        try {
            val name = "default"
            val fqName = ITouchFeature.DESCRIPTOR + "/" + name
            val binder = android.os.Binder.allowBlocking(
                android.os.ServiceManager.waitForDeclaredService(fqName)
            )
            mTouchFeature = ITouchFeature.Stub.asInterface(binder)
        } catch (e: Exception) {
            // Silent catch
        }
    }

    /**
     * Registers a BroadcastReceiver to handle screen unlock and screen on events.
     */
    private fun registerScreenUnlockReceiver() {
        mScreenUnlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_USER_PRESENT == intent.action || Intent.ACTION_SCREEN_ON == intent.action) {
                    Log.d(TAG, "Screen turned on or device unlocked. Reapplying touch sampling rate.")
                    updateTouchSamplingState()
                }
            }
        }

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_USER_PRESENT)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        registerReceiver(mScreenUnlockReceiver, filter)
    }

    /**
     * Registers a SharedPreferences.OnSharedPreferenceChangeListener to monitor
     * changes in the touch sampling rate setting.
     */
    private fun registerPreferenceChangeListener() {
        val sharedPref = getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
        )

        mPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (TouchSamplingSettingsFragment.HTSR_STATE == key) {
                Log.d(TAG, "Preference changed. Reapplying touch sampling rate.")
                updateTouchSamplingState()
            }
        }

        sharedPref.registerOnSharedPreferenceChangeListener(mPreferenceChangeListener)
    }

    private fun registerDefaultPreferenceChangeListener() {
        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)

        mDefaultPreferenceChangeListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY ||
                    key == TouchSamplingSettingsFragment.HTSR_APPS_PREF
                ) {
                    updateTouchSamplingState()
                }
            }

        defaultPref.registerOnSharedPreferenceChangeListener(mDefaultPreferenceChangeListener)
    }

    private fun updateTouchSamplingState() {
        val sharedGlobal = getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
        )
        val globalEnabled = sharedGlobal.getBoolean(
            TouchSamplingSettingsFragment.HTSR_STATE, false
        )
        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
        val autoEnable = defaultPref.getBoolean(
            TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false
        )

        when {
            globalEnabled -> {
                stopForegroundPolling()
                applyTouchSamplingRate(1)
            }
            autoEnable -> startForegroundPolling()
            else -> {
                stopForegroundPolling()
                applyTouchSamplingRate(0)
            }
        }
    }

    /**
     * Applies the given touch sampling rate state directly to the hardware file.
     *
     * @param state 1 to enable high touch sampling rate, 0 to disable it.
     */
    private fun applyTouchSamplingRate(state: Int) {
        try {
            // Avoid reapplying the same state repeatedly
            if (mLastAppliedState == state) return

            if (mTouchFeature != null) {
                mTouchFeature!!.setTouchMode(0, TOUCH_GAME_MODE, state)
                mTouchFeature!!.setTouchMode(0, 202, state)
                mTouchFeature!!.setTouchMode(0, 1, state)
                mTouchFeature!!.setTouchMode(0, 3, if (state == 1) 34 else 0)
                mTouchFeature!!.setTouchMode(0, 2, if (state == 1) 99 else 0)
                mTouchFeature!!.setTouchMode(0, 7, if (state == 1) 0 else 1)
            }
            mLastAppliedState = state
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set touch sampling state", e)
        }
    }

    private fun startForegroundPolling() {
        if (mPollingRunnable != null) return

        val handler = mHandler ?: Handler(Looper.getMainLooper()).also { mHandler = it }
        val runnable = object : Runnable {
            override fun run() {
                try {
                    checkAndApplyPerAppState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during per-app check", e)
                }
                if (mPollingRunnable === this) {
                    handler.postDelayed(this, POLLING_INTERVAL_MS)
                }
            }
        }

        mPollingRunnable = runnable
        handler.post(runnable)
    }

    private fun stopForegroundPolling() {
        mPollingRunnable?.let { runnable ->
            mHandler?.removeCallbacks(runnable)
        }
        mPollingRunnable = null
    }

    private fun checkAndApplyPerAppState() {
        // Global setting takes precedence
        val sharedGlobal = getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
        )
        val globalEnabled = sharedGlobal.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false)
        if (globalEnabled) {
            applyTouchSamplingRate(1)
            return
        }

        // Check per-app auto-enable
        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
        val autoEnable = defaultPref.getBoolean(TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false)
        if (!autoEnable) {
            applyTouchSamplingRate(0)
            return
        }

        val autoApps = defaultPref.getStringSet(TouchSamplingSettingsFragment.HTSR_APPS_PREF, HashSet()) ?: HashSet()
        val foreground = ForegroundAppDetector.getForegroundPackageName(this)
        val enable = autoApps.contains(foreground)
        applyTouchSamplingRate(if (enable) 1 else 0)
    }

    companion object {
        private const val TAG = "TouchSamplingService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val TOUCH_GAME_MODE = 0
        private const val POLLING_INTERVAL_MS = 1000L
    }
}
