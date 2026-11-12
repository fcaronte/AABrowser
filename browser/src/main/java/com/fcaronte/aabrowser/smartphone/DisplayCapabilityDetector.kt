package com.fcaronte.aabrowser.smartphone

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import java.util.UUID

object DisplayCapabilityDetector {
    private const val TAG = "DisplayCapabilityDetector"

    data class DeviceCapabilities(
        val apiLevel: Int,
        val isMultiDisplaySupported: Boolean,
        val isSamsungOneUi: Boolean,
        val widevineSecurityLevel: String,
        val supportsSecureDisplay: Boolean
    )

    fun detectCapabilities(context: Context): DeviceCapabilities {
        val apiLevel = Build.VERSION.SDK_INT
        val pm = context.packageManager
        
        // Multi-display is supported from Android 10 (API 29) onwards
        val isMultiDisplaySupported = apiLevel >= Build.VERSION_CODES.Q

        val brand = Build.BRAND ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val isSamsungOneUi = brand.equals("samsung", ignoreCase = true) ||
                manufacturer.equals("samsung", ignoreCase = true)

        val widevineLevel = getWidevineSecurityLevel()
        // Secure display support generally requires API 29+ and hardware security level L1
        val supportsSecureDisplay = apiLevel >= Build.VERSION_CODES.Q && widevineLevel.contains("L1", ignoreCase = true)

        Log.d(TAG, "Capabilities: API=$apiLevel, MultiDisplay=$isMultiDisplaySupported, Samsung=$isSamsungOneUi, Widevine=$widevineLevel, SecureDisplay=$supportsSecureDisplay")

        return DeviceCapabilities(
            apiLevel = apiLevel,
            isMultiDisplaySupported = isMultiDisplaySupported,
            isSamsungOneUi = isSamsungOneUi,
            widevineSecurityLevel = widevineLevel,
            supportsSecureDisplay = supportsSecureDisplay
        )
    }

    private fun getWidevineSecurityLevel(): String {
        return try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            if (MediaDrm.isCryptoSchemeSupported(widevineUuid)) {
                val mediaDrm = MediaDrm(widevineUuid)
                val securityLevel = mediaDrm.getPropertyString("securityLevel")
                mediaDrm.close()
                securityLevel ?: "Unknown"
            } else {
                "Not Supported"
            }
        } catch (e: UnsupportedSchemeException) {
            "Unsupported Scheme"
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking Widevine security level", e)
            "Error"
        }
    }
}
