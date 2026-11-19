/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.service.simpmusic

/**
 * YouTube audio and video format ITAG constants matching SimpMusic.
 */
object ITAG {
    /** Opus, adaptive audio (Low quality, ~70 kbps) */
    const val AUDIO_OPUS_LOW: Int = 250

    /** Opus, adaptive audio (Medium quality, ~160 kbps) - Preferred default */
    const val AUDIO_OPUS_MEDIUM: Int = 251

    /** Opus 256 kbps, adaptive audio - YouTube Premium only */
    const val AUDIO_OPUS_HIGH: Int = 774

    /** AAC 128 kbps, adaptive audio (Medium quality) */
    const val AUDIO_AAC_MEDIUM: Int = 140

    /** AAC 256 kbps, adaptive audio (High quality fallback) */
    const val AUDIO_AAC_HIGH: Int = 141

    /** H.264 360p, adaptive video */
    const val VIDEO_360P: Int = 134

    /** H.264 720p, adaptive video */
    const val VIDEO_720P: Int = 136

    /** H.264 1080p, adaptive video */
    const val VIDEO_1080P: Int = 137

    /** H.264 360p and AAC muxed stream */
    const val MUXED_360P: Int = 18

    /** Every adaptive audio itag the player can handle */
    val AUDIO: Set<Int> = setOf(AUDIO_OPUS_LOW, AUDIO_OPUS_MEDIUM, AUDIO_OPUS_HIGH, AUDIO_AAC_MEDIUM, AUDIO_AAC_HIGH)

    /** Every adaptive video itag */
    val VIDEO: Set<Int> = setOf(VIDEO_1080P, VIDEO_720P, VIDEO_360P)

    /**
     * Preferred audio itags in priority order.
     */
    val PREFERRED_AUDIO_ITAGS: List<Int> = listOf(
        AUDIO_OPUS_MEDIUM, // 251
        AUDIO_AAC_HIGH,   // 141
        AUDIO_AAC_MEDIUM, // 140
        AUDIO_OPUS_HIGH,  // 774
        AUDIO_OPUS_LOW    // 250
    )
}

