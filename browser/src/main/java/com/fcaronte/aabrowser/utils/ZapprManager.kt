package com.fcaronte.aabrowser.utils

object ZapprManager {
    /**
     * Logic to extract Zappr metadata and force live stream mode.
     */
    fun getMetadataScript(): String {
        return """
        if (window.location.host.includes('zappr.stream') || window.location.host.includes('zapps')) {
            title = "Zappr";
            artist = "Live Stream";
            
            const zapprLogo = document.querySelector('.player-channel-logo img, .channel-logo img, .stream-logo, .logo img, img[src*="logo"], img[src*="station"], img[src*="thumb"], [class*="logo"] img, [class*="player"] img[src*="http"]')?.src;
            if (zapprLogo) {
                artUrl = zapprLogo;
            }
            duration = 0; // Force live stream
        }
        """.trimIndent()
    }
}
