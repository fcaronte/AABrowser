package com.fcaronte.aabrowser.smartphone

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/** Captures frames from a Shizuku-owned virtual display into an ImageReader surface. */
class NormalVirtualDisplayRenderer(
    private val width: Int,
    private val height: Int,
    private val onFrameAvailable: (Bitmap) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var imageReader: ImageReader? = null

    val surface: Surface?
        get() = imageReader?.surface

    fun start(): Surface? {
        return try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ reader ->
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (_: IllegalStateException) {
                        null
                    } ?: return@setOnImageAvailableListener
                    try {
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val paddedWidth = width + (rowStride - pixelStride * width) / pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val frame = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
                        handler.post { onFrameAvailable(frame) }
                    } catch (error: Throwable) {
                        Log.e(TAG, "Could not copy virtual display frame", error)
                    } finally {
                        image.close()
                    }
                }, handler)
            }
            imageReader?.surface
        } catch (error: Throwable) {
            Log.e(TAG, "Could not create image reader", error)
            null
        }
    }

    fun stop() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not close image reader", error)
        } finally {
            imageReader = null
        }
    }

    companion object {
        private const val TAG = "NormalVirtualDisplayRenderer"
    }
}