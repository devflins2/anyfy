package com.example.service.integrations.ytmusic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Robust parser for YouTube Music InnerTube continuation responses (search pagination
 * and artist full discography tracks pagination).
 */
object YtMusicContinuationParser {

    /**
     * Parses an artist's full songs browse page or continuation response,
     * returning all discovered tracks and continuation token if more pages exist.
     */
    fun parseTracksPage(root: JSONObject): Pair<List<YtTrack>, String?> {
        // First check standard continuation responses
        val contResult = YtMusicParser.parseContinuationTracks(root)
        if (contResult.first.isNotEmpty()) {
            return contResult
        }

        // Browse page format (singleColumnBrowseResultsRenderer / sectionListRenderer)
        val tracks = mutableListOf<YtTrack>()
        var continuationToken: String? = null

        try {
            val sectionList = optSectionListContents(root)
            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i) ?: continue
                val shelf = section.optJSONObject("musicShelfRenderer")
                    ?: section.optJSONObject("musicPlaylistShelfRenderer") ?: continue

                val contents = shelf.optJSONArray("contents") ?: JSONArray()
                for (j in 0 until contents.length()) {
                    val item = contents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveTrack(item)?.let { tracks.add(it) }
                }

                val continuations = shelf.optJSONArray("continuations")
                if (continuations != null && continuations.length() > 0) {
                    continuationToken = continuations.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                }
                if (continuationToken.isNullOrBlank()) {
                    for (j in 0 until contents.length()) {
                        val item = contents.optJSONObject(j)?.optJSONObject("continuationItemRenderer") ?: continue
                        val token = item.optJSONObject("continuationEndpoint")
                            ?.optJSONObject("continuationCommand")
                            ?.optString("token")
                        if (!token.isNullOrBlank()) {
                            continuationToken = token
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return Pair(tracks, continuationToken)
    }

    /**
     * Parses search continuation response, extracting subsequent tracks, albums, artists,
     * playlists, and the next continuation token.
     */
    fun parseSearchContinuation(root: JSONObject): YtSearchResult {
        val tracks = mutableListOf<YtTrack>()
        val albums = mutableListOf<YtAlbum>()
        val artists = mutableListOf<YtArtist>()
        val playlists = mutableListOf<YtPlaylist>()
        var nextContinuation: String? = null

        try {
            // 1. Check modern InnerTube format (onResponseReceivedActions)
            val actions = root.optJSONArray("onResponseReceivedActions")
            if (actions != null && actions.length() > 0) {
                for (a in 0 until actions.length()) {
                    val action = actions.optJSONObject(a) ?: continue
                    val appendAction = action.optJSONObject("appendContinuationItemsAction") ?: continue
                    val contItems = appendAction.optJSONArray("continuationItems") ?: continue
                    for (i in 0 until contItems.length()) {
                        val itemObj = contItems.optJSONObject(i) ?: continue
                        val listItem = itemObj.optJSONObject("musicResponsiveListItemRenderer")
                        if (listItem != null) {
                            classifyAndParseItem(listItem, tracks, albums, artists, playlists)
                            continue
                        }
                        val contRenderer = itemObj.optJSONObject("continuationItemRenderer")
                        if (contRenderer != null) {
                            val token = contRenderer.optJSONObject("continuationEndpoint")
                                ?.optJSONObject("continuationCommand")
                                ?.optString("token")
                            if (!token.isNullOrBlank()) {
                                nextContinuation = token
                            }
                        }
                    }
                }
            }

            // 2. Check legacy format (continuationContents)
            val contContents = root.optJSONObject("continuationContents")
            val shelfCont = contContents?.optJSONObject("musicShelfContinuation")
                ?: contContents?.optJSONObject("musicPlaylistShelfContinuation")
            if (shelfCont != null) {
                val contents = shelfCont.optJSONArray("contents") ?: JSONArray()
                for (i in 0 until contents.length()) {
                    val cObj = contents.optJSONObject(i) ?: continue
                    val listItem = cObj.optJSONObject("musicResponsiveListItemRenderer")
                    if (listItem != null) {
                        classifyAndParseItem(listItem, tracks, albums, artists, playlists)
                        continue
                    }
                    val twoRow = cObj.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val nav = twoRow.optJSONObject("navigationEndpoint")
                        val browseId = nav?.optJSONObject("browseEndpoint")?.optString("browseId", "").orEmpty()
                        val watchId = nav?.optJSONObject("watchEndpoint")?.optString("videoId", "").orEmpty()
                        if (YtMusicParser.isValidVideoId(watchId)) {
                            val title = extractRunsText(twoRow.optJSONObject("title"))
                            val subtitle = extractRunsText(twoRow.optJSONObject("subtitle"))
                            val thumbs = YtMusicParser.extractThumbnails(twoRow)
                            val art = YtMusicParser.extractHighResArtwork(thumbs, watchId)
                            tracks.add(YtTrack(id = "yt_$watchId", title = title, artist = YtMusicParser.cleanArtistName(subtitle), artwork = art))
                        } else if (browseId.startsWith("UC")) {
                            val title = extractRunsText(twoRow.optJSONObject("title"))
                            val thumbs = YtMusicParser.extractThumbnails(twoRow)
                            artists.add(YtArtist(id = browseId, name = title, artwork = YtMusicParser.extractHighResArtwork(thumbs, "")))
                        } else if (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) {
                            val title = extractRunsText(twoRow.optJSONObject("title"))
                            val subtitle = extractRunsText(twoRow.optJSONObject("subtitle"))
                            val thumbs = YtMusicParser.extractThumbnails(twoRow)
                            albums.add(YtAlbum(id = browseId, title = title, artist = YtMusicParser.cleanArtistName(subtitle), artwork = YtMusicParser.extractHighResArtwork(thumbs, "")))
                        }
                        continue
                    }
                    val contRenderer = cObj.optJSONObject("continuationItemRenderer")
                    if (contRenderer != null) {
                        val token = contRenderer.optJSONObject("continuationEndpoint")
                            ?.optJSONObject("continuationCommand")
                            ?.optString("token")
                        if (!token.isNullOrBlank()) {
                            nextContinuation = token
                        }
                    }
                }
                if (nextContinuation.isNullOrBlank()) {
                    val continuations = shelfCont.optJSONArray("continuations")
                    if (continuations != null && continuations.length() > 0) {
                        nextContinuation = continuations.optJSONObject(0)
                            ?.optJSONObject("nextContinuationData")
                            ?.optString("continuation")
                    }
                }
            }
        } catch (_: Exception) {}

        return YtSearchResult(
            tracks = tracks,
            albums = albums,
            artists = artists,
            playlists = playlists,
            continuationToken = nextContinuation
        )
    }

    private fun classifyAndParseItem(
        item: JSONObject,
        tracks: MutableList<YtTrack>,
        albums: MutableList<YtAlbum>,
        artists: MutableList<YtArtist>,
        playlists: MutableList<YtPlaylist>
    ) {
        val flexCols = item.optJSONArray("flexColumns") ?: return
        if (flexCols.length() == 0) return

        val col0 = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        val title = extractRunsText(col0?.optJSONObject("text"))
        if (title.isBlank()) return

        val runs0 = col0?.optJSONObject("text")?.optJSONArray("runs") ?: JSONArray()
        val navEp0 = runs0.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("doubleTapCommand")

        val watchEp = navEp0?.optJSONObject("watchEndpoint")
        val browseEp = navEp0?.optJSONObject("browseEndpoint")
        val browseId = browseEp?.optString("browseId", "") ?: ""
        var videoId = watchEp?.optString("videoId") ?: item.optString("videoId")
        if (videoId.isNullOrBlank()) {
            videoId = item.optJSONObject("playlistItemData")?.optString("videoId") ?: ""
        }
        if (videoId.isNullOrBlank()) {
            videoId = item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId") ?: ""
        }
        if (videoId.isNullOrBlank()) {
            videoId = item.optJSONObject("onTap")?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""
        }

        val col1Text = if (flexCols.length() > 1) {
            extractRunsText(flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        } else ""

        val thumbnails = YtMusicParser.extractThumbnails(item)
        val artwork = YtMusicParser.extractHighResArtwork(thumbnails, videoId)

        when {
            browseId.startsWith("UC") -> {
                artists.add(
                    YtArtist(
                        id = browseId,
                        name = title,
                        artwork = artwork,
                        monthlyListeners = col1Text
                    )
                )
            }
            browseId.startsWith("MPRE") || browseId.startsWith("OLAK") || col1Text.contains("album", ignoreCase = true) || col1Text.contains("single", ignoreCase = true) || col1Text.contains("ep", ignoreCase = true) -> {
                albums.add(
                    YtAlbum(
                        id = browseId.ifBlank { "alb_${System.currentTimeMillis()}" },
                        title = title,
                        artist = YtMusicParser.cleanArtistName(col1Text),
                        artwork = artwork
                    )
                )
            }
            browseId.startsWith("VL") || browseId.startsWith("PL") || col1Text.contains("playlist", ignoreCase = true) -> {
                playlists.add(
                    YtPlaylist(
                        id = browseId.removePrefix("VL").ifBlank { "pl_${System.currentTimeMillis()}" },
                        title = title,
                        author = YtMusicParser.cleanArtistName(col1Text).ifBlank { "YouTube Music" },
                        artwork = artwork
                    )
                )
            }
            else -> {
                val cleanVid = videoId.trim()
                val trackId = when {
                    cleanVid.isNotBlank() -> "yt_$cleanVid"
                    else -> "yt_meta_${Math.abs(title.hashCode())}_${Math.abs(col1Text.hashCode())}"
                }
                tracks.add(
                    YtTrack(
                        id = trackId,
                        title = title,
                        artist = YtMusicParser.cleanArtistName(col1Text),
                        artwork = artwork,
                        bannerArtwork = YtMusicParser.extractBannerArtwork(thumbnails, cleanVid).ifBlank { artwork }
                    )
                )
            }
        }
    }

    private fun parseResponsiveTrack(item: JSONObject): YtTrack? {
        val flexCols = item.optJSONArray("flexColumns") ?: return null
        if (flexCols.length() == 0) return null

        val col0 = flexCols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
        val title = extractRunsText(col0?.optJSONObject("text"))
        if (title.isBlank()) return null

        val navEp = col0?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("navigationEndpoint")
            ?: item.optJSONObject("doubleTapCommand")

        var videoId = navEp?.optJSONObject("watchEndpoint")?.optString("videoId", "") ?: item.optString("videoId")
        if (videoId.isBlank()) {
            videoId = item.optJSONObject("playlistItemData")?.optString("videoId") ?: ""
        }

        val artistName = if (flexCols.length() > 1) {
            extractRunsText(flexCols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
        } else "Unknown Artist"

        val thumbnails = YtMusicParser.extractThumbnails(item)
        val cleanVid = videoId.trim()
        val artwork = YtMusicParser.extractHighResArtwork(thumbnails, cleanVid)
        val banner = YtMusicParser.extractBannerArtwork(thumbnails, cleanVid).ifBlank { artwork }

        val trackId = if (cleanVid.isNotBlank()) "yt_$cleanVid" else "yt_meta_${Math.abs(title.hashCode())}_${Math.abs(artistName.hashCode())}"

        return YtTrack(
            id = trackId,
            title = title,
            artist = YtMusicParser.cleanArtistName(artistName),
            artwork = artwork,
            bannerArtwork = banner
        )
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val runs = obj.optJSONArray("runs") ?: return obj.optString("simpleText", "")
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            val text = runs.optJSONObject(i)?.optString("text")
            if (!text.isNullOrBlank()) {
                sb.append(text)
            }
        }
        return sb.toString().trim()
    }

    private fun optSectionListContents(root: JSONObject): JSONArray {
        val tabbed = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (tabbed != null) return tabbed

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
        }

        return JSONArray()
    }
}

/**
 * Extension functions on YtMusicParser allowing callers to invoke
 * YtMusicParser.parseTracksPage(root) and YtMusicParser.parseSearchContinuation(root) directly.
 */
fun YtMusicParser.parseTracksPage(root: JSONObject): Pair<List<YtTrack>, String?> =
    YtMusicContinuationParser.parseTracksPage(root)

fun YtMusicParser.parseSearchContinuation(root: JSONObject): YtSearchResult =
    YtMusicContinuationParser.parseSearchContinuation(root)
