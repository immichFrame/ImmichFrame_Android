package com.immichframe.immichframe

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log

/**
 * Service that listens for screen-off events (power button press) and returns to ImmichFrame.
 * Used when the user opens Android Settings and wants a quick way to return to the app.
 */
class PowerButtonReturnService : Service() {

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.d(TAG, "Screen off detected, returning to ImmichFrame")
                returnToApp()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created, registering screen-off receiver")

        // Register broadcast receiver for screen off events
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_NOT_STICKY // Don't restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is not a bound service
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed, unregistering receiver")
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered, ignore
            Log.w(TAG, "Receiver already unregistered", e)
        }
    }

    private fun returnToApp() {
        // Check if app is already in foreground
        if (isAppInForeground()) {
            Log.d(TAG, "App already in foreground, skipping return")
            stopSelf()
            return
        }

        // Cancel the timeout alarm since we're returning via power button
        SettingsTimeoutReceiver.cancelTimeout(this)

        // Launch ImmichFrame and bring it to foreground
        Log.d(TAG, "App in background, bringing to foreground via power button")
        val returnIntent = packageManager.getLaunchIntentForPackage(packageName)
        returnIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(returnIntent)

        // Stop this service since we're done
        stopSelf()
    }

    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false

        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "PowerButtonReturnService"

        /**
         * Start the service to begin listening for power button presses
         */
        fun start(context: Context) {
            val intent = Intent(context, PowerButtonReturnService::class.java)
            context.startService(intent)
        }

        /**
         * Stop the service to stop listening for power button presses
         */
        fun stop(context: Context) {
            val intent = Intent(context, PowerButtonReturnService::class.java)
            context.stopService(intent)
        }
    }
}
