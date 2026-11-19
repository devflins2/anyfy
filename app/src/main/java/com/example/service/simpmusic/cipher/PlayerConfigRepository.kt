/*
 * Copyright (C) 2024-2026 maxrave-dev (SimpMusic)
 * Ported to Anyfy under GNU General Public License v3.0 (GPL-3.0)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.example.service.simpmusic.cipher

interface PlayerConfigRepository {
    val enabled: Boolean
    val sourceUrl: String
    val defaultSourceUrl: String

    var cachedJson: String
    var cachedAtMs: Long
    var cachedSourceUrl: String
    var cachedEtag: String

    companion object {
        /** Creates a non-persistent repository with remote config loading disabled. */
        fun disabled(): PlayerConfigRepository =
            object : PlayerConfigRepository {
                override val enabled: Boolean = false
                override val sourceUrl: String = ""
                override val defaultSourceUrl: String = ""
                override var cachedJson: String = ""
                override var cachedAtMs: Long = 0L
                override var cachedSourceUrl: String = ""
                override var cachedEtag: String = ""
            }
    }
}

