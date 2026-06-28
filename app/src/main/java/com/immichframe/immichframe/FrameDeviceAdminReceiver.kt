package com.immichframe.immichframe

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

class FrameDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, FrameDeviceAdminReceiver::class.java)
    }
}
