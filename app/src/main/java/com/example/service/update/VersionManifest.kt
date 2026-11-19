package com.example.service.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents the remote version manifest hosted on GitHub Releases (version.json).
 */
data class VersionManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: List<String> = emptyList(),
    val mandatory: Boolean = false,
    val minSupportedVersionCode: Int = 0,
    val publishedAt: String = ""
) {
    /**
     * Validates that the manifest contains safe, well-formed HTTPS release metadata.
     */
    fun isValid(): Boolean {
        if (versionCode <= 0) return false
        if (versionName.isBlank()) return false
        if (!apkUrl.startsWith("https://", ignoreCase = true)) return false
        val cleanSha = sha256.trim()
        if (cleanSha.length != 64) return false
        if (!cleanSha.matches(Regex("^[a-fA-F0-9]{64}$"))) return false
        return true
    }

    companion object {
        fun fromJson(jsonString: String): VersionManifest {
            val json = JSONObject(jsonString)
            val notesList = mutableListOf<String>()
            val notesArray = json.optJSONArray("releaseNotes")
            if (notesArray != null) {
                for (i in 0 until notesArray.length()) {
                    val note = notesArray.optString(i)
                    if (note.isNotBlank()) {
                        notesList.add(note.trim())
                    }
                }
            }

            return VersionManifest(
                versionCode = json.optInt("versionCode", -1),
                versionName = json.optString("versionName", "").trim(),
                apkUrl = json.optString("apkUrl", "").trim(),
                sha256 = json.optString("sha256", "").trim().lowercase(),
                releaseNotes = notesList,
                mandatory = json.optBoolean("mandatory", false),
                minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0),
                publishedAt = json.optString("publishedAt", "")
            )
        }
    }
}
