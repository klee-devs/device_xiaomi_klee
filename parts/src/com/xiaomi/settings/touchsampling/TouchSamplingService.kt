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
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.utils.ForegroundAppDetector
import vendor.xiaomi.hw.touchfeature.ITouchFeature

class TouchSamplingService : Service() {

    @Volatile private var mTouchFeature: ITouchFeature? = null
    private var mScreenStateReceiver: BroadcastReceiver? = null
    private var mPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mDefaultPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var mHandler: Handler? = null
    private var mPollingRunnable: Runnable? = null
    @Volatile private var mLastAppliedState = -1
    @Volatile private var mLastAppliedPackageName: String? = null
    @Volatile private var mLastFailedState = -1
    @Volatile private var mLastFailedPackageName: String? = null
    @Volatile private var mLastFailureUptimeMs = 0L
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
                mLastAppliedPackageName = null
                mLastFailedState = -1
                mLastFailedPackageName = null
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
                        applyTouchSamplingRate(TOUCH_STATE_DISABLED, force = true)
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        if (DEBUG) Log.d(TAG, "Screen active (${intent.action}). Reapplying touch sampling rate.")
                        mIsScreenInteractive = true
                        mLastAppliedState = -1
                        mLastAppliedPackageName = null
                        mLastFailedState = -1
                        mLastFailedPackageName = null
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
                startForegroundPolling()
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

    private fun applyTouchSamplingRate(
        state: Int,
        packageName: String? = null,
        force: Boolean = false,
    ) {
        val enabled = state == TOUCH_STATE_ENABLED
        val targetPackage = if (enabled) {
            packageName?.takeIf { it.isNotBlank() && it != UNKNOWN_FOREGROUND_PACKAGE }
        } else {
            ""
        }

        if (enabled && targetPackage.isNullOrEmpty()) {
            Log.w(TAG, "Cannot enable SuperTouch without a foreground package; restoring stock rate")
            applyTouchSamplingRate(TOUCH_STATE_DISABLED, force = true)
            return
        }

        if (!force && mLastAppliedState == state &&
            mLastAppliedPackageName == targetPackage && mTouchFeature != null
        ) return
        if (!force && mLastFailedState == state &&
            mLastFailedPackageName == targetPackage &&
            SystemClock.elapsedRealtime() - mLastFailureUptimeMs < APPLY_RETRY_INTERVAL_MS
        ) return

        if (mTouchFeature == null) initTouchFeature()
        val tf = mTouchFeature
        if (tf == null) {
            recordApplyFailure(state, targetPackage)
            Log.w(TAG, "ITouchFeature unavailable; retrying state=$state package=$targetPackage later")
            return
        }

        val applied = if (enabled) {
            val superTouchEnabled = setTouchModeValue(
                tf, TOUCH_SUPERTOUCH_MODE, SUPERTOUCH_ENABLE, "SuperTouch enable"
            )
            val maxLevelApplied = setTouchModeValue(
                tf, TOUCH_SUPERTOUCH_LEVEL_MODE, SUPERTOUCH_MAX_LEVEL, "SuperTouch maximum level"
            )
            val appListApplied = setTouchModePackageName(
                tf, TOUCH_SUPERTOUCH_APP_LIST_MODE, targetPackage!!
            )
            val success = superTouchEnabled && maxLevelApplied && appListApplied
            if (!success && mTouchFeature === tf) {
                setTouchModeValue(tf, TOUCH_SUPERTOUCH_MODE, SUPERTOUCH_DISABLE, "SuperTouch rollback")
                setTouchModePackageName(tf, TOUCH_SUPERTOUCH_APP_LIST_MODE, "")
            }
            success
        } else {
            val superTouchDisabled = setTouchModeValue(
                tf, TOUCH_SUPERTOUCH_MODE, SUPERTOUCH_DISABLE, "SuperTouch disable"
            )
            val stockLevelApplied = setTouchModeValue(
                tf, TOUCH_SUPERTOUCH_LEVEL_MODE, SUPERTOUCH_DEFAULT_LEVEL, "SuperTouch stock level"
            )
            val appListCleared = setTouchModePackageName(
                tf, TOUCH_SUPERTOUCH_APP_LIST_MODE, ""
            )
            superTouchDisabled && stockLevelApplied && appListCleared
        }

        if (mTouchFeature !== tf || !applied) {
            recordApplyFailure(state, targetPackage)
            Log.w(TAG, "Stock SuperTouch request failed: state=$state package=$targetPackage")
            return
        }

        mLastAppliedState = state
        mLastAppliedPackageName = targetPackage
        mLastFailedState = -1
        mLastFailedPackageName = null
        Log.i(
            TAG,
            "Applied stock SuperTouch: enabled=$enabled level=" +
                "${if (enabled) SUPERTOUCH_MAX_LEVEL else SUPERTOUCH_DEFAULT_LEVEL} " +
                "package=$targetPackage"
        )
    }

