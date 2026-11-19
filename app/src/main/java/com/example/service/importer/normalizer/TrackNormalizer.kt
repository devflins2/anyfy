package com.example.service.importer.normalizer

import java.text.Normalizer
import java.util.Locale

data class NormalizedTrackMetadata(
    val cleanTitle: String,
    val cleanArtists: List<String>,
    val featuredArtists: List<String>,
    val primaryArtist: String,
    val cleanAlbum: String,
    val searchTerms: List<String>
)

object TrackNormalizer {

    // Common noise patterns in track titles across Spotify and YouTube Music
    private val NOISE_PATTERNS = listOf(
        Regex("""(?i)\s*[\(\[\{]\s*(official\s*(music\s*)?(video|audio|visualizer|lyric\s*video|track))\s*[\)\]\}]"""),
        Regex("""(?i)\s*[\(\[\{]\s*(audio|video|lyrics|hd|4k|hq|remastered(\s*\d{4})?|remaster)\s*[\)\]\}]"""),
        Regex("""(?i)\s*[\(\[\{]\s*(full\s*song|original\s*mix|lyric\s*video)\s*[\)\]\}]"""),
        Regex("""(?i)\s*[\(\[\{]\s*(live(\s*at\s*[^)\]]+)?|acoustic|unplugged)\s*[\)\]\}]"""),
        Regex("""(?i)\s*[\(\[\{]\s*(bonus\s*track|deluxe(\s*edition)?)\s*[\)\]\}]""")
    )

    private val FEAT_REGEX = Regex("""(?i)\s*[\(\[\{]?\s*(?:feat\.?|ft\.?|featuring)\s+([^()\[\]{}]+)[\)\]\}]?""")

    fun normalize(rawTitle: String, rawArtists: List<String>, rawAlbum: String = ""): NormalizedTrackMetadata {
        val extractedFeatured = mutableListOf<String>()

        // 1. Unicode normalization (decomposing diacritics, standardizing smart quotes)
        var title = stripAccents(rawTitle)
            .replace("’", "'")
            .replace("‘", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("–", "-")
            .replace("—", "-")
            .trim()

        // 2. Extract and strip featured artists from title
        FEAT_REGEX.find(title)?.let { match ->
            val featArtistString = match.groupValues[1]
            extractedFeatured.addAll(splitArtists(featArtistString))
            title = title.replace(match.value, "").trim()
        }

        // 3. Strip noise patterns
        for (pattern in NOISE_PATTERNS) {
            title = pattern.replace(title, "").trim()
        }

        // Clean extra punctuation at ends (e.g. trailing hyphens, brackets)
        title = title.replace(Regex("""^[\s\-–—:|]+|[\s\-–—:|]+$"""), "").trim()
        if (title.isBlank()) {
            title = rawTitle.trim()
        }

        // 4. Normalize artist names
        val allArtists = (rawArtists.flatMap { splitArtists(it) } + extractedFeatured)
            .map { cleanArtistString(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val primaryArtist = allArtists.firstOrNull() ?: "Unknown Artist"

        val cleanAlbum = stripAccents(rawAlbum)
            .replace(Regex("""(?i)\s*[\(\[]?(deluxe(\s*edition)?|remastered|bonus(\s*track)?\s*version)[\)\]]?"""), "")
            .trim()

        // 5. Build prioritized search terms for matching engine
        val searchTerms = mutableListOf<String>()
        // Priority 1: "CleanTitle PrimaryArtist"
        if (primaryArtist != "Unknown Artist") {
            searchTerms.add("$title $primaryArtist".trim())
        }
        // Priority 2: "CleanTitle"
        searchTerms.add(title)
        // Priority 3: "Title AllArtists"
        if (allArtists.size > 1) {
            searchTerms.add("$title ${allArtists.take(2).joinToString(" ")}".trim())
        }

        return NormalizedTrackMetadata(
            cleanTitle = title,
            cleanArtists = allArtists,
            featuredArtists = extractedFeatured,
            primaryArtist = primaryArtist,
            cleanAlbum = cleanAlbum,
            searchTerms = searchTerms.distinct()
        )
    }

    fun cleanArtistString(artist: String): String {
        return stripAccents(artist)
            .replace("’", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun splitArtists(raw: String): List<String> {
        val delimiters = Regex(""",|\s+&\s+|\s+and\s+|\s+x\s+|\s+with\s+|;""", RegexOption.IGNORE_CASE)
        return raw.split(delimiters)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun stripAccents(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        // Strips only Latin/European combining diacritical marks (\u0300-\u036F)
        // Preserves Indic (Devanagari/Gurmukhi/Bengali) vowel signs and non-Latin scripts intact
        return Regex("""[\u0300-\u036f]""").replace(normalized, "")
    }

    /**
     * Compute string similarity between 0.0 and 1.0 (token overlap + Jaccard + Levenshtein).
     */
    fun computeSimilarity(s1: String, s2: String): Float {
        val n1 = simplifyForComparison(s1)
        val n2 = simplifyForComparison(s2)

        if (n1.isEmpty() || n2.isEmpty()) return 0f
        if (n1 == n2) return 1.0f
        if (n1.contains(n2) || n2.contains(n1)) {
            val shorter = minOf(n1.length, n2.length).toFloat()
            val longer = maxOf(n1.length, n2.length).toFloat()
            return 0.85f + 0.15f * (shorter / longer)
        }

        val tokens1 = n1.split(" ").filter { it.length > 1 }.toSet()
        val tokens2 = n2.split(" ").filter { it.length > 1 }.toSet()

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0f

        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        val jaccard = intersection.toFloat() / union.toFloat()

        return jaccard
    }

    fun simplifyForComparison(s: String): String {
        return stripAccents(s.lowercase(Locale.ROOT))
            .replace(Regex("""(?i)^\s*(the|dj|mc)\s+"""), "")
            .replace(Regex("""[^\p{L}\p{M}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
