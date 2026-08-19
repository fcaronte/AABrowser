package com.fcaronte.aabrowser

import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fcaronte.aabrowser.ui.MainScreen
import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.gms.car.input.InputManager

class MainCarActivity : CarActivity(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val mViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = mViewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate(bundle: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(bundle)

        // Controlla permessi critici
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("MainCarActivity", "RECORD_AUDIO permission not granted!")
        }

        // Avvia il servizio in primo piano per evitare che il sistema lo killi in background
        ForegroundService.startForegroundService(this)

        savedStateRegistryController.performRestore(bundle)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainCarActivity)
            setViewTreeViewModelStoreOwner(this@MainCarActivity)
            setViewTreeSavedStateRegistryOwner(this@MainCarActivity)

            val inputManager = try {
                CarInputManager(findInputManager())
            } catch (_: Exception) {
                null
            }

            setContent {
                MainScreen(carInputManager = inputManager)
            }
        }

        setContentView(composeView)

        // Attempt to set tree owners on the decor view if accessible
        try {
            val window = getCarWindow()
            window?.decorView?.let {
                it.setViewTreeLifecycleOwner(this)
                it.setViewTreeViewModelStoreOwner(this)
                it.setViewTreeSavedStateRegistryOwner(this)
            }
        } catch (_: Exception) {
            // Fallback or ignore if obfuscated name changed
        }

        try {
            val methodSetIgnore =
                this.javaClass.getMethod("setIgnoreConfigChanges", Int::class.javaPrimitiveType)
            methodSetIgnore.invoke(this, 0xFFFF)
        } catch (_: Exception) {
        }

        updateSystemUi(true)
    }

    private fun getCarWindow(): android.view.Window? {
        return try {
            val methodC = this.javaClass.getMethod("c")
            methodC.invoke(this) as? android.view.Window
        } catch (_: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        updateSystemUi(true)
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        mViewModelStore.clear()

        // Ferma il servizio in primo piano alla chiusura dell'activity sull'auto
        ForegroundService.stopForegroundService(this)

        super.onDestroy()
    }

    private fun findInputManager(): InputManager? {
        return try {
            var currentClass: Class<*>? = this.javaClass
            while (currentClass != null && currentClass != Any::class.java) {
                val methods = currentClass.declaredMethods
                for (method in methods) {
                    // Alcune versioni dell'SDK usano nomi diversi o sono offuscate
                    if (method.returnType.name.endsWith(".input.InputManager")) {
                        method.isAccessible = true
                        val result = method.invoke(this) as? InputManager
                        if (result != null) return result
                    }
                }
                currentClass = currentClass.superclass
            }

            // Fallback: prova a chiamare il metodo "getInputManager" se esiste
            val getIM = this.javaClass.getMethod("getInputManager")
            getIM.isAccessible = true
            getIM.invoke(this) as? InputManager
        } catch (_: Exception) {
            // Ultima spiaggia: cerca un metodo che restituisca Object ma si chiami in modo sospetto
            try {
                val methods = CarActivity::class.java.declaredMethods
                for (method in methods) {
                    if (method.name.contains("Input") && method.parameterCount == 0) {
                        method.isAccessible = true
                        val res = method.invoke(this)
                        if (res != null && res.javaClass.name.contains("InputManager")) {
                            return res as InputManager
                        }
                    }
                }
            } catch (_: Exception) {
            }
            null
        }
    }

    fun updateSystemUi(fullscreen: Boolean) {
        try {
            val window = getCarWindow() ?: return
            val controller = WindowInsetsControllerCompat(window, window.decorView)

            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {
        }
    }

    // CarActivity in alcune versioni riceve onActivityResult tramite questo metodo o simili
    // Se dà errore di override, usiamo una versione senza override e vediamo se viene chiamata
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == 1002 && resultCode == -1) { // -1 is Activity.RESULT_OK
            val results =
                data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.firstOrNull()
            if (!spokenText.isNullOrEmpty()) {
                getCarWindow()?.decorView?.let { decor ->
                    findWebView(decor)?.let { webView ->
                        val escaped = spokenText.replace("'", "\\'")
                        webView.evaluateJavascript(
                            "if(window.AndroidBridge) AndroidBridge.injectText('$escaped');",
                            null
                        )
                    }
                }
            }
        }
    }

    private fun findWebView(view: View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findWebView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
