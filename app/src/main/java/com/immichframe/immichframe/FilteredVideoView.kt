package com.immichframe.immichframe

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * A minimal counterpart to VideoView that can have a colour filter applied to it.
 *
 * VideoView renders into a SurfaceView, whose surface is composited outside the view
 * hierarchy and therefore ignores colour filters. A TextureView is drawn like any other
 * view and honours its layer paint, which lets the image adjustments reach native video.
 * That costs an extra GPU copy per frame, so this view is only used while adjustments are
 * active; unadjusted playback stays on the plain VideoView.
 *
 * It mirrors the small part of the VideoView API the frame relies on.
 */
class FilteredVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingUri: Uri? = null
    private var pendingHeaders: Map<String, String> = emptyMap()
    private var isPrepared = false
    private var videoWidth = 0
    private var videoHeight = 0

    private var onPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var onInfoListener: MediaPlayer.OnInfoListener? = null
    private var onCompletionListener: MediaPlayer.OnCompletionListener? = null
    private var onErrorListener: MediaPlayer.OnErrorListener? = null

    val isPlaying: Boolean
        get() = isPrepared && player?.isPlaying == true

    init {
        surfaceTextureListener = this
    }

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener?) {
        onPreparedListener = listener
    }

    fun setOnInfoListener(listener: MediaPlayer.OnInfoListener?) {
        onInfoListener = listener
    }

    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener?) {
        onCompletionListener = listener
    }

    fun setOnErrorListener(listener: MediaPlayer.OnErrorListener?) {
        onErrorListener = listener
    }

    /**
     * Sets the colour filter the video is drawn through, or null for none.
     */
    fun setColorFilter(filter: ColorFilter?) {
        // A TextureView is always a hardware layer, so only the paint matters here
        setLayerPaint(filter?.let { Paint().apply { colorFilter = it } })
    }

    fun setVideoURI(uri: Uri, headers: Map<String, String>) {
        pendingUri = uri
        pendingHeaders = headers
        // Without a surface yet, onSurfaceTextureAvailable() opens the video instead
        openVideo()
    }

    fun start() {
        if (isPrepared) player?.start()
    }

    fun pause() {
        if (isPlaying) player?.pause()
    }

    fun stopPlayback() {
        pendingUri = null
        releasePlayer()
    }

    private fun openVideo() {
        val uri = pendingUri ?: return
        val target = surface ?: return
        releasePlayer()

        val newPlayer = MediaPlayer()
        player = newPlayer
        try {
            newPlayer.setOnPreparedListener { mp ->
                isPrepared = true
                updateVideoSize(mp.videoWidth, mp.videoHeight)
                onPreparedListener?.onPrepared(mp)
            }
            newPlayer.setOnVideoSizeChangedListener { _, width, height ->
                updateVideoSize(width, height)
            }
            newPlayer.setOnInfoListener { mp, what, extra ->
                onInfoListener?.onInfo(mp, what, extra) ?: false
            }
            newPlayer.setOnCompletionListener { mp ->
                onCompletionListener?.onCompletion(mp)
            }
            newPlayer.setOnErrorListener { mp, what, extra ->
                isPrepared = false
                onErrorListener?.onError(mp, what, extra) ?: false
            }
            newPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            newPlayer.setDataSource(context, uri, pendingHeaders)
            newPlayer.setSurface(target)
            newPlayer.prepareAsync()
        } catch (e: Exception) {
            // Same outcome as VideoView: an unopenable source is reported as a playback error
            Log.w("FilteredVideoView", "Unable to open video: ${e.message}")
            isPrepared = false
            onErrorListener?.onError(newPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun releasePlayer() {
        isPrepared = false
        player?.let {
            it.reset()
            it.release()
        }
        player = null
    }

    private fun updateVideoSize(width: Int, height: Int) {
        if (width != videoWidth || height != videoHeight) {
            videoWidth = width
            videoHeight = height
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)
        // Fit the video inside the available space while keeping its aspect ratio
        if (videoWidth > 0 && videoHeight > 0) {
            if (videoWidth * height < width * videoHeight) {
                width = height * videoWidth / videoHeight
            } else if (videoWidth * height > width * videoHeight) {
                height = width * videoHeight / videoWidth
            }
        }
        setMeasuredDimension(width, height)
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        openVideo()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }
}
