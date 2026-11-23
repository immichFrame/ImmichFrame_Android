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
    private val onSetPausedCommand: (Boolean) -> Boolean?,
    private val onReloadCommand: () -> Boolean?,
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
                // NanoHTTPD leaves the unbound socket open, and every retry makes a new one
                super.stop()
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
            // Bare /pause toggles, as it always has. /pause?value=true|false and /play set
            // the state outright, so a caller need not know which way the slideshow was left.
            "/pause" -> {
                when (session.parameters["value"]?.firstOrNull()) {
                    null -> {
                        onPauseCommand()
                        newFixedLengthResponse("Pause")
                    }
                    "true" -> stateChangeResponse(onSetPausedCommand(true), "Paused", PAUSE_UNAVAILABLE)
                    "false" -> stateChangeResponse(onSetPausedCommand(false), "Playing", PAUSE_UNAVAILABLE)
                    else -> newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "Pause value should be true or false, or left out to toggle",
                    )
                }
            }
            "/play" -> stateChangeResponse(onSetPausedCommand(false), "Playing", PAUSE_UNAVAILABLE)
            "/reload" -> stateChangeResponse(onReloadCommand(), "Reloading", RELOAD_UNAVAILABLE)
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

    /**
     * Turns the outcome of a command that may not apply into a response.
     *
     * @param result true if it was carried out, false if it does not apply in the frame's
     * current state, or null if the frame did not answer in time
     */
    private fun stateChangeResponse(result: Boolean?, done: String, unavailable: String): Response {
        return when (result) {
            true -> newFixedLengthResponse(done)
            false -> newFixedLengthResponse(Response.Status.CONFLICT, MIME_PLAINTEXT, unavailable)
            null -> newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Frame did not respond",
            )
        }
    }

    companion object {
        const val PORT = 53287
        private const val PAUSE_UNAVAILABLE =
            "Not available while the frame is off, still starting, or in WebView mode"
        private const val RELOAD_UNAVAILABLE =
            "Not available while the frame is off or has no server configured"
        private val lock = Any()
        private var active: RpcHttpServer? = null
    }
}
