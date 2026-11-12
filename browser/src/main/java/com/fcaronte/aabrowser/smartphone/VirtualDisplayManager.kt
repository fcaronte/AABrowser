package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface
import android.util.Log

class VirtualDisplayManager(private val context: Context) {
    private val TAG = "VirtualDisplayManager"
    private var virtualDisplay: VirtualDisplay? = null

    val displayId: Int
        get() = virtualDisplay?.display?.displayId ?: -1

    /**
     * Creates a virtual display with the given surface and dimensions.
     */
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        density: Int,
        surface: Surface?,
        flags: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                     DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    ): Int {
        release()
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (displayManager == null) {
                Log.e(TAG, "DisplayManager service not available")
                return -1
            }

            virtualDisplay = displayManager.createVirtualDisplay(
                name,
                width,
                height,
                density,
                surface,
                flags
            )

            val id = virtualDisplay?.display?.displayId ?: -1
            Log.i(TAG, "Virtual display created successfully: id=$id, ${width}x${height}@${density}dpi")
            return id
        } catch (e: Throwable) {
            Log.e(TAG, "Error creating virtual display", e)
            return -1
        }
    }

    /**
     * Releases the virtual display.
     */
    fun release() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            Log.i(TAG, "Virtual display released")
        } catch (e: Throwable) {
            Log.e(TAG, "Error releasing virtual display", e)
        }
    }
}
