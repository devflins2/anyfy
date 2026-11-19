package com.example.service.importer

import com.example.service.importer.normalizer.TrackNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNormalizerTest {

    @Test
    fun normalize_stripsNoiseAndVersionIdentifiers() {
        val norm1 = TrackNormalizer.normalize(
            rawTitle = "Kesariya (Official Music Video)",
            rawArtists = listOf("Arijit Singh"),
            rawAlbum = "Brahmāstra (Deluxe Edition)"
        )
        assertEquals("Kesariya", norm1.cleanTitle)
        assertEquals("Arijit Singh", norm1.primaryArtist)
        assertEquals("Brahmastra", norm1.cleanAlbum)

        val norm2 = TrackNormalizer.normalize(
            rawTitle = "Blinding Lights [Remastered 2020]",
            rawArtists = listOf("The Weeknd")
        )
        assertEquals("Blinding Lights", norm2.cleanTitle)

        val norm3 = TrackNormalizer.normalize(
            rawTitle = "Believer - (Official Audio)",
            rawArtists = listOf("Imagine Dragons")
        )
        assertEquals("Believer", norm3.cleanTitle)
    }

    @Test
    fun normalize_extractsFeaturedArtists() {
        val norm = TrackNormalizer.normalize(
            rawTitle = "Starboy (feat. Daft Punk)",
            rawArtists = listOf("The Weeknd")
        )
        assertEquals("Starboy", norm.cleanTitle)
        assertTrue(norm.cleanArtists.contains("The Weeknd"))
        assertTrue(norm.cleanArtists.contains("Daft Punk"))
        assertEquals("Daft Punk", norm.featuredArtists.first())
    }

    @Test
    fun normalize_unicodeAccentsAndSmartPunctuation() {
        val norm = TrackNormalizer.normalize(
            rawTitle = "Señorita",
            rawArtists = listOf("Shawn Mendes", "Camila Cabello")
        )
        assertEquals("Senorita", norm.cleanTitle)
        assertEquals("Shawn Mendes", norm.primaryArtist)
        assertEquals(2, norm.cleanArtists.size)
    }

    @Test
    fun computeSimilarity_accurateScoring() {
        // Exact match
        val simExact = TrackNormalizer.computeSimilarity("Kesariya", "Kesariya")
        assertEquals(1.0f, simExact, 0.001f)

        // Substring / containment match
        val simSub = TrackNormalizer.computeSimilarity("Kesariya", "Kesariya Audio")
        assertTrue("Substrings must score high (>=0.85)", simSub >= 0.85f)

        // Completely unrelated titles
        val simUnrelated = TrackNormalizer.computeSimilarity("Kesariya", "Blinding Lights")
        assertEquals(0.0f, simUnrelated, 0.001f)
    }
}

