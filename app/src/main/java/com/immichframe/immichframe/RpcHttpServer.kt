package com.immichframe.immichframe

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class RpcHttpServer(
    private val onFrameActiveCommand: (Boolean) -> Unit,
    private val onNextCommand: () -> Unit,
    private val onPreviousCommand: () -> Unit,
    private val onPauseCommand: () -> Unit,
    private val onSettingsCommand: () -> Unit,
    private val onBrightnessCommand: (Float) -> Unit,
    private val onStatusRequest: () -> String?,
) : NanoHTTPD(PORT) {

    /**
     * Starts the server without ever throwing.
     *
     * Only one server can hold the port, so an instance left behind by an earlier activity
     * in this process is stopped first. If the port still cannot be bound (another app
     * holds it, say) this returns false and the caller may simply try again later.
     *
     * @return true if the server is now listening
     */
    fun startSafely(): Boolean {
        synchronized(lock) {
            if (active !== this) {
                active?.stop()
            }
            return try {
                start()
                active = this
                true
            } catch (e: IOException) {
                Log.w("RpcHttpServer", "Unable to listen on port $PORT: ${e.message}")
                false
            }
        }
    }

    override fun stop() {
        super.stop()
        synchronized(lock) {
            if (active === this) {
                active = null
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            // "/dim" and "/undim" are kept as aliases for backwards compatibility
            "/off", "/dim" -> {
                onFrameActiveCommand(false)
                newFixedLengthResponse("Frame off")
            }
            "/on", "/undim" -> {
                onFrameActiveCommand(true)
                newFixedLengthResponse("Frame on")
            }
            "/next" -> {
                onNextCommand()
                newFixedLengthResponse("Next")
            }
            "/previous" -> {
                onPreviousCommand()
                newFixedLengthResponse("Previous")
            }
            "/pause" -> {
                onPauseCommand()
                newFixedLengthResponse("Pause")
            }
            "/settings" -> {
                onSettingsCommand()
                newFixedLengthResponse("Settings")
            }
            "/brightness" -> {
                val brightness = session.parameters["value"]?.firstOrNull()?.toFloatOrNull()
                if (brightness != null && brightness >= -1 && brightness <= 1) {
                    onBrightnessCommand(brightness)
                    newFixedLengthResponse("Brightness set to $brightness")
                } else {
                    newFixedLengthResponse("Brightness parameter missing or invalid (should be a value between 0.00 and 1.00, or -1.00 for system default)")
                }
            }
            "/status" -> {
                val status = onStatusRequest()
                if (status != null) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", status)
                } else {
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Status unavailable")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown command")
        }
    }

    companion object {
        const val PORT = 53287
        private val lock = Any()
        private var active: RpcHttpServer? = null
    }
}
