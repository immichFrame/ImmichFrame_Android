package com.immichframe.immichframe

import fi.iki.elonen.NanoHTTPD

class RpcHttpServer(
    private val onFrameActiveCommand: (Boolean) -> Unit,
    private val onNextCommand: () -> Unit,
    private val onPreviousCommand: () -> Unit,
    private val onPauseCommand: () -> Unit,
    private val onSettingsCommand: () -> Unit,
    private val onBrightnessCommand: (Float) -> Unit,
) : NanoHTTPD(53287) {

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
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown command")
        }
    }
}
