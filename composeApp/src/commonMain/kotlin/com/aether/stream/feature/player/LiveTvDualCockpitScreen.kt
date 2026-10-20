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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.aether.stream.core.navigation.LiveTvLayoutMode
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.EpgProgramEntity
import com.aether.stream.data.local.FavoriteFolderEntity
import com.aether.stream.data.local.StreamItemEntity

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
    onSelectCategory: (String) -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit,
    onQuickFavoriteToggle: (StreamItemEntity) -> Unit,
    onOpenFavoriteFolderDialog: (StreamItemEntity) -> Unit,
    onToggleLayoutMode: () -> Unit,
    onCycleBufferPreset: () -> Unit,
    onZapChannelOffset: (Int) -> Unit,
    onBackToDashboard: () -> Unit
) {
    var isExpandedToPureFullscreen by remember { mutableStateOf(false) }
    var isTrackSelectorVisible by remember { mutableStateOf(false) }
    var isTiviMateOverlayVisible by remember { mutableStateOf(true) }

    val techSpecs by playerEngine.techMetadata.collectAsState()
    val audioTracks by playerEngine.audioTracks.collectAsState()
    val subtitleTracks by playerEngine.subtitleTracks.collectAsState()

    val handleChannelClick: (StreamItemEntity) -> Unit = { clickedStream ->
        if (currentPlayingStream?.uniqueStreamKey == clickedStream.uniqueStreamKey) {
            isExpandedToPureFullscreen = true
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
                if (event.type == KeyEventType.KeyUp &&
                    (isExpandedToPureFullscreen ||
                        (layoutMode == LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD && !isTiviMateOverlayVisible))
                ) {
                    when (event.key) {
                        Key.DirectionUp, Key.ChannelUp -> {
                            onZapChannelOffset(-1)
                            true
                        }
                        Key.DirectionDown, Key.ChannelDown -> {
                            onZapChannelOffset(1)
                            true
                        }
                        Key.Back, Key.Escape -> {
                            if (isExpandedToPureFullscreen) {
                                isExpandedToPureFullscreen = false
                                true
                            } else {
                                isTiviMateOverlayVisible = true
                                true
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        when {
            isExpandedToPureFullscreen ||
                (layoutMode == LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD && !isTiviMateOverlayVisible) -> {

                AetherVideoSurface(
                    engine = playerEngine,
                    modifier = Modifier.fillMaxSize()
                )

                FullscreenPlayerHudOverlay(
                    stream = currentPlayingStream,
                    techSpecs = techSpecs,
                    currentBufferPreset = currentBufferPreset,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    isTrackSelectorOpen = isTrackSelectorVisible,
                    onToggleTrackSelector = { isTrackSelectorVisible = !isTrackSelectorVisible },
                    onSelectAudio = playerEngine::selectAudioTrack,
                    onSelectSubtitle = playerEngine::selectSubtitleTrack,
                    onCycleBuffer = onCycleBufferPreset,
                    onZapPrevious = { onZapChannelOffset(-1) },
                    onZapNext = { onZapChannelOffset(1) },
                    onExitFullscreen = {
                        isExpandedToPureFullscreen = false
                        isTiviMateOverlayVisible = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            layoutMode == LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD -> {
                AetherVideoSurface(
                    engine = playerEngine,
                    modifier = Modifier.fillMaxSize()
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AetherColors.ObsidianBlack.copy(alpha = 0.65f))
                        .padding(24.dp)
                ) {
                    LiveTvTopBar(
                        layoutMode = layoutMode,
                        techSpecs = techSpecs,
                        currentBufferPreset = currentBufferPreset,
                        onToggleLayoutMode = onToggleLayoutMode,
                        onCycleBufferPreset = onCycleBufferPreset,
                        onBackToDashboard = onBackToDashboard
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CategoryColumnList(
                            categories = categories,
                            favoriteFolders = favoriteFolders,
                            selectedCategoryKey = selectedCategoryKey,
                            onSelectCategory = onSelectCategory,
                            modifier = Modifier
                                .width(270.dp)
                                .fillMaxHeight()
                        )

                        ChannelColumnList(
                            streams = streamsInCategory,
                            currentPlayingStream = currentPlayingStream,
                            onChannelClick = { stream ->
                                if (currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey) {
                                    isTiviMateOverlayVisible = false
                                } else {
                                    onSelectStream(stream)
                                    playerEngine.playStream(stream.streamUrl)
                                }
                            },
                            onQuickFavoriteToggle = onQuickFavoriteToggle,
                            onLongPressFavoriteFolder = onOpenFavoriteFolderDialog,
                            modifier = Modifier
                                .width(380.dp)
                                .fillMaxHeight()
                        )

                        EpgProgramListPanel(
                            epgList = currentChannelEpg,
                            currentStream = currentPlayingStream,
                            onExpandFullscreen = { isTiviMateOverlayVisible = false },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                ) {
                    LiveTvTopBar(
                        layoutMode = layoutMode,
                        techSpecs = techSpecs,
                        currentBufferPreset = currentBufferPreset,
                        onToggleLayoutMode = onToggleLayoutMode,
                        onCycleBufferPreset = onCycleBufferPreset,
                        onBackToDashboard = onBackToDashboard
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CategoryColumnList(
                            categories = categories,
                            favoriteFolders = favoriteFolders,
                            selectedCategoryKey = selectedCategoryKey,
                            onSelectCategory = onSelectCategory,
                            modifier = Modifier
                                .width(260.dp)
                                .fillMaxHeight()
                        )

                        ChannelColumnList(
                            streams = streamsInCategory,
                            currentPlayingStream = currentPlayingStream,
                            onChannelClick = handleChannelClick,
                            onQuickFavoriteToggle = onQuickFavoriteToggle,
                            onLongPressFavoriteFolder = onOpenFavoriteFolderDialog,
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                        )

                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            AetherFocusableGlassCard(
                                onClick = { isExpandedToPureFullscreen = true },
                                cornerRadius = 18.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AetherVideoSurface(
                                        engine = playerEngine,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(10.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AetherColors.ObsidianBlack.copy(alpha = 0.8f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${techSpecs.resolutionLabel} • ${techSpecs.fpsLabel} • ${techSpecs.audioCodec}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(AetherColors.ObsidianBlack.copy(alpha = 0.8f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "⛶ Noch 1x OK für Vollbild",
                                            style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue)
                                        )
                                    }
                                }
                            }

                            EpgProgramListPanel(
                                epgList = currentChannelEpg,
                                currentStream = currentPlayingStream,
                                onExpandFullscreen = { isExpandedToPureFullscreen = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvTopBar(
    layoutMode: LiveTvLayoutMode,
    techSpecs: StreamTechMetadata,
    currentBufferPreset: StreamBufferPreset,
    onToggleLayoutMode: () -> Unit,
    onCycleBufferPreset: () -> Unit,
    onBackToDashboard: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherFocusableGlassCard(onClick = onBackToDashboard, cornerRadius = 10.dp) {
                Text(
                    text = "◄ DASHBOARD",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            Text(
                text = "AETHER LIVE TV",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherFocusableGlassCard(onClick = onCycleBufferPreset, cornerRadius = 10.dp) {
                Text(
                    text = "🛡️ Puffer: ${currentBufferPreset.title}",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            AetherFocusableGlassCard(onClick = onToggleLayoutMode, cornerRadius = 10.dp) {
                val modeText = when (layoutMode) {
                    LiveTvLayoutMode.IBO_COCKPIT_3_COLUMN -> "⊞ ANSICHT: IBO 3-SPALTEN (Wechseln zu TiviMate)"
                    LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD -> "📺 ANSICHT: TIVIMATE HUD (Wechseln zu IBO)"
                }
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun CategoryColumnList(
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AetherColors.GlassSurface)
            .padding(12.dp)
    ) {
        item(key = "SYS_RECENT") {
            CategoryItemButton(
                title = "🕒 Zuletzt gesehen",
                isSelected = selectedCategoryKey == "SYS_RECENT",
                onClick = { onSelectCategory("SYS_RECENT") }
            )
        }
        item(key = "SYS_QUICK_FAV") {
            CategoryItemButton(
                title = "⭐ Schnell-Favoriten",
                isSelected = selectedCategoryKey == "SYS_QUICK_FAV",
                onClick = { onSelectCategory("SYS_QUICK_FAV") }
            )
        }

        items(
            items = favoriteFolders,
            key = { "FOLDER_${it.folderId}" }
        ) { folder ->
            val key = "FOLDER_${folder.folderId}"
            CategoryItemButton(
                title = "📁 ${folder.folderName}",
                isSelected = selectedCategoryKey == key,
                onClick = { onSelectCategory(key) }
            )
        }

        items(
            items = categories,
            key = { it.uniqueCategoryKey }
        ) { category ->
            CategoryItemButton(
                title = category.categoryName,
                isSelected = selectedCategoryKey == category.uniqueCategoryKey,
                onClick = { onSelectCategory(category.uniqueCategoryKey) }
            )
        }
    }
}

@Composable
private fun CategoryItemButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        isSelected = isSelected,
        cornerRadius = 10.dp,
        enableScaleOnFocus = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ChannelColumnList(
    streams: List<StreamItemEntity>,
    currentPlayingStream: StreamItemEntity?,
    onChannelClick: (StreamItemEntity) -> Unit,
    onQuickFavoriteToggle: (StreamItemEntity) -> Unit,
    onLongPressFavoriteFolder: (StreamItemEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AetherColors.GlassSurface)
            .padding(12.dp)
    ) {
        items(
            items = streams,
            key = { it.uniqueStreamKey }
        ) { stream ->
            val isPlaying = currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey

            AetherFocusableGlassCard(
                onClick = { onChannelClick(stream) },
                onLongClick = { onLongPressFavoriteFolder(stream) },
                isSelected = isPlaying,
                cornerRadius = 10.dp,
                enableScaleOnFocus = false,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
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
                        Text(
                            text = stream.name,
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
private fun EpgProgramListPanel(
    epgList: List<EpgProgramEntity>,
    currentStream: StreamItemEntity?,
    onExpandFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AetherColors.GlassSurface)
            .padding(16.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = currentStream?.name ?: "Kein Sender ausgewählt",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
            )
            AetherFocusableGlassCard(onClick = onExpandFullscreen, cornerRadius = 8.dp) {
                Text(
                    text = "⛶ VOLLBILD",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (epgList.isEmpty()) {
            Text(
                text = "Für diesen Kanal liegen aktuell keine EPG-Programmdaten vor. (Tipp: Halte OK auf einem Sender gedrückt, um ihn einem eigenen Favoriten-Ordner zuzuweisen).",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(epgList) { program ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(AetherColors.ObsidianBlack)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.TextPrimary)
                        )
                        if (!program.description.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
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
            .padding(24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AetherColors.GlassSurface)
            .border(1.5.dp, AetherColors.CyberCyan, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stream?.let { "${it.customChannelNumber}. ${it.name}" } ?: "LIVE STREAM",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.TextPrimary)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (techSpecs.reconnectAttempt > 0) {
                    Text(
                        text = "⚡ STREAM-AIRBAG RECONNECT (${techSpecs.reconnectAttempt}/5)",
                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.LaserAmber)
                    )
                }
                Text(
                    text = "${techSpecs.resolutionLabel} • ${techSpecs.fpsLabel} • ${techSpecs.videoCodec} • ${techSpecs.audioCodec}",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherFocusableGlassCard(onClick = onZapPrevious, cornerRadius = 10.dp) {
                Text(
                    text = "▲ KANAL -",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onZapNext, cornerRadius = 10.dp) {
                Text(
                    text = "▼ KANAL +",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onToggleTrackSelector, cornerRadius = 10.dp) {
                Text(
                    text = "🔊 TONSPUR & UNTERTITEL (${audioTracks.size})",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.CyberCyan),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onCycleBuffer, cornerRadius = 10.dp) {
                Text(
                    text = "🛡️ ${currentBufferPreset.title}",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
            AetherFocusableGlassCard(onClick = onExitFullscreen, cornerRadius = 10.dp) {
                Text(
                    text = "⊞ ZURÜCK ZUR SENDERLISTE",
                    style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        if (isTrackSelectorOpen) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "VERFÜGBARE AUDIO-SPUREN:", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    audioTracks.forEach { track ->
                        AetherFocusableGlassCard(
                            onClick = { onSelectAudio(track) },
                            isSelected = track.isSelected,
                            cornerRadius = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = "${if (track.isSelected) "● " else "○ "}${track.languageLabel} (${track.codecLabel})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "UNTERTITEL:", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    AetherFocusableGlassCard(
                        onClick = { onSelectSubtitle(null) },
                        cornerRadius = 8.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            text = "Untertitel deaktivieren",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    subtitleTracks.forEach { sub ->
                        AetherFocusableGlassCard(
                            onClick = { onSelectSubtitle(sub) },
                            isSelected = sub.isSelected,
                            cornerRadius = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = "${if (sub.isSelected) "● " else "○ "}${sub.languageLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}