package com.immichframe.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager (RTC_WAKEUP) at the next scheduled active-start time. Wakes the device by
 * bringing MainActivity to the foreground; MainActivity then re-evaluates the schedule and powers
 * the screen back on. Needed because the device may be in deep sleep, where the in-app Handler loop
 * is suspended.
 */
class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_WAKE = "com.immichframe.immichframe.ACTION_SCHEDULE_WAKE"
        const val REQUEST_CODE = 4711
    }
}
