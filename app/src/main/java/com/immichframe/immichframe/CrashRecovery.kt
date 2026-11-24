package com.immichframe.immichframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Brings the frame back after an uncaught exception.
 *
 * A frame usually runs unattended, so a crash otherwise leaves it showing a system dialog
 * or the launcher until somebody walks up to it. This schedules a relaunch of MainActivity
 * shortly after a crash, then hands the exception on to the system's own handler, so the
 * crash is still logged and reported exactly as before.
 *
 * A crash within the first minute of running is left alone: relaunching would most likely
 * only repeat it, and a frame stuck in a restart loop is worse than one that has stopped.
 */
object CrashRecovery {
    private const val TAG = "CrashRecovery"
    private const val MIN_UPTIME_MS = 60_000L
    private const val RELAUNCH_DELAY_MS = 3_000L
    private const val REQUEST_CODE = 4712
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val startedAt = SystemClock.elapsedRealtime()
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (SystemClock.elapsedRealtime() - startedAt >= MIN_UPTIME_MS) {
                    scheduleRelaunch(appContext)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to schedule relaunch: ${e.message}")
            }
            systemHandler?.uncaughtException(thread, error)
        }
    }

    private fun scheduleRelaunch(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + RELAUNCH_DELAY_MS,
            pendingIntent,
        )
    }
}
