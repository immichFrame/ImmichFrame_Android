package com.immichframe.immichframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that handles the 2-minute timeout when user opens Android Settings.
 * When the alarm fires, it brings ImmichFrame back to the foreground and stops the
 * PowerButtonReturnService.
 */
class SettingsTimeoutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Settings timeout alarm fired, returning to ImmichFrame")

        // Stop the power button service since we're returning via timeout
        PowerButtonReturnService.stop(context)

        // Launch ImmichFrame and bring it to foreground
        val returnIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        returnIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(returnIntent)
    }

    companion object {
        private const val TAG = "SettingsTimeoutReceiver"
        private const val ACTION_TIMEOUT = "com.immichframe.immichframe.SETTINGS_TIMEOUT"
        private const val TIMEOUT_MILLIS = 2 * 60 * 1000L // 2 minutes

        /**
         * Schedule the 2-minute timeout alarm
         */
        fun scheduleTimeout(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SettingsTimeoutReceiver::class.java).apply {
                action = ACTION_TIMEOUT
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            // Schedule alarm for 2 minutes from now
            val triggerTime = System.currentTimeMillis() + TIMEOUT_MILLIS

            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    // Android 12+ - check if we can schedule exact alarms
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.d(TAG, "Settings timeout alarm scheduled (exact) for 2 minutes from now")
                    } else {
                        // Fallback to inexact alarm if exact permission not granted
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Log.d(TAG, "Settings timeout alarm scheduled (inexact) for ~2 minutes from now")
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Settings timeout alarm scheduled (exact) for 2 minutes from now")
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Settings timeout alarm scheduled (exact) for 2 minutes from now")
                }
            }
        }

        /**
         * Cancel the timeout alarm
         */
        fun cancelTimeout(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SettingsTimeoutReceiver::class.java).apply {
                action = ACTION_TIMEOUT
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            alarmManager.cancel(pendingIntent)

            Log.d(TAG, "Settings timeout alarm cancelled")
        }
    }
}
