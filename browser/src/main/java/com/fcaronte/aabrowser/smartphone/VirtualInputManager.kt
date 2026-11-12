package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import java.lang.reflect.Method

class VirtualInputManager(private val context: Context) {
    private val TAG = "VirtualInputManager"
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    private val inputManager: Any? by lazy {
        try {
            val imClass = InputManager::class.java
            val getInstanceMethod = imClass.getMethod("getInstance")
            getInstanceMethod.invoke(null)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get InputManager instance via reflection", e)
            null
        }
    }

    init {
        try {
            val imClass = InputManager::class.java
            injectInputEventMethod = imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            injectInputEventMethod?.isAccessible = true

            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            setDisplayIdMethod?.isAccessible = true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to resolve input reflection methods", e)
        }
    }

    /**
     * Injects a MotionEvent into the specified virtual display.
     */
    fun injectMotionEvent(motionEvent: MotionEvent, displayId: Int, mode: Int = 0): Boolean {
        if (displayId < 0) return false
        try {
            setDisplayIdMethod?.invoke(motionEvent, displayId)
            return invokeInject(motionEvent, mode)
        } catch (e: Throwable) {
            Log.e(TAG, "Error injecting MotionEvent for display $displayId", e)
            return false
        }
    }

    /**
     * Injects a KeyEvent into the specified virtual display.
     */
    fun injectKeyEvent(keyEvent: KeyEvent, displayId: Int, mode: Int = 0): Boolean {
        if (displayId < 0) return false
        try {
            setDisplayIdMethod?.invoke(keyEvent, displayId)
            return invokeInject(keyEvent, mode)
        } catch (e: Throwable) {
            Log.e(TAG, "Error injecting KeyEvent for display $displayId", e)
            return false
        }
    }

    private fun invokeInject(event: InputEvent, mode: Int): Boolean {
        val im = inputManager ?: return false
        val method = injectInputEventMethod ?: return false
        try {
            val result = method.invoke(im, event, mode) as? Boolean
            return result ?: false
        } catch (e: Throwable) {
            Log.e(TAG, "Error invoking injectInputEvent", e)
            return false
        }
    }
}
