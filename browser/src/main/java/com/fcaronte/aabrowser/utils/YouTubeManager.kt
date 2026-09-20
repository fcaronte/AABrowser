package com.fcaronte.aabrowser.utils

object YouTubeManager {
    fun getMetadataScript(): String {
        return """
        const ytTitle = document.querySelector('.ytp-title-link')?.innerText || 
                         document.querySelector('ytmusic-player-bar .title')?.textContent ||
                         document.querySelector('.ytmusic-player-bar .title')?.textContent ||
                         document.querySelector('.video-title')?.textContent;
        const ytArtist = document.querySelector('.ytp-ce-channel-title')?.innerText || 
                         document.querySelector('#upload-info #channel-name')?.innerText ||
                         document.querySelector('ytmusic-player-bar .byline')?.textContent ||
                         document.querySelector('.channel-name')?.textContent;
        
        if (ytTitle) title = ytTitle.trim();
        if (ytArtist) artist = ytArtist.trim();
        
        const urlParams = new URLSearchParams(window.location.search);
        const v = urlParams.get('v');
        if (v) artUrl = 'https://img.youtube.com/vi/' + v + '/hqdefault.jpg';
        
        if (window.location.host.includes('music.youtube.com')) {
            const mArt = document.querySelector('ytmusic-player-bar img')?.src || document.querySelector('.ytmusic-player-bar img')?.src;
            if (mArt) artUrl = mArt.replace(/=w\d+-h\d+/, '=w512-h512');
        }
        """.trimIndent()
    }
}
