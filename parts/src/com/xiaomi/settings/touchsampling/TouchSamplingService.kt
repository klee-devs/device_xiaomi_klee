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

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.gamebar.ForegroundAppDetector
import vendor.xiaomi.hw.touchfeature.ITouchFeature

class TouchSamplingService : Service() {

    @Volatile private var mTouchFeature: ITouchFeature? = null
    private var mScreenStateReceiver: BroadcastReceiver? = null
    private var mPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mDefaultPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mHandler: Handler? = null
    private var mPollingRunnable: Runnable? = null
    @Volatile private var mLastAppliedState = -1
    private var mIsScreenInteractive = true
    private var mListenersRegistered = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TouchSamplingService started")

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        mIsScreenInteractive = pm?.isInteractive ?: true

        initTouchFeature()
        registerScreenStateReceiver()
        ensurePreferenceListenersRegistered()
        updateTouchSamplingState()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (DEBUG) Log.d(TAG, "onStartCommand")
        ensurePreferenceListenersRegistered()
        updateTouchSamplingState()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "TouchSamplingService stopped")

        stopForegroundPolling()
        applyTouchSamplingRate(0, force = true)
        cancelTileNotificationIfNeeded()

        mScreenStateReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        mScreenStateReceiver = null

        if (mListenersRegistered && isUserUnlocked()) {
            runCatching {
                val sharedPref = getSharedPreferences(
                    TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
                )
                mPreferenceChangeListener?.let {
                    sharedPref.unregisterOnSharedPreferenceChangeListener(it)
                }
            }
            runCatching {
                val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
                mDefaultPreferenceChangeListener?.let {
                    defaultPref.unregisterOnSharedPreferenceChangeListener(it)
                }
            }
        }
        mListenersRegistered = false

        mHandler?.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun isUserUnlocked(): Boolean {
        val um = getSystemService(Context.USER_SERVICE) as? UserManager
        return um?.isUserUnlocked ?: false
    }

    private fun initTouchFeature() {
        try {
            val fqName = "${ITouchFeature.DESCRIPTOR}/default"
            val binder = android.os.Binder.allowBlocking(
                android.os.ServiceManager.waitForDeclaredService(fqName)
            )
            binder?.linkToDeath({
                Log.w(TAG, "ITouchFeature service died, resetting binder")
                mTouchFeature = null
                mLastAppliedState = -1
            }, 0)
            mTouchFeature = ITouchFeature.Stub.asInterface(binder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ITouchFeature", e)
        }
    }

    private fun registerScreenStateReceiver() {
        mScreenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (DEBUG) Log.d(TAG, "Screen turned off. Pausing polling.")
                        mIsScreenInteractive = false
                        stopForegroundPolling()
                        // Touch IC resets hardware registers when display powers down
                        mLastAppliedState = -1
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        if (DEBUG) Log.d(TAG, "Screen active (${intent.action}). Reapplying touch sampling rate.")
                        mIsScreenInteractive = true
                        mLastAppliedState = -1
                        ensurePreferenceListenersRegistered()
                        updateTouchSamplingState()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(mScreenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    private fun ensurePreferenceListenersRegistered() {
        if (mListenersRegistered || !isUserUnlocked()) return

        runCatching {
            val sharedPref = getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
            )
            mPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (TouchSamplingSettingsFragment.HTSR_STATE == key) {
                    val enabled = prefs.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false)
                    if (!enabled) {
                        cancelTileNotificationIfNeeded()
                    }
                    Log.d(TAG, "Preference changed (global=$enabled). Reapplying touch sampling rate.")
                    updateTouchSamplingState()
                }
            }
            sharedPref.registerOnSharedPreferenceChangeListener(mPreferenceChangeListener)

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
            mListenersRegistered = true
        }.onFailure { e ->
            Log.w(TAG, "Deferring preference listener registration until user unlock", e)
        }
    }

    private fun updateTouchSamplingState() {
        if (!mIsScreenInteractive || !isUserUnlocked()) return

        val (globalEnabled, autoEnable) = runCatching {
            val sharedGlobal = getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
            )
            val global = sharedGlobal.getBoolean(
                TouchSamplingSettingsFragment.HTSR_STATE, false
            )
            val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
            val auto = defaultPref.getBoolean(
                TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false
            )
            global to auto
        }.getOrElse {
            return
        }

        when {
            globalEnabled -> {
                stopForegroundPolling()
                applyTouchSamplingRate(1)
            }
            autoEnable -> {
                val autoApps = PreferenceManager.getDefaultSharedPreferences(this)
                    .getStringSet(TouchSamplingSettingsFragment.HTSR_APPS_PREF, emptySet())
                    ?: emptySet()
                if (autoApps.isEmpty()) {
                    stopForegroundPolling()
                    applyTouchSamplingRate(0)
                } else {
                    startForegroundPolling()
                }
            }
            else -> {
                stopForegroundPolling()
                applyTouchSamplingRate(0)
            }
        }
    }

    private fun applyTouchSamplingRate(state: Int, force: Boolean = false) {
        if (!force && mLastAppliedState == state && mTouchFeature != null) return

        if (mTouchFeature == null) {
            initTouchFeature()
        }

        val tf = mTouchFeature ?: return

        val primarySuccess = runCatching {
            tf.setTouchMode(0, TOUCH_GAME_MODE, state)
        }.onFailure { e ->
            Log.e(TAG, "Failed to set TOUCH_GAME_MODE", e)
            mTouchFeature = null
            mLastAppliedState = -1
        }.getOrDefault(false)

        if (!primarySuccess) {
            Log.w(TAG, "ITouchFeature rejected TOUCH_GAME_MODE state=$state")
            return
        }

        runCatching { tf.setTouchMode(0, TOUCH_SAMPLE_RATE_EXT, state) }
        runCatching { tf.setTouchMode(0, TOUCH_ACTIVE_MODE, state) }
        runCatching { tf.setTouchMode(0, TOUCH_TOLERANCE, if (state == 1) 34 else 0) }
        runCatching { tf.setTouchMode(0, TOUCH_UP_THRESHOLD, if (state == 1) 99 else 0) }
        runCatching { tf.setTouchMode(0, TOUCH_EDGE_FILTER, if (state == 1) 0 else 1) }

        mLastAppliedState = state
        if (DEBUG) Log.d(TAG, "Touch sampling modes applied: state=$state")
    }

    private fun startForegroundPolling() {
        if (!mIsScreenInteractive || !isUserUnlocked()) return
        if (mPollingRunnable != null) {
            checkAndApplyPerAppState()
            return
        }

        val handler = mHandler ?: Handler(Looper.getMainLooper()).also { mHandler = it }
        val runnable = object : Runnable {
            override fun run() {
                if (!mIsScreenInteractive) return
                try {
                    checkAndApplyPerAppState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during per-app check", e)
                }
                if (mPollingRunnable === this && mIsScreenInteractive) {
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
        if (!isUserUnlocked()) return

        val sharedGlobal = getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE
        )
        val globalEnabled = sharedGlobal.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false)
        if (globalEnabled) {
            applyTouchSamplingRate(1)
            return
        }

        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
        val autoEnable = defaultPref.getBoolean(TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false)
        if (!autoEnable) {
            applyTouchSamplingRate(0)
            return
        }

        val autoApps = defaultPref.getStringSet(
            TouchSamplingSettingsFragment.HTSR_APPS_PREF, emptySet()
        ) ?: emptySet()
        if (autoApps.isEmpty()) {
            applyTouchSamplingRate(0)
            return
        }
        val foreground = ForegroundAppDetector.getForegroundPackageName(this)
        val enable = autoApps.contains(foreground)
        applyTouchSamplingRate(if (enable) 1 else 0)
    }

    private fun cancelTileNotificationIfNeeded() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(TouchSamplingTileService.NOTIFICATION_ID)
        }
    }

    companion object {
        private const val TAG = "TouchSamplingService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val TOUCH_GAME_MODE = 0
        private const val TOUCH_ACTIVE_MODE = 1
        private const val TOUCH_UP_THRESHOLD = 2
        private const val TOUCH_TOLERANCE = 3
        private const val TOUCH_EDGE_FILTER = 7
        private const val TOUCH_SAMPLE_RATE_EXT = 202

        private const val POLLING_INTERVAL_MS = 1000L
    }
}
