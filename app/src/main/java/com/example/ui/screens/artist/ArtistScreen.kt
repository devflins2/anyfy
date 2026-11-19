package com.example.ui.screens.artist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.model.Album
import com.example.model.Artist
import com.example.model.Track
import com.example.ui.components.AlbumCard
import com.example.ui.components.ArtistCard
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassIconButton
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongRow
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme

@Composable
fun ArtistScreen(
    artist: Artist,
    allTracks: List<Track>,
    allAlbums: List<Album>,
    allArtists: List<Artist>,
    likedTrackIds: Set<String>,
    currentPlayingTrack: Track?,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onRelatedArtistClick: (Artist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    var isFollowing by remember { mutableStateOf(false) }

    val artistTracks = remember(artist, allTracks) {
        if (artist.tracks.isNotEmpty()) {
            artist.tracks
        } else {
            val mappedById = artist.popularTrackIds.mapNotNull { tid -> allTracks.find { it.id == tid } }
            if (mappedById.isNotEmpty()) mappedById else allTracks.filter { it.artistId == artist.id || it.artist == artist.name }
        }
    }

    val artistAlbums = remember(artist, allAlbums) {
        if (artist.albums.isNotEmpty()) {
            artist.albums
        } else {
            allAlbums.filter { it.artist.equals(artist.name, ignoreCase = true) }
        }
    }

    val artistSingles = remember(artist) {
        artist.singles
    }

    val artistVideos = remember(artist) {
        artist.videos
    }

    val relatedArtists = remember(artist, allArtists) {
        if (artist.similarArtists.isNotEmpty()) {
            artist.similarArtists
        } else {
            allArtists.filter { it.id != artist.id }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 140.dp)
    ) {
        // Hero Header with Back button
        item(key = "artist_hero_header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                AsyncImage(
                    model = artist.displayArtwork.ifBlank { artist.bannerArtwork.ifBlank { artist.artwork } },
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Dark vignette and ambient glass overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x66101010),
                                    Color(0xBB101010),
                                    FlinsDarkBg
                                )
                            )
                        )
                )
                // Back Button
                GlassIconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp),
                    testTag = "artist_back_button"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Artist Info
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp)
                ) {
                    Text(
                        text = "VERIFIED ARTIST",
                        style = FlinsTheme.typography.overline,
                        color = Color(0xFFBDBDBD)
                    )
                    Text(
                        text = artist.name,
                        style = FlinsTheme.typography.display,
                        color = Color.White
                    )
                    Text(
                        text = "${artist.monthlyListeners} monthly listeners",
                        style = FlinsTheme.typography.bodyMedium,
                        color = FlinsTheme.colors.textSecondary
                    )
                }
            }
        }

        // Actions: Play, Shuffle, Follow (Section 15)
        item(key = "artist_actions_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (artistTracks.isNotEmpty()) {
                    GlassButton(
                        onClick = { onTrackClick(artistTracks.first(), artistTracks) },
                        variant = GlassButtonVariant.PRIMARY,
                        testTag = "artist_play_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = FlinsDarkBg,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play", style = FlinsTheme.typography.button, color = FlinsDarkBg)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    GlassIconButton(
                        onClick = {
                            val shuffled = artistTracks.shuffled()
                            onTrackClick(shuffled.first(), shuffled)
                        },
                        size = 46.dp,
                        testTag = "artist_shuffle_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = Color(0xFFC0C0C0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                // Follow button toggle
                GlassButton(
                    onClick = { isFollowing = !isFollowing },
                    variant = if (isFollowing) GlassButtonVariant.SECONDARY else GlassButtonVariant.OUTLINE,
                    testTag = "artist_follow_button"
                ) {
                    Text(
                        text = if (isFollowing) "Following" else "Follow",
                        style = FlinsTheme.typography.button,
                        color = if (isFollowing) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary
                    )
                }
            }
        }

        // Bio snippet
        item(key = "artist_bio_section") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = artist.bio,
                    style = FlinsTheme.typography.body,
                    color = FlinsTheme.colors.textSecondary
                )
            }
        }

        // Popular Songs
        if (artistTracks.isNotEmpty()) {
            item(key = "header_popular_songs") {
                Spacer(modifier = Modifier.height(14.dp))
                SectionHeader(
                    overline = "Catalog",
                    title = "Popular Songs"
                )
            }
            items(artistTracks, key = { "art_trk_${it.id}" }) { track ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                ) {
                    SongRow(
                        track = track,
                        onTrackClick = { onTrackClick(track, artistTracks) },
                        isCurrentPlaying = track.id == currentPlayingTrack?.id,
                        isLiked = likedTrackIds.contains(track.id),
                        onLikeToggle = { onLikeToggle(track.id) },
                        onMoreClick = { onTrackOptionsClick(track) },
                        onArtistClick = null,
                        onAlbumClick = { albumTitle ->
                            val matchedAlbum = (artistAlbums + artistSingles + allAlbums).find {
                                (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_") && it.id == track.albumId) ||
                                it.title.equals(albumTitle, ignoreCase = true) ||
                                it.title.equals(track.album, ignoreCase = true)
                            } ?: Album(
                                id = if (track.albumId.isNotBlank() && !track.albumId.startsWith("alb_")) track.albumId else "alb_${(albumTitle.ifBlank { track.album }).lowercase().replace(" ", "_")}",
                                title = if (track.album.isNotBlank()) track.album else albumTitle,
                                artist = track.artist,
                                artistId = track.artistId,
                                artwork = track.artwork,
                                bannerArtwork = track.bannerArtwork
                            )
                            onAlbumClick(matchedAlbum)
                        }
                    )
                }
            }
        }

        // Albums
        if (artistAlbums.isNotEmpty()) {
            item(key = "header_albums") {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Discography",
                    title = if (artistAlbums.size > 1) "Discography (${artistAlbums.size})" else "Albums & EPs"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artistAlbums, key = { "art_alb_${it.id}" }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }
                }
            }
        }

        // Singles & EPs
        if (artistSingles.isNotEmpty()) {
            item(key = "header_singles") {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Releases",
                    title = if (artistSingles.size > 1) "Singles & EPs (${artistSingles.size})" else "Singles & EPs"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artistSingles, key = { "art_sng_${it.id}" }) { single ->
                        AlbumCard(
                            album = single,
                            onClick = { onAlbumClick(single) }
                        )
                    }
                }
            }
        }

        // Music Videos
        if (artistVideos.isNotEmpty()) {
            item(key = "header_videos") {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Visuals",
                    title = if (artistVideos.size > 1) "Music Videos (${artistVideos.size})" else "Music Videos"
                )
            }
            items(artistVideos, key = { "art_vid_${it.id}" }) { videoTrack ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                ) {
                    SongRow(
                        track = videoTrack,
                        onTrackClick = { onTrackClick(videoTrack, artistVideos) },
                        isCurrentPlaying = videoTrack.id == currentPlayingTrack?.id,
                        isLiked = likedTrackIds.contains(videoTrack.id),
                        onLikeToggle = { onLikeToggle(videoTrack.id) },
                        onMoreClick = { onTrackOptionsClick(videoTrack) },
                        onArtistClick = null,
                        onAlbumClick = null
                    )
                }
            }
        }

        // Related Artists
        if (relatedArtists.isNotEmpty()) {
            item(key = "header_related_artists") {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Resonant Creators",
                    title = "Fans Also Like"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(relatedArtists, key = { "art_rel_${it.id}" }) { related ->
                        ArtistCard(
                            artist = related,
                            onClick = { onRelatedArtistClick(related) }
                        )
                    }
                }
            }
        }
    }
}
