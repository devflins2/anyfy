package com.example

import com.example.service.PlaybackUiState
import com.example.service.RepeatMode
import com.example.service.sponsorblock.ReturnYouTubeDislikeRepository
import com.example.service.sponsorblock.SponsorBlockRepository
import com.example.service.sponsorblock.SponsorSegment
import com.example.service.sponsorblock.VideoVotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpMusicFeaturesTest {

    @Test
    fun testRepeatModeTransitions() {
        var mode = RepeatMode.OFF
        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.ALL, mode)

        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.ONE, mode)

        mode = when (mode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        assertEquals(RepeatMode.OFF, mode)
    }

    @Test
    fun testPlaybackUiStateRepeatCompatibility() {
        val stateOff = PlaybackUiState(repeatMode = RepeatMode.OFF)
        assertFalse(stateOff.isRepeat)

        val stateAll = PlaybackUiState(repeatMode = RepeatMode.ALL)
        assertTrue(stateAll.isRepeat)

        val stateOne = PlaybackUiState(repeatMode = RepeatMode.ONE)
        assertTrue(stateOne.isRepeat)
    }

    @Test
    fun testSponsorBlockSegmentMatching() {
        val segments = listOf(
            SponsorSegment(startMs = 5000L, endMs = 12000L, category = "sponsor"),
            SponsorSegment(startMs = 45000L, endMs = 52000L, category = "outro")
        )

        // Inside segment 1
        val match1 = segments.firstOrNull { 8000L in it.startMs until it.endMs }
        assertEquals("sponsor", match1?.category)
        assertEquals(12000L, match1?.endMs)

        // Outside segments
        val matchOutside = segments.firstOrNull { 20000L in it.startMs until it.endMs }
        assertEquals(null, matchOutside)

        // Inside segment 2
        val match2 = segments.firstOrNull { 48000L in it.startMs until it.endMs }
        assertEquals("outro", match2?.category)
    }

    @Test
    fun testVideoVotesModel() {
        val votes = VideoVotes(
            videoId = "dQw4w9WgXcQ",
            likes = 15000000,
            dislikes = 400000,
            rating = 4.88,
            viewCount = 1400000000L
        )
        assertEquals("dQw4w9WgXcQ", votes.videoId)
        assertEquals(15000000, votes.likes)
        assertEquals(400000, votes.dislikes)
        assertTrue(votes.rating > 4.5)
        assertTrue(votes.viewCount > 1000000L)
    }

    @Test
    fun testPlaybackSpeedClamping() {
        fun clampSpeed(speed: Float): Float = speed.coerceIn(0.25f, 3.0f)

        assertEquals(1.0f, clampSpeed(1.0f), 0.001f)
        assertEquals(0.25f, clampSpeed(0.1f), 0.001f)
        assertEquals(3.0f, clampSpeed(5.0f), 0.001f)
        assertEquals(1.5f, clampSpeed(1.5f), 0.001f)
    }
}

