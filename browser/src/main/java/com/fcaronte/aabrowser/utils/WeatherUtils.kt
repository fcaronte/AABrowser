package com.fcaronte.aabrowser.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Geocoder
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "WeatherWidget"

data class WeatherData(
    val temperature: Double,
    val weatherCode: Int,
    val description: String,
    val locationName: String,
    val icon: ImageVector,
    val bitmap: Bitmap,
    val isDay: Boolean = true
)

fun getWeatherDescription(code: Int, isDay: Boolean = true): String {
    return when (code) {
        0 -> if (isDay) "Sereno" else "Notte serena"
        1, 2 -> "Parz. nuvoloso"
        3 -> "Nuvoloso"
        45, 48 -> "Nebbia"
        51, 53, 55, 56, 57 -> "Pioviggine"
        61, 63, 65, 66, 67 -> "Pioggia"
        71, 73, 75, 77 -> "Neve"
        80, 81, 82 -> "Rovesci"
        85, 86 -> "Nevicate"
        95, 96, 99 -> "Temporale"
        else -> "Meteo"
    }
}

fun getWeatherIcon(code: Int, isDay: Boolean = true): ImageVector {
    return when (code) {
        0 -> if (isDay) Icons.Default.WbSunny else Icons.Default.NightsStay
        1, 2 -> if (isDay) Icons.Default.WbCloudy else Icons.Default.NightsStay
        3 -> Icons.Default.Cloud
        45, 48 -> Icons.Default.Grain
        51, 53, 55, 56, 57 -> Icons.Default.WaterDrop
        61, 63, 65, 66, 67 -> Icons.Default.WaterDrop
        71, 73, 75, 77 -> Icons.Default.AcUnit
        80, 81, 82 -> Icons.Default.WaterDrop
        85, 86 -> Icons.Default.AcUnit
        95, 96, 99 -> Icons.Default.FlashOn
        else -> if (isDay) Icons.Default.WbSunny else Icons.Default.NightsStay
    }
}

