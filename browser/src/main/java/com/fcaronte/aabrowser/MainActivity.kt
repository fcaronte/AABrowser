package com.fcaronte.aabrowser

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.ui.MainScreen
import com.fcaronte.aabrowser.utils.VoiceCommandRouter
import com.fcaronte.aabrowser.utils.WebLocation

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

        setContent {
            MainScreen()
        }

        // Solo al primo avvio dell'activity: ricreata (rotazione) ripeterebbe il comando
        if (savedInstanceState == null) handleVoiceSearch(intent)

        checkAndRequestAppPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceSearch(intent)
    }

    // "Ok Google, metti … su AABrowser" sul telefono, sui sistemi che aprono l'app con l'intent invece di usare la
    // sessione media (CarMediaService). Il comando aspetta che la Main sia pronta (VoiceCommandRouter)
    private fun handleVoiceSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        VoiceCommandRouter.fromAssistant(intent.getStringExtra(SearchManager.QUERY))
    }

    override fun onResume() {
        super.onResume()
        AppSettings.enableWeatherByDefaultIfLocationGranted(this)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Posizione per i siti (da Impostazioni): dopo quella precisa chiede subito "Consenti sempre" (serve in auto)
        WebLocation.onPermissionResult(this, requestCode)
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

        // 4. Rete locale (Android 17+): senza, le pagine non raggiungono gli IP di casa (Home Assistant,
        //    Proxmox, proxy). Si può chiedere solo dal telefono: in auto il sistema non mostra la richiesta
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }

        // Se c'è almeno un permesso mancante, li chiede tutti insieme con un unico popup di sistema
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), permissionRequestCode)
        }
    }

}