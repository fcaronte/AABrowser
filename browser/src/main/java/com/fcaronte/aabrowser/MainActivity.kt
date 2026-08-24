package com.fcaronte.aabrowser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fcaronte.aabrowser.ui.MainScreen
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        ForegroundService.startForegroundService(this)

        setContent {
            MainScreen()
        }

        requestIgnoreBatteryOptimizations()
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    override fun onDestroy() {
        ForegroundService.stopForegroundService(this)
        super.onDestroy()
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
}
