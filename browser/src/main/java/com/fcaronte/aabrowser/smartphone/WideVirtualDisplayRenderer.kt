package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log

class WideVirtualDisplayRenderer(
    private val context: Context,
    private val width: Int = 1280,
    private val height: Int = 720,
    private val density: Int = 216,
    private val onFrameAvailable: (Bitmap) -> Unit
) {
    private val TAG = "WideVirtualDisplayRenderer"
    private val virtualDisplayManager = VirtualDisplayManager(context)
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    val displayId: Int
        get() = virtualDisplayManager.displayId

    fun start() {
        try {
            imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image.close()

                        val croppedBitmap = if (rowPadding == 0) {
                            bitmap
                        } else {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        }

                        handler.post {
                            onFrameAvailable(croppedBitmap)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error processing Wide virtual display frame", e)
                        image.close()
                    }
                }, handler)
            }

            val surface = imageReader?.surface
            virtualDisplayManager.createVirtualDisplay(
                name = "CarWebWideVirtualDisplay",
                width = width,
                height = height,
                density = density,
                surface = surface
            )
            Log.i(TAG, "WideVirtualDisplayRenderer started successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WideVirtualDisplayRenderer", e)
        }
    }

    fun stop() {
        try {
            virtualDisplayManager.release()
            imageReader?.close()
            imageReader = null
            Log.i(TAG, "WideVirtualDisplayRenderer stopped")
        } catch (e: Throwable) {
            Log.e(TAG, "Error stopping WideVirtualDisplayRenderer", e)
        }
    }
}