    /**
     * Vendor transaction 9 returns an int status, with 0 indicating success.
     * The frozen local AIDL exposes it as boolean and reads that 0 as false.
     */
    private fun setTouchModeValue(
        tf: ITouchFeature,
        mode: Int,
        value: Int,
        description: String,
    ): Boolean {
        if (mTouchFeature !== tf) return false

        val binder = tf.asBinder()
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(ITouchFeature.DESCRIPTOR)
            request.writeInt(TOUCH_ID)
            request.writeInt(mode)
            request.writeInt(value)
            val transacted = binder.transact(
                TRANSACTION_SET_MODE_VALUE, request, response, 0
            )
            if (!transacted) {
                Log.w(TAG, "Touch HAL rejected setModeValue transaction for mode=$mode")
                false
            } else {
                response.readException()
                val result = response.readInt()
                val accepted = result == TOUCH_MODE_RESULT_SUCCESS
                Log.i(
                    TAG,
                    "$description: mode=$mode value=$value result=$result accepted=$accepted"
                )
                accepted
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply $description: mode=$mode value=$value", e)
            mTouchFeature = null
            mLastAppliedState = -1
            mLastAppliedPackageName = null
            false
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    /** Mirrors stock TouchService's AIDL transaction for setModePackageName. */
    private fun setTouchModePackageName(
        tf: ITouchFeature,
        mode: Int,
        packageNames: String,
    ): Boolean {
        if (mTouchFeature !== tf) return false

        val binder = tf.asBinder()
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(ITouchFeature.DESCRIPTOR)
            request.writeInt(TOUCH_ID)
            request.writeInt(mode)
            request.writeString(packageNames)
            val transacted = binder.transact(
                TRANSACTION_SET_MODE_PACKAGE_NAME, request, response, 0
            )
            if (!transacted) {
                Log.w(TAG, "Touch HAL rejected setModePackageName transaction for mode=$mode")
                false
            } else {
                response.readException()
                Log.i(TAG, "setModePackageName: mode=$mode packages=$packageNames")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set touch package list for mode=$mode", e)
            mTouchFeature = null
            mLastAppliedState = -1
            mLastAppliedPackageName = null
            false
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    private fun recordApplyFailure(state: Int, packageName: String?) {
        mLastAppliedState = -1
        mLastAppliedPackageName = null
        mLastFailedState = state
        mLastFailedPackageName = packageName
        mLastFailureUptimeMs = SystemClock.elapsedRealtime()
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
        val defaultPref = PreferenceManager.getDefaultSharedPreferences(this)
        val autoEnable = defaultPref.getBoolean(TouchSamplingSettingsFragment.HTSR_AUTO_ENABLE_KEY, false)
        val autoApps = defaultPref.getStringSet(
            TouchSamplingSettingsFragment.HTSR_APPS_PREF, emptySet()
        ) ?: emptySet()
        val foreground = ForegroundAppDetector.getForegroundPackageName(this)
            .takeIf { it.isNotBlank() && it != UNKNOWN_FOREGROUND_PACKAGE }
        val selectedApp = foreground != null && autoApps.contains(foreground)
        val enable = foreground != null && (globalEnabled || (autoEnable && selectedApp))
        applyTouchSamplingRate(
            if (enable) TOUCH_STATE_ENABLED else TOUCH_STATE_DISABLED,
            if (enable) foreground else null,
        )
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

        private const val TOUCH_ID = 0
        private const val TOUCH_STATE_DISABLED = 0
        private const val TOUCH_STATE_ENABLED = 1
        private const val TOUCH_SUPERTOUCH_MODE = 0x2711
        private const val TOUCH_SUPERTOUCH_LEVEL_MODE = 0x2779
        private const val TOUCH_SUPERTOUCH_APP_LIST_MODE = 0x2777
        private const val SUPERTOUCH_ENABLE = 0x6d
        private const val SUPERTOUCH_DISABLE = 0x6e
        private const val SUPERTOUCH_DEFAULT_LEVEL = 2
        private const val SUPERTOUCH_MAX_LEVEL = 3
        private const val TRANSACTION_SET_MODE_VALUE = 9
        private const val TRANSACTION_SET_MODE_PACKAGE_NAME = 14
        private const val TOUCH_MODE_RESULT_SUCCESS = 0
        private const val UNKNOWN_FOREGROUND_PACKAGE = "Unknown"

        private const val POLLING_INTERVAL_MS = 1000L
        private const val APPLY_RETRY_INTERVAL_MS = 5000L
    }
}
