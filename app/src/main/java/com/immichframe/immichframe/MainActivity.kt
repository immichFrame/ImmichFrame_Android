package com.immichframe.immichframe

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var txtPhotoInfo: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var btnPrevious: Button
    private lateinit var btnPause: Button
    private lateinit var btnNext: Button
    private lateinit var dimOverlay: View
    private lateinit var swipeRefreshLayout: View
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private lateinit var rcpServer: RpcHttpServer
    private var isWeatherTimerRunning = false
    private var useWebView = true
    private var keepScreenOn = true
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
    private val dimCheckRunnable = object : Runnable {
        override fun run() {
            checkDimTime()
            handler.postDelayed(this, 30000)
        }
    }
    private var isShowingFirst = true
    private var zoomAnimator: ObjectAnimator? = null

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        //force dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.main_view)
        hideSystemUI()

        webView = findViewById(R.id.webView)
        webView.setBackgroundColor(Color.BLACK)
        webView.loadUrl("about:blank")
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        txtPhotoInfo = findViewById(R.id.txtPhotoInfo)
        txtDateTime = findViewById(R.id.txtDateTime)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPause = findViewById(R.id.btnPause)
        btnNext = findViewById(R.id.btnNext)
        dimOverlay = findViewById(R.id.dimOverlay)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = false
            settingsAction()
        }

        btnPrevious.setOnClickListener {
            val toast = Toast.makeText(this, "Previous", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.START, 0, 0)
            toast.show()
            previousAction()
        }

        btnPause.setOnClickListener {
            val toast = Toast.makeText(this, "Pause", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            pauseAction()
        }

        btnNext.setOnClickListener {
            val toast = Toast.makeText(this, "Next", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.END, 0, 0)
            toast.show()
            nextAction()
        }

        rcpServer = RpcHttpServer(
            onDimCommand = { dim -> runOnUiThread { screenDim(dim) } },
            onNextCommand = { runOnUiThread { nextAction() } },
            onPreviousCommand = { runOnUiThread { previousAction() } },
            onPauseCommand = { runOnUiThread { pauseAction() } },
            onSettingsCommand = { runOnUiThread { settingsActionFromRpc() } },
            onBrightnessCommand = { brightness -> runOnUiThread { screenBrightnessAction(brightness) } },
        )
        rcpServer.start()

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val savedUrl = prefs.getString("webview_url", "") ?: ""

        if (savedUrl.isBlank()) {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        } else {
            loadSettings()
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
                        this@MainActivity,
                        "Failed to load image (HTTP ${response.code()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                t.printStackTrace()
                Toast.makeText(
                    this@MainActivity,
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

    private fun startWeatherTimer() {
        if (!isWeatherTimerRunning) {
            isWeatherTimerRunning = true
            handler.post(weatherRunnable)
        }
    }

    private fun stopWeatherTimer() {
        isWeatherTimerRunning = false
        handler.removeCallbacks(weatherRunnable)
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
                            "\n ${weatherResponse.location}, ${"%.1f".format(weatherResponse.temperature)}${weatherResponse.unit} \n ${weatherResponse.description}"
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
        maxRetries: Int = 36,
        retryDelayMillis: Long = 5000
    ) {
        var retryCount = 0

        fun attemptFetch() {
            if (useWebView) {
                return
            }
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
                    if (useWebView) {
                        return
                    }
                    if (retryCount < maxRetries) {
                        retryCount++
                        Toast.makeText(
                            this@MainActivity,
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
        keepScreenOn = prefs.getBoolean("keepScreenOn", true)
        val authSecret = prefs.getString("authSecret", "") ?: ""
        val screenDim = prefs.getBoolean("screenDim", false)
        val settingsLock = prefs.getBoolean("settingsLock", false)

        webView.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPrevious.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPause.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnNext.visibility = if (useWebView) View.GONE else View.VISIBLE
        swipeRefreshLayout.isEnabled = !settingsLock
        txtPhotoInfo.visibility = View.GONE //enabled in onSettingsLoaded based on server settings
        txtDateTime.visibility = View.GONE //enabled in onSettingsLoaded based on server settings
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (screenDim) {
            handler.post(dimCheckRunnable)
        } else {
            handler.removeCallbacks(dimCheckRunnable)
            dimOverlay.visibility = View.GONE
            val lp = WindowManager.LayoutParams()
            lp.copyFrom(window.attributes)
            lp.screenBrightness = 1f
            window.attributes = lp
        }
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
                        //check url again in case the user has changed it
                        var currentUrl = prefs.getString("webview_url", "")?.trim() ?: ""
                        currentUrl = if (authSecret.isNotEmpty()) {
                            savedUrl.toUri()
                                .buildUpon()
                                .appendQueryParameter("authsecret", authSecret)
                                .build()
                                .toString()
                        } else {
                            currentUrl
                        }
                        if (currentUrl.isNotEmpty()) {
                            webView.loadUrl(currentUrl)
                        }
                    }, 5000)
                }
            }
            webView.settings.javaScriptEnabled = true
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.settings.domStorageEnabled = true
            loadWebViewWithRetry(savedUrl)
        } else {
            retrofit = Helpers.createRetrofit(savedUrl, authSecret)
            apiService = retrofit!!.create(Helpers.ApiService::class.java)
            getServerSettings(
                onSuccess = { settings ->
                    serverSettings = settings
                    onSettingsLoaded()
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

    private fun onSettingsLoaded() {
        if (serverSettings.imageFill) {
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
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
            startWeatherTimer()
        }
    }

    private fun previousAction() {
        if (useWebView) {
            // Simulate a key press
            webView.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyEvent(event)
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
            dispatchKeyEvent(event)
        } else {
            stopImageTimer()
            getNextImage()
            startImageTimer()
        }
    }

    private fun pauseAction() {
        if (useWebView) {
            // Simulate a key press
            webView.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
            dispatchKeyEvent(event)
        } else {
            zoomAnimator?.cancel()
            if (isImageTimerRunning) {
                stopImageTimer()
            } else {
                getNextImage()
                startImageTimer()
            }
        }
    }

    private fun settingsAction() {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedPassword = sharedPrefs.getString("settings_pincode", "")

        if (!savedPassword.isNullOrEmpty()) {
            // Password is set, prompt for verification
            promptForPasswordVerification(savedPassword)
        } else {
            // No password, open settings directly
            openSettings()
        }
    }

    private fun settingsActionFromRpc() {
        // RPC access bypasses password protection
        openSettings()
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        stopImageTimer()
        settingsLauncher.launch(intent)
    }

    private fun promptForPasswordVerification(correctPassword: String) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        AlertDialog.Builder(this)
            .setTitle("Enter Pincode")
            .setMessage("Enter your pincode to access settings:")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val enteredPassword = input.text.toString()
                if (enteredPassword == correctPassword) {
                    openSettings()
                } else {
                    Toast.makeText(this, "Incorrect pincode", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun screenBrightnessAction(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsAction()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    pauseAction()
                    return true
                }
            }
            if (!useWebView) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        previousAction()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        nextAction()
                        return true
                    }

                    KeyEvent.KEYCODE_SPACE -> {
                        pauseAction()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For API 30 and above
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // For API 21 to 29
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun screenDim(dim: Boolean) {
        val lp = window.attributes
        if (dim) {
            lp.screenBrightness = 0.01f
            window.attributes = lp
            if (dimOverlay.visibility != View.VISIBLE) {
                dimOverlay.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    if (useWebView) {
                        webView.loadUrl("about:blank")
                    } else {
                        stopImageTimer()
                        stopWeatherTimer()
                    }
                    animate()
                        .alpha(0.99f)
                        .setDuration(500L)
                        .start()
                }
            }
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            if (dimOverlay.isVisible) {
                dimOverlay.animate()
                    .alpha(0f)
                    .setDuration(500L)
                    .withEndAction {
                        dimOverlay.visibility = View.GONE
                        loadSettings()
                    }
                    .start()
            }
        }
    }

    private fun checkDimTime() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val startHour = prefs.getInt("dimStartHour", 22)
        val startMinute = prefs.getInt("dimStartMinute", 0)
        val endHour = prefs.getInt("dimEndHour", 6)
        val endMinute = prefs.getInt("dimEndMinute", 0)

        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        val shouldDim =
            if (startMinutes < endMinutes) {
                nowMinutes in startMinutes until endMinutes
            } else {
                nowMinutes !in endMinutes until startMinutes
            }

        val isOverlayVisible = dimOverlay.isVisible
        if (shouldDim && !isOverlayVisible) {
            screenDim(true)
        } else if (!shouldDim && isOverlayVisible) {
            screenDim(false)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onResume() {
        super.onResume()
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        hideSystemUI()

    }

    override fun onDestroy() {
        super.onDestroy()
        rcpServer.stop()
        handler.removeCallbacksAndMessages(null)
    }

    private fun loadWebViewWithRetry(
        url: String,
        attempt: Int = 1,
        maxAttempts: Int = 36
    ) {
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                Helpers.isServerReachable(url)
            }

            if (reachable) {
                webView.loadUrl(url)
            } else if (attempt <= maxAttempts) {
                Toast.makeText(
                    this@MainActivity,
                    "Connecting to server... Attempt $attempt of $maxAttempts",
                    Toast.LENGTH_SHORT
                ).show()

                delay(5_000)
                loadWebViewWithRetry(url, attempt + 1, maxAttempts)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Could not connect to server after $maxAttempts attempts",
                    Toast.LENGTH_LONG
                ).show()

                webView.loadUrl(url)
            }
        }
    }
}
