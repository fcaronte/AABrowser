package com.fcaronte.aabrowser.utils

object YouTubeManager {
    fun getMetadataScript(): String {
        return """
        let ytTitle = "";
        let ytArtist = "";
        
        if (navigator.mediaSession && navigator.mediaSession.metadata) {
            const meta = navigator.mediaSession.metadata;
            if (meta.title && !meta.title.toLowerCase().includes("youtube")) {
                ytTitle = meta.title;
            }
            if (meta.artist && !meta.artist.toLowerCase().includes("youtube")) {
                ytArtist = meta.artist;
            }
            if (meta.artwork && meta.artwork.length > 0) {
                const lastArt = meta.artwork[meta.artwork.length - 1];
                if (lastArt && lastArt.src) artUrl = lastArt.src;
            }
        }
        
        if (!ytTitle) {
            ytTitle = document.querySelector('.ytp-title-link')?.innerText || 
                     document.querySelector('ytmusic-player-bar .title')?.textContent ||
                     document.querySelector('.ytmusic-player-bar .title')?.textContent ||
                     document.querySelector('.video-title')?.textContent ||
                     document.querySelector('h1.ytd-watch-metadata')?.textContent ||
                     document.querySelector('h1.title.ytd-video-primary-info-renderer')?.textContent ||
                     document.querySelector('yt-formatted-string.ytd-watch-metadata')?.textContent ||
                     "";
        }
        if (!ytArtist) {
            ytArtist = document.querySelector('.ytp-ce-channel-title')?.innerText || 
                      document.querySelector('#upload-info #channel-name')?.innerText ||
                      document.querySelector('ytmusic-player-bar .byline')?.textContent ||
                      document.querySelector('.ytmusic-player-bar .byline')?.textContent ||
                      document.querySelector('#owner #channel-name')?.innerText ||
                      document.querySelector('.channel-name')?.textContent ||
                      "";
        }
        
        if (ytTitle) title = ytTitle.trim();
        if (ytArtist) artist = ytArtist.trim();
        
        const urlParams = new URLSearchParams(window.location.search);
        const v = urlParams.get('v');
        if (v && !artUrl) artUrl = 'https://img.youtube.com/vi/' + v + '/hqdefault.jpg';
        
        if (window.location.host.includes('music.youtube.com')) {
            const mArt = document.querySelector('ytmusic-player-bar img')?.src || document.querySelector('.ytmusic-player-bar img')?.src;
            if (mArt) artUrl = mArt.replace(/=w\d+-h\d+/, '=w512-h512');
        }
        """.trimIndent()
    }
}
