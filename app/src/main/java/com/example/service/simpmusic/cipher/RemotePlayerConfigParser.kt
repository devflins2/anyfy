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

import org.json.JSONObject

internal object RemotePlayerConfigParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val SIG_RE = Regex("^[A-Za-z0-9\$_]{1,8}\\(\\d+,\\d+,INPUT\\)\$")
    private val NCLASS_RE = Regex("^[A-Za-z0-9\$_]{1,8}\$")
    private val HASH_RE = Regex("^[a-f0-9]{8}\$")
    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("/player/([a-f0-9]{8})/"),
        Regex("player_ias\\.vflset/[^/]+/([a-f0-9]{8})/"),
        Regex("/s/player/([a-f0-9]{8})/")
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        val sigJsExpression: String? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?,
        val nJsExpression: String? = null,
        val signatureTimestamp: Int
    )

    sealed class ParseResult {
        data class Success(
            val configs: Map<String, HardcodedPlayerConfig>,
            val skippedEntries: List<String>
        ) : ParseResult()

        data class Failure(
            val reason: String
        ) : ParseResult()
    }

    fun buildNJsExpression(nClass: String): String =
        "(function(n){try{var u=new g.$nClass('https://x.googlevideo.com/videoplayback?n='+n,true);" +
            "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)"

    fun parse(jsonText: String): ParseResult {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return ParseResult.Failure("malformed JSON")
        }

        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion <= 0) return ParseResult.Failure("schemaVersion missing or not an int")
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return ParseResult.Failure("unsupported schemaVersion $schemaVersion (supported: $SUPPORTED_SCHEMA_VERSION)")
        }

        val players = root.optJSONObject("players")
            ?: return ParseResult.Failure("players missing or not an object")

        val configs = mutableMapOf<String, HardcodedPlayerConfig>()
        val skipped = mutableListOf<String>()

        val keysIterator = players.keys()
        while (keysIterator.hasNext()) {
            val hash = keysIterator.next()
            val entryObj = players.optJSONObject(hash)
            val entry = parseEntry(hash, entryObj)
            if (entry == null) {
                skipped += hash
                continue
            }
            val (config, aliases) = entry
            val keys = listOf(hash) + aliases
            val duplicate = keys.firstOrNull { it in configs }
                ?: keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicate != null) {
                return ParseResult.Failure("duplicate hash/alias '$duplicate' (entry $hash)")
            }
            configs[hash] = config
            for (alias in aliases) {
                configs[alias] = config
            }
        }

        return ParseResult.Success(configs, skipped)
    }

    fun extractPlayerHash(playerUrl: String): String? {
        for (pattern in PLAYER_HASH_PATTERNS) {
            val match = pattern.find(playerUrl)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    internal fun parseLegacySignatureTimestamp(
        jsonText: String,
        expectedPlayerHash: String
    ): Int? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val playerHash = root.optString("playerHash").takeIf { it.isNotEmpty() }
        if (playerHash != null && playerHash != expectedPlayerHash) return null
        val player = root.optJSONObject("player")

        return root.positiveInt("signatureTimestamp")
            ?: root.positiveInt("sts")
            ?: player?.positiveInt("signatureTimestamp")
            ?: player?.positiveInt("sts")
    }

    private fun JSONObject.positiveInt(name: String): Int? {
        val v = optInt(name, -1)
        return if (v > 0) v else null
    }

    private fun parseEntry(
        hash: String,
        obj: JSONObject?
    ): Pair<HardcodedPlayerConfig, List<String>>? {
        if (obj == null || !HASH_RE.matches(hash)) return null

        val sig = obj.optString("sig").takeIf { it.isNotEmpty() } ?: return null
        if (!SIG_RE.matches(sig)) return null

        val nClass = obj.optString("nClass").takeIf { it.isNotEmpty() } ?: return null
        if (!NCLASS_RE.matches(nClass)) return null

        val sts = obj.optInt("sts", -1)
        if (sts <= 0) return null

        val aliases = mutableListOf<String>()
        val aliasesArray = obj.optJSONArray("aliases")
        if (aliasesArray != null) {
            for (i in 0 until aliasesArray.length()) {
                val alias = aliasesArray.optString(i)
                if (!HASH_RE.matches(alias)) return null
                aliases.add(alias)
            }
        }

        val config = HardcodedPlayerConfig(
            sigFuncName = "_expr_sig",
            sigConstantArg = null,
            sigJsExpression = sig,
            nFuncName = "_expr_n",
            nArrayIndex = null,
            nConstantArgs = null,
            nJsExpression = buildNJsExpression(nClass),
            signatureTimestamp = sts
        )
        return config to aliases
    }
}

