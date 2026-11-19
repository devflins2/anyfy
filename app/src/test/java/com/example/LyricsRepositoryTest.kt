package com.example

import com.example.service.lyrics.LyricsRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun parseLrc_standardFormat_correctlyComputesTimestamps() {
        val lrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [00:05.50]First line of lyrics
            [00:12.00]Second line after pause
            [01:05.123]Line after one minute mark
        """.trimIndent()

        val parsed = LyricsRepository.parseLrc(lrc)
        assertEquals(3, parsed.size)

        assertEquals(5500L, parsed[0].timeMs)
        assertEquals("First line of lyrics", parsed[0].text)

        assertEquals(12000L, parsed[1].timeMs)
        assertEquals("Second line after pause", parsed[1].text)

        assertEquals(65123L, parsed[2].timeMs)
        assertEquals("Line after one minute mark", parsed[2].text)
    }

    @Test
    fun parseLrc_emptyOrMalformed_handlesGracefullyWithoutException() {
        val emptyParsed = LyricsRepository.parseLrc("")
        assertTrue(emptyParsed.isEmpty())

        val malformed = """
            This is plain text with no timestamp
            [invalid:timestamp] another line
        """.trimIndent()
        val malformedParsed = LyricsRepository.parseLrc(malformed)
        assertTrue(malformedParsed.isEmpty())
    }

    @Test
    fun parseLrc_multipleTimestampsPerLine_generatesChronologicalLines() {
        val lrc = """
            [00:10.00][00:30.00]Chorus line repeated
            [00:20.50]Middle verse
        """.trimIndent()

        val parsed = LyricsRepository.parseLrc(lrc)
        assertEquals(3, parsed.size)
        assertEquals(10000L, parsed[0].timeMs)
        assertEquals("Chorus line repeated", parsed[0].text)
        assertEquals(20500L, parsed[1].timeMs)
        assertEquals("Middle verse", parsed[1].text)
        assertEquals(30000L, parsed[2].timeMs)
        assertEquals("Chorus line repeated", parsed[2].text)
    }

    @Test
    fun parsePlainLyrics_returnsPlainLyricsWithoutFakeTimestamps() {
        val plain = """
            Verse 1
            Chorus line
            Outro
        """.trimIndent()

        val parsed = LyricsRepository.parsePlainLyrics(plain, durationSeconds = 60)
        assertEquals(3, parsed.size)
        assertEquals("Verse 1", parsed[0].text)
        assertEquals(-1L, parsed[0].timeMs)
        assertEquals(-1L, parsed[1].timeMs)
        assertEquals(-1L, parsed[2].timeMs)
    }

    @Test
    fun parseRadioTracks_extractsValidPlaylistPanelVideos() {
        val mockJson = JSONObject().apply {
            put("contents", JSONObject().apply {
                put("singleColumnMusicWatchNextResultsRenderer", JSONObject().apply {
                    put("tabbedRenderer", JSONObject().apply {
                        put("watchNextTabbedResultsRenderer", JSONObject().apply {
                            put("tabs", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("tabRenderer", JSONObject().apply {
                                        put("content", JSONObject().apply {
                                            put("musicQueueRenderer", JSONObject().apply {
                                                put("content", JSONObject().apply {
                                                    put("playlistPanelRenderer", JSONObject().apply {
                                                        put("contents", JSONArray().apply {
                                                            put(JSONObject().apply {
                                                                put("playlistPanelVideoRenderer", JSONObject().apply {
                                                                    put("videoId", "dQw4w9WgXcQ")
                                                                    put("title", JSONObject().apply {
                                                                        put("runs", JSONArray().put(JSONObject().put("text", "Never Gonna Give You Up")))
                                                                    })
                                                                    put("longBylineText", JSONObject().apply {
                                                                        put("runs", JSONArray().put(JSONObject().put("text", "Rick Astley")))
                                                                    })
                                                                })
                                                            })
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val parsed = com.example.service.integrations.ytmusic.YtMusicParser.parseRadioTracks(mockJson)
        assertEquals(1, parsed.size)
        assertEquals("yt_dQw4w9WgXcQ", parsed[0].id)
        assertEquals("Never Gonna Give You Up", parsed[0].title)
        assertEquals("Rick Astley", parsed[0].artist)
    }
}

