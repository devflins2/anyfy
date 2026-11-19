package com.example.data.remote

/**
 * Centralized media streaming header provider.
 *
 * NOTE: YouTube session cookies are NEVER stored or handled in the Android client.
 * All authenticated YouTube extraction and session management is handled strictly
 * on the backend server, protecting credentials from APK decompilation.
 */
object SessionCookieManager {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Standard HTTP request headers for audio streaming and API calls.
     * Contains standard browser headers; no secrets or cookies.
     */
    fun getMediaHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://www.youtube.com/"
        )
    }
}
