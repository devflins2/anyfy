/*
 * Copyright (C) 2024–2026 Nguyễn Đức Tuấn Minh (maxrave-dev) <ndtminh2608@gmail.com>
 * Portions adapted from SimpMusic (commit f037043d714450bac5aaac2f9dbf77e17b019303)
 * and maxrave-dev/core (commit 7d11cf437e59ba4f348ec92f7558732ee3d34c1a).
 *
 * Licensed under the GNU General Public License Version 3.0 (GPL-3.0-only).
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *     https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Modified for FlinsMusic on 2026-10-19:
 * Robust public JSON parser for YouTube Music InnerTube responses without private cookies,
 * WebView interception, or SAPISID authentication.
 */

package com.example.service.integrations.ytmusic

import org.json.JSONArray
import org.json.JSONObject

object YtMusicParser {

    /**
     * Parses search response payload containing songs, albums, artists, or community playlists.
     */
    fun parseSearchResponse(root: JSONObject): YtSearchResult {
        val tracks = mutableListOf<YtTrack>()
        val albums = mutableListOf<YtAlbum>()
        val artists = mutableListOf<YtArtist>()
        val playlists = mutableListOf<YtPlaylist>()
        var continuationToken: String? = null

        try {
            val sectionListContents = optSectionListContents(root)

            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue

                // 1. Top Result Card Shelf (musicCardShelfRenderer)
                val cardShelf = section.optJSONObject("musicCardShelfRenderer")
                if (cardShelf != null) {
                    parseCardShelf(cardShelf, tracks, albums, artists, playlists)
                }

                // 2. Music Shelf (musicShelfRenderer)
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val shelfTitle = extractRunsText(shelf.optJSONObject("title"))
                    val contents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until contents.length()) {
                        val item = contents.optJSONObject(j) ?: continue
                        parseSearchItem(item, shelfTitle, tracks, albums, artists, playlists)
                    }
                    if (continuationToken == null) {
                        continuationToken = extractContinuationToken(shelf)
                    }
                }

                // 3. Item Section Renderer (modern InnerTube search format)
                val itemSection = section.optJSONObject("itemSectionRenderer")
                if (itemSection != null) {
                    val itemContents = itemSection.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until itemContents.length()) {
                        val itemObj = itemContents.optJSONObject(j) ?: continue
                        val innerCard = itemObj.optJSONObject("musicCardShelfRenderer")
                        if (innerCard != null) {
                            parseCardShelf(innerCard, tracks, albums, artists, playlists)
                            continue
                        }
                        val innerShelf = itemObj.optJSONObject("musicShelfRenderer")
                        if (innerShelf != null) {
                            val innerTitle = extractRunsText(innerShelf.optJSONObject("title"))
                            val innerContents = innerShelf.optJSONArray("contents") ?: JSONArray()
                            for (k in 0 until innerContents.length()) {
                                val item = innerContents.optJSONObject(k) ?: continue
                                parseSearchItem(item, innerTitle, tracks, albums, artists, playlists)
                            }
                            if (continuationToken == null) {
                                continuationToken = extractContinuationToken(innerShelf)
                            }
                            continue
                        }
                        val carousel = itemObj.optJSONObject("musicCarouselShelfRenderer")
                        if (carousel != null) {
                            val carTitle = extractRunsText(carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
                            val carContents = carousel.optJSONArray("contents") ?: JSONArray()
                            for (k in 0 until carContents.length()) {
                                val item = carContents.optJSONObject(k) ?: continue
                                parseSearchItem(item, carTitle, tracks, albums, artists, playlists)
                            }
                            continue
                        }
                        val grid = itemObj.optJSONObject("gridRenderer")
                        if (grid != null) {
                            val gridItems = grid.optJSONArray("items") ?: JSONArray()
                            for (k in 0 until gridItems.length()) {
                                val item = gridItems.optJSONObject(k) ?: continue
                                parseSearchItem(item, "", tracks, albums, artists, playlists)
                            }
                            continue
                        }
                        // Direct item in itemSectionRenderer
                        parseSearchItem(itemObj, "", tracks, albums, artists, playlists)
                    }
                    if (continuationToken == null) {
                        continuationToken = extractContinuationToken(itemSection)
                    }
                }

                // 4. Music Carousel Shelf (musicCarouselShelfRenderer)
                val carousel = section.optJSONObject("musicCarouselShelfRenderer")
                if (carousel != null) {
                    val carTitle = extractRunsText(carousel.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title"))
                    val carContents = carousel.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until carContents.length()) {
                        val item = carContents.optJSONObject(j) ?: continue
                        parseSearchItem(item, carTitle, tracks, albums, artists, playlists)
                    }
                }

