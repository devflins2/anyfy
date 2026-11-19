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

import android.util.Log

enum class InnerTubeLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class InnerTubeLogEvent(
    val level: InnerTubeLogLevel,
    val tag: String,
    val message: String,
    val mediaId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

fun interface InnerTubeLogger {
    fun log(event: InnerTubeLogEvent)

    companion object {
        val ANDROID_LOGCAT = InnerTubeLogger { event ->
            val detailsStr = if (event.details.isNotEmpty()) {
                event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            } else ""
            val fullMsg = event.message + detailsStr
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Log.d(event.tag, fullMsg)
                InnerTubeLogLevel.INFO -> Log.i(event.tag, fullMsg)
                InnerTubeLogLevel.WARN -> Log.w(event.tag, fullMsg)
                InnerTubeLogLevel.ERROR -> Log.e(event.tag, fullMsg)
            }
        }

        val NONE = InnerTubeLogger {}
    }
}

internal fun InnerTubeLogger.d(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerTubeLogEvent(InnerTubeLogLevel.DEBUG, tag, message, mediaId, details))

internal fun InnerTubeLogger.i(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerTubeLogEvent(InnerTubeLogLevel.INFO, tag, message, mediaId, details))

internal fun InnerTubeLogger.w(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerTubeLogEvent(InnerTubeLogLevel.WARN, tag, message, mediaId, details))

internal fun InnerTubeLogger.e(
    tag: String,
    message: String,
    mediaId: String? = null,
    details: Map<String, String> = emptyMap(),
) = log(InnerTubeLogEvent(InnerTubeLogLevel.ERROR, tag, message, mediaId, details))

