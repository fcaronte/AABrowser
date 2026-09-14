package com.fcaronte.aabrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.net.toUri
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val permissionRequestCode = 1005

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        AppSettings.init(applicationContext)
        if (!AppSettings.onboardingCompleted.value) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge()

        ForegroundService.startForegroundService(this)

        setContent {
            MainScreen()
        }

        // Richieste all'avvio
        requestIgnoreBatteryOptimizations()
        checkAndRequestAppPermissions()
    }

    private fun checkAndRequestAppPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // 1. Permesso Notifiche (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 2. Posizione approssimativa (per il meteo senza GPS ad alta precisione)
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 3. Microfono (per la ricerca vocale)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Se c'è almeno un permesso mancante, li chiede tutti insieme con un unico popup di sistema
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), permissionRequestCode)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            @SuppressLint("BatteryLife")
            val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivityForResult(intent, 1001)
        }
    }

    override fun onDestroy() {
        ForegroundService.stopForegroundService(this)
        super.onDestroy()
    }
}