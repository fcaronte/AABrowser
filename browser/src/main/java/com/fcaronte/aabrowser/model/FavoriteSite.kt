package com.fcaronte.aabrowser.model

data class FavoriteSite(
    val id: String,
    val name: String,
    val url: String,
    val color: Long = 0xFF2196F3, // Default Blue
    val faviconUrl: String? = null,
    val isDesktopMode: Boolean? = null,
    val mobileZoom: Float? = null,
    val desktopZoom: Float? = null
)