fun createWeatherBitmap(weatherCode: Int, isDay: Boolean = true): Bitmap {
    val size = 192
    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)

    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    // Dark rounded background
    paint.color = "#212121".toColorInt()
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 36f, 36f, paint)

    paint.color = Color.WHITE
    when (weatherCode) {
        0 -> { // Sun or Moon
            if (isDay) {
                paint.color = "#FFD700".toColorInt()
                canvas.drawCircle(size / 2f, size / 2f, 42f, paint)
                paint.strokeWidth = 8f
                paint.style = Paint.Style.STROKE
                for (i in 0 until 8) {
                    val angle = i * (Math.PI / 4)
                    val x1 = (size / 2f + 56 * cos(angle)).toFloat()
                    val y1 = (size / 2f + 56 * sin(angle)).toFloat()
                    val x2 = (size / 2f + 72 * cos(angle)).toFloat()
                    val y2 = (size / 2f + 72 * sin(angle)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            } else {
                // Moon (crescent)
                paint.style = Paint.Style.FILL
                paint.color = "#FFF59D".toColorInt()
                canvas.drawCircle(size * 0.48f, size * 0.5f, 48f, paint)
                paint.color = "#212121".toColorInt()
                canvas.drawCircle(size * 0.62f, size * 0.42f, 42f, paint)

                // Stars
                paint.color = Color.WHITE
                canvas.drawCircle(size * 0.28f, size * 0.3f, 4f, paint)
                canvas.drawCircle(size * 0.35f, size * 0.72f, 3f, paint)
                canvas.drawCircle(size * 0.78f, size * 0.7f, 4f, paint)
            }
        }
        1, 2, 3 -> { // Cloud
            if (weatherCode in 1..2 && !isDay) {
                // Partly cloudy night: Crescent moon behind cloud
                paint.style = Paint.Style.FILL
                paint.color = "#FFF59D".toColorInt()
                canvas.drawCircle(size * 0.38f, size * 0.4f, 28f, paint)
                paint.color = "#212121".toColorInt()
                canvas.drawCircle(size * 0.46f, size * 0.35f, 24f, paint)

                paint.color = "#E0E0E0".toColorInt()
                canvas.drawCircle(size * 0.4f, size * 0.6f, 32f, paint)
                canvas.drawCircle(size * 0.65f, size * 0.55f, 40f, paint)
                canvas.drawRect(size * 0.35f, size * 0.6f, size * 0.7f, size * 0.8f, paint)
            } else {
                paint.color = "#E0E0E0".toColorInt()
                canvas.drawCircle(size * 0.4f, size * 0.55f, 36f, paint)
                canvas.drawCircle(size * 0.65f, size * 0.5f, 46f, paint)
                canvas.drawRect(size * 0.35f, size * 0.55f, size * 0.7f, size * 0.76f, paint)
            }
        }
        45, 48 -> { // Fog
            paint.color = "#B0BEC5".toColorInt()
            paint.strokeWidth = 12f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(40f, 60f, 152f, 60f, paint)
            canvas.drawLine(30f, 96f, 162f, 96f, paint)
            canvas.drawLine(50f, 132f, 142f, 132f, paint)
        }
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> { // Rain
            paint.color = "#90CAF9".toColorInt()
            paint.strokeWidth = 10f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(60f, 110f, 50f, 145f, paint)
            canvas.drawLine(96f, 110f, 86f, 145f, paint)
            canvas.drawLine(132f, 110f, 122f, 145f, paint)
            paint.color = "#CFD8DC".toColorInt()
            canvas.drawCircle(96f, 75f, 40f, paint)
        }
        71, 73, 75, 77, 85, 86 -> { // Snow
            paint.color = Color.WHITE
            canvas.drawCircle(60f, 120f, 10f, paint)
            canvas.drawCircle(96f, 135f, 12f, paint)
            canvas.drawCircle(132f, 120f, 10f, paint)
            paint.color = "#CFD8DC".toColorInt()
            canvas.drawCircle(96f, 75f, 40f, paint)
        }
        95, 96, 99 -> { // Thunderstorm
            paint.color = "#FFEE58".toColorInt()
            val path = Path().apply {
                moveTo(100f, 35f)
                lineTo(68f, 100f)
                lineTo(104f, 100f)
                lineTo(82f, 155f)
                lineTo(132f, 88f)
                lineTo(96f, 88f)
                close()
            }
            canvas.drawPath(path, paint)
        }
        else -> {
            if (isDay) {
                paint.color = "#FFD700".toColorInt()
                canvas.drawCircle(size / 2f, size / 2f, 50f, paint)
            } else {
                paint.style = Paint.Style.FILL
                paint.color = "#FFF59D".toColorInt()
                canvas.drawCircle(size * 0.48f, size * 0.5f, 48f, paint)
                paint.color = "#212121".toColorInt()
                canvas.drawCircle(size * 0.62f, size * 0.42f, 42f, paint)
            }
        }
    }

    return bitmap
}

fun getCityName(context: Context, lat: Double, lon: Double): String {
    return try {
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(lat, lon, 1)
        if (!addresses.isNullOrEmpty()) {
            addresses[0].locality ?: addresses[0].subAdminArea ?: addresses[0].adminArea ?: "Posizione attuale"
        } else {
            "Posizione attuale"
        }
    } catch (_: Exception) {
        "Posizione attuale"
    }
}

suspend fun getLocation(context: Context): Triple<Double, Double, String> {
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    Log.d(TAG, "getLocation: ACCESS_COARSE_LOCATION granted = $hasCoarse")

    if (hasCoarse) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            
            // Prova prima la lastLocation che è istantanea
            val lastLoc: Location? = suspendCancellableCoroutine { continuation ->
                fusedClient.lastLocation.addOnSuccessListener { loc ->
                    continuation.resume(loc)
                }.addOnFailureListener {
                    continuation.resume(null)
                }
            }
            
            // Se lastLoc è recente (es. < 1 ora), usala subito per la massima velocità all'avvio
            if (lastLoc != null && (System.currentTimeMillis() - lastLoc.time) < 3600000) {
                val lat = lastLoc.latitude
                val lon = lastLoc.longitude
                val cityName = getCityName(context, lat, lon)
                Log.d(TAG, "Using fast lastLocation: lat=$lat, lon=$lon, city=$cityName")
                return Triple(lat, lon, cityName)
            }

            // Altrimenti chiedi una posizione fresca ma con timeout breve
            val currentLocation: Location? =
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cts = CancellationTokenSource()
                    fusedClient.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cts.token
                    ).addOnSuccessListener { loc ->
                        continuation.resume(loc)
                    }.addOnFailureListener {
                        continuation.resume(null)
                    }
                    // Timeout di sicurezza di 3 secondi per non bloccare l'avvio
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (continuation.isActive) {
                            cts.cancel()
                            continuation.resume(null)
                        }
                    }, 3000)
                }

            val location = currentLocation ?: lastLoc

            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                val cityName = getCityName(context, lat, lon)
                Log.d(TAG, "Using GPS/Cell location: lat=$lat, lon=$lon, city=$cityName")
                return Triple(lat, lon, cityName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting FusedLocation: ${e.message}", e)
        }
    }

    // IP Geolocation fallback (no location permissions required)
    try {
        Log.d(TAG, "Attempting IP geolocation fallback (ipapi.co)...")
        val ipResult = withContext(Dispatchers.IO) {
            val url = URL("https://ipapi.co/json/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val lat = json.optDouble("latitude", Double.NaN)
                val lon = json.optDouble("longitude", Double.NaN)
                val city = json.optString("city", "")
                if (!lat.isNaN() && !lon.isNaN()) {
                    Triple(lat, lon, if (city.isNotBlank()) city else "Posizione IP")
                } else null
            } else null
        }
        if (ipResult != null) {
            Log.d(TAG, "Using IP geolocation: lat=${ipResult.first}, lon=${ipResult.second}, city=${ipResult.third}")
            return ipResult
        }
    } catch (e: Exception) {
        Log.e(TAG, "IP geolocation fallback error: ${e.message}", e)
    }

    // Default fallback (Rome coordinates)
    Log.d(TAG, "Using default fallback location (Rome): lat=41.9028, lon=12.4964, city=Roma")
    return Triple(41.9028, 12.4964, "Roma")
}

