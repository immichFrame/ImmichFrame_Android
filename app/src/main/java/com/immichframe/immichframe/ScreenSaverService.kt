package com.immichframe.immichframe

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.dreams.DreamService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri

class ScreenSaverService : DreamService() {
    private var webViewRetryScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var webView: WebView
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var txtPhotoInfo: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var isWeatherTimerRunning = false
    private var useWebView = true
    private var blurredBackground = true
    private var showCurrentDate = true
    private var currentWeather = ""
    private var isImageTimerRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private var previousImage: Helpers.ImageResponse? = null
    private var currentImage: Helpers.ImageResponse? = null
    private var portraitCache: Helpers.ImageResponse? = null
    private val imageRunnable = object : Runnable {
        override fun run() {
            if (isImageTimerRunning) {
                handler.postDelayed(this, (serverSettings.interval * 1000).toLong())
                getNextImage()
            }
        }
    }
    private val weatherRunnable = object : Runnable {
        override fun run() {
            if (isWeatherTimerRunning) {
                handler.postDelayed(this, 600000)
                getWeather()
            }
        }
    }
    private var isShowingFirst = true
    private var zoomAnimator: ObjectAnimator? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        webViewRetryScope = CoroutineScope(Dispatchers.Main + Job())
        isFullscreen = true
        isInteractive = true
        setContentView(R.layout.screen_saver_view)
        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(Color.BLACK)
        webView.loadUrl("about:blank")
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        txtDateTime = findViewById(R.id.txtDateTime)

        webView.setOnTouchListener { _, _ ->
            finish()
            true
        }

        acquireWakeLock()
        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        webViewRetryScope?.cancel()
        webViewRetryScope = null
        stopImageTimer()
        releaseWakeLock()
        handler.removeCallbacksAndMessages(null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    previousAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nextAction()
                    return true
                }
                else -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun previousAction() {
        if (useWebView) {
            // Simulate a key press
            webView.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            webView.dispatchKeyEvent(event)
        } else {
            val safePreviousImage = previousImage
            if (safePreviousImage != null) {
                stopImageTimer()
                showImage(safePreviousImage)
                startImageTimer()
            }
        }
    }

    private fun nextAction() {
        if (useWebView) {
            // Simulate a key press
            webView.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            webView.dispatchKeyEvent(event)
        } else {
            stopImageTimer()
            getNextImage()
            startImageTimer()
        }
    }

    private fun showImage(imageResponse: Helpers.ImageResponse) {
        CoroutineScope(Dispatchers.IO).launch {
            //get the window size
            val decorView = window.decorView
            val width = decorView.width
            val height = decorView.height
            val maxSize = maxOf(width, height)

            var randomBitmap = Helpers.decodeBitmapFromBytes(imageResponse.randomImageBase64)
            val thumbHashBitmap = Helpers.decodeBitmapFromBytes(imageResponse.thumbHashImageBase64)
            var isMerged = false

            val isPortrait = randomBitmap.height > randomBitmap.width
            if (isPortrait && serverSettings.layout == "splitview") {
                if (portraitCache != null) {
                    var decodedPortraitImageBitmap =
                        Helpers.decodeBitmapFromBytes(portraitCache!!.randomImageBase64)
                    decodedPortraitImageBitmap =
                        Helpers.reduceBitmapQuality(decodedPortraitImageBitmap, maxSize)
                    randomBitmap = Helpers.reduceBitmapQuality(randomBitmap, maxSize)

                    val colorString =
                        serverSettings.primaryColor?.takeIf { it.isNotBlank() } ?: "#FFFFFF"
                    val parsedColor = colorString.toColorInt()

                    randomBitmap =
                        Helpers.mergeImages(decodedPortraitImageBitmap, randomBitmap, parsedColor)
                    isMerged = true

                    decodedPortraitImageBitmap.recycle()
                } else {
                    portraitCache = imageResponse
                    getNextImage()
                    return@launch
                }
            } else {
                randomBitmap = Helpers.reduceBitmapQuality(randomBitmap, maxSize * 2)
            }

            withContext(Dispatchers.Main) {
                updateUI(randomBitmap, thumbHashBitmap, isMerged, imageResponse)
            }
        }
    }

    private fun updateUI(
        finalImage: Bitmap,
        thumbHashBitmap: Bitmap,
        isMerged: Boolean,
        imageResponse: Helpers.ImageResponse
    ) {
        val imageViewOld = if (isShowingFirst) imageView1 else imageView2
        val imageViewNew = if (isShowingFirst) imageView2 else imageView1

        zoomAnimator?.cancel()
        imageViewNew.alpha = 0f
        imageViewNew.scaleX = 1f
        imageViewNew.scaleY = 1f
        imageViewNew.setImageBitmap(finalImage)
        imageViewNew.colorFilter = Helpers.getImageAdjustmentFilter(this)
        imageViewNew.visibility = View.VISIBLE

        if (blurredBackground) {
            imageViewNew.background = thumbHashBitmap.toDrawable(resources)
        } else {
            imageViewNew.background = null
        }

        imageViewNew.animate()
            .alpha(1f)
            .setDuration((serverSettings.transitionDuration * 1000).toLong())
            .withEndAction {
                if (serverSettings.imageZoom) {
                    startZoomAnimation(imageViewNew)
                }
            }
            .start()

        imageViewOld.animate()
            .alpha(0f)
            .setDuration((serverSettings.transitionDuration * 1000).toLong())
            .withEndAction {
                imageViewOld.visibility = View.GONE
            }
            .start()

        // Toggle active ImageView
        isShowingFirst = !isShowingFirst

        if (isMerged) {
            val mergedPhotoDate =
                if (portraitCache!!.photoDate.isNotEmpty() || imageResponse.photoDate.isNotEmpty()) {
                    "${portraitCache!!.photoDate} | ${imageResponse.photoDate}"
                } else {
                    ""
                }

            val mergedImageLocation =
                if (portraitCache!!.imageLocation.isNotEmpty() || imageResponse.imageLocation.isNotEmpty()) {
                    "${portraitCache!!.imageLocation} | ${imageResponse.imageLocation}"
                } else {
                    ""
                }

            updatePhotoInfo(mergedPhotoDate, mergedImageLocation)
            portraitCache = null
        } else {
            updatePhotoInfo(imageResponse.photoDate, imageResponse.imageLocation)
        }

        updateDateTimeWeather()
    }

    private fun updatePhotoInfo(photoDate: String, photoLocation: String) {
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            val photoInfo = buildString {
                if (serverSettings.showPhotoDate && photoDate.isNotEmpty()) {
                    append(photoDate)
                }
                if (serverSettings.showImageLocation && photoLocation.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(photoLocation)
                }
            }
            txtPhotoInfo.text = photoInfo
        }
    }

    private fun updateDateTimeWeather() {
        if (serverSettings.showClock) {
            val currentDateTime = Calendar.getInstance().time

            val formattedDate = try {
                SimpleDateFormat(serverSettings.photoDateFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (_: Exception) {
                ""
            }

            val formattedTime = try {
                SimpleDateFormat(serverSettings.clockFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (_: Exception) {
                ""
            }

            val dt = if (showCurrentDate && formattedDate.isNotEmpty()) {
                "$formattedDate\n$formattedTime"
            } else {
                formattedTime
            }

            txtDateTime.text = SpannableString(dt).apply {
                val start =
                    if (showCurrentDate && formattedDate.isNotEmpty()) formattedDate.length + 1 else 0
                setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        if (serverSettings.showWeatherDescription) {
            txtDateTime.append(currentWeather)
        }
    }

    private fun getNextImage() {
        apiService.getImageData().enqueue(object : Callback<Helpers.ImageResponse> {
            override fun onResponse(
                call: Call<Helpers.ImageResponse>,
                response: Response<Helpers.ImageResponse>
            ) {
                if (response.isSuccessful) {
                    val imageResponse = response.body()
                    if (imageResponse != null) {
                        previousImage = currentImage
                        currentImage = imageResponse
                        showImage(imageResponse)
                    }
                } else {
                    Toast.makeText(
                        applicationContext,
                        "Failed to load image (HTTP ${response.code()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                t.printStackTrace()
                Toast.makeText(
                    applicationContext,
                    "Failed to load image: ${t.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        })
    }

    private fun startImageTimer() {
        if (!isImageTimerRunning) {
            isImageTimerRunning = true
            handler.postDelayed(imageRunnable, (serverSettings.interval * 1000).toLong())
        }
    }

    private fun stopImageTimer() {
        isImageTimerRunning = false
        handler.removeCallbacks(imageRunnable)
    }

    private fun startZoomAnimation(imageView: ImageView) {
        zoomAnimator?.cancel()
        zoomAnimator = ObjectAnimator.ofPropertyValuesHolder(
            imageView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f)
        )
        zoomAnimator?.duration = (serverSettings.interval * 1000).toLong()
        zoomAnimator?.start()
    }

    private fun getWeather() {
        apiService.getWeather().enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(
                call: Call<Helpers.Weather>,
                response: Response<Helpers.Weather>
            ) {
                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    if (weatherResponse != null) {
                        currentWeather =
                            "\n ${weatherResponse.location}, ${weatherResponse.temperatureUnit} \n ${weatherResponse.description}"
                    }
                }
            }

            override fun onFailure(call: Call<Helpers.Weather>, t: Throwable) {
                Log.e("Weather", "Failed to fetch weather: ${t.message}")
            }
        })
    }

    private fun getServerSettings(
        onSuccess: (Helpers.ServerSettings) -> Unit,
        onFailure: (Throwable) -> Unit,
        maxRetries: Int = 18,
        retryDelayMillis: Long = 5000
    ) {
        var retryCount = 0

        fun attemptFetch() {
            apiService.getServerSettings().enqueue(object : Callback<Helpers.ServerSettings> {
                override fun onResponse(
                    call: Call<Helpers.ServerSettings>,
                    response: Response<Helpers.ServerSettings>
                ) {
                    if (response.isSuccessful) {
                        val serverSettingsResponse = response.body()
                        if (serverSettingsResponse != null) {
                            onSuccess(serverSettingsResponse)
                        } else {
                            handleFailure(Exception("Empty response body"))
                        }
                    } else {
                        handleFailure(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }

                override fun onFailure(call: Call<Helpers.ServerSettings>, t: Throwable) {
                    handleFailure(t)
                }

                private fun handleFailure(t: Throwable) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        Toast.makeText(
                            this@ScreenSaverService,
                            "Retrying to fetch server settings... Attempt $retryCount of $maxRetries",
                            Toast.LENGTH_SHORT
                        ).show()
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptFetch()
                        }, retryDelayMillis)
                    } else {
                        onFailure(t)
                    }
                }
            })
        }

        attemptFetch()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        blurredBackground = prefs.getBoolean("blurredBackground", true)
        showCurrentDate = prefs.getBoolean("showCurrentDate", true)
        var savedUrl = prefs.getString("webview_url", "") ?: ""
        useWebView = prefs.getBoolean("useWebView", true)
        val authSecret = prefs.getString("authSecret", "") ?: ""

        webView.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        txtPhotoInfo.visibility = View.GONE //enabled in onSettingsLoaded based on server settings
        txtDateTime.visibility = View.GONE //enabled in onSettingsLoaded based on server settings

        if (useWebView) {
            savedUrl = if (authSecret.isNotEmpty()) {
                savedUrl.toUri()
                    .buildUpon()
                    .appendQueryParameter("authsecret", authSecret)
                    .build()
                    .toString()
            } else {
                savedUrl
            }
            handler.removeCallbacks(imageRunnable)
            handler.removeCallbacks(weatherRunnable)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url
                    if (url != null) {
                        // Open the URL in the default browser
                        val intent = Intent(Intent.ACTION_VIEW, url)
                        startActivity(intent)
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)

                    if (request?.isForMainFrame == true && error != null) {
                        view?.loadUrl("file:///android_asset/error_page.html")

                        Handler(Looper.getMainLooper()).postDelayed({
                            val errorCode = error.errorCode
                            val errorDescription = error.description.toString().replace("'", "\\'")
                            view?.evaluateJavascript("showError('$errorCode', '$errorDescription')", null)
                        }, 500)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.loadUrl(savedUrl)
                    }, 5000)
                }
            }
            webView.settings.javaScriptEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.domStorageEnabled = true

            // Apply color filter to WebView using layer rendering
            val filter = Helpers.getImageAdjustmentFilter(this)
            if (filter != null) {
                val paint = Paint()
                paint.colorFilter = filter
                try {
                    webView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                } catch (e: Exception) {
                    // Fallback to software layer if hardware fails
                    Log.w("ScreenSaverService", "Hardware layer failed, falling back to software: ${e.message}")
                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
                }
            } else {
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            loadWebViewWithRetry(savedUrl)
        } else {
            retrofit = Helpers.createRetrofit(savedUrl, authSecret)
            apiService = retrofit!!.create(Helpers.ApiService::class.java)
            getServerSettings(
                onSuccess = { settings ->
                    serverSettings = settings
                    onSettingsLoaded()
                    // Reapply filters to currently visible image
                    reapplyImageFilters()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this,
                        "Failed to load server settings: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun reapplyImageFilters() {
        if (!useWebView) {
            // Apply filter to both ImageViews to prevent transition artifacts
            val filter = Helpers.getImageAdjustmentFilter(this)
            imageView1.colorFilter = filter
            imageView2.colorFilter = filter
        } else if (::webView.isInitialized) {
            // Reapply filter to WebView (only if initialized)
            val filter = Helpers.getImageAdjustmentFilter(this)
            if (filter != null) {
                val paint = Paint()
                paint.colorFilter = filter
                try {
                    webView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                } catch (e: Exception) {
                    Log.w("ScreenSaverService", "Hardware layer failed, falling back to software: ${e.message}")
                    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
                }
            } else {
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }
    }

    private fun onSettingsLoaded() {
        if (serverSettings.imageFill){
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        else{
            imageView1.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView2.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            txtPhotoInfo.visibility = View.VISIBLE
            txtPhotoInfo.textSize =
                Helpers.cssFontSizeToSp(serverSettings.baseFontSize, this)
            if (serverSettings.primaryColor != null) {
                txtPhotoInfo.setTextColor(
                    runCatching { serverSettings.primaryColor!!.toColorInt() }
                        .getOrDefault(Color.WHITE)
                )
            } else {
                txtPhotoInfo.setTextColor(Color.WHITE)
            }
        } else {
            txtPhotoInfo.visibility = View.GONE
        }
        if (serverSettings.showClock) {
            txtDateTime.visibility = View.VISIBLE
            txtDateTime.textSize = Helpers.cssFontSizeToSp(serverSettings.baseFontSize, this)
            if (serverSettings.primaryColor != null) {
                txtDateTime.setTextColor(
                    runCatching { serverSettings.primaryColor!!.toColorInt() }
                        .getOrDefault(Color.WHITE)
                )
            } else {
                txtDateTime.setTextColor(Color.WHITE)
            }
        } else {
            txtDateTime.visibility = View.GONE
        }

        getNextImage()
        startImageTimer()

        if (serverSettings.showWeatherDescription) {
            getWeather()
            if (!isWeatherTimerRunning) {
                isWeatherTimerRunning = true
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        getWeather()
                        handler.postDelayed(this, 600000)
                    }
                }, (300000))
            }

        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ImmichFrame::ScreenSaverWakeLock"
            )
            wakeLock?.acquire(120 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun loadWebViewWithRetry(
        url: String,
        attempt: Int = 1,
        maxAttempts: Int = 36
    ) {
        webViewRetryScope?.launch {
            val reachable = withContext(Dispatchers.IO) {
                Helpers.isServerReachable(url)
            }

            if (reachable) {
                webView.loadUrl(url)
            } else if (attempt <= maxAttempts) {
                Toast.makeText(
                    this@ScreenSaverService,
                    "Connecting to server... Attempt $attempt of $maxAttempts",
                    Toast.LENGTH_SHORT
                ).show()

                delay(5_000)
                loadWebViewWithRetry(url, attempt + 1, maxAttempts)
            } else {
                Toast.makeText(
                    this@ScreenSaverService,
                    "Could not connect to server after $maxAttempts attempts",
                    Toast.LENGTH_LONG
                ).show()

                webView.loadUrl(url)
            }
        }
    }
}