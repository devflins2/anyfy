package com.example.service.integrations

/**
 * Utility for generating matched HTTP request headers for YouTube Music and CDN audio streams.
 *
 * Ensures the User-Agent sent by ExoPlayer / OkHttp precisely matches the YouTube client that
 * generated the stream URL (e.g. android client streams require an Android User-Agent to avoid
 * HTTP 403 Forbidden errors from googlevideo.com).
 */
object YouTubeStreamHeaders {
    const val USER_AGENT_ANDROID = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
    const val USER_AGENT_ANDROID_MUSIC = "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 11) gzip"
    const val USER_AGENT_IOS = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    const val USER_AGENT_TV = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15"

    /**
     * Inspects the stream URL to detect the originating YouTube/InnerTube client
     * and returns the matching User-Agent string.
     */
    fun getUserAgentForUrl(url: String?): String {
        if (url.isNullOrBlank()) return USER_AGENT_WEB
        val lower = url.lowercase()
        return when {
            lower.contains("c=android_music") -> USER_AGENT_ANDROID_MUSIC
            lower.contains("c=android") -> USER_AGENT_ANDROID
            lower.contains("c=ios") -> USER_AGENT_IOS
            lower.contains("c=tvhtml5") -> USER_AGENT_TV
            else -> USER_AGENT_WEB
        }
    }

    /**
     * Builds standard headers for ExoPlayer DataSource and OkHttp.
     * Enforces Range support, identity encoding (prevents corrupt chunk decompression),
     * and CDN referrers.
     */
    fun buildRequestHeadersForUrl(url: String?): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val userAgent = getUserAgentForUrl(url)
        headers["User-Agent"] = userAgent
        headers["Accept"] = "*/*"
        headers["Accept-Encoding"] = "identity" // Ensures raw uncompressed progressive byte streaming
        // NOTE: Range header is dynamically added by Media3 DataSource per chunk.
        // Never hardcode Range: bytes=0- in default request properties to avoid chunk collision.

        if (url != null) {
            val lower = url.lowercase()
            if (lower.contains("saavncdn.com") || lower.contains("jiosaavn.com")) {
                headers["Referer"] = "https://www.jiosaavn.com/"
                headers["Origin"] = "https://www.jiosaavn.com"
            } else if (lower.contains("googlevideo.com")) {
                if (!lower.contains("c=android") && !lower.contains("c=ios")) {
                    headers["Origin"] = "https://music.youtube.com"
                    headers["Referer"] = "https://music.youtube.com/"
                }
            }
        }
        return headers
    }
}