                // 5. Grid Renderer (gridRenderer)
                val grid = section.optJSONObject("gridRenderer")
                if (grid != null) {
                    val gridItems = grid.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until gridItems.length()) {
                        val item = gridItems.optJSONObject(j) ?: continue
                        parseSearchItem(item, "", tracks, albums, artists, playlists)
                    }
                }
            }

            // Also check sectionListRenderer top-level continuations
            if (continuationToken == null) {
                val secContinuations = root.optJSONObject("contents")
                    ?.optJSONObject("tabbedSearchResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("continuations")
                if (secContinuations != null && secContinuations.length() > 0) {
                    continuationToken = secContinuations.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                }
            }
        } catch (_: Exception) {
            // Safe fallback on malformed JSON
        }

        return YtSearchResult(
            tracks = tracks,
            albums = albums,
            artists = artists,
            playlists = playlists,
            continuationToken = continuationToken
        )
    }

    private fun parseCardShelf(
        cardShelf: JSONObject,
        tracks: MutableList<YtTrack>,
        albums: MutableList<YtAlbum>,
        artists: MutableList<YtArtist>,
        playlists: MutableList<YtPlaylist>
    ) {
        val cardTitle = extractRunsText(cardShelf.optJSONObject("title"))
        val cardSubtitle = extractRunsText(cardShelf.optJSONObject("subtitle"))
        val thumbArray = extractThumbnails(cardShelf)
        val titleEndpoint = cardShelf.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: cardShelf.optJSONObject("onTap")?.optJSONObject("watchEndpoint")
            ?: cardShelf.optJSONObject("onTap")?.optJSONObject("browseEndpoint")
        val watchEp = titleEndpoint?.optJSONObject("watchEndpoint")
            ?: cardShelf.optJSONObject("onTap")?.optJSONObject("watchEndpoint")
        val browseEp = titleEndpoint?.optJSONObject("browseEndpoint")
            ?: cardShelf.optJSONObject("onTap")?.optJSONObject("browseEndpoint")
        val browseId = browseEp?.optString("browseId").orEmpty()
        var videoId = watchEp?.optString("videoId").orEmpty()

        if (!isValidVideoId(videoId)) {
            videoId = cardShelf.optJSONArray("buttons")?.optJSONObject(0)
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("command")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId").orEmpty()
        }
        if (!isValidVideoId(videoId) && browseId.startsWith("MPED")) {
            val mpedVid = browseId.removePrefix("MPED")
            if (isValidVideoId(mpedVid)) {
                videoId = mpedVid
            }
        }

        val cardThumbnail = extractHighResArtwork(thumbArray, videoId)
        val cardBanner = extractBannerArtwork(thumbArray, videoId).ifBlank { cardThumbnail }

        if (isValidVideoId(videoId)) {
            tracks.add(
                YtTrack(
                    id = "yt_$videoId",
                    title = cardTitle.ifBlank { "Top Song" },
                    artist = cleanArtistName(cardSubtitle),
                    artwork = cardThumbnail,
                    bannerArtwork = cardBanner
                )
            )
        } else if (browseId.startsWith("UC")) {
            artists.add(
                YtArtist(
                    id = browseId,
                    name = cardTitle.ifBlank { "Top Artist" },
                    artwork = cardThumbnail,
                    bio = cardSubtitle
                )
            )
        } else if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK") || cardSubtitle.lowercase().contains("album") || cardSubtitle.lowercase().contains("single") || cardSubtitle.lowercase().contains("ep")) {
            albums.add(
                YtAlbum(
                    id = browseId.ifBlank { "alb_${System.currentTimeMillis()}" },
                    title = cardTitle.ifBlank { "Top Album" },
                    artist = cleanArtistName(cardSubtitle),
                    artwork = cardThumbnail
                )
            )
        } else if (browseId.startsWith("VL") || browseId.startsWith("PL") || cardSubtitle.lowercase().contains("playlist")) {
            playlists.add(
                YtPlaylist(
                    id = browseId.removePrefix("VL").ifBlank { "pl_${System.currentTimeMillis()}" },
                    title = cardTitle.ifBlank { "Top Playlist" },
                    author = cleanArtistName(cardSubtitle),
                    artwork = cardThumbnail
                )
            )
        }

        // Also parse any nested contents inside cardShelf
        val cardContents = cardShelf.optJSONArray("contents")
        if (cardContents != null) {
            for (k in 0 until cardContents.length()) {
                val cItem = cardContents.optJSONObject(k) ?: continue
                parseSearchItem(cItem, "", tracks, albums, artists, playlists)
            }
        }
    }

    private fun parseSearchItem(
        itemObj: JSONObject,
        shelfTitle: String,
        tracks: MutableList<YtTrack>,
        albums: MutableList<YtAlbum>,
        artists: MutableList<YtArtist>,
        playlists: MutableList<YtPlaylist>
    ) {
        val responsiveItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
        if (responsiveItem != null) {
            val category = classifyListItem(responsiveItem, shelfTitle)
            when (category) {
                ItemCategory.SONG -> parseResponsiveSong(responsiveItem)?.let { tracks.add(it) }
                ItemCategory.ALBUM -> parseResponsiveAlbum(responsiveItem)?.let { albums.add(it) }
                ItemCategory.ARTIST -> parseResponsiveArtist(responsiveItem)?.let { artists.add(it) }
                ItemCategory.PLAYLIST -> parseResponsivePlaylist(responsiveItem)?.let { playlists.add(it) }
            }
            return
        }

        val twoRowItem = itemObj.optJSONObject("musicTwoRowItemRenderer")
        if (twoRowItem != null) {
            val nav = twoRowItem.optJSONObject("navigationEndpoint")
            val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId", "").orEmpty()
            val watchId = nav?.optJSONObject("watchEndpoint")?.optString("videoId", "").orEmpty()

            if (isValidVideoId(watchId)) {
                parseTwoRowTrack(twoRowItem)?.let { tracks.add(it) }
            } else if (browseId.startsWith("UC")) {
                parseTwoRowArtist(twoRowItem)?.let { artists.add(it) }
            } else if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) {
                parseTwoRowAlbum(twoRowItem, "")?.let { albums.add(it) }
            } else if (browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD")) {
                parseTwoRowPlaylist(twoRowItem)?.let { playlists.add(it) }
            } else {
                val sub = extractRunsText(twoRowItem.optJSONObject("subtitle")).lowercase()
                if (sub.contains("artist")) {
                    parseTwoRowArtist(twoRowItem)?.let { artists.add(it) }
                } else if (sub.contains("album") || sub.contains("single") || sub.contains("ep")) {
                    parseTwoRowAlbum(twoRowItem, "")?.let { albums.add(it) }
                } else if (sub.contains("playlist")) {
                    parseTwoRowPlaylist(twoRowItem)?.let { playlists.add(it) }
                } else {
                    parseTwoRowTrack(twoRowItem)?.let { tracks.add(it) }
                }
            }
            return
        }

        val compactVideo = itemObj.optJSONObject("compactVideoRenderer") ?: itemObj.optJSONObject("videoRenderer")
        if (compactVideo != null) {
            val videoId = compactVideo.optString("videoId").takeIf { isValidVideoId(it) }
                ?: compactVideo.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()
            if (isValidVideoId(videoId)) {
                val title = extractRunsText(compactVideo.optJSONObject("title"))
                val artist = cleanArtistName(extractRunsText(compactVideo.optJSONObject("shortBylineText") ?: compactVideo.optJSONObject("longBylineText")))
                val thumbs = extractThumbnails(compactVideo)
                val artwork = extractHighResArtwork(thumbs, videoId)
                tracks.add(
                    YtTrack(
                        id = "yt_$videoId",
                        title = title.ifBlank { "Video" },
                        artist = artist,
                        artwork = artwork,
                        bannerArtwork = extractBannerArtwork(thumbs, videoId).ifBlank { artwork }
                    )
                )
            }
        }
    }

    private fun extractContinuationToken(container: JSONObject): String? {
        val continuations = container.optJSONArray("continuations")
        if (continuations != null && continuations.length() > 0) {
            val token = continuations.optJSONObject(0)
                ?.optJSONObject("nextContinuationData")
                ?.optString("continuation")
            if (!token.isNullOrBlank()) return token
        }
        val contents = container.optJSONArray("contents") ?: container.optJSONArray("items")
        if (contents != null) {
            for (i in 0 until contents.length()) {
                val item = contents.optJSONObject(i) ?: continue
                val contItem = item.optJSONObject("continuationItemRenderer")
                if (contItem != null) {
                    val token = contItem.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank()) return token
                }
            }
        }
        return null
    }

    /**
     * Checks if a string is a valid 11-character YouTube video ID.
     */
    fun isValidVideoId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val trimmed = id.trim()
        if (trimmed.length != 11) return false
        if (trimmed.startsWith("MPRE") || trimmed.startsWith("VL") || trimmed.startsWith("PL") ||
            trimmed.startsWith("UC") || trimmed.startsWith("RD")
        ) return false
        return trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))
    }

    /**
     * Cleans artist name by stripping bullet points, labels (Song, Album), duration, and view counts.
     */
    fun cleanArtistName(raw: String?): String {
        if (raw.isNullOrBlank()) return "YouTube Music"
        val parts = raw.split("•").map { it.trim() }.filter { it.isNotBlank() }
        val candidate = parts.firstOrNull { part ->
            val lower = part.lowercase()
            lower != "song" &&
            lower != "album" &&
            lower != "single" &&
            lower != "ep" &&
            lower != "video" &&
            lower != "playlist" &&
            !lower.contains("views") &&
            !lower.contains("plays") &&
            !lower.contains("subscribers") &&
            !part.matches(Regex("""^\d+:\d+$""")) &&
            !part.matches(Regex("""^\d{4}$"""))
        }
        return candidate?.ifBlank { "YouTube Music" } ?: "YouTube Music"
    }

    /**
     * Parses public home shelves (e.g. Trending, Quick picks, Hits).
     */
    fun parseHomeResponse(root: JSONObject): List<YtHomeShelf> {
        val shelves = mutableListOf<YtHomeShelf>()
        try {
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val carousel = section.optJSONObject("musicCarouselShelfRenderer")
                val regularShelf = section.optJSONObject("musicShelfRenderer")
                if (carousel == null && regularShelf == null) continue

                val headerObj = carousel?.optJSONObject("header") ?: regularShelf?.optJSONObject("header")
                val basicHeader = headerObj?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                    ?: headerObj?.optJSONObject("musicCarouselShelfHeaderRenderer")
                    ?: headerObj?.optJSONObject("musicShelfBasicHeaderRenderer")
                    ?: headerObj?.optJSONObject("musicHeaderRenderer")
                    ?: headerObj

                var title = if (basicHeader != null) {
                    extractRunsText(basicHeader.optJSONObject("title"))
                } else if (regularShelf != null) {
                    extractRunsText(regularShelf.optJSONObject("title"))
                } else ""

                if (title.isBlank() && headerObj != null) {
                    title = extractRunsText(headerObj.optJSONObject("title"))
                }

                val subtitle = if (basicHeader != null) {
                    extractRunsText(basicHeader.optJSONObject("strapline"))
                } else ""

                val contents = carousel?.optJSONArray("contents")
                    ?: regularShelf?.optJSONArray("contents")
                    ?: JSONArray()

                val items = mutableListOf<YtTrack>()
                val albums = mutableListOf<YtAlbum>()
                val artists = mutableListOf<YtArtist>()
                val playlists = mutableListOf<YtPlaylist>()

                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j) ?: continue
                    val responsiveItem = item.optJSONObject("musicResponsiveListItemRenderer")
                    val twoRowItem = item.optJSONObject("musicTwoRowItemRenderer")

                    if (responsiveItem != null) {
                        val category = classifyListItem(responsiveItem, title)
                        when (category) {
                            ItemCategory.SONG -> parseResponsiveSong(responsiveItem)?.let { items.add(it) }
                            ItemCategory.ALBUM -> parseResponsiveAlbum(responsiveItem)?.let { albums.add(it) }
                            ItemCategory.ARTIST -> parseResponsiveArtist(responsiveItem)?.let { artists.add(it) }
                            ItemCategory.PLAYLIST -> parseResponsivePlaylist(responsiveItem)?.let { playlists.add(it) }
                        }
                    } else if (twoRowItem != null) {
                        val endpoint = twoRowItem.optJSONObject("navigationEndpoint")
                        val watchEndpoint = endpoint?.optJSONObject("watchEndpoint")
                            ?: twoRowItem.optJSONObject("thumbnailOverlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                        val browseId = endpoint?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: ""

                        if (watchEndpoint != null || isValidVideoId(twoRowItem.optString("videoId"))) {
                            parseTwoRowTrack(twoRowItem)?.let { items.add(it) }
                        } else if (browseId.startsWith("MPREb_") || browseId.contains("album")) {
                            parseTwoRowAlbum(twoRowItem, "")?.let { albums.add(it) }
                        } else if (browseId.startsWith("UC")) {
                            parseTwoRowArtist(twoRowItem)?.let { artists.add(it) }
                        } else if (browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") || browseId.startsWith("MPED")) {
                            parseTwoRowPlaylist(twoRowItem)?.let { playlists.add(it) }
                        } else {
                            val subtitleLower = extractRunsText(twoRowItem.optJSONObject("subtitle")).lowercase()
                            when {
                                subtitleLower.contains("artist") -> parseTwoRowArtist(twoRowItem)?.let { artists.add(it) }
                                subtitleLower.contains("album") || subtitleLower.contains("single") || subtitleLower.contains("ep") -> parseTwoRowAlbum(twoRowItem, "")?.let { albums.add(it) }
                                subtitleLower.contains("playlist") -> parseTwoRowPlaylist(twoRowItem)?.let { playlists.add(it) }
                                else -> parseTwoRowTrack(twoRowItem)?.let { items.add(it) }
                            }
                        }
                    }
                }

                val displayTitle = when {
                    title.isNotBlank() -> title
                    subtitle.isNotBlank() -> subtitle
                    else -> "Recommended"
                }

                if (items.isNotEmpty() || albums.isNotEmpty() || artists.isNotEmpty() || playlists.isNotEmpty()) {
                    shelves.add(
                        YtHomeShelf(
                            title = displayTitle,
                            subtitle = subtitle,
                            items = items,
                            albums = albums,
                            artists = artists,
                            playlists = playlists
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return shelves
    }

    /**
     * Parses artist page containing bio, listener count, top tracks, discography albums/singles, videos, and related artists.
     */
    fun parseArtistResponse(root: JSONObject, artistId: String): YtArtist {
        var artistName = ""
        var bio = ""
        var listeners = ""
        var artwork = ""
        var bannerArtwork = ""
        val topTracks = mutableListOf<YtTrack>()
        val albums = mutableListOf<YtAlbum>()
        val singles = mutableListOf<YtAlbum>()
        val videos = mutableListOf<YtTrack>()
        val featuredOn = mutableListOf<YtPlaylist>()
        val similarArtists = mutableListOf<YtArtist>()
        var albumsBrowseEndpoint: Pair<String, String?>? = null
        var singlesBrowseEndpoint: Pair<String, String?>? = null
        var featuredBrowseEndpoint: Pair<String, String?>? = null
        var songsBrowseEndpoint: Pair<String, String?>? = null

        try {
            // Header parsing
            val header = root.optJSONObject("header")
            val immersiveHeader = header?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: header?.optJSONObject("musicVisualHeaderRenderer")
                ?: header?.optJSONObject("musicHeaderRenderer")

            if (immersiveHeader != null) {
                artistName = extractRunsText(immersiveHeader.optJSONObject("title"))
                bio = extractRunsText(immersiveHeader.optJSONObject("description"))
                listeners = extractRunsText(
                    immersiveHeader.optJSONObject("subscriptionButton")
                        ?.optJSONObject("subscribeButtonRenderer")
                        ?.optJSONObject("subscriberCountText")
                )
                val thumbArray = extractThumbnails(immersiveHeader) ?: extractThumbnails(header)
                artwork = extractHighResArtwork(thumbArray)
                bannerArtwork = extractBannerArtwork(thumbArray).ifBlank { artwork }
            }

            // Section parsing (Top songs, Albums, Singles, Videos, Featured On, Similar Artists)
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue

                // Check for Top songs shelf
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val shelfHeader = shelf.optJSONObject("header")
                    val shelfTitle = extractRunsText(
                        shelf.optJSONObject("title")
                            ?: shelfHeader?.optJSONObject("musicShelfBasicHeaderRenderer")?.optJSONObject("title")
                            ?: shelfHeader?.optJSONObject("musicShelfHeaderRenderer")?.optJSONObject("title")
                    ).lowercase()
                    if (shelfTitle.contains("song") || shelfTitle.contains("track") || topTracks.isEmpty()) {
                        val moreEp = extractBrowseEndpoint(
                            shelfHeader?.optJSONObject("musicShelfBasicHeaderRenderer")
                                ?: shelfHeader?.optJSONObject("musicShelfHeaderRenderer")
                                ?: shelfHeader
                        ) ?: extractBrowseEndpoint(shelf.optJSONObject("bottomEndpoint"))
                        if (songsBrowseEndpoint == null) songsBrowseEndpoint = moreEp

                        val contents = shelf.optJSONArray("contents") ?: JSONArray()
                        for (j in 0 until contents.length()) {
                            val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            parseResponsiveSong(item)?.let { topTracks.add(it) }
                        }
                    }
                }

                // Check for Carousels (Albums, Singles, Videos, Featured on, Fans might also like)
                val carousel = section.optJSONObject("musicCarouselShelfRenderer")
                if (carousel != null) {
                    val headerObj = carousel.optJSONObject("header")
                    val carouselTitle = extractRunsText(
                        headerObj?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")?.optJSONObject("title")
                            ?: headerObj?.optJSONObject("musicCarouselShelfHeaderRenderer")?.optJSONObject("title")
                            ?: headerObj?.optJSONObject("musicShelfBasicHeaderRenderer")?.optJSONObject("title")
                            ?: headerObj?.optJSONObject("musicHeaderRenderer")?.optJSONObject("title")
                            ?: headerObj?.optJSONObject("title")
                    ).lowercase()
                    val contents = carousel.optJSONArray("contents") ?: JSONArray()

                    val moreBtnEndpoint = extractBrowseEndpoint(
                        headerObj?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                            ?: headerObj?.optJSONObject("musicCarouselShelfHeaderRenderer")
                            ?: headerObj?.optJSONObject("musicShelfBasicHeaderRenderer")
                            ?: headerObj?.optJSONObject("musicHeaderRenderer")
                            ?: headerObj
                    )

                    when {
                        carouselTitle.contains("single") || carouselTitle.contains("ep") -> {
                            if (singlesBrowseEndpoint == null) singlesBrowseEndpoint = moreBtnEndpoint
                            for (j in 0 until contents.length()) {
                                val twoRow = contents.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                parseTwoRowAlbum(twoRow, artistName, isSingle = true)?.let { singles.add(it) }
                            }
                        }
                        carouselTitle.contains("album") || carouselTitle.contains("discography") || carouselTitle.contains("release") -> {
                            if (albumsBrowseEndpoint == null) albumsBrowseEndpoint = moreBtnEndpoint
                            for (j in 0 until contents.length()) {
                                val twoRow = contents.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                parseTwoRowAlbum(twoRow, artistName)?.let { albums.add(it) }
                            }
                        }
                        carouselTitle.contains("video") -> {
                            for (j in 0 until contents.length()) {
                                val twoRow = contents.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                parseTwoRowTrack(twoRow)?.let { videos.add(it) }
                            }
                        }
                        carouselTitle.contains("featured") || carouselTitle.contains("appears on") -> {
                            if (featuredBrowseEndpoint == null) featuredBrowseEndpoint = moreBtnEndpoint
                            for (j in 0 until contents.length()) {
                                val twoRow = contents.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                parseTwoRowPlaylist(twoRow)?.let { featuredOn.add(it) }
                            }
                        }
                        carouselTitle.contains("similar") || carouselTitle.contains("fans") || carouselTitle.contains("like") || carouselTitle.contains("artist") -> {
                            for (j in 0 until contents.length()) {
                                val twoRow = contents.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                parseTwoRowArtist(twoRow)?.let { similarArtists.add(it) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return YtArtist(
            id = artistId,
            name = artistName.ifBlank { "Unknown Artist" },
            artwork = artwork,
            bannerArtwork = bannerArtwork.ifBlank { artwork },
            bio = bio,
            monthlyListeners = listeners,
            topTracks = topTracks,
            albums = albums,
            singles = singles,
            videos = videos,
            featuredOn = featuredOn,
            similarArtists = similarArtists,
            songsBrowseEndpoint = songsBrowseEndpoint,
            albumsBrowseEndpoint = albumsBrowseEndpoint,
            singlesBrowseEndpoint = singlesBrowseEndpoint,
            featuredBrowseEndpoint = featuredBrowseEndpoint
        )
    }

    /**
     * Parses album details and tracklist.
     */
    fun parseAlbumResponse(
        root: JSONObject,
        albumId: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackArtwork: String = ""
    ): YtAlbum {
        var title = ""
        var artist = ""
        var artistId = ""
        var artwork = ""
        var bannerArtwork = ""
        var year = ""
        var description = ""
        var type = ""
        val tracks = mutableListOf<YtTrack>()

        try {
            // 1. Locate Album Header from all possible InnerTube structures
            val header = root.optJSONObject("header")
            var detailHeader = header?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: header?.optJSONObject("musicDetailHeaderRenderer")
                ?: header?.optJSONObject("musicVisualHeaderRenderer")
                ?: header?.optJSONObject("musicHeaderRenderer")
                ?: header?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")

            // Check contents if header was not at root level
            if (detailHeader == null) {
                val tabs = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
                    ?: root.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")?.optJSONArray("tabs")
                val secContents = tabs?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                if (secContents != null && secContents.length() > 0) {
                    val firstSec = secContents.optJSONObject(0)
                    detailHeader = firstSec?.optJSONObject("musicResponsiveHeaderRenderer")
                        ?: firstSec?.optJSONObject("musicDetailHeaderRenderer")
                        ?: firstSec?.optJSONObject("musicVisualHeaderRenderer")
                }
            }

            // 2. Extract Title & Description
            if (detailHeader != null) {
                title = extractRunsText(detailHeader.optJSONObject("title"))
                if (title.isBlank()) {
                    title = detailHeader.optString("title", "")
                }
                description = extractRunsText(detailHeader.optJSONObject("description"))
                if (description.isBlank()) {
                    description = detailHeader.optString("description", "")
                }
            }
            if (title.isBlank()) {
                title = fallbackTitle
            }

            // 3. Extract Artist & ArtistId
            // A. Check straplineTextOne / strapline runs (standard modern InnerTube artist location)
            val straplineObj = detailHeader?.optJSONObject("straplineTextOne")
                ?: detailHeader?.optJSONObject("strapline")
                ?: detailHeader?.optJSONObject("straplineThumbnail")
            val straplineRuns = straplineObj?.optJSONArray("runs")
            if (straplineRuns != null && straplineRuns.length() > 0) {
                for (k in 0 until straplineRuns.length()) {
                    val run = straplineRuns.optJSONObject(k) ?: continue
                    val text = run.optString("text", "").trim()
                    if (text.isBlank() || text == "•" || text == "&" || text == "/" || text == ",") continue
                    val bId = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                    if (!text.equals("Album", ignoreCase = true) && !text.equals("EP", ignoreCase = true) && !text.equals("Single", ignoreCase = true)) {
                        artist = cleanArtistName(text)
                        if (bId.isNotBlank()) artistId = bId
                        break
                    }
                }
            }

            // B. Check bylineText runs
            if (artist.isBlank() || artist == "Various Artists") {
                val bylineRuns = detailHeader?.optJSONObject("bylineText")?.optJSONArray("runs")
                if (bylineRuns != null && bylineRuns.length() > 0) {
                    for (k in 0 until bylineRuns.length()) {
                        val run = bylineRuns.optJSONObject(k) ?: continue
                        val text = run.optString("text", "").trim()
                        if (text.isBlank() || text == "•" || text == "&" || text == "/" || text == ",") continue
                        val bId = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                        if (!text.equals("Album", ignoreCase = true) && !text.equals("EP", ignoreCase = true) && !text.equals("Single", ignoreCase = true) && !text.matches(Regex("""^\d{4}$"""))) {
                            artist = cleanArtistName(text)
                            if (bId.isNotBlank()) artistId = bId
                            break
                        }
                    }
                }
            }

            // C. Check subtitle runs (matching UC/FE browse IDs or text runs, also extracting year)
            val subtitleRuns = detailHeader?.optJSONObject("subtitle")?.optJSONArray("runs")
                ?: detailHeader?.optJSONObject("secondSubtitle")?.optJSONArray("runs")
                ?: JSONArray()

            for (k in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(k) ?: continue
                val text = run.optString("text", "").trim()
                if (text.isBlank() || text == "•" || text == "·" || text == "&" || text == "/" || text == ",") continue
                val bId = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
                val pageType = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType", "") ?: ""

                val isArtistBrowse = bId.startsWith("UC") || bId.startsWith("FEmusic_library_artist_") || bId.startsWith("FEmusic_artist_") || pageType == "MUSIC_PAGE_TYPE_ARTIST"

                if (isArtistBrowse) {
                    if (artist.isBlank() || artist == "Various Artists") {
                        artist = cleanArtistName(text)
                        artistId = bId
                    }
                } else if (text.matches(Regex("""\b(19\d{2}|20\d{2})\b"""))) {
                    if (year.isBlank()) year = text
                } else if (text.equals("Single", ignoreCase = true)) {
                    type = "Single"
                } else if (text.equals("EP", ignoreCase = true)) {
                    type = "EP"
                } else if (text.equals("Album", ignoreCase = true)) {
                    if (type.isBlank()) type = "Album"
                } else if ((artist.isBlank() || artist == "Various Artists") &&
                    !text.contains("song", ignoreCase = true) &&
                    !text.contains("track", ignoreCase = true) &&
                    !text.contains("minute", ignoreCase = true) &&
                    !text.contains("min", ignoreCase = true) &&
                    !text.contains("play", ignoreCase = true) &&
                    !text.contains("view", ignoreCase = true) &&
                    !text.equals("Various Artists", ignoreCase = true)) {
                    val candidate = cleanArtistName(text)
                    if (candidate != "YouTube Music" && candidate.isNotBlank()) {
                        artist = candidate
                    }
                }
            }

            // D. Fallback Year check via regex
            if (year.isBlank() && detailHeader != null) {
                val subText = extractRunsText(detailHeader.optJSONObject("subtitle")) + " " +
                              extractRunsText(detailHeader.optJSONObject("secondSubtitle")) + " " +
                              description
                val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(subText)
                if (yearMatch != null) {
                    year = yearMatch.value
                }
            }

            // 4. Extract Artwork & Banner
            val thumbArray = extractThumbnails(detailHeader) ?: extractThumbnails(header) ?: extractThumbnails(root)
            artwork = extractHighResArtwork(thumbArray)
            bannerArtwork = extractBannerArtwork(thumbArray).ifBlank { artwork }
            if (artwork.isBlank() && fallbackArtwork.isNotBlank()) {
                artwork = fallbackArtwork
                bannerArtwork = fallbackArtwork
            }

            // 5. Tracklist Extraction
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicPlaylistShelfRenderer") ?: continue
                val contents = shelf.optJSONArray("contents") ?: JSONArray()

                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveSong(
                        item,
                        fallbackAlbum = title.ifBlank { fallbackTitle },
                        fallbackArtwork = artwork.ifBlank { fallbackArtwork }
                    )?.let { parsedTrack ->
                        val refinedTrack = parsedTrack.copy(
                            album = if (parsedTrack.album.isNotBlank() && parsedTrack.album != "Single") parsedTrack.album else title.ifBlank { fallbackTitle },
                            albumId = if (parsedTrack.albumId.isNotBlank() && !parsedTrack.albumId.startsWith("alb_")) parsedTrack.albumId else albumId
                        )
                        tracks.add(refinedTrack)
                    }
                }
            }

            // Secondary contents / tabs check if tracks are empty
            if (tracks.isEmpty()) {
                val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                val secContents = twoCol?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
                if (secContents != null) {
                    for (i in 0 until secContents.length()) {
                        val section = secContents.optJSONObject(i) ?: continue
                        val shelf = section.optJSONObject("musicShelfRenderer")
                            ?: section.optJSONObject("musicPlaylistShelfRenderer") ?: continue
                        val contents = shelf.optJSONArray("contents") ?: JSONArray()
                        for (j in 0 until contents.length()) {
                            val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            parseResponsiveSong(
                                item,
                                fallbackAlbum = title.ifBlank { fallbackTitle },
                                fallbackArtwork = artwork.ifBlank { fallbackArtwork }
                            )?.let { tracks.add(it) }
                        }
                    }
                }
            }

            // 6. Refine Artist from Tracklist if still blank or generic
            if (artist.isBlank() || artist == "Various Artists") {
                val nonGenericArtists = tracks.map { it.artist }.filter { it.isNotBlank() && it != "Various Artists" && it != "YouTube Music" }
                if (nonGenericArtists.isNotEmpty()) {
                    val primary = nonGenericArtists.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    if (!primary.isNullOrBlank()) {
                        artist = primary
                    }
                }
            }

            // 7. Refine Artist from fallbackArtist
            if ((artist.isBlank() || artist == "Various Artists") && fallbackArtist.isNotBlank() && fallbackArtist != "Various Artists") {
                artist = fallbackArtist
            }

            // 8. Refine Type
            if (type.isBlank()) {
                type = when {
                    title.contains("Single", ignoreCase = true) || tracks.size == 1 -> "Single"
                    title.contains("EP", ignoreCase = true) -> "EP"
                    else -> "Album"
                }
            }
        } catch (_: Exception) {}

        return YtAlbum(
            id = albumId,
            title = when {
                title.isNotBlank() && title != "Unknown Album" -> title
                fallbackTitle.isNotBlank() && fallbackTitle != "Unknown Album" -> fallbackTitle
                else -> title.ifBlank { "Unknown Album" }
            },
            artist = when {
                artist.isNotBlank() && artist != "Various Artists" -> artist
                fallbackArtist.isNotBlank() && fallbackArtist != "Various Artists" -> fallbackArtist
                else -> artist.ifBlank { "Various Artists" }
            },
            artistId = artistId,
            artwork = artwork.ifBlank { fallbackArtwork },
            bannerArtwork = bannerArtwork.ifBlank { artwork.ifBlank { fallbackArtwork } },
            year = year,
            tracks = tracks,
            description = description,
            trackCount = tracks.size,
            durationText = "${tracks.sumOf { it.durationSeconds } / 60} min",
            type = if (type.isNotBlank()) type else if (title.contains("EP", ignoreCase = true)) "EP" else if (tracks.size == 1) "Single" else "Album"
        )
    }

    /**
     * Parses the artist discography browse response (or its continuation)
     * returning all discovered albums/singles and any pagination continuation token.
     */
    fun parseDiscographyPage(root: JSONObject, fallbackArtist: String, isSingle: Boolean = false): Pair<List<YtAlbum>, String?> {
        val albums = mutableListOf<YtAlbum>()
        var continuationToken: String? = null

        try {
            // Check for continuations in onResponseReceivedActions (continuation response format)
            val actions = root.optJSONArray("onResponseReceivedActions")
            if (actions != null && actions.length() > 0) {
                for (a in 0 until actions.length()) {
                    val action = actions.optJSONObject(a) ?: continue
                    val appendCont = action.optJSONObject("appendContinuationItemsAction")
                    val items = appendCont?.optJSONArray("continuationItems") ?: continue
                    for (i in 0 until items.length()) {
                        val itemObj = items.optJSONObject(i) ?: continue
                        val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowAlbum(twoRow, fallbackArtist, isSingle)?.let { albums.add(it) }
                            continue
                        }
                        val listItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                        if (listItem != null) {
                            parseResponsiveAlbum(listItem)?.let { albums.add(it) }
                            continue
                        }
                        val contRenderer = itemObj.optJSONObject("continuationItemRenderer")
                        if (contRenderer != null) {
                            val token = contRenderer.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) {
                                continuationToken = token
                            }
                        }
                    }
                }
            }

            // Check continuationContents (legacy continuation response format)
            val continuationContents = root.optJSONObject("continuationContents")
            if (continuationContents != null) {
                val gridCont = continuationContents.optJSONObject("gridContinuation")
                if (gridCont != null) {
                    val items = gridCont.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val itemObj = items.optJSONObject(i) ?: continue
                        val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowAlbum(twoRow, fallbackArtist, isSingle)?.let { albums.add(it) }
                            continue
                        }
                        val contRenderer = itemObj.optJSONObject("continuationItemRenderer")
                        if (contRenderer != null) {
                            val token = contRenderer.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) {
                                continuationToken = token
                            }
                        }
                    }
                    if (continuationToken.isNullOrBlank()) {
                        continuationToken = gridCont.optJSONArray("continuations")
                            ?.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                    }
                }

                val shelfCont = continuationContents.optJSONObject("musicShelfContinuation")
                if (shelfCont != null) {
                    val contents = shelfCont.optJSONArray("contents") ?: JSONArray()
                    for (i in 0 until contents.length()) {
                        val itemObj = contents.optJSONObject(i) ?: continue
                        val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowAlbum(twoRow, fallbackArtist, isSingle)?.let { albums.add(it) }
                        }
                        val listItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                        if (listItem != null) {
                            parseResponsiveAlbum(listItem)?.let { albums.add(it) }
                        }
                    }
                    if (continuationToken.isNullOrBlank()) {
                        continuationToken = shelfCont.optJSONArray("continuations")
                            ?.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                    }
                }
            }

            // Check initial browse structure: gridRenderer or musicShelfRenderer or sectionList
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val grid = section.optJSONObject("gridRenderer")
                if (grid != null) {
                    val items = grid.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) {
                        val itemObj = items.optJSONObject(j) ?: continue
                        val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowAlbum(twoRow, fallbackArtist, isSingle)?.let { albums.add(it) }
                            continue
                        }
                        val contRenderer = itemObj.optJSONObject("continuationItemRenderer")
                        if (contRenderer != null) {
                            val token = contRenderer.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) {
                                continuationToken = token
                            }
                        }
                    }
                    if (continuationToken.isNullOrBlank()) {
                        continuationToken = grid.optJSONArray("continuations")
                            ?.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                    }
                }

                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val contents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until contents.length()) {
                        val itemObj = contents.optJSONObject(j) ?: continue
                        val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                        if (twoRow != null) {
                            parseTwoRowAlbum(twoRow, fallbackArtist, isSingle)?.let { albums.add(it) }
                            continue
                        }
                        val listItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                        if (listItem != null) {
                            parseResponsiveAlbum(listItem)?.let { albums.add(it) }
                            continue
                        }
                        val contRenderer = itemObj.optJSONObject("continuationItemRenderer")
                        if (contRenderer != null) {
                            val token = contRenderer.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) {
                                continuationToken = token
                            }
                        }
                    }
                    if (continuationToken.isNullOrBlank()) {
                        continuationToken = shelf.optJSONArray("continuations")
                            ?.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(albums, continuationToken)
    }

    fun extractAlbumContinuationToken(root: JSONObject): String? {
        val actions = root.optJSONArray("onResponseReceivedActions")
        if (actions != null) {
            for (a in 0 until actions.length()) {
                val action = actions.optJSONObject(a) ?: continue
                val items = action.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems") ?: continue
                for (i in 0 until items.length()) {
                    val contRenderer = items.optJSONObject(i)?.optJSONObject("continuationItemRenderer") ?: continue
                    val token = contRenderer.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank()) return token
                }
            }
        }
        val gridCont = root.optJSONObject("continuationContents")?.optJSONObject("gridContinuation")
        if (gridCont != null) {
            val token = gridCont.optJSONArray("continuations")?.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
            if (!token.isNullOrBlank()) return token
        }
        val shelfCont = root.optJSONObject("continuationContents")?.optJSONObject("musicShelfContinuation")
            ?: root.optJSONObject("continuationContents")?.optJSONObject("musicPlaylistShelfContinuation")
        if (shelfCont != null) {
            val token = shelfCont.optJSONArray("continuations")?.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
            if (!token.isNullOrBlank()) return token
        }

        // Also check initial browse response sectionList shelves for continuations
        val sectionList = optSectionListContents(root)
        for (i in 0 until sectionList.length()) {
            val section = sectionList.optJSONObject(i) ?: continue
            val shelf = section.optJSONObject("musicPlaylistShelfRenderer")
                ?: section.optJSONObject("musicShelfRenderer") ?: continue
            val continuations = shelf.optJSONArray("continuations")
            if (continuations != null && continuations.length() > 0) {
                val token = continuations.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
                if (!token.isNullOrBlank()) return token
            }
        }

        return null
    }

    /**
     * Parses public playlist tracks and pagination continuation token.
     */
    fun parsePlaylistResponse(root: JSONObject, playlistId: String): YtPlaylist {
        var title = ""
        var author = "YouTube Music"
        var description = ""
        var artwork = ""
        var trackCount = 0
        var continuationToken: String? = null
        val tracks = mutableListOf<YtTrack>()

        try {
            val header = root.optJSONObject("header")
            val detailHeader = header?.optJSONObject("musicDetailHeaderRenderer")
                ?: header?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                    ?.optJSONObject("header")
                    ?.optJSONObject("musicDetailHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                    ?.optJSONObject("header")
                    ?.optJSONObject("musicResponsiveHeaderRenderer")

            if (detailHeader != null) {
                title = extractRunsText(detailHeader.optJSONObject("title"))
                description = extractRunsText(detailHeader.optJSONObject("description"))

                val subtitleRuns = detailHeader.optJSONObject("subtitle")?.optJSONArray("runs") ?: JSONArray()
                for (k in 0 until subtitleRuns.length()) {
                    val text = subtitleRuns.optJSONObject(k)?.optString("text", "") ?: continue
                    if (k == 0 && text.isNotBlank()) author = text
                    if (text.contains("song") || text.contains("track")) {
                        trackCount = text.filter { it.isDigit() }.toIntOrNull() ?: 0
                    }
                }

                val thumbArray = extractThumbnails(detailHeader) ?: extractThumbnails(header)
                artwork = extractHighResArtwork(thumbArray)
            }

            // Playlist tracks
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicPlaylistShelfRenderer")
                    ?: section.optJSONObject("musicShelfRenderer") ?: continue

                val contents = shelf.optJSONArray("contents") ?: JSONArray()
                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveSong(item)?.let { tracks.add(it) }
                }

                // Continuation token for next page
                val continuations = shelf.optJSONArray("continuations")
                if (continuations != null && continuations.length() > 0) {
                    continuationToken = continuations.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                }
            }
        } catch (_: Exception) {}

        return YtPlaylist(
            id = playlistId,
            title = title.ifBlank { "YouTube Playlist" },
            author = author,
            description = description,
            artwork = artwork,
            trackCount = if (trackCount > 0) trackCount else tracks.size,
            tracks = tracks,
            continuationToken = continuationToken
        )
    }

    /**
     * Parses continuation response for public playlist or search pagination.
     */
    fun parseContinuationTracks(root: JSONObject): Pair<List<YtTrack>, String?> {
        val tracks = mutableListOf<YtTrack>()
        var nextContinuation: String? = null

        try {
            // 1. Check legacy continuationContents
            val contContents = root.optJSONObject("continuationContents")
            val playlistShelf = contContents?.optJSONObject("musicPlaylistShelfContinuation")
                ?: contContents?.optJSONObject("musicShelfContinuation")

            if (playlistShelf != null) {
                val contents = playlistShelf.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until contents.length()) {
                    val item = contents.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveSong(item)?.let { tracks.add(it) }
                }

                val continuations = playlistShelf.optJSONArray("continuations")
                if (continuations != null && continuations.length() > 0) {
                    nextContinuation = continuations.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                }
            }

            // 2. Check modern InnerTube onResponseReceivedActions
            val actions = root.optJSONArray("onResponseReceivedActions")
            if (actions != null && actions.length() > 0) {
                val appendAction = actions.optJSONObject(0)?.optJSONObject("appendContinuationItemsAction")
                val contItems = appendAction?.optJSONArray("continuationItems") ?: JSONArray()
                for (i in 0 until contItems.length()) {
                    val itemObj = contItems.optJSONObject(i) ?: continue
                    val listItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                    if (listItem != null) {
                        parseResponsiveSong(listItem)?.let { tracks.add(it) }
                    }
                    val contItemRenderer = itemObj.optJSONObject("continuationItemRenderer")
                    if (contItemRenderer != null) {
                        nextContinuation = contItemRenderer.optJSONObject("continuationEndpoint")
                            ?.optJSONObject("continuationCommand")
                            ?.optString("token")
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(tracks, nextContinuation)
    }

    // ==========================================
    // Internal Helper Parsers
    // ==========================================

    private enum class ItemCategory { SONG, ALBUM, ARTIST, PLAYLIST }

    private fun classifyListItem(item: JSONObject, shelfTitle: String): ItemCategory {
        val lowerShelf = shelfTitle.lowercase()
        if (lowerShelf.contains("artist")) return ItemCategory.ARTIST
        if (lowerShelf.contains("album")) return ItemCategory.ALBUM
        if (lowerShelf.contains("playlist")) return ItemCategory.PLAYLIST

        // Inspect navigationEndpoint
        val flexCols = item.optJSONArray("flexColumns") ?: JSONArray()
        if (flexCols.length() > 0) {
            val endpoint = flexCols.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?: item.optJSONObject("navigationEndpoint")

            if (endpoint?.optJSONObject("watchEndpoint") != null) return ItemCategory.SONG
            val browseEp = endpoint?.optJSONObject("browseEndpoint")
            val browseId = browseEp?.optString("browseId", "") ?: ""
            val pageType = browseEp?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType", "") ?: ""

            if (browseId.startsWith("UC") || pageType == "MUSIC_PAGE_TYPE_ARTIST") return ItemCategory.ARTIST
            if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK") || pageType == "MUSIC_PAGE_TYPE_ALBUM") return ItemCategory.ALBUM
            if (browseId.startsWith("VL") || browseId.startsWith("PL") || pageType == "MUSIC_PAGE_TYPE_PLAYLIST") return ItemCategory.PLAYLIST

            if (flexCols.length() > 1) {
                val col1Text = extractRunsText(flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")).lowercase()
                if (col1Text.startsWith("song") || col1Text.startsWith("video") || col1Text.contains("• song") || col1Text.contains("• video")) return ItemCategory.SONG
                if (col1Text.startsWith("artist •") || col1Text == "artist" || col1Text.contains("• artist •") || col1Text.endsWith("• artist")) return ItemCategory.ARTIST
                if (col1Text.startsWith("album") || col1Text.startsWith("ep") || col1Text.startsWith("single") || col1Text.contains("• album") || col1Text.contains("• single") || col1Text.contains("• ep")) return ItemCategory.ALBUM
                if (col1Text.startsWith("playlist") || col1Text.contains("• playlist")) return ItemCategory.PLAYLIST
            }
        }

        return ItemCategory.SONG
    }

    private fun parseResponsiveSong(
        item: JSONObject,
        fallbackAlbum: String = "",
        fallbackArtwork: String = ""
    ): YtTrack? {
        var videoId = item.optString("videoId").takeIf { isValidVideoId(it) }
        if (videoId == null) {
            videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
        }
        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("doubleTapCommand")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }
        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        val flexCols = item.optJSONArray("flexColumns") ?: JSONArray()
        if (flexCols.length() == 0) return null

        // Title & videoId from column 0
        val col0Runs = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val title = col0Runs?.optJSONObject(0)?.optString("text") ?: return null
        if (!isValidVideoId(videoId)) {
            videoId = col0Runs?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }
        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("onTap")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        // Artist & Album from column 1
        var artistName = "YouTube Music"
        var artistId = ""
        var albumName = fallbackAlbum
        var albumId = ""
        var durationSec = 180

        val artistNames = mutableListOf<String>()
        if (flexCols.length() > 1) {
            val col1Runs = flexCols.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: JSONArray()

            for (k in 0 until col1Runs.length()) {
                val run = col1Runs.optJSONObject(k) ?: continue
                val text = run.optString("text", "").trim()
                if (text == "•" || text.isBlank() || text == "&" || text == "/" || text == ",") continue

                val nav = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = nav?.optString("browseId", "") ?: ""

                val pageType = nav?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType", "") ?: ""

                val isArtist = browseId.startsWith("UC") || browseId.startsWith("FEmusic_library_artist_") || browseId.startsWith("FEmusic_artist_") || pageType == "MUSIC_PAGE_TYPE_ARTIST"
                val isAlbum = browseId.startsWith("MPRE") || browseId.startsWith("OLAK") || browseId.startsWith("FEmusic_library_album_") || browseId.startsWith("FEmusic_album_") || pageType == "MUSIC_PAGE_TYPE_ALBUM"

                if (isArtist) {
                    val clean = cleanArtistName(text)
                    if (clean != "YouTube Music") {
                        artistNames.add(clean)
                    } else if (text.isNotBlank()) {
                        artistNames.add(text)
                    }
                    if (artistId.isBlank() && browseId.isNotBlank()) artistId = browseId
                } else if (isAlbum) {
                    albumName = text
                    albumId = browseId
                } else {
                    val lower = text.lowercase()
                    if (lower != "song" && lower != "video" && lower != "single" && lower != "album" && lower != "ep" &&
                        !lower.contains("views") && !lower.contains("plays")
                    ) {
                        if (text.matches(Regex("""^\d+:\d+$""")) || text.matches(Regex("""^\d+:\d+:\d+$"""))) {
                            durationSec = parseDurationSeconds(text)
                        } else if (artistNames.isEmpty()) {
                            val clean = cleanArtistName(text)
                            if (clean != "YouTube Music") artistNames.add(clean) else artistNames.add(text)
                        } else if (albumName.isBlank()) {
                            albumName = text
                        }
                    }
                }
            }
        }
        if (artistNames.isNotEmpty()) {
            artistName = artistNames.distinct().joinToString(", ")
        }
        artistName = cleanArtistName(artistName)

        // Duration from fixedColumns if available
        val fixedCols = item.optJSONArray("fixedColumns")
        if (fixedCols != null && fixedCols.length() > 0) {
            val durText = extractRunsText(
                fixedCols.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
            )
            val parsedDur = parseDurationSeconds(durText)
            if (parsedDur > 0) durationSec = parsedDur
        }

        // Artwork
        val thumbnails = extractThumbnails(item)
        val artwork = extractHighResArtwork(thumbnails, videoId).ifBlank { fallbackArtwork }
        val bannerArtwork = extractBannerArtwork(thumbnails, videoId).ifBlank { artwork }

        val trackId = if (isValidVideoId(videoId)) {
            "yt_${videoId!!.trim()}"
        } else {
            "yt_meta_${Math.abs(title.hashCode())}_${Math.abs(artistName.hashCode())}"
        }

        return YtTrack(
            id = trackId,
            title = title,
            artist = artistName,
            artistId = artistId,
            album = albumName.ifBlank { "Single" },
            albumId = albumId,
            artwork = artwork,
            bannerArtwork = bannerArtwork,
            durationSeconds = durationSec
        )
    }

    private fun parseTwoRowTrack(item: JSONObject): YtTrack? {
        val title = extractRunsText(item.optJSONObject("title"))
        if (title.isBlank()) return null

        var videoId = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")

        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("thumbnailOverlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        if (!isValidVideoId(videoId)) {
            videoId = item.optJSONObject("doubleTapCommand")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")
        }

        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val cleanArtist = cleanArtistName(subtitle)
        val thumbnails = extractThumbnails(item)

        val artwork = extractHighResArtwork(thumbnails, videoId)
        val bannerArtwork = extractBannerArtwork(thumbnails, videoId).ifBlank { artwork }

        val trackId = if (isValidVideoId(videoId)) {
            "yt_${videoId!!.trim()}"
        } else {
            "yt_meta_${Math.abs(title.hashCode())}_${Math.abs(cleanArtist.hashCode())}"
        }

        return YtTrack(
            id = trackId,
            title = title,
            artist = cleanArtist,
            artwork = artwork,
            bannerArtwork = bannerArtwork,
            durationSeconds = 200
        )
    }

    private fun parseResponsiveAlbum(item: JSONObject): YtAlbum? {
        val flexCols = item.optJSONArray("flexColumns") ?: return null
        val title = extractRunsText(flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        if (title.isBlank()) return null

        var browseId = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        if (browseId.isBlank()) {
            browseId = item.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId") ?: ""
        }
        if (browseId.isBlank()) {
            browseId = item.optJSONObject("doubleTapCommand")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId") ?: ""
        }

        var parsedArtist = ""
        var parsedArtistId = ""
        var parsedYear = ""
        var parsedType = "Album"

        if (flexCols.length() > 1) {
            val col1Runs = flexCols.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: JSONArray()

            for (k in 0 until col1Runs.length()) {
                val run = col1Runs.optJSONObject(k) ?: continue
                val text = run.optString("text", "").trim()
                if (text.isBlank() || text == "•") continue
                val bId = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""

                if (bId.startsWith("UC") || bId.startsWith("FEmusic_library_artist_") || bId.startsWith("FEmusic_artist_")) {
                    parsedArtist = text
                    parsedArtistId = bId
                } else if (text.matches(Regex("""\b(19\d{2}|20\d{2})\b"""))) {
                    parsedYear = text
                } else if (text.equals("Single", ignoreCase = true)) {
                    parsedType = "Single"
                } else if (text.equals("EP", ignoreCase = true)) {
                    parsedType = "EP"
                } else if (parsedArtist.isBlank() && !text.equals("Album", ignoreCase = true)) {
                    parsedArtist = cleanArtistName(text)
                }
            }
        }

        val thumbnails = extractThumbnails(item)
        val artwork = extractHighResArtwork(thumbnails)
        val bannerArtwork = extractBannerArtwork(thumbnails).ifBlank { artwork }

        return YtAlbum(
            id = browseId.ifBlank { "alb_${System.currentTimeMillis()}_${Math.abs(title.hashCode())}" },
            title = title,
            artist = parsedArtist.ifBlank { "Various Artists" },
            artistId = parsedArtistId,
            artwork = artwork,
            bannerArtwork = bannerArtwork.ifBlank { artwork },
            year = parsedYear,
            type = parsedType
        )
    }

    fun parseTwoRowAlbum(item: JSONObject, fallbackArtist: String, isSingle: Boolean = false): YtAlbum? {
        val title = extractRunsText(item.optJSONObject("title"))
        if (title.isBlank()) return null

        // Check all places for browseId
        var browseId = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""
        if (browseId.isBlank()) {
            browseId = item.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId") ?: ""
        }
        if (browseId.isBlank()) {
            browseId = item.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optString("browseId") ?: ""
        }

        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val subtitleRuns = item.optJSONObject("subtitle")?.optJSONArray("runs")
        var parsedArtist = ""
        var parsedArtistId = ""
        var parsedYear = ""

        if (subtitleRuns != null && subtitleRuns.length() > 0) {
            for (i in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(i) ?: continue
                val text = run.optString("text", "").trim()
                if (text.isBlank() || text == "•") continue

                val runBrowseId = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId", "") ?: ""

                if (runBrowseId.startsWith("UC") || runBrowseId.startsWith("FEmusic_library_artist_") || runBrowseId.startsWith("FEmusic_artist_")) {
                    parsedArtist = text
                    parsedArtistId = runBrowseId
                } else if (text.matches(Regex("""\b(19\d\d|20\d\d)\b"""))) {
                    parsedYear = text
                } else if (parsedArtist.isBlank() && !text.equals("Album", ignoreCase = true) &&
                    !text.equals("Single", ignoreCase = true) && !text.equals("EP", ignoreCase = true)) {
                    parsedArtist = cleanArtistName(text)
                }
            }
        }

        if (parsedYear.isBlank()) {
            val yearMatch = Regex("""\b(19\d\d|20\d\d)\b""").find(subtitle)
            parsedYear = yearMatch?.value ?: ""
        }

        val finalArtist = when {
            parsedArtist.isNotBlank() && parsedArtist != "YouTube Music" -> parsedArtist
            fallbackArtist.isNotBlank() -> fallbackArtist
            else -> cleanArtistName(subtitle).ifBlank { "Various Artists" }
        }

        val thumbnails = extractThumbnails(item)

        val artwork = extractHighResArtwork(thumbnails)
        val bannerArtwork = extractBannerArtwork(thumbnails).ifBlank { artwork }

        val isSingleType = isSingle ||
            subtitle.contains("Single", ignoreCase = true) ||
            title.contains("Single", ignoreCase = true)
        val isEpType = subtitle.contains("EP", ignoreCase = true) ||
            title.contains("EP", ignoreCase = true)

        val albumType = when {
            isSingleType -> "Single"
            isEpType -> "EP"
            else -> "Album"
        }

        return YtAlbum(
            id = browseId.ifBlank { "alb_${System.currentTimeMillis()}_${Math.abs(title.hashCode())}" },
            title = title,
            artist = finalArtist,
            artistId = parsedArtistId,
            artwork = artwork,
            bannerArtwork = bannerArtwork.ifBlank { artwork },
            year = parsedYear,
            type = albumType
        )
    }

    fun parseTwoRowArtist(item: JSONObject): YtArtist? {
        val title = extractRunsText(item.optJSONObject("title"))
        if (title.isBlank()) return null
        val browseId = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val thumbnails = item.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        return YtArtist(
            id = browseId,
            name = title,
            artwork = extractHighResArtwork(thumbnails),
            monthlyListeners = subtitle
        )
    }

    fun parseTwoRowPlaylist(item: JSONObject): YtPlaylist? {
        val title = extractRunsText(item.optJSONObject("title"))
        if (title.isBlank()) return null
        val browseId = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        val subtitle = extractRunsText(item.optJSONObject("subtitle"))
        val thumbnails = item.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        return YtPlaylist(
            id = browseId,
            title = title,
            author = subtitle.ifBlank { "YouTube Music" },
            artwork = extractHighResArtwork(thumbnails)
        )
    }

    private fun parseResponsiveArtist(item: JSONObject): YtArtist? {
        val flexCols = item.optJSONArray("flexColumns") ?: return null
        val name = extractRunsText(flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        if (name.isBlank()) return null

        val browseId = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        val listeners = if (flexCols.length() > 1) {
            extractRunsText(flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        } else ""

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        return YtArtist(
            id = browseId,
            name = name,
            artwork = extractHighResArtwork(thumbnails),
            monthlyListeners = listeners
        )
    }

    private fun parseResponsivePlaylist(item: JSONObject): YtPlaylist? {
        val flexCols = item.optJSONArray("flexColumns") ?: return null
        val title = extractRunsText(flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        if (title.isBlank()) return null

        val browseId = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId") ?: ""

        val author = if (flexCols.length() > 1) {
            extractRunsText(flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        } else "YouTube Music"

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        return YtPlaylist(
            id = browseId,
            title = title,
            author = author,
            artwork = extractHighResArtwork(thumbnails)
        )
    }

    private fun optSectionListContents(root: JSONObject): JSONArray {
        // Search structure
        val tabbed = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (tabbed != null) return tabbed

        // Browse / Home / Playlist structure via singleColumnBrowseResultsRenderer
        val singleColContent = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")

        if (singleColContent != null) {
            val sectionList = singleColContent.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            if (sectionList != null) return sectionList

            val plShelf = singleColContent.optJSONObject("musicPlaylistShelfRenderer")
            if (plShelf != null) return JSONArray().put(JSONObject().put("musicPlaylistShelfRenderer", plShelf))

            val mShelf = singleColContent.optJSONObject("musicShelfRenderer")
            if (mShelf != null) return JSONArray().put(JSONObject().put("musicShelfRenderer", mShelf))

            val grid = singleColContent.optJSONObject("gridRenderer")
            if (grid != null) return JSONArray().put(JSONObject().put("gridRenderer", grid))
        }

        // twoColumnBrowseResultsRenderer structure
        val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
        if (twoCol != null) {
            val secContents = twoCol.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")
            if (secContents != null) return secContents

            val tabs = twoCol.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
            val tabSec = tabs?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            if (tabSec != null) return tabSec
        }

        // Direct sectionListRenderer
        val direct = root.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (direct != null) return direct

        // Direct musicPlaylistShelfRenderer list
        val rootPlShelf = root.optJSONObject("contents")?.optJSONObject("musicPlaylistShelfRenderer")
        if (rootPlShelf != null) {
            return JSONArray().put(JSONObject().put("musicPlaylistShelfRenderer", rootPlShelf))
        }

        // Direct musicShelfRenderer list
        val shelf = root.optJSONObject("contents")?.optJSONObject("musicShelfRenderer")
        if (shelf != null) {
            return JSONArray().put(JSONObject().put("musicShelfRenderer", shelf))
        }

        // Direct gridRenderer list
        val rootGrid = root.optJSONObject("contents")?.optJSONObject("gridRenderer")
        if (rootGrid != null) {
            return JSONArray().put(JSONObject().put("gridRenderer", rootGrid))
        }

        return JSONArray()
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val runs = obj.optJSONArray("runs")
        if (runs != null && runs.length() > 0) {
            val sb = java.lang.StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text", "") ?: "")
            }
            return sb.toString().trim()
        }
        return obj.optString("simpleText", "").trim()
    }

    fun extractThumbnails(obj: JSONObject?): JSONArray? {
        if (obj == null) return null
        obj.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        val thumbObj = obj.optJSONObject("thumbnail")
        if (thumbObj != null) {
            thumbObj.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            thumbObj.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            thumbObj.optJSONObject("musicThumbnailRenderer")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            thumbObj.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            thumbObj.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        }
        val rendererObj = obj.optJSONObject("thumbnailRenderer")
        if (rendererObj != null) {
            rendererObj.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            rendererObj.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            rendererObj.optJSONObject("musicThumbnailRenderer")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            rendererObj.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        }
        val mThumbRenderer = obj.optJSONObject("musicThumbnailRenderer")
        if (mThumbRenderer != null) {
            mThumbRenderer.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
            mThumbRenderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        }
        val details = obj.optJSONObject("thumbnailDetails")
        if (details != null) {
            details.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        }
        val cropped = obj.optJSONObject("croppedThumbnail")
        if (cropped != null) {
            cropped.optJSONArray("thumbnails")?.let { if (it.length() > 0) return it }
        }
        return null
    }

    fun extractHighResArtwork(thumbnails: JSONArray?, videoId: String? = null): String {
        if (thumbnails != null && thumbnails.length() > 0) {
            var bestUrl = ""
            var maxDim = 0
            for (i in 0 until thumbnails.length()) {
                val thumb = thumbnails.optJSONObject(i) ?: continue
                val url = thumb.optString("url", "").trim()
                if (url.isBlank()) continue
                val w = thumb.optInt("width", 0)
                val h = thumb.optInt("height", 0)
                val dim = if (w > 0 && h > 0) w * h else w + h
                if (dim > maxDim || bestUrl.isBlank()) {
                    maxDim = dim
                    bestUrl = url
                }
            }

            if (bestUrl.isNotBlank()) {
                var cleanUrl = bestUrl
                if (cleanUrl.startsWith("//")) {
                    cleanUrl = "https:$cleanUrl"
                } else if (cleanUrl.startsWith("http://")) {
                    cleanUrl = "https://${cleanUrl.removePrefix("http://")}"
                }
                // Upstream SimpMusic high-res upgrade: replace =w120-h120 with =w544-h544 or =s544 safely without corrupting path hashes
                return cleanUrl.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
                    .replace(Regex("(?<=[/=])s\\d+(-c)?(?=[/=.-]|$)"), "s544")
                    .replace(Regex("=s\\d+.*"), "=s544-c-k-c0x00ffffff-no-rj")
            }
        }

        val cleanVid = videoId?.removePrefix("yt_")?.trim()
        if (cleanVid != null && isValidVideoId(cleanVid)) {
            return "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg"
        }

        return ""
    }

    fun extractBannerArtwork(thumbnails: JSONArray?, videoId: String? = null): String {
        if (thumbnails != null && thumbnails.length() > 0) {
            var widestUrl = ""
            var maxRatio = 1.0
            var maxWidth = 0
            for (i in 0 until thumbnails.length()) {
                val thumb = thumbnails.optJSONObject(i) ?: continue
                val url = thumb.optString("url", "").trim()
                if (url.isBlank()) continue
                val w = thumb.optInt("width", 0)
                val h = thumb.optInt("height", 0)
                if (w > 0 && h > 0) {
                    val ratio = w.toDouble() / h.toDouble()
                    if (ratio > maxRatio) {
                        maxRatio = ratio
                        widestUrl = url
                        maxWidth = w
                    } else if (Math.abs(ratio - maxRatio) < 0.1 && w > maxWidth) {
                        widestUrl = url
                        maxWidth = w
                    }
                }
            }
            if (maxRatio >= 1.3 && widestUrl.isNotBlank()) {
                var cleanUrl = widestUrl
                if (cleanUrl.startsWith("//")) {
                    cleanUrl = "https:$cleanUrl"
                } else if (cleanUrl.startsWith("http://")) {
                    cleanUrl = "https://${cleanUrl.removePrefix("http://")}"
                }
                return cleanUrl.replace(Regex("=w\\d+-h\\d+.*"), "=w1280-h720-l90-rj")
                    .replace(Regex("(?<=[/=])s\\d+(-c)?(?=[/=.-]|$)"), "s1280")
                    .replace(Regex("=s\\d+.*"), "=w1280-h720-l90-rj")
            }
            // If square thumbnail from Google CDN, transform to wide 1280x720 landscape presentation
            val highResSquare = extractHighResArtwork(thumbnails, videoId)
            if (highResSquare.isNotBlank() && (highResSquare.contains("googleusercontent.com") || highResSquare.contains("=w") || highResSquare.contains("=s"))) {
                return highResSquare.replace(Regex("=w\\d+-h\\d+.*"), "=w1280-h720-l90-rj")
                    .replace(Regex("(?<=[/=])s\\d+(-c)?(?=[/=.-]|$)"), "s1280")
                    .replace(Regex("=s\\d+.*"), "=w1280-h720-l90-rj")
            }
        }
        val cleanVid = videoId?.removePrefix("yt_")?.trim()
        if (cleanVid != null && isValidVideoId(cleanVid)) {
            return "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg"
        }
        return extractHighResArtwork(thumbnails, videoId)
    }

    private fun extractBrowseEndpoint(headerObj: JSONObject?): Pair<String, String?>? {
        if (headerObj == null) return null
        val buttonRenderer = headerObj.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
        val ep = buttonRenderer?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: buttonRenderer?.optJSONObject("command")?.optJSONObject("browseEndpoint")
            ?: headerObj.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: headerObj.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("command")?.optJSONObject("browseEndpoint")
            ?: headerObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?: headerObj.optJSONObject("command")?.optJSONObject("browseEndpoint")
        val browseId = ep?.optString("browseId", "")
        val params = ep?.optString("params", "").takeIf { !it.isNullOrBlank() }
        return if (!browseId.isNullOrBlank()) Pair(browseId, params) else null
    }

    private fun parseDurationSeconds(timer: String): Int {
        if (timer.isBlank()) return 180
        val parts = timer.split(":")
        return when (parts.size) {
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            1 -> parts[0].toIntOrNull() ?: 180
            else -> 180
        }
    }

    /**
     * Parses the response from getAccountSwitcherEndpoint to extract authentic accounts,
     * display names, channel handles, avatars, pageIds, and authUser indices.
     */
    fun parseAccountSwitcherResponse(rawResponse: String, rawCookie: String): List<YtAccount> {
        val list = mutableListOf<YtAccount>()
        try {
            val cleanJson = rawResponse.trim().removePrefix(")]}'\n").removePrefix(")]}'").trim()
            val root = JSONObject(cleanJson)
            val code = root.optString("code")
            if (code != "SUCCESS" && !root.has("data")) return emptyList()

            val data = root.optJSONObject("data") ?: return emptyList()
            val contents = data.optJSONArray("contents") ?: JSONArray()
            val authUserRegex = Regex("[?&]authuser=(\\d+)")

            for (i in 0 until contents.length()) {
                val section = contents.optJSONObject(i)?.optJSONObject("accountSectionListRenderer") ?: continue
                val secContents = section.optJSONArray("contents") ?: JSONArray()
                for (j in 0 until secContents.length()) {
                    val itemSec = secContents.optJSONObject(j)?.optJSONObject("accountItemSectionRenderer") ?: continue
                    val items = itemSec.optJSONArray("contents") ?: JSONArray()
                    for (k in 0 until items.length()) {
                        val accountItem = items.optJSONObject(k)?.optJSONObject("accountItem") ?: continue
                        val name = accountItem.optJSONObject("accountName")?.optString("simpleText", "")?.trim() ?: ""
                        if (name.isBlank()) continue

                        val email = accountItem.optJSONObject("channelHandle")?.optString("simpleText", "")?.trim() ?: ""
                        val thumbnails = accountItem.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
                        val avatarUrl = extractHighResArtwork(thumbnails)

                        var pageId = accountItem.optString("onBehalfOfParameter", "").ifBlank { null }
                        var authUser = 0

                        val tokens = accountItem.optJSONObject("serviceEndpoint")
                            ?.optJSONObject("selectActiveIdentityEndpoint")
                            ?.optJSONArray("supportedTokens")

                        if (tokens != null) {
                            for (t in 0 until tokens.length()) {
                                val tokenObj = tokens.optJSONObject(t) ?: continue
                                if (pageId == null && tokenObj.has("pageIdToken")) {
                                    pageId = tokenObj.optJSONObject("pageIdToken")?.optString("pageId", "")?.ifBlank { null }
                                }
                                if (tokenObj.has("accountSigninToken")) {
                                    val signinUrl = tokenObj.optJSONObject("accountSigninToken")?.optString("signinUrl", "") ?: ""
                                    authUserRegex.find(signinUrl)?.groupValues?.get(1)?.toIntOrNull()?.let {
                                        authUser = it
                                    }
                                }
                            }
                        }

                        val accountId = pageId ?: email.ifBlank { name }
                        list.add(
                            YtAccount(
                                id = accountId,
                                name = name,
                                email = email,
                                avatarUrl = avatarUrl,
                                cookie = rawCookie,
                                pageId = pageId,
                                authUser = authUser,
                                isActive = (list.isEmpty())
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Parses the fallback account_menu response if getAccountSwitcherEndpoint does not return items.
     */
    fun parseAccountMenuResponse(root: JSONObject, rawCookie: String): YtAccount? {
        try {
            val actions = root.optJSONArray("actions") ?: return null
            for (i in 0 until actions.length()) {
                val header = actions.optJSONObject(i)
                    ?.optJSONObject("openPopupAction")
                    ?.optJSONObject("popup")
                    ?.optJSONObject("multiPageMenuRenderer")
                    ?.optJSONObject("header")
                    ?.optJSONObject("activeAccountHeaderRenderer") ?: continue

                val name = extractRunsText(header.optJSONObject("accountName"))
                if (name.isBlank()) continue

                val email = extractRunsText(header.optJSONObject("channelHandle"))
                val avatarUrl = extractHighResArtwork(header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails"))

                return YtAccount(
                    id = email.ifBlank { name },
                    name = name,
                    email = email,
                    avatarUrl = avatarUrl,
                    cookie = rawCookie,
                    pageId = null,
                    authUser = 0,
                    isActive = true
                )
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * Parses search suggestions from music/get_search_suggestions response.
     */
    fun parseSearchSuggestions(root: JSONObject): List<String> {
        val list = mutableListOf<String>()
        try {
            val contents = root.optJSONArray("contents") ?: return emptyList()
            for (i in 0 until contents.length()) {
                val section = contents.optJSONObject(i)?.optJSONObject("searchSuggestionsSectionRenderer") ?: continue
                val items = section.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    val suggestion = items.optJSONObject(j)?.optJSONObject("searchSuggestionRenderer")
                        ?.optJSONObject("suggestion") ?: continue
                    val text = extractRunsText(suggestion)
                    if (text.isNotBlank()) list.add(text)
                }
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Parses playlists from FEmusic_liked_playlists gridRenderer or musicShelfRenderer.
     */
    fun parseLibraryPlaylists(root: JSONObject): List<YtPlaylist> {
        val playlists = mutableListOf<YtPlaylist>()
        try {
            val sectionListContents = optSectionListContents(root)
            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue

                // Check gridRenderer items (standard for FEmusic_liked_playlists)
                val grid = section.optJSONObject("gridRenderer")
                if (grid != null) {
                    val items = grid.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) {
                        val twoRow = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                        val title = extractRunsText(twoRow.optJSONObject("title"))
                        if (title.isBlank()) continue

                        val browseId = twoRow.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("browseEndpoint")
                            ?.optString("browseId", "") ?: ""

                        if (browseId.isBlank() || title.equals("New playlist", ignoreCase = true) || title.equals("Create playlist", ignoreCase = true)) {
                            continue
                        }

                        val author = extractRunsText(twoRow.optJSONObject("subtitle"))
                        val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")

                        playlists.add(
                            YtPlaylist(
                                id = browseId,
                                title = title,
                                author = author.ifBlank { "YouTube Music" },
                                artwork = extractHighResArtwork(thumbnails)
                            )
                        )
                    }
                }

                // Check musicShelfRenderer
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val contents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until contents.length()) {
                        val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseResponsivePlaylist(item)?.let { playlists.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}
        return playlists
    }

    /**
     * Parses albums from FEmusic_library_corpus_albums gridRenderer or musicShelfRenderer.
     */
    fun parseLibraryAlbums(root: JSONObject): List<YtAlbum> {
        val albums = mutableListOf<YtAlbum>()
        try {
            val sectionListContents = optSectionListContents(root)
            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue

                // Check gridRenderer items (standard for FEmusic_library_corpus_albums)
                val grid = section.optJSONObject("gridRenderer")
                if (grid != null) {
                    val items = grid.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) {
                        val twoRow = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                        parseTwoRowAlbum(twoRow, fallbackArtist = "Various Artists")?.let { albums.add(it) }
                    }
                }

                // Check musicShelfRenderer
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val contents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until contents.length()) {
                        val elem = contents.optJSONObject(j) ?: continue
                        val item = elem.optJSONObject("musicResponsiveListItemRenderer")
                        if (item != null) {
                            parseResponsiveAlbum(item)?.let { albums.add(it) }
                        } else {
                            val twoRow = elem.optJSONObject("musicTwoRowItemRenderer")
                            if (twoRow != null) {
                                parseTwoRowAlbum(twoRow, fallbackArtist = "Various Artists")?.let { albums.add(it) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return albums
    }

    /**
     * Parses artists from FEmusic_library_corpus_track_artists gridRenderer or musicShelfRenderer.
     */
    fun parseLibraryArtists(root: JSONObject): List<YtArtist> {
        val artists = mutableListOf<YtArtist>()
        try {
            val sectionListContents = optSectionListContents(root)
            for (i in 0 until sectionListContents.length()) {
                val section = sectionListContents.optJSONObject(i) ?: continue

                // Check gridRenderer items (standard for FEmusic_library_corpus_track_artists)
                val grid = section.optJSONObject("gridRenderer")
                if (grid != null) {
                    val items = grid.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) {
                        val twoRow = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                        parseTwoRowArtist(twoRow)?.let { artists.add(it) }
                    }
                }

                // Check musicShelfRenderer
                val shelf = section.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val contents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until contents.length()) {
                        val elem = contents.optJSONObject(j) ?: continue
                        val item = elem.optJSONObject("musicResponsiveListItemRenderer")
                        if (item != null) {
                            parseResponsiveArtist(item)?.let { artists.add(it) }
                        } else {
                            val twoRow = elem.optJSONObject("musicTwoRowItemRenderer")
                            if (twoRow != null) {
                                parseTwoRowArtist(twoRow)?.let { artists.add(it) }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return artists
    }

    /**
     * Parses user listening history from FEmusic_history response.
     */
    fun parseHistoryResponse(root: JSONObject): List<YtTrack> {
        val tracks = mutableListOf<YtTrack>()
        try {
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicPlaylistShelfRenderer")
                    ?: section.optJSONObject("itemSectionRenderer")
                        ?.optJSONArray("contents")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("itemSectionRenderer")
                        ?.optJSONArray("contents")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicPlaylistShelfRenderer")
                    ?: continue
                val contents = shelf.optJSONArray("contents") ?: JSONArray()
                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveSong(item)?.let { tracks.add(it) }
                }
            }
        } catch (_: Exception) {}
        return tracks
    }

    /**
     * Parses YouTube Music algorithmic radio queue from /next response.
     */
    fun parseRadioTracks(root: JSONObject): List<YtTrack> {
        val tracks = mutableListOf<YtTrack>()
        try {
            val tabs = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")

            val queueContent = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicQueueRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents")

            if (queueContent != null) {
                for (i in 0 until queueContent.length()) {
                    val panelVideo = queueContent.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                    val videoId = panelVideo.optString("videoId")
                    if (!isValidVideoId(videoId)) continue

                    val title = extractRunsText(panelVideo.optJSONObject("title"))
                    val artist = extractRunsText(panelVideo.optJSONObject("longBylineText"))
                    val thumbnails = extractThumbnails(panelVideo) ?: panelVideo.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val artwork = extractHighResArtwork(thumbnails, videoId)
                    val bannerArtwork = extractBannerArtwork(thumbnails, videoId).ifBlank { artwork }

                    tracks.add(
                        YtTrack(
                            id = "yt_$videoId",
                            title = title.ifBlank { "YouTube Track" },
                            artist = cleanArtistName(artist),
                            artwork = artwork,
                            bannerArtwork = bannerArtwork,
                            durationSeconds = 180
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return tracks
    }
}
