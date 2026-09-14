package com.immichframe.immichframe

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream

class HardwareDisplayManager(
    context: Context,
    private val motionPath: String = "/dev/motion0",
    private var timeoutMillis: Long = 5 * 60 * 1000L,
    private var isEnabled: Boolean = true,
    private val onSleepRequest: () -> Boolean,
    private val onWakeRequest: () -> Unit,
    private val canWake: () -> Boolean = { true }
) {
    private val tag = "HardwareDisplayManager"
    private var managerScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listeningJob: Job? = null
    private var sleepJob: Job? = null

    var isDisplaySleeping: Boolean = false
        private set

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cpuWakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "immichframe:motionCpuWake"
    )

    fun start() {
        stopListening()

        if (!isEnabled) {
            wakeDisplay()
            return
        }

        listeningJob = managerScope.launch(Dispatchers.IO) {
            val motionFile = File(motionPath)
            val buffer = ByteArray(1)
            var hasConfirmedSensor = false

            // Sensor-Probe asynchron innerhalb des CoroutineScopes
            try {
                if (motionFile.exists() && motionFile.canRead()) {
                    FileInputStream(motionFile).use { stream ->
                        val bytesRead = stream.read(buffer)
                        if (bytesRead >= 0) {
                            hasConfirmedSensor = true
                            Log.d(tag, "Motion sensor $motionPath confirmed usable; arming sleep timer")
                            resetSleepTimer()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Motion sensor probe failed for $motionPath: ${e.message}")
            }

            // Wenn der Sensor nicht lesbar ist: Display wach halten, Timer nicht scharfschalten
            if (!hasConfirmedSensor) {
                Log.w(tag, "Motion sensor $motionPath unavailable or unreadable; keeping display awake")
                return@launch
            }

            while (isActive) {
                try {
                    if (motionFile.exists()) {
                        FileInputStream(motionFile).use { stream ->
                            if (stream.read(buffer) == 1 && buffer[0].toInt() == 1) {
                                if (canWake()) {
                                    if (isDisplaySleeping) {
                                        Log.d(tag, "Motion detected -> waking display")
                                        withContext(Dispatchers.Main) {
                                            wakeDisplay()
                                        }
                                    }
                                    resetSleepTimer()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Error reading from $motionPath: ${e.message}")
                }
                delay(300)
            }
        }
    }

    fun updateConfig(enabled: Boolean, newTimeoutMillis: Long, applyLifecycle: Boolean = true) {
        this.isEnabled = enabled
        this.timeoutMillis = newTimeoutMillis
        Log.d(tag, "Config updated: enabled=$enabled, timeout=${newTimeoutMillis / 1000}s, applyLifecycle=$applyLifecycle")

        if (!applyLifecycle) {
            return
        }

        if (!enabled) {
            stopListening()
            wakeDisplay()
        } else {
            start()
        }
    }

    fun resetSleepTimer() {
        sleepJob?.cancel()
        if (!isEnabled) return

        sleepJob = managerScope.launch {
            delay(timeoutMillis)
            if (!isDisplaySleeping) {
                Log.d(tag, "Inactivity timeout reached -> sleeping display")
                withContext(Dispatchers.Main) {
                    sleepDisplay()
                }
            }
        }
    }

    fun sleepDisplay(): Boolean {
        if (isDisplaySleeping) return true

        var lockAcquired = false
        try {
            if (!cpuWakeLock.isHeld) {
                cpuWakeLock.acquire()
                lockAcquired = true
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to acquire CPU wake lock: ${e.message}")
        }

        // Erfolg der Schlaf-Anforderung zuerst prüfen
        val success = try {
            onSleepRequest()
        } catch (e: Exception) {
            Log.w(tag, "onSleepRequest threw exception: ${e.message}")
            false
        }

        if (success) {
            isDisplaySleeping = true
            sleepJob?.cancel()
        } else {
            // Bei Fehlschlag WakeLock nicht halten und Schlafzustand nicht setzen
            if (lockAcquired && cpuWakeLock.isHeld) {
                try {
                    cpuWakeLock.release()
                } catch (e: Exception) {
                    Log.w(tag, "Failed to release CPU wake lock on sleep failure: ${e.message}")
                }
            }
        }

        return success
    }

    fun wakeDisplay() {
        isDisplaySleeping = false

        try {
            if (cpuWakeLock.isHeld) {
                cpuWakeLock.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to release CPU wake lock: ${e.message}")
        }

        onWakeRequest()
        resetSleepTimer()
    }

    private fun stopListening() {
        listeningJob?.cancel()
        listeningJob = null
        sleepJob?.cancel()
        sleepJob = null
    }

    fun stop() {
        stopListening()
        try {
            if (cpuWakeLock.isHeld) {
                cpuWakeLock.release()
            }
        } catch (_: Exception) { }
        managerScope.cancel()
        managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }
}