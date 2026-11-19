package com.example.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.R
import com.example.model.Album
import com.example.model.Artist
import com.example.model.QuickMix
import com.example.model.Track
import com.example.model.VisualQuality
import com.example.ui.components.AlbumCard
import com.example.ui.components.ArtistCard
import com.example.ui.components.GlassButton
import com.example.ui.components.GlassButtonVariant
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassIconButton
import com.example.ui.components.LoadingSkeleton
import com.example.ui.components.MiniAudioEqualizer
import com.example.ui.components.SectionHeader
import com.example.ui.components.SongRow
import com.example.ui.theme.FlinsCyan
import com.example.ui.theme.FlinsDarkBg
import com.example.ui.theme.FlinsTheme
import com.example.ui.theme.FlinsViolet
import java.util.Calendar

@Composable
fun HomeScreen(
    userName: String,
    visualQuality: VisualQuality,
    tracks: List<Track>,
    artists: List<Artist>,
    albums: List<Album>,
    quickMixes: List<QuickMix>,
    selectedQuickMix: String?,
    likedTrackIds: Set<String>,
    recentlyPlayed: List<Track> = emptyList(),
    currentPlayingTrack: Track?,
    isPlaying: Boolean = false,
    onTrackClick: (Track, List<Track>) -> Unit,
    onLikeToggle: (String) -> Unit,
    onTrackOptionsClick: (Track) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onAlbumClick: (Album) -> Unit,
    onEditNameClick: () -> Unit,
    onQuickMixToggle: (String) -> Unit,
    onCycleVisualQuality: () -> Unit,
    onOpenSettingsClick: () -> Unit = {},
    onNavigateToLibrary: () -> Unit = {},
    greetingText: String? = null,
    modifier: Modifier = Modifier
) {
    // Personalized greeting
    val effectiveGreeting = remember(greetingText, userName) {
        greetingText?.takeIf { it.isNotBlank() } ?: run {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeGreeting = when (hour) {
                in 5..11 -> "Good Morning"
                in 12..16 -> "Good Afternoon"
                in 17..20 -> "Good Evening"
                else -> "Good Night"
            }
            val effectiveName = userName.trim().ifBlank { "Gaurav" }
            "$timeGreeting, $effectiveName"
        }
    }

    // Quick mix filtered tracks
    val activeFilteredTracks = remember(selectedQuickMix, tracks) {
        if (selectedQuickMix == null) tracks else {
            val mix = quickMixes.find { it.id == selectedQuickMix }
            if (mix != null) {
                tracks.filter {
                    it.mood.equals(mix.filterKeyword, ignoreCase = true) ||
                    it.genre.contains(mix.filterKeyword, ignoreCase = true)
                }
            } else tracks
        }
    }

    // Hero discovery track
    val featuredTrack = activeFilteredTracks.firstOrNull() ?: tracks.firstOrNull()

    // Recommended tracks carousel (remembered at composable scope)
    val recommendedCarouselTracks = remember(activeFilteredTracks) {
        activeFilteredTracks.drop(1).take(8)
    }

    // Recently played / rotation list (remembered at composable scope)
    val displayRecent = remember(recentlyPlayed, activeFilteredTracks) {
        if (recentlyPlayed.isNotEmpty()) recentlyPlayed.take(6) else activeFilteredTracks.take(5)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 150.dp) // Clearance for floating mini-player & bottom nav
    ) {
        // 1. Top Bar: Anyfy Branding, Quality Pill, Profile Avatar, Settings Access
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Anyfy Official Logo & Gradient Brand Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF555555),
                                        Color(0xFF282828)
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.anyfy_logo),
                            contentDescription = "Anyfy Logo",
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Text(
                        text = "Anyfy",
                        style = FlinsTheme.typography.title.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFF0F0F0),
                                    Color(0xFFBDBDBD)
                                )
                            )
                        )
                    )
                }

                // Controls: Visual Quality Pill + Profile Avatar + Settings Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Visual Quality Pill
                    Box(
                        modifier = Modifier
                            .testTag("visual_quality_pill")
                            .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                            .background(Color(0xFF202020))
                            .border(1.dp, Color(0xFF383838), RoundedCornerShape(FlinsTheme.radii.pill))
                            .clickable(onClick = onCycleVisualQuality)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Visual quality",
                                tint = Color(0xFFC0C0C0),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = visualQuality.label,
                                style = FlinsTheme.typography.metadata,
                                color = FlinsTheme.colors.textSecondary
                            )
                        }
                    }

                    // Profile Avatar Button (Tapping opens edit name sheet)
                    Box(
                        modifier = Modifier
                            .testTag("edit_name_trigger")
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF282828), Color(0xFF202020))
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                brush = Brush.sweepGradient(
                                    listOf(Color(0xFF383838), Color(0xFFBDBDBD), Color(0xFF383838))
                                ),
                                shape = CircleShape
                            )
                            .clickable(onClick = onEditNameClick),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = userName.trim().firstOrNull()?.uppercase() ?: "A"
                        Text(
                            text = initial,
                            style = FlinsTheme.typography.label.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = Color.White
                        )
                    }

                    // Settings Button
                    GlassIconButton(
                        onClick = onOpenSettingsClick,
                        size = 36.dp,
                        testTag = "home_settings_button"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = FlinsCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 2. Personalized Greeting & Subtitle
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = effectiveGreeting,
                        style = FlinsTheme.typography.hero.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = FlinsTheme.colors.textPrimary
                    )
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit name",
                        tint = FlinsCyan.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(onClick = onEditNameClick)
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = "Your living audio sanctuary • Ready to immerse",
                    style = FlinsTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = FlinsTheme.colors.textMuted
                )
            }
        }

        // 3. Quick Access Tiles (Library & Recently Played shortcuts)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Quick Tile 1: Liked Songs
                    QuickAccessTile(
                        title = "Liked Songs",
                        subtitle = "${likedTrackIds.size} tracks",
                        icon = Icons.Filled.Favorite,
                        iconTint = Color(0xFFF0F0F0),
                        iconBg = Brush.linearGradient(listOf(Color(0xFF454545), Color(0xFF282828))),
                        onClick = onNavigateToLibrary,
                        modifier = Modifier.weight(1f)
                    )

                    // Quick Tile 2: Your Library
                    QuickAccessTile(
                        title = "Your Library",
                        subtitle = "Playlists & Albums",
                        icon = Icons.Filled.LibraryMusic,
                        iconTint = Color(0xFFF0F0F0),
                        iconBg = Brush.linearGradient(listOf(Color(0xFF454545), Color(0xFF282828))),
                        onClick = onNavigateToLibrary,
                        modifier = Modifier.weight(1f)
                    )
                }

                val recent1 = recentlyPlayed.getOrNull(0) ?: tracks.getOrNull(0)
                val recent2 = recentlyPlayed.getOrNull(1) ?: tracks.getOrNull(1)

                if (recent1 != null || recent2 != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (recent1 != null) {
                            QuickAccessTrackTile(
                                track = recent1,
                                isPlaying = recent1.id == currentPlayingTrack?.id && isPlaying,
                                isCurrentTrack = recent1.id == currentPlayingTrack?.id,
                                onClick = { onTrackClick(recent1, listOf(recent1)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (recent2 != null) {
                            QuickAccessTrackTile(
                                track = recent2,
                                isPlaying = recent2.id == currentPlayingTrack?.id && isPlaying,
                                isCurrentTrack = recent2.id == currentPlayingTrack?.id,
                                onClick = { onTrackClick(recent2, listOf(recent2)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 4. Quick Mixes Chips (Mood & Genre Filters)
        if (quickMixes.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "VIBES & QUICK MIXES",
                        style = FlinsTheme.typography.overline,
                        color = FlinsTheme.colors.textMuted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(quickMixes, key = { it.id }) { mix ->
                            val isSelected = mix.id == selectedQuickMix
                            val chipBg = if (isSelected) Color(0xFF454545) else Color(0xFF202020)
                            val chipBorder = if (isSelected) Color(0xFFBDBDBD) else Color(0xFF383838)
                            val textColor = if (isSelected) Color(0xFFF0F0F0) else FlinsTheme.colors.textSecondary

                            Box(
                                modifier = Modifier
                                    .testTag("quick_mix_${mix.id}")
                                    .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                    .background(chipBg)
                                    .border(1.dp, chipBorder, RoundedCornerShape(FlinsTheme.radii.pill))
                                    .clickable { onQuickMixToggle(mix.id) }
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = mix.name,
                                    style = FlinsTheme.typography.label,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Featured Music Discovery Hero
        item {
            if (featuredTrack != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        backgroundColor = Color(0xFF202020),
                        borderColor = Color(0xFF383838),
                        onClick = { onTrackClick(featuredTrack, listOf(featuredTrack)) }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val heroHierarchy = remember(featuredTrack) {
                                featuredTrack.resolveBannerHierarchy(null)
                            }
                            val primaryBannerUrl = heroHierarchy.firstOrNull()?.url
                                ?: featuredTrack.displayBannerArtwork.ifBlank { featuredTrack.displayArtwork.ifBlank { featuredTrack.artwork } }
                            val fallbackBannerUrl = heroHierarchy.getOrNull(1)?.url
                                ?: featuredTrack.cleanVideoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                                ?: featuredTrack.displayArtwork

                            // High-res Artwork with ambient dark gradient overlay
                            SubcomposeAsyncImage(
                                model = primaryBannerUrl,
                                contentDescription = featuredTrack.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(215.dp),
                                contentScale = ContentScale.Crop,
                                error = {
                                    if (!fallbackBannerUrl.isNullOrBlank() && fallbackBannerUrl != primaryBannerUrl) {
                                        SubcomposeAsyncImage(
                                            model = fallbackBannerUrl,
                                            contentDescription = featuredTrack.title,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(215.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(215.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.Transparent,
                                                FlinsDarkBg.copy(alpha = 0.50f),
                                                FlinsDarkBg.copy(alpha = 0.98f)
                                            )
                                        )
                                    )
                            )

                            // Content overlay
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(20.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(FlinsTheme.radii.pill))
                                        .background(FlinsCyan.copy(alpha = 0.22f))
                                        .border(1.dp, FlinsCyan.copy(alpha = 0.5f), RoundedCornerShape(FlinsTheme.radii.pill))
                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = FlinsCyan,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "FEATURED DISCOVERY",
                                            style = FlinsTheme.typography.overline,
                                            color = FlinsCyan
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = featuredTrack.title,
                                    style = FlinsTheme.typography.title,
                                    color = FlinsTheme.colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${featuredTrack.artist} • ${featuredTrack.genre.ifBlank { featuredTrack.album }}",
                                    style = FlinsTheme.typography.bodyMedium,
                                    color = FlinsTheme.colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                // Play & More Controls
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GlassButton(
                                        onClick = { onTrackClick(featuredTrack, listOf(featuredTrack)) },
                                        variant = GlassButtonVariant.PRIMARY,
                                        testTag = "featured_play_button"
                                    ) {
                                        if (featuredTrack.id == currentPlayingTrack?.id && isPlaying) {
                                            MiniAudioEqualizer(color = FlinsDarkBg, isPlaying = true)
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Playing",
                                                style = FlinsTheme.typography.button,
                                                color = FlinsDarkBg
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = FlinsDarkBg,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Listen Now",
                                                style = FlinsTheme.typography.button,
                                                color = FlinsDarkBg
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    GlassIconButton(
                                        onClick = { onTrackOptionsClick(featuredTrack) },
                                        size = 46.dp,
                                        testTag = "featured_more_button"
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "More Options for ${featuredTrack.title}",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tracks.isEmpty()) {
                // Graceful loading skeleton while tracks are loading
                LoadingSkeleton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    itemCount = 3
                )
            }
        }

        // 6. Recommended Tracks Horizontal Carousel
        if (recommendedCarouselTracks.isNotEmpty()) {
            item(key = "recommended_carousel_section") {
                Spacer(modifier = Modifier.height(12.dp))
                SectionHeader(
                    overline = "Curated For You",
                    title = "Recommended Music"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(recommendedCarouselTracks, key = { it.id }) { track ->
                        RecommendedTrackCard(
                            track = track,
                            isCurrentPlaying = track.id == currentPlayingTrack?.id,
                            onClick = { onTrackClick(track, recommendedCarouselTracks) }
                        )
                    }
                }
            }
        }

        // 7. Recently Played / Rotation (Compact List)
        if (displayRecent.isNotEmpty()) {
            item(key = "recently_played_header") {
                Spacer(modifier = Modifier.height(18.dp))
                SectionHeader(
                    overline = if (recentlyPlayed.isNotEmpty()) "Your Rotation" else "Trending Now",
                    title = if (recentlyPlayed.isNotEmpty()) "Recently Played" else "Top Hits"
                )
            }
            items(displayRecent, key = { "recent_${it.id}" }) { track ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 3.dp)
                ) {
                    SongRow(
                        track = track,
                        onTrackClick = { onTrackClick(track, displayRecent) },
                        isCurrentPlaying = track.id == currentPlayingTrack?.id,
                        isLiked = likedTrackIds.contains(track.id),
                        onLikeToggle = { onLikeToggle(track.id) },
                        onMoreClick = { onTrackOptionsClick(track) },
                        onArtistClick = { artistName ->
                            val foundArtist = artists.find { it.id == track.artistId || it.name.equals(artistName, ignoreCase = true) }
                                ?: Artist(id = if (track.artistId.isNotBlank() && track.artistId != "art_1") track.artistId else "art_${artistName.lowercase().replace(" ", "_")}", name = artistName, artwork = track.artwork)
                            onArtistClick(foundArtist)
                        },
                        onAlbumClick = { albumTitle ->
                            val foundAlbum = albums.find {
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
                            onAlbumClick(foundAlbum)
                        }
                    )
                }
            }
        }

        // 8. Recommended Albums & EPs
        if (albums.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Atmospheric Collections",
                    title = "Albums & EPs"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(
                            album = album,
                            onClick = { onAlbumClick(album) }
                        )
                    }
                }
            }
        }

        // 9. Popular Artists (Sound Sculptors)
        if (artists.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(
                    overline = "Sound Sculptors",
                    title = "Popular Artists"
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(artists, key = { it.id }) { artist ->
                        ArtistCard(
                            artist = artist,
                            onClick = { onArtistClick(artist) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modern Quick Access tile for library shortcuts (Liked Songs, Your Library)
 */
@Composable
private fun QuickAccessTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    iconBg: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(Color(0xFF202020))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                style = FlinsTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = FlinsTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = FlinsTheme.typography.metadata.copy(fontSize = 11.sp),
                color = FlinsTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Modern Quick Access tile for recently played songs
 */
@Composable
private fun QuickAccessTrackTile(
    track: Track,
    isPlaying: Boolean,
    isCurrentTrack: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(Color(0xFF202020))
            .border(
                width = 1.dp,
                brush = if (isCurrentTrack) {
                    Brush.linearGradient(listOf(Color(0xFFBDBDBD), Color(0xFF454545)))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF383838), Color(0xFF282828)))
                },
                shape = shape
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF202020))
        ) {
            val tileArt = track.displayArtwork.ifBlank { track.artwork }
            val cleanVid = track.cleanVideoId
            val tileYtFallback = if (cleanVid != null) "https://i.ytimg.com/vi/$cleanVid/hqdefault.jpg" else ""

            SubcomposeAsyncImage(
                model = tileArt.ifBlank { tileYtFallback },
                contentDescription = track.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = {
                    if (tileYtFallback.isNotBlank() && tileYtFallback != tileArt) {
                        SubcomposeAsyncImage(
                            model = tileYtFallback,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            )
            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center
                ) {
                    MiniAudioEqualizer(color = Color(0xFFBDBDBD), isPlaying = isPlaying)
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = track.title,
                style = FlinsTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = if (isCurrentTrack) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                style = FlinsTheme.typography.metadata.copy(fontSize = 11.sp),
                color = FlinsTheme.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Card for recommended music carousel
 */
@Composable
private fun RecommendedTrackCard(
    track: Track,
    isCurrentPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .testTag("recommended_track_${track.id}")
            .width(148.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(cardShape)
                .background(Color(0xFF202020))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = if (isCurrentPlaying) {
                            listOf(Color(0xFFBDBDBD), Color(0xFF454545))
                        } else {
                            listOf(Color(0xFF383838), Color(0xFF282828))
                        }
                    ),
                    shape = cardShape
                )
                .background(Color(0xFF202020))
        ) {
            val cardArt = track.displayArtwork.ifBlank { track.artwork }
            val cardVid = track.cleanVideoId
            val cardFallback = if (cardVid != null) "https://i.ytimg.com/vi/$cardVid/hqdefault.jpg" else ""

            SubcomposeAsyncImage(
                model = cardArt.ifBlank { cardFallback },
                contentDescription = "${track.title} artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = {
                    if (cardFallback.isNotBlank() && cardFallback != cardArt) {
                        SubcomposeAsyncImage(
                            model = cardFallback,
                            contentDescription = "${track.title} artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            )

            // Genre or Mood badge
            val tagText = track.genre.ifBlank { track.mood }
            if (tagText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xD9101010))
                        .border(0.5.dp, Color(0xFF383838), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = tagText,
                        style = FlinsTheme.typography.metadata.copy(fontSize = 10.sp),
                        color = Color(0xFFBDBDBD),
                        maxLines = 1
                    )
                }
            }

            // Play or Equalizer Floating Action Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (isCurrentPlaying) Color(0xFFF0F0F0) else Color(0x99101010))
                    .border(
                        1.dp,
                        if (isCurrentPlaying) Color(0xFFF0F0F0) else Color(0xFF383838),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentPlaying) {
                    MiniAudioEqualizer(color = Color(0xFF101010), isPlaying = true)
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color(0xFFF0F0F0),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            style = FlinsTheme.typography.titleMedium,
            color = if (isCurrentPlaying) Color(0xFFF0F0F0) else FlinsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = track.artist,
            style = FlinsTheme.typography.bodyMedium,
            color = FlinsTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
