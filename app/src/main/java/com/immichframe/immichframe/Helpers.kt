package com.immichframe.immichframe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceManager
import retrofit2.Call
import retrofit2.http.GET
import androidx.core.graphics.scale
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Helpers {
    // Cache for image adjustment filter to avoid recreating on every image load
    // Thread-safe via synchronization
    private var cachedFilterSettings: IntArray? = null
    private var cachedFilter: ColorMatrixColorFilter? = null
    private val filterCacheLock = Any()

    fun textSizeMultiplier(context: Context, currentSizeSp: Float, multiplier: Float): Float {
        val resources = context.resources
        val fontScale = resources.configuration.fontScale
        val density = resources.displayMetrics.density
        val currentSizePx = currentSizeSp * density * fontScale
        val newSizePx = currentSizePx * multiplier

        return newSizePx / (density * fontScale)
    }

    fun cssFontSizeToSp(cssSize: String?, context: Context, baseFontSizePx: Float = 16f): Float {
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        val fontScale = resources.configuration.fontScale
        val density = displayMetrics.density

        // Handle null cssSize
        val effectiveCssSize = cssSize ?: "medium"

        return when {
            effectiveCssSize.equals("xx-small", ignoreCase = true) -> 8f * fontScale
            effectiveCssSize.equals("x-small", ignoreCase = true) -> 10f * fontScale
            effectiveCssSize.equals("small", ignoreCase = true) -> 12f * fontScale
            effectiveCssSize.equals("medium", ignoreCase = true) -> 16f * fontScale
            effectiveCssSize.equals("large", ignoreCase = true) -> 20f * fontScale
            effectiveCssSize.equals("x-large", ignoreCase = true) -> 24f * fontScale
            effectiveCssSize.equals("xx-large", ignoreCase = true) -> 32f * fontScale

            effectiveCssSize.endsWith("px", ignoreCase = true) -> {
                val px = effectiveCssSize.removeSuffix("px").toFloatOrNull() ?: baseFontSizePx
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("pt", ignoreCase = true) -> {
                val pt = effectiveCssSize.removeSuffix("pt").toFloatOrNull() ?: baseFontSizePx
                val px = pt * (density * 160f / 72f)
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("em", ignoreCase = true) -> {
                val em = effectiveCssSize.removeSuffix("em").toFloatOrNull() ?: 1f
                val px = em * baseFontSizePx
                px / (density * fontScale)
            }

            else -> 16f * fontScale
        }
    }

    fun mergeImages(leftImage: Bitmap, rightImage: Bitmap, lineColor: Int): Bitmap {
        val lineWidth = 10
        val targetHeight = maxOf(leftImage.height, rightImage.height) // Use max height

        val totalWidth = leftImage.width + rightImage.width + lineWidth

        val result = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(leftImage, 0f, 0f, paint)

        // Draw dividing line
        paint.color = lineColor
        canvas.drawRect(
            leftImage.width.toFloat(), // Line starts after left image
            0f, (leftImage.width + lineWidth).toFloat(), targetHeight.toFloat(), paint
        )

        canvas.drawBitmap(rightImage, (leftImage.width + lineWidth).toFloat(), 0f, paint)

        return result
    }

    fun decodeBitmapFromBytes(data: String): Bitmap {
        val decodedImage = Base64.decode(data, Base64.DEFAULT)

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.size, options)
    }

    /**
     * Reduces bitmap size while maintaining aspect ratio.
     *
     * IMPORTANT: This function RECYCLES the input bitmap.
     * After calling this function, the input bitmap is no longer valid and must not be used.
     *
     * @param bitmap The bitmap to resize (will be recycled)
     * @param maxSize Maximum dimension (width or height)
     * @return New resized bitmap
     */
    fun reduceBitmapQuality(bitmap: Bitmap, maxSize: Int = 1000): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // If already smaller than maxSize, still create a copy and recycle original for consistency
        val scaleFactor = maxSize.toFloat() / width.coerceAtLeast(height)

        val resizedBitmap = if (scaleFactor >= 1f) {
            // Image is already small enough - create explicit copy to avoid scale() returning same bitmap
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            copy
        } else {
            // Need to scale down
            val newWidth = (width * scaleFactor).toInt()
            val newHeight = (height * scaleFactor).toInt()
            val scaled = bitmap.scale(newWidth, newHeight)
            bitmap.recycle()
            scaled
        }

        return resizedBitmap
    }

    data class ImageResponse(
        val randomImageBase64: String,
        val thumbHashImageBase64: String,
        val photoDate: String,
        val imageLocation: String
    )

    data class ServerSettings(
        val margin: String,
        val interval: Int,
        val transitionDuration: Double,
        val downloadImages: Boolean,
        val renewImagesDuration: Int,
        val showClock: Boolean,
        val clockFormat: String,
        val showPhotoDate: Boolean,
        val photoDateFormat: String,
        val showImageDesc: Boolean,
        val showPeopleDesc: Boolean,
        val showImageLocation: Boolean,
        val imageLocationFormat: String,
        val primaryColor: String?,
        val secondaryColor: String,
        val style: String,
        val baseFontSize: String?,
        val showWeatherDescription: Boolean,
        val unattendedMode: Boolean,
        val imageZoom: Boolean,
        val imageFill: Boolean,
        val layout: String,
        val language: String
    )

    data class Weather(
        val location: String,
        val temperature: Double,
        val unit: String,
        val temperatureUnit: String,
        val description: String,
        val iconId: String
    )

    interface ApiService {
        @GET("api/Asset/RandomImageAndInfo")
        fun getImageData(): Call<ImageResponse>

        @GET("api/Config")
        fun getServerSettings(): Call<ServerSettings>

        @GET("api/Weather")
        fun getWeather(): Call<Weather>
    }

    fun createRetrofit(baseUrl: String, authSecret: String): Retrofit {
        val normalizedBaseUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val client = OkHttpClient.Builder().addInterceptor { chain ->
                val originalRequest = chain.request()

                val request = if (authSecret.isNotEmpty()) {
                    originalRequest.newBuilder().addHeader("Authorization", "Bearer $authSecret")
                        .build()
                } else {
                    originalRequest
                }

                chain.proceed(request)
            }.build()

        return Retrofit.Builder().baseUrl(normalizedBaseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }

    private val reachabilityClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun isServerReachable(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            reachabilityClient.newCall(request).execute().use {
                true // any HTTP response = reachable
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getImageAdjustmentFilter(context: Context, includeGamma: Boolean = true): ColorMatrixColorFilter? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // Check if image adjustments are enabled
        val adjustmentsEnabled = prefs.getBoolean("imageAdjustments", false)
        if (!adjustmentsEnabled) {
            synchronized(filterCacheLock) {
                cachedFilterSettings = null
                cachedFilter = null
            }
            return null
        }

        // Validate and clamp preference values to prevent corruption issues
        val brightness = prefs.getInt("image_brightness", 0).coerceIn(-100, 100)
        val contrast = prefs.getInt("image_contrast", 0).coerceIn(-100, 100)
        val red = prefs.getInt("image_red_channel", 0).coerceIn(-100, 100)
        val green = prefs.getInt("image_green_channel", 0).coerceIn(-100, 100)
        val blue = prefs.getInt("image_blue_channel", 0).coerceIn(-100, 100)
        val gamma = if (includeGamma) prefs.getInt("image_gamma", 100).coerceIn(10, 300) else 100

        // If all default, return null (no filter needed)
        if (brightness == 0 && contrast == 0 && red == 0 &&
            green == 0 && blue == 0 && gamma == 100) {
            synchronized(filterCacheLock) {
                cachedFilterSettings = null
                cachedFilter = null
            }
            return null
        }

        // Check cache (thread-safe)
        // Include includeGamma flag in cache key (use -1 for false, 1 for true)
        val gammaKey = if (includeGamma) 1 else -1
        val currentSettings = intArrayOf(brightness, contrast, red, green, blue, gamma, gammaKey)
        synchronized(filterCacheLock) {
            if (cachedFilterSettings != null && cachedFilterSettings!!.contentEquals(currentSettings)) {
                return cachedFilter
            }
        }

        // Create new filter and cache it
        val newFilter = createColorMatrixFilter(brightness, contrast, red, green, blue, gamma)
        synchronized(filterCacheLock) {
            cachedFilterSettings = currentSettings
            cachedFilter = newFilter
        }
        return newFilter
    }

    private fun createColorMatrixFilter(
        brightness: Int,
        contrast: Int,
        red: Int,
        green: Int,
        blue: Int,
        gamma: Int
    ): ColorMatrixColorFilter {
        val finalMatrix = ColorMatrix()

        // Apply brightness (-100 to +100 range)
        if (brightness != 0) {
            val brightnessValue = brightness.toFloat()
            val brightnessMatrix = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, brightnessValue,
                0f, 1f, 0f, 0f, brightnessValue,
                0f, 0f, 1f, 0f, brightnessValue,
                0f, 0f, 0f, 1f, 0f
            ))
            finalMatrix.postConcat(brightnessMatrix)
        }

        // Apply contrast (-100 to +100 range)
        if (contrast != 0) {
            val scale = (100 + contrast) / 100f
            val translate = (1 - scale) * 128
            val contrastMatrix = ColorMatrix(floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            ))
            finalMatrix.postConcat(contrastMatrix)
        }

        // Apply RGB channel adjustments (-100 to +100 range as gain/multiplier)
        if (red != 0 || green != 0 || blue != 0) {
            val redScale = 1f + (red / 100f)
            val greenScale = 1f + (green / 100f)
            val blueScale = 1f + (blue / 100f)
            val rgbMatrix = ColorMatrix(floatArrayOf(
                redScale, 0f, 0f, 0f, 0f,
                0f, greenScale, 0f, 0f, 0f,
                0f, 0f, blueScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            finalMatrix.postConcat(rgbMatrix)
        }

        // Apply gamma (10 to 300 range, representing 0.1 to 3.0)
        // NOTE: This is an APPROXIMATION for ColorFilter mode (ImageView/WebView)
        // True gamma correction requires non-linear per-pixel transformation
        // For widgets (bitmap mode), proper gamma is applied via applyGammaToBitmap()
        if (gamma != 100) {
            val gammaValue = gamma / 100f
            // Better approximation: combine contrast and brightness to approximate gamma curve
            // For gamma > 1: increases contrast in midtones (darkens image)
            // For gamma < 1: decreases contrast in midtones (lightens image)
            val contrastFactor = if (gammaValue > 1f) {
                0.7f + (gammaValue - 1f) * 0.3f  // Reduce contrast for darkening
            } else {
                1f + (1f - gammaValue) * 0.5f  // Increase contrast for lightening
            }
            val brightnessFactor = if (gammaValue > 1f) {
                -(gammaValue - 1f) * 30f  // Darken
            } else {
                (1f - gammaValue) * 40f  // Lighten
            }

            val gammaMatrix = ColorMatrix(floatArrayOf(
                contrastFactor, 0f, 0f, 0f, brightnessFactor,
                0f, contrastFactor, 0f, 0f, brightnessFactor,
                0f, 0f, contrastFactor, 0f, brightnessFactor,
                0f, 0f, 0f, 1f, 0f
            ))
            finalMatrix.postConcat(gammaMatrix)
        }

        return ColorMatrixColorFilter(finalMatrix)
    }

    /**
     * Applies image adjustments to a bitmap by creating a new bitmap with filters applied.
     *
     * IMPORTANT: This function ALWAYS RECYCLES the input bitmap to prevent memory leaks.
     * After calling this function, the input bitmap is no longer valid and must not be used.
     * A new bitmap is always returned, even if no adjustments are applied.
     *
     * Note: For bitmap mode, gamma correction is applied accurately using per-pixel transformation.
     *
     * @param bitmap The source bitmap to apply adjustments to (will be recycled)
     * @param context Context for accessing SharedPreferences
     * @return A new bitmap with adjustments applied (or copy if no adjustments)
     */
    fun applyImageAdjustmentsToBitmap(bitmap: Bitmap, context: Context): Bitmap {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val gamma = prefs.getInt("image_gamma", 100).coerceIn(10, 300)

        // Apply ColorMatrix-based adjustments (brightness, contrast, RGB)
        // Exclude gamma from ColorFilter - we'll apply it properly via LUT
        val filter = getImageAdjustmentFilter(context, includeGamma = false)
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        var result = Bitmap.createBitmap(bitmap.width, bitmap.height, config)
        val canvas = Canvas(result)
        val paint = Paint()

        if (filter != null) {
            paint.colorFilter = filter
        }

        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // ALWAYS recycle original bitmap to maintain consistent contract
        bitmap.recycle()

        // Apply proper gamma correction if needed (per-pixel operation)
        if (gamma != 100) {
            result = applyGammaToBitmap(result, gamma / 100f)
        }

        return result
    }

    /**
     * Applies proper gamma correction to a bitmap using per-pixel transformation.
     * Uses a lookup table for performance.
     *
     * @param bitmap The bitmap to apply gamma to (will be recycled)
     * @param gamma The gamma value (0.1 to 3.0, where 1.0 is no change)
     * @return New bitmap with gamma applied
     */
    private fun applyGammaToBitmap(bitmap: Bitmap, gamma: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Safety check: prevent excessive memory allocation
        // Max 4000x4000 = 16M pixels = 64MB for ARGB_8888
        val maxPixels = 4000 * 4000
        if (width * height > maxPixels) {
            Log.w("Helpers", "Bitmap too large for gamma correction: ${width}x${height}, skipping gamma")
            return bitmap
        }

        // Build lookup table for gamma correction
        val gammaLUT = IntArray(256) { i ->
            val normalized = i / 255f
            val corrected = Math.pow(normalized.toDouble(), gamma.toDouble()).toFloat()
            (corrected * 255f).coerceIn(0f, 255f).toInt()
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Apply gamma correction to each pixel
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xff
            val r = (pixel shr 16) and 0xff
            val g = (pixel shr 8) and 0xff
            val b = pixel and 0xff

            val rCorrected = gammaLUT[r]
            val gCorrected = gammaLUT[g]
            val bCorrected = gammaLUT[b]

            pixels[i] = (a shl 24) or (rCorrected shl 16) or (gCorrected shl 8) or bCorrected
        }

        val result = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)

        // Recycle input bitmap
        bitmap.recycle()

        return result
    }

}