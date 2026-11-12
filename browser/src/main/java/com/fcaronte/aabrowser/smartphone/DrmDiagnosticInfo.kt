package com.fcaronte.aabrowser.smartphone

import android.media.MediaDrm
import android.util.Log
import java.util.UUID

object DrmDiagnosticInfo {
    private const val TAG = "DrmDiagnosticInfo"

    data class DrmReport(
        val isWidevineSupported: Boolean,
        val securityLevel: String,
        val vendor: String,
        val version: String,
        val supportsSecureDisplayWarning: String
    )

    fun getReport(): DrmReport {
        var isSupported = false
        var securityLevel = "Unknown"
        var vendor = "Unknown"
        var version = "Unknown"

        try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            isSupported = MediaDrm.isCryptoSchemeSupported(widevineUuid)
            if (isSupported) {
                val mediaDrm = MediaDrm(widevineUuid)
                securityLevel = mediaDrm.getPropertyString("securityLevel")
                vendor = mediaDrm.getPropertyString("vendor")
                version = mediaDrm.getPropertyString("version")
                mediaDrm.close()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error generating DRM diagnostic report", e)
        }

        val warning = if (securityLevel.contains("L1", ignoreCase = true)) {
            "Widevine L1 detected. However, virtual displays and non-secure compositing layers may trigger FLAG_SECURE black screen protection for protected streaming apps (Netflix, Prime Video, etc.)."
        } else {
            "Widevine L3 detected. Streaming quality may be limited to SD, and virtual display mirroring may be blocked by security policies."
        }

        Log.i(TAG, "DRM Report: Supported=$isSupported, Level=$securityLevel, Vendor=$vendor, Version=$version")

        return DrmReport(
            isWidevineSupported = isSupported,
            securityLevel = securityLevel,
            vendor = vendor,
            version = version,
            supportsSecureDisplayWarning = warning
        )
    }
}
