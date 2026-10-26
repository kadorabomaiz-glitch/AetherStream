package com.aether.stream.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aether.stream.core.navigation.LiveTvLayoutMode
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.EpgProgramEntity
import com.aether.stream.data.local.FavoriteFolderEntity
import com.aether.stream.data.local.StreamItemEntity
import kotlinx.coroutines.delay

private fun formatMillisToTimecode(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val minStr = minutes.toString().padStart(2, '0')
    val secStr = seconds.toString().padStart(2, '0')
    return if (hours > 0L) "$hours:$minStr:$secStr" else "$minStr:$secStr"
}

@Composable
fun LiveTvDualCockpitScreen(
    playerEngine: AetherVideoPlayerEngine,
    layoutMode: LiveTvLayoutMode,
    currentBufferPreset: StreamBufferPreset,
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    streamsInCategory: List<StreamItemEntity>,
    currentPlayingStream: StreamItemEntity?,
    currentChannelEpg: List<EpgProgramEntity>,
    activeSection: AetherSectionType = AetherSectionType.LIVE,
    onSelectSection: (AetherSectionType) -> Unit = {},
    onSelectCategory: (String) -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit,
    onQuickFavoriteToggle: (StreamItemEntity) -> Unit,
    onOpenFavoriteFolderDialog: (StreamItemEntity) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onToggleLayoutMode: () -> Unit,
    onCycleBufferPreset: () -> Unit,
    onZapChannelOffset: (Int) -> Unit,
    onBackToDashboard: () -> Unit,
    startInPureFullscreen: Boolean = false,
    isVodOrSeriesMode: Boolean = false
) {
    var isExpandedToPureFullscreen by remember(startInPureFullscreen) { mutableStateOf(startInPureFullscreen) }
    var isFullscreenHudVisible by remember { mutableStateOf(true) }
    var hudActivityTick by remember { mutableStateOf(0) }
    var isTrackSelectorVisible by remember { mutableStateOf(false) }
    var focusedStreamItem by remember { mutableStateOf<StreamItemEntity?>(null) }

    val techSpecs by playerEngine.techMetadata.collectAsState()
    val audioTracks by playerEngine.audioTracks.collectAsState()
    val subtitleTracks by playerEngine.subtitleTracks.collectAsState()
    val currentPosMs by playerEngine.currentPositionMs.collectAsState()
    val durationMs by playerEngine.durationMs.collectAsState()
    val isPlaying by playerEngine.isPlaying.collectAsState()

    val displayStream = focusedStreamItem ?: currentPlayingStream ?: streamsInCategory.firstOrNull()

    LaunchedEffect(isExpandedToPureFullscreen, isFullscreenHudVisible, hudActivityTick, isTrackSelectorVisible) {
        if (isExpandedToPureFullscreen && isFullscreenHudVisible && !isTrackSelectorVisible) {
            delay(4500L)
            isFullscreenHudVisible = false
        }
    }

    val handleItemClick: (StreamItemEntity) -> Unit = { clickedStream ->
        if (currentPlayingStream?.uniqueStreamKey == clickedStream.uniqueStreamKey) {
            isExpandedToPureFullscreen = true
            isFullscreenHudVisible = true
            hudActivityTick++
        } else {
            onSelectStream(clickedStream)
            playerEngine.playStream(clickedStream.streamUrl)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && isExpandedToPureFullscreen) {
                    hudActivityTick++
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (!isFullscreenHudVisible) {
                                isFullscreenHudVisible = true
                                true
                            } else false
                        }
                        Key.DirectionLeft -> {
                            if (!isFullscreenHudVisible && isVodOrSeriesMode) {
                                playerEngine.seekRelativeMs(-15_000L)
                                isFullscreenHudVisible = true
                                true
                            } else false
                        }
                        Key.DirectionRight -> {
                            if (!isFullscreenHudVisible && isVodOrSeriesMode) {
                                playerEngine.seekRelativeMs(15_000L)
                                isFullscreenHudVisible = true
                                true
                            } else false
                        }
                        Key.DirectionUp, Key.ChannelUp -> {
                            if (!isVodOrSeriesMode) {
                                onZapChannelOffset(-1)
                                isFullscreenHudVisible = true
                                true
                            } else false
                        }
                        Key.DirectionDown, Key.ChannelDown -> {
                            if (!isVodOrSeriesMode) {
                                onZapChannelOffset(1)
                                isFullscreenHudVisible = true
                                true
                            } else false
                        }
                        Key.Back, Key.Escape -> {
                            if (isTrackSelectorVisible) {
                                isTrackSelectorVisible = false
                                true
                            } else if (isVodOrSeriesMode) {
                                onBackToDashboard()
                                true
                            } else {
                                isExpandedToPureFullscreen = false
                                true
                            }
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        if (isExpandedToPureFullscreen) {
            AetherVideoSurface(
                engine = playerEngine,
                modifier = Modifier.fillMaxSize()
            )

            if (isFullscreenHudVisible) {
                FullscreenPlayerHudOverlay(
                    stream = currentPlayingStream,
                    techSpecs = techSpecs,
                    currentBufferPreset = currentBufferPreset,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    isTrackSelectorOpen = isTrackSelectorVisible,
                    isVodOrSeriesMode = isVodOrSeriesMode,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPosMs,
                    durationMs = durationMs,
                    onTogglePlayPause = {
                        hudActivityTick++
                        if (isPlaying) playerEngine.pause() else playerEngine.resume()
                    },
                    onSeekRelative = { offsetMs ->
                        hudActivityTick++
                        playerEngine.seekRelativeMs(offsetMs)
                    },
                    onToggleTrackSelector = {
                        hudActivityTick++
                        isTrackSelectorVisible = !isTrackSelectorVisible
                    },
                    onSelectAudio = {
                        hudActivityTick++
                        playerEngine.selectAudioTrack(it)
                    },
                    onSelectSubtitle = {
                        hudActivityTick++
                        playerEngine.selectSubtitleTrack(it)
                    },
                    onCycleBuffer = {
                        hudActivityTick++
                        onCycleBufferPreset()
                    },
                    onZapPrevious = {
                        hudActivityTick++
                        onZapChannelOffset(-1)
                    },
                    onZapNext = {
                        hudActivityTick++
                        onZapChannelOffset(1)
                    },
                    onExitFullscreen = {
                        if (isVodOrSeriesMode) {
                            onBackToDashboard()
                        } else {
                            isExpandedToPureFullscreen = false
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        } else {
            // ============================================================
            // TIVIMATE COCKPIT LAYOUT (60 FPS FLÜSSIG OHNE RECOMPOSITION-LOOP)
            // ============================================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // SPALTE 1: TiviMate Icon-Sidebar (ganz links)
                TiviMateNavigationRail(
                    activeSection = activeSection,
                    onSelectSection = onSelectSection,
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings
                )

                // SPALTE 2: Gruppen / Kategorien
                TiviMateCategoryColumn(
                    categories = categories,
                    favoriteFolders = favoriteFolders,
                    selectedCategoryKey = selectedCategoryKey,
                    onSelectCategory = onSelectCategory,
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                )

                // RECHTE BÜHNE: Oben Mini-Player & Info / Unten Medienliste
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // OBERE BÜHNE: Mini-Player (links) + Details (rechts daneben)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AetherFocusableGlassCard(
                            onClick = {
                                if (currentPlayingStream != null) {
                                    isExpandedToPureFullscreen = true
                                    isFullscreenHudVisible = true
                                    hudActivityTick++
                                }
                            },
                            cornerRadius = 14.dp,
                            modifier = Modifier
                                .width(355.dp)
                                .fillMaxHeight()
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AetherVideoSurface(
                                    engine = playerEngine,
                                    modifier = Modifier.fillMaxSize()
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AetherColors.ObsidianBlack.copy(alpha = 0.85f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "⛶ 1x OK für Vollbild",
                                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                                    )
                                }
                            }
                        }

                        TiviMateDetailsPanel(
                            stream = displayStream,
                            techSpecs = techSpecs,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }

                    // UNTERE MEDIENLISTE: Flüssig mit hardwarenahem onFocusChanged
                    TiviMateMediaListPanel(
                        streams = streamsInCategory,
                        currentPlayingStream = currentPlayingStream,
                        onFocusStream = { focusedStreamItem = it },
                        onStreamClick = handleItemClick,
                        onQuickFavoriteToggle = onQuickFavoriteToggle,
                        onLongPressFavoriteFolder = onOpenFavoriteFolderDialog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TiviMateNavigationRail(
    activeSection: AetherSectionType,
    onSelectSection: (AetherSectionType) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.90f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = "AETH",
                    style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                )
            }

            TiviMateNavIcon(
                icon = "🔍",
                isSelected = false,
                onClick = onOpenSearch
            )

            TiviMateNavIcon(
                icon = "📺",
                isSelected = activeSection == AetherSectionType.LIVE,
                onClick = { onSelectSection(AetherSectionType.LIVE) }
            )

            TiviMateNavIcon(
                icon = "🎬",
                isSelected = activeSection == AetherSectionType.VOD,
                onClick = { onSelectSection(AetherSectionType.VOD) }
            )

            TiviMateNavIcon(
                icon = "🍿",
                isSelected = activeSection == AetherSectionType.SERIES,
                onClick = { onSelectSection(AetherSectionType.SERIES) }
            )
        }

        TiviMateNavIcon(
            icon = "⚙️",
            isSelected = false,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun TiviMateNavIcon(
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        isSelected = isSelected,
        cornerRadius = 12.dp,
        enableScaleOnFocus = true,
        modifier = Modifier
            .width(48.dp)
            .height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(text = icon, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TiviMateCategoryColumn(
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.70f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(10.dp)
    ) {
        item(key = "SYS_FAV") {
            TiviMateCategoryRow(
                title = "⭐ Favoriten",
                isSelected = selectedCategoryKey == "SYS_QUICK_FAV",
                onClick = { onSelectCategory("SYS_QUICK_FAV") }
            )
        }

        item(key = "SYS_RECENT") {
            TiviMateCategoryRow(
                title = "🕒 Zuletzt gesehen",
                isSelected = selectedCategoryKey == "SYS_RECENT" || selectedCategoryKey == "SYS_CONTINUE",
                onClick = { onSelectCategory("SYS_RECENT") }
            )
        }

        items(
            items = favoriteFolders,
            key = { "FOLDER_${it.folderId}" }
        ) { folder ->
            val key = "FOLDER_${folder.folderId}"
            TiviMateCategoryRow(
                title = "📁 ${folder.folderName}",
                isSelected = selectedCategoryKey == key,
                onClick = { onSelectCategory(key) }
            )
        }

        items(
            items = categories,
            key = { it.uniqueCategoryKey }
        ) { category ->
            TiviMateCategoryRow(
                title = category.categoryName,
                isSelected = selectedCategoryKey == category.uniqueCategoryKey,
                onClick = { onSelectCategory(category.uniqueCategoryKey) }
            )
        }
    }
}

@Composable
private fun TiviMateCategoryRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        isSelected = isSelected,
        cornerRadius = 8.dp,
        enableScaleOnFocus = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary
            ),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun TiviMateDetailsPanel(
    stream: StreamItemEntity?,
    techSpecs: StreamTechMetadata,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.85f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stream?.name ?: "Kein Eintrag ausgewählt",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
                )

                Text(
                    text = "${techSpecs.resolutionLabel} • ${techSpecs.fpsLabel}",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (!stream?.rating.isNullOrBlank()) "⭐ Bewertung: ${stream?.rating}" else "Bereit zur Wiedergabe",
                style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.LaserAmber)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stream?.plotDescription
                    ?: "Drücke 1x OK auf einen Eintrag zum Abspielen im Vorschaufenster oder 2x OK für Vollbild.",
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.TextSecondary)
            )
        }

        if (stream?.resumePositionMs ?: 0L > 0L && stream?.totalDurationMs ?: 0L > 0L) {
            val progress = (stream!!.resumePositionMs.toFloat() / stream.totalDurationMs.toFloat()).coerceIn(0f, 1f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatMillisToTimecode(stream.resumePositionMs),
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = AetherColors.CyberCyan,
                    trackColor = AetherColors.ObsidianBlack,
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Text(
                    text = formatMillisToTimecode(stream.totalDurationMs),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * L10 Saubere Medienliste: Aktualisiert die Info-Bühne hardwarenah über onFocusChanged,
 * ohne den Main-Thread mit Recomposition-Schleifen zu überlasten!
 */
@Composable
private fun TiviMateMediaListPanel(
    streams: List<StreamItemEntity>,
    currentPlayingStream: StreamItemEntity?,
    onFocusStream: (StreamItemEntity) -> Unit,
    onStreamClick: (StreamItemEntity) -> Unit,
    onQuickFavoriteToggle: (StreamItemEntity) -> Unit,
    onLongPressFavoriteFolder: (StreamItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.70f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(10.dp)
    ) {
        items(
            items = streams,
            key = { it.uniqueStreamKey }
        ) { stream ->
            val isPlaying = currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey

            AetherFocusableGlassCard(
                onClick = { onStreamClick(stream) },
                onLongClick = { onLongPressFavoriteFolder(stream) },
                isSelected = isPlaying,
                cornerRadius = 8.dp,
                enableScaleOnFocus = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            onFocusStream(stream)
                        }
                    }
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "${stream.customChannelNumber}",
                            style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                        )

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AetherColors.ObsidianBlack.copy(alpha = 0.7f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when (stream.sectionType) {
                                    AetherSectionType.VOD -> "VOD"
                                    AetherSectionType.SERIES -> "SERIE"
                                    else -> "TV"
                                },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        if (isPlaying) {
                            Text(
                                text = "▶",
                                style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                            )
                        }

                        Text(
                            text = stream.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = if (isPlaying) AetherColors.CyberCyan else AetherColors.TextPrimary
                            )
                        )
                    }

                    AetherFocusableGlassCard(
                        onClick = { onQuickFavoriteToggle(stream) },
                        cornerRadius = 6.dp,
                        enableScaleOnFocus = false
                    ) {
                        Text(
                            text = if (stream.isQuickFavorite) "★" else "☆",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.TextSecondary
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenPlayerHudOverlay(
    stream: StreamItemEntity?,
    techSpecs: StreamTechMetadata,
    currentBufferPreset: StreamBufferPreset,
    audioTracks: List<TrackOption>,
    subtitleTracks: List<TrackOption>,
    isTrackSelectorOpen: Boolean,
    isVodOrSeriesMode: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onToggleTrackSelector: () -> Unit,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectSubtitle: (TrackOption?) -> Unit,
    onCycleBuffer: () -> Unit,
    onZapPrevious: () -> Unit,
    onZapNext: () -> Unit,
    onExitFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AetherColors.ObsidianBlack.copy(alpha = 0.90f))
            .border(1.5.dp, AetherColors.CyberCyan, RoundedCornerShape(20.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stream?.name ?: "AETHER STREAM",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.TextPrimary)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (techSpecs.reconnectAttempt > 0) {
                    Text(
                        text = "⚡ RECONNECT (${techSpecs.reconnectAttempt}/5)",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.LaserAmber)
                    )
                }
                Text(
                    text = "${techSpecs.resolutionLabel} • ${techSpecs.fpsLabel} • ${techSpecs.videoCodec} • ${techSpecs.audioCodec}",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                )
            }
        }

        if (isVodOrSeriesMode && durationMs > 0L) {
            Spacer(modifier = Modifier.height(10.dp))
            val progress = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatMillisToTimecode(currentPositionMs),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                )
                LinearProgressIndicator(
                    progress = { progress },
                    color = AetherColors.CyberCyan,
                    trackColor = AetherColors.DeepSpaceNavy,
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Text(
                    text = formatMillisToTimecode(durationMs),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isVodOrSeriesMode) {
                AetherFocusableGlassCard(onClick = onTogglePlayPause, cornerRadius = 10.dp) {
                    Text(
                        text = if (isPlaying) "⏸ PAUSE" else "▶️ PLAY",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
                AetherFocusableGlassCard(onClick = { onSeekRelative(-15_000L) }, cornerRadius = 10.dp) {
                    Text(
                        text = "⏪ -15s",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                AetherFocusableGlassCard(onClick = { onSeekRelative(15_000L) }, cornerRadius = 10.dp) {
                    Text(
                        text = "⏩ +15s",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            } else {
                AetherFocusableGlassCard(onClick = onZapPrevious, cornerRadius = 10.dp) {
                    Text(
                        text = "▲ KANAL -",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                AetherFocusableGlassCard(onClick = onZapNext, cornerRadius = 10.dp) {
                    Text(
                        text = "▼ KANAL +",
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            AetherFocusableGlassCard(onClick = onToggleTrackSelector, cornerRadius = 10.dp) {
                Text(
                    text = "🔊 TON & SUB (${audioTracks.size})",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onCycleBuffer, cornerRadius = 10.dp) {
                Text(
                    text = "🛡️ ${currentBufferPreset.title}",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onExitFullscreen, cornerRadius = 10.dp) {
                Text(
                    text = if (isVodOrSeriesMode) "◄ ZURÜCK" else "⊞ ÜBERSICHT",
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}
