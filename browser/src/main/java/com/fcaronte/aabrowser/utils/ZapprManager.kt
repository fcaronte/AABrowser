package com.fcaronte.aabrowser.utils

object ZapprManager {
    fun getMetadataScript(): String {
        return """
        title = "Zappr";
        artist = "Live Stream";
        const zLogo = document.querySelector('.player-channel-logo img, .channel-logo img, .stream-logo, .logo img, img[src*="logo"], img[src*="station"], img[src*="thumb"]')?.src;
        if (zLogo) artUrl = zLogo;
        duration = 0;
        """.trimIndent()
    }
}
