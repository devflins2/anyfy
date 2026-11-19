package com.example.service.importer.matcher

import com.example.data.repository.MusicRepository
import com.example.model.Track
import com.example.service.importer.model.ImportTrackItem
import com.example.service.importer.model.MatchConfidence
import com.example.service.importer.model.MatchStatus
import com.example.service.importer.model.TrackMatchResult
import com.example.service.importer.normalizer.TrackNormalizer
import com.example.service.integrations.ytmusic.YtMusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

class SongMatchingEngine(
    private val musicRepository: MusicRepository,
    private val ytMusicService: YtMusicService = YtMusicService.getInstance()
) {

    private val concurrencySemaphore = Semaphore(3) // Controlled concurrency to prevent rate-limiting

    suspend fun matchTrack(
        item: ImportTrackItem,
        knownCatalog: List<Track> = musicRepository.getAllKnownTracks()
    ): TrackMatchResult = withContext(Dispatchers.IO) {
        val normalized = TrackNormalizer.normalize(
            rawTitle = item.title,
            rawArtists = item.artists,
            rawAlbum = item.album
        )

        // Step 1: Check existing local / cached catalog first (Instant & offline-first)
        val localCandidates = searchCatalog(normalized.cleanTitle, normalized.primaryArtist, knownCatalog)
        val scoredLocal = scoreCandidates(item, normalized.cleanTitle, normalized.primaryArtist, localCandidates)

        val topLocal = scoredLocal.firstOrNull()
        if (topLocal != null && topLocal.score >= 0.90f) {
            val status = if (scoredLocal.size > 1 && (scoredLocal[0].score - scoredLocal[1].score) < 0.05f) {
                MatchStatus.MULTIPLE_MATCHES
            } else {
                MatchStatus.MATCHED
            }
            return@withContext TrackMatchResult(
                original = item,
                matchedTrack = topLocal.track,
                status = status,
                confidence = MatchConfidence.HIGH,
                confidenceScore = topLocal.score,
                candidates = scoredLocal.map { it.track }.take(5)
            )
        }

        // Step 2: Query remote music catalog via existing Anyfy search API with controlled concurrency
        val remoteCandidates = concurrencySemaphore.withPermit {
            delay(100L) // Polite request pacing
            searchRemoteCatalog(normalized.searchTerms)
        }

        // Combine local and remote candidates
        val allCandidates = (scoredLocal.map { it.track } + remoteCandidates).distinctBy { it.id }
        val scoredAll = scoreCandidates(item, normalized.cleanTitle, normalized.primaryArtist, allCandidates)

        if (scoredAll.isEmpty()) {
            return@withContext TrackMatchResult(
                original = item,
                matchedTrack = null,
                status = MatchStatus.NOT_FOUND,
                confidence = MatchConfidence.NONE,
                confidenceScore = 0f,
                candidates = emptyList()
            )
        }

        val best = scoredAll.first()
        val topScore = best.score

        when {
            topScore >= 0.85f -> {
                val hasMultipleCloseMatches = scoredAll.size > 1 &&
                        scoredAll[1].score >= 0.80f &&
                        (topScore - scoredAll[1].score) <= 0.08f

                val status = if (hasMultipleCloseMatches) MatchStatus.MULTIPLE_MATCHES else MatchStatus.MATCHED
                TrackMatchResult(
                    original = item,
                    matchedTrack = best.track,
                    status = status,
                    confidence = MatchConfidence.HIGH,
                    confidenceScore = topScore,
                    candidates = scoredAll.map { it.track }.take(5)
                )
            }
            topScore >= 0.50f -> {
                TrackMatchResult(
                    original = item,
                    matchedTrack = best.track,
                    status = MatchStatus.NEEDS_REVIEW,
                    confidence = MatchConfidence.MEDIUM,
                    confidenceScore = topScore,
                    candidates = scoredAll.map { it.track }.take(5)
                )
            }
            else -> {
                // Low confidence: never automatically select an unrelated track
                TrackMatchResult(
                    original = item,
                    matchedTrack = null,
                    status = MatchStatus.NOT_FOUND,
                    confidence = MatchConfidence.LOW,
                    confidenceScore = topScore,
                    candidates = scoredAll.map { it.track }.take(5)
                )
            }
        }
    }

    private fun searchCatalog(cleanTitle: String, primaryArtist: String, catalog: List<Track>): List<Track> {
        val lowerTitle = cleanTitle.lowercase()
        val lowerArtist = primaryArtist.lowercase()
        return catalog.filter { track ->
            val trkTitle = track.title.lowercase()
            val trkArtist = track.artist.lowercase()
            (trkTitle.contains(lowerTitle) || lowerTitle.contains(trkTitle)) &&
                    (trkArtist.contains(lowerArtist) || lowerArtist.contains(trkArtist) || trkArtist.contains("various"))
        }
    }

    private suspend fun searchRemoteCatalog(searchTerms: List<String>): List<Track> {
        val tracks = mutableListOf<Track>()
        for (term in searchTerms.take(2)) {
            try {
                val result = ytMusicService.searchTracks(term)
                if (result.isSuccess) {
                    val found = result.getOrNull()?.map { it.toTrack() } ?: emptyList()
                    tracks.addAll(found)
                    if (tracks.isNotEmpty()) break
                }
            } catch (_: Exception) {}
        }
        return tracks
    }

    data class ScoredTrack(val track: Track, val score: Float)

    private fun scoreCandidates(
        item: ImportTrackItem,
        cleanTitle: String,
        primaryArtist: String,
        candidates: List<Track>
    ): List<ScoredTrack> {
        return candidates.map { candidate ->
            val titleSim = TrackNormalizer.computeSimilarity(cleanTitle, candidate.title)
            val artistSim = TrackNormalizer.computeSimilarity(primaryArtist, candidate.artist)

            // Duration comparison check (if available)
            val origSec = (item.durationMs / 1000L).toInt()
            val durPenalty = if (origSec > 0 && candidate.durationSeconds > 0) {
                val diff = abs(origSec - candidate.durationSeconds)
                when {
                    diff <= 10 -> 0.05f  // Bonus for matching duration
                    diff <= 30 -> 0.0f
                    diff <= 60 -> -0.05f
                    else -> -0.15f      // Large duration mismatch likely different remix/live version
                }
            } else 0.0f

            // Composite score: Title weight 0.60, Artist weight 0.40 + duration adjustment
            val baseScore = (titleSim * 0.60f) + (artistSim * 0.40f) + durPenalty
            val finalScore = baseScore.coerceIn(0.0f, 1.0f)

            ScoredTrack(candidate, finalScore)
        }.sortedByDescending { it.score }
    }
}