suspend fun fetchWeather(context: Context): WeatherData? {
    return withContext(Dispatchers.IO) {
        try {
            val (lat, lon, locationName) = getLocation(context)
            val urlStr =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day"
            Log.d(TAG, "Fetching Open-Meteo URL: $urlStr")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val current = root.optJSONObject("current")
                if (current != null) {
                    val temp = current.optDouble("temperature_2m", 0.0)
                    val code = current.optInt("weather_code", 0)
                    val isDayRaw = current.optInt("is_day", -1)
                    val isDay = if (isDayRaw != -1) isDayRaw == 1 else isDaytimeFallback()
                    Log.d(TAG, "Open-Meteo success: temp=$temp, code=$code, isDay=$isDay, location=$locationName")
                    WeatherData(
                        temperature = temp,
                        weatherCode = code,
                        description = getWeatherDescription(code, isDay),
                        locationName = locationName,
                        icon = getWeatherIcon(code, isDay),
                        bitmap = createWeatherBitmap(code, isDay),
                        isDay = isDay
                    )
                } else {
                    Log.w(TAG, "Open-Meteo response missing 'current' object")
                    null
                }
            } else {
                Log.e(TAG, "Open-Meteo HTTP error code: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching weather: ${e.message}", e)
            null
        }
    }
}

private fun isDaytimeFallback(): Boolean {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return hour in 6..20
}