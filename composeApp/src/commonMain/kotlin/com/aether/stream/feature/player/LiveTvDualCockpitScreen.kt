package com.aether.stream.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.data.local.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SYS_QUICK_FAV = "SYS_QUICK_FAV"
const val SYS_RECENT = "SYS_RECENT"
const val FOLDER_PREFIX = "FOLDER_"

data class CockpitCategoryUiItem(
    val key: String,
    val title: String,
    val iconPrefix: String = "",
    val isSystem: Boolean = false,
    val isNewlyDiscovered: Boolean = false
)

@Composable
fun LiveTvDualCockpitScreen(
    playerEngine: AetherVideoPlayerEngine,
    activeSection: AetherSectionType,
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    streams: List<StreamItemEntity>,
    activeStream: StreamItemEntity?,
    focusedPreviewStream: StreamItemEntity?,
    isFullscreen: Boolean,
    isCinemaPlayerActive: Boolean,
    isSyncing: Boolean,
    syncStatusMessage: String?,
    currentBufferPreset: StreamBufferPreset,
    selectedSeriesForModal: StreamItemEntity?,
    seriesEpisodes: List<SeriesEpisodeEntity>,
    isSeriesLoading: Boolean,
    onSelectSection: (AetherSectionType) -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit,
    onFocusStreamChanged: (StreamItemEntity) -> Unit,
    onToggleQuickFavorite: (StreamItemEntity) -> Unit,
    onEnterFullscreen: () -> Unit,
    onExitFullscreen: () -> Unit,
    onDismissSeriesModal: () -> Unit,
    onPlaySeriesEpisode: (SeriesEpisodeEntity) -> Unit,
    onApplyBufferPreset: (StreamBufferPreset) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    val railFocusRequester = remember { FocusRequester() }
    val activeCategoryFocusRequester = remember { FocusRequester() }
    val mediaListEntryFocusRequester = remember { FocusRequester() }

    var categoryScrubJob by remember { mutableStateOf<Job?>(null) }

    val categoryUiItems = remember(categories, favoriteFolders, activeSection) {
        buildList {
            add(CockpitCategoryUiItem(SYS_QUICK_FAV, "Favoriten", "⭐ ", isSystem = true))
            add(CockpitCategoryUiItem(SYS_RECENT, "Zuletzt gesehen", "🕒 ", isSystem = true))
            favoriteFolders.forEach { folder ->
                add(CockpitCategoryUiItem("$FOLDER_PREFIX${folder.id}", folder.folderName, "📁 ", isSystem = true))
            }
            categories.forEach { cat ->
                add(CockpitCategoryUiItem(cat.categoryId, cat.categoryName, "", false, cat.isNewlyDiscovered))
            }
        }
    }

    val categoryListState = rememberLazyListState()
    val mediaListState = rememberLazyListState()

    LaunchedEffect(activeSection) {
        categoryListState.scrollToItem(0)
        mediaListState.scrollToItem(0)
    }

    LaunchedEffect(selectedCategoryKey) {
        mediaListState.scrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    when {
                        selectedSeriesForModal != null -> {
                            onDismissSeriesModal()
                            true
                        }
                        isFullscreen -> {
                            onExitFullscreen()
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        if (isFullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AetherVideoSurface(playerEngine, Modifier.fillMaxSize())
                FullscreenPlayerHudOverlay(
                    playerEngine = playerEngine,
                    streamTitle = activeStream?.name ?: "Aether Stream",
                    isCinemaMode = isCinemaPlayerActive,
                    currentPreset = currentBufferPreset,
                    onApplyPreset = onApplyBufferPreset,
                    onExitFullscreen = onExitFullscreen
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ZONE 1: Left Navigation Rail
                LeftNavigationRailZone1(
                    activeSection = activeSection,
                    isSyncing = isSyncing,
                    railFocusRequester = railFocusRequester,
                    onNavigateRightToCategories = { runCatching { activeCategoryFocusRequester.requestFocus() } },
                    onSelectSection = { section ->
                        categoryScrubJob?.cancel()
                        onSelectSection(section)
                    },
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings
                )

                // ZONE 2: Obsidian Neon Glass Category Column
                Column(
                    modifier = Modifier
                        .width(250.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                        .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (activeSection) {
                                AetherSectionType.LIVE -> "LIVE TV KATEGORIEN"
                                AetherSectionType.VOD -> "VOD FILM-KATEGORIEN"
                                AetherSectionType.SERIES -> "SERIEN-KATEGORIEN"
                            },
                            color = AetherColors.CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${categories.size}",
                            color = AetherColors.IceBlue.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (!syncStatusMessage.isNullOrBlank()) {
                        Text(
                            text = syncStatusMessage,
                            color = AetherColors.NeonEmerald,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        state = categoryListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(
                            items = categoryUiItems,
                            key = { item -> "${activeSection.name}_${item.key}" }
                        ) { item ->
                            val isSelected = item.key == selectedCategoryKey
                            val itemMod = if (isSelected) Modifier.focusRequester(activeCategoryFocusRequester) else Modifier

                            ObsidianCategoryRowItem(
                                item = item,
                                isSelected = isSelected,
                                modifier = itemMod,
                                onFocusSettled = { focusedKey ->
                                    if (focusedKey != selectedCategoryKey) {
                                        categoryScrubJob?.cancel()
                                        categoryScrubJob = coroutineScope.launch {
                                            delay(120L)
                                            onSelectCategory(focusedKey)
                                        }
                                    }
                                },
                                onInstantCommit = { committedKey ->
                                    categoryScrubJob?.cancel()
                                    if (committedKey != selectedCategoryKey) onSelectCategory(committedKey)
                                },
                                onNavigateLeftToRail = {
                                    categoryScrubJob?.cancel()
                                    runCatching { railFocusRequester.requestFocus() }
                                },
                                onNavigateRightToMediaList = { committedKey ->
                                    categoryScrubJob?.cancel()
                                    if (committedKey != selectedCategoryKey) onSelectCategory(committedKey)
                                    runCatching { mediaListEntryFocusRequester.requestFocus() }
                                }
                            )
                        }
                    }
                }

                // ZONE 3 (TOP) + ZONE 4 (BOTTOM)
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ZONE 3: Mini-Player + Details Panel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.82f))
                            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        var isMiniFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .border(
                                    width = if (isMiniFocused) 2.dp else 1.dp,
                                    color = if (isMiniFocused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .onFocusChanged { isMiniFocused = it.isFocused }
                                .focusable()
                                .clickable { onEnterFullscreen() }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                        onEnterFullscreen()
                                        true
                                    } else false
                                }
                        ) {
                            AetherVideoSurface(playerEngine, Modifier.fillMaxSize())
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AetherColors.ObsidianBlack.copy(alpha = 0.75f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (activeStream != null) "LIVE PREVIEW • OK = VOLLBILD" else "BEREIT",
                                    color = AetherColors.CyberCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        TiviMateDetailsPanel(
                            playerEngine = playerEngine,
                            stream = focusedPreviewStream ?: activeStream,
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        )
                    }

                    // ZONE 4: Media List
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        if (streams.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = when {
                                        isSyncing -> "Synchronisiere Streams im Hintergrund..."
                                        selectedCategoryKey == SYS_QUICK_FAV -> "Noch keine Favoriten markiert (★)."
                                        selectedCategoryKey == SYS_RECENT -> "Noch keine zuletzt gesehenen Einträge."
                                        else -> "Keine Einträge in dieser Kategorie."
                                    },
                                    color = AetherColors.IceBlue.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = mediaListState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                itemsIndexed(
                                    items = streams,
                                    key = { _, stream -> stream.streamKey }
                                ) { index, stream ->
                                    val isPlayingThis = activeStream?.streamKey == stream.streamKey
                                    val entryMod = if (index == 0) Modifier.focusRequester(mediaListEntryFocusRequester) else Modifier

                                    Zone4MediaRowItem(
                                        indexNumber = index + 1,
                                        stream = stream,
                                        isCurrentlyPlaying = isPlayingThis,
                                        modifier = entryMod,
                                        onFocused = { onFocusStreamChanged(stream) },
                                        onClickStream = { onSelectStream(stream) },
                                        onToggleFavorite = { onToggleQuickFavorite(stream) },
                                        onNavigateLeftToCategories = {
                                            runCatching { activeCategoryFocusRequester.requestFocus() }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedSeriesForModal != null) {
            AetherSeriesDetailModal(
                seriesItem = selectedSeriesForModal,
                episodes = seriesEpisodes,
                isLoading = isSeriesLoading,
                onDismiss = onDismissSeriesModal,
                onPlayEpisode = onPlaySeriesEpisode
            )
        }
    }
}

@Composable
private fun LeftNavigationRailZone1(
    activeSection: AetherSectionType,
    isSyncing: Boolean,
    railFocusRequester: FocusRequester,
    onNavigateRightToCategories: () -> Unit,
    onSelectSection: (AetherSectionType) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.90f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AetherColors.CyberCyan.copy(alpha = 0.16f))
                    .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("AETH", color = AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (isSyncing) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(AetherColors.NeonEmerald))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RailIconButton("🔍", "Suche", false, Modifier, onNavigateRightToCategories, onOpenSearch)
            RailIconButton(
                icon = "📺",
                label = "Live",
                isActive = activeSection == AetherSectionType.LIVE,
                modifier = if (activeSection == AetherSectionType.LIVE) Modifier.focusRequester(railFocusRequester) else Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = { onSelectSection(AetherSectionType.LIVE) }
            )
            RailIconButton(
                icon = "🎬",
                label = "VOD",
                isActive = activeSection == AetherSectionType.VOD,
                modifier = if (activeSection == AetherSectionType.VOD) Modifier.focusRequester(railFocusRequester) else Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = { onSelectSection(AetherSectionType.VOD) }
            )
            RailIconButton(
                icon = "🍿",
                label = "Serien",
                isActive = activeSection == AetherSectionType.SERIES,
                modifier = if (activeSection == AetherSectionType.SERIES) Modifier.focusRequester(railFocusRequester) else Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = { onSelectSection(AetherSectionType.SERIES) }
            )
        }

        RailIconButton("⚙️", "Setup", false, Modifier, onNavigateRightToCategories, onOpenSettings)
    }
}

@Composable
private fun RailIconButton(
    icon: String,
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onNavigateRight: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (isFocused) 1.06f else 1.0f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> AetherColors.CyberCyan.copy(alpha = 0.25f)
                    isActive -> AetherColors.DeepSpaceNavy
                    else -> Color.Transparent
                }
            )
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = when {
                    isFocused -> AetherColors.CyberCyan
                    isActive -> AetherColors.CyberCyan.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> { onNavigateRight(); true }
                        Key.DirectionCenter, Key.Enter -> { onClick(); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = icon, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = when {
                isFocused -> Color.White
                isActive -> AetherColors.CyberCyan
                else -> Color(0xFF94A3B8)
            },
            fontSize = 10.sp,
            fontWeight = if (isFocused || isActive) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun ObsidianCategoryRowItem(
    item: CockpitCategoryUiItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onFocusSettled: (String) -> Unit,
    onInstantCommit: (String) -> Unit,
    onNavigateLeftToRail: () -> Unit,
    onNavigateRightToMediaList: (String) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = if (isFocused) 1.02f else 1.0f
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> AetherColors.CyberCyan.copy(alpha = 0.18f)
                    isSelected -> AetherColors.DeepSpaceNavy.copy(alpha = 0.90f)
                    else -> AetherColors.ObsidianBlack.copy(alpha = 0.40f)
                }
            )
            .border(
                width = when {
                    isFocused -> 1.5.dp
                    isSelected -> 1.dp
                    else -> 0.dp
                },
                color = when {
                    isFocused -> AetherColors.CyberCyan
                    isSelected -> AetherColors.CyberCyan.copy(alpha = 0.35f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocusSettled(item.key)
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> { onInstantCommit(item.key); true }
                        Key.DirectionRight -> { onNavigateRightToMediaList(item.key); true }
                        Key.DirectionLeft -> { onNavigateLeftToRail(); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .clickable { onInstantCommit(item.key) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(if (isFocused) 4.dp else 3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    when {
                        isFocused -> AetherColors.CyberCyan
                        isSelected -> AetherColors.NeonEmerald
                        else -> Color.Transparent
                    }
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${item.iconPrefix}${item.title}",
            color = when {
                isFocused -> Color.White
                isSelected -> AetherColors.IceBlue
                else -> Color(0xFF94A3B8)
            },
            fontSize = 13.sp,
            fontWeight = when {
                isFocused -> FontWeight.Bold
                isSelected -> FontWeight.SemiBold
                else -> FontWeight.Normal
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (item.isNewlyDiscovered) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AetherColors.NeonEmerald.copy(alpha = 0.2f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("NEU", color = AetherColors.NeonEmerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TiviMateDetailsPanel(
    playerEngine: AetherVideoPlayerEngine,
    stream: StreamItemEntity?,
    modifier: Modifier = Modifier
) {
    val techMeta by playerEngine.techMetadata.collectAsState()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AetherColors.ObsidianBlack.copy(alpha = 0.55f))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (stream == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Wähle einen Eintrag aus der Liste für Detailinformationen.",
                    color = AetherColors.IceBlue.copy(alpha = 0.65f),
                    fontSize = 13.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stream.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (stream.isFavorite) {
                        Text(
                            text = "★ FAVORIT",
                            color = AetherColors.LaserAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sectionLabel = when (stream.sectionType) {
                        AetherSectionType.LIVE -> "LIVE TV"
                        AetherSectionType.VOD -> "VOD FILM"
                        AetherSectionType.SERIES -> "SERIE"
                    }
                    DetailBadge(sectionLabel, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.CyberCyan)

                    if (!stream.containerExtension.isNullOrBlank()) {
                        DetailBadge(
                            stream.containerExtension.uppercase(),
                            AetherColors.NeonEmerald.copy(alpha = 0.45f),
                            AetherColors.NeonEmerald
                        )
                    }
                    if (techMeta.resolutionLabel.isNotBlank()) {
                        DetailBadge(techMeta.resolutionLabel, AetherColors.IceBlue.copy(alpha = 0.4f), AetherColors.IceBlue)
                    }
                    if (techMeta.videoCodec.isNotBlank()) {
                        DetailBadge(techMeta.videoCodec, AetherColors.CyberCyan.copy(alpha = 0.35f), Color.White)
                    }
                    if (!stream.rating.isNullOrBlank() && stream.rating != "0") {
                        DetailBadge("★ ${stream.rating}", AetherColors.LaserAmber.copy(alpha = 0.5f), AetherColors.LaserAmber)
                    }
                }

                Text(
                    text = stream.plot?.takeIf { it.isNotBlank() }
                        ?: when (stream.sectionType) {
                            AetherSectionType.LIVE -> "Direkter Live-Stream • 1x OK = Mini-Player, 2x OK = Vollbild."
                            AetherSectionType.VOD -> "On-Demand Film • 1x OK startet direkt im Pure Fullscreen (.mkv/.mp4/.ts Fallback)."
                            AetherSectionType.SERIES -> "Serie • 1x OK öffnet die Staffel- und Episodenauswahl."
                        },
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (stream.sectionType != AetherSectionType.LIVE && stream.resumePositionMs > 0L) {
                val progressFraction = if (stream.totalDurationMs > 0L) {
                    (stream.resumePositionMs.toFloat() / stream.totalDurationMs.toFloat()).coerceIn(0.02f, 1f)
                } else 0.15f

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "▶ Fortsetzen bei ${formatMillisToTime(stream.resumePositionMs)}",
                            color = AetherColors.NeonEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (stream.totalDurationMs > 0L) {
                            Text(
                                text = "${(progressFraction * 100).toInt()}% (${formatMillisToTime(stream.totalDurationMs)})",
                                color = AetherColors.IceBlue,
                                fontSize = 11.sp
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progressFraction },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = AetherColors.NeonEmerald,
                        trackColor = AetherColors.DeepSpaceNavy
                    )
                }
            } else {
                Text(
                    text = when (stream.sectionType) {
                        AetherSectionType.VOD -> "Drücke [OK] für direkten Vollbild-Start"
                        AetherSectionType.SERIES -> "Drücke [OK] für Episoden-Auswahl"
                        AetherSectionType.LIVE -> "Drücke [OK] für Wiedergabe / Vollbild"
                    },
                    color = AetherColors.CyberCyan.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DetailBadge(text: String, borderColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(AetherColors.DeepSpaceNavy)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Zone4MediaRowItem(
    indexNumber: Int,
    stream: StreamItemEntity,
    isCurrentlyPlaying: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClickStream: () -> Unit,
    onToggleFavorite: () -> Unit,
    onNavigateLeftToCategories: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isFocused -> AetherColors.CyberCyan.copy(alpha = 0.20f)
                    isCurrentlyPlaying -> AetherColors.NeonEmerald.copy(alpha = 0.14f)
                    else -> AetherColors.ObsidianBlack.copy(alpha = 0.45f)
                }
            )
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = when {
                    isFocused -> AetherColors.CyberCyan
                    isCurrentlyPlaying -> AetherColors.NeonEmerald.copy(alpha = 0.65f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> { onNavigateLeftToCategories(); true }
                        Key.DirectionCenter, Key.Enter -> { onClickStream(); true }
                        Key.Menu, Key.Bookmark -> { onToggleFavorite(); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .clickable { onClickStream() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$indexNumber",
            color = if (isFocused) AetherColors.CyberCyan else Color(0xFF64748B),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(42.dp)
        )

        val badgeText = when (stream.sectionType) {
            AetherSectionType.LIVE -> "TV"
            AetherSectionType.VOD -> "VOD"
            AetherSectionType.SERIES -> "SERIE"
        }
        val badgeColor = when (stream.sectionType) {
            AetherSectionType.LIVE -> AetherColors.CyberCyan
            AetherSectionType.VOD -> AetherColors.NeonEmerald
            AetherSectionType.SERIES -> AetherColors.LaserAmber
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.16f))
                .border(1.dp, badgeColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(text = badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (isCurrentlyPlaying) {
            Text(
                text = "▶",
                color = AetherColors.NeonEmerald,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.name,
                color = when {
                    isFocused -> Color.White
                    isCurrentlyPlaying -> AetherColors.NeonEmerald
                    else -> Color(0xFFE2E8F0)
                },
                fontSize = 14.sp,
                fontWeight = if (isFocused || isCurrentlyPlaying) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (stream.sectionType != AetherSectionType.LIVE && stream.resumePositionMs > 0L) {
                Text(
                    text = "Fortsetzen ab ${formatMillisToTime(stream.resumePositionMs)}",
                    color = AetherColors.NeonEmerald.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (stream.sectionType == AetherSectionType.VOD && !stream.containerExtension.isNullOrBlank()) {
            Text(
                text = ".${stream.containerExtension.lowercase()}",
                color = AetherColors.IceBlue.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onToggleFavorite() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (stream.isFavorite) "★" else "☆",
                color = if (stream.isFavorite) AetherColors.LaserAmber else Color(0xFF64748B),
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun FullscreenPlayerHudOverlay(
    playerEngine: AetherVideoPlayerEngine,
    streamTitle: String,
    isCinemaMode: Boolean,
    currentPreset: StreamBufferPreset,
    onApplyPreset: (StreamBufferPreset) -> Unit,
    onExitFullscreen: () -> Unit
) {
    val currentPosMs by playerEngine.currentPositionMs.collectAsState()
    val durationMs by playerEngine.durationMs.collectAsState()
    val isPlaying by playerEngine.isPlaying.collectAsState()
    val techMeta by playerEngine.techMetadata.collectAsState()
    val audioTracks by playerEngine.audioTracks.collectAsState()
    val subtitleTracks by playerEngine.subtitleTracks.collectAsState()

    var isHudVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    val hudFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { hudFocusRequester.requestFocus() }
    }

    LaunchedEffect(interactionTick, isPlaying, isHudVisible) {
        if (isHudVisible && isPlaying) {
            delay(4500L)
            isHudVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hudFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    interactionTick += 1L
                    if (!isHudVisible) isHudVisible = true

                    when (event.key) {
                        Key.DirectionLeft -> {
                            if (isCinemaMode) playerEngine.seekRelativeMs(-15_000L)
                            true
                        }
                        Key.DirectionRight -> {
                            if (isCinemaMode) playerEngine.seekRelativeMs(15_000L)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                            if (isPlaying) playerEngine.pause() else playerEngine.resume()
                            true
                        }
                        Key.Back, Key.Escape -> {
                            onExitFullscreen()
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                interactionTick += 1L
                isHudVisible = !isHudVisible
            }
    ) {
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                AetherColors.ObsidianBlack.copy(alpha = 0.85f),
                                Color.Transparent,
                                Color.Transparent,
                                AetherColors.ObsidianBlack.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(streamTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isCinemaMode) {
                                "CINEMA FULLSCREEN • [LINKS/RECHTS] ±15s Spulen • [OK] Play/Pause • [ZURÜCK] Cockpit"
                            } else {
                                "LIVE TV FULLSCREEN • [OK] Play/Pause • [ZURÜCK] Mini-Player Cockpit"
                            },
                            color = AetherColors.CyberCyan,
                            fontSize = 12.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (techMeta.resolutionLabel.isNotBlank()) {
                            DetailBadge(techMeta.resolutionLabel, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.CyberCyan)
                        }
                        if (techMeta.videoCodec.isNotBlank()) {
                            DetailBadge(techMeta.videoCodec, AetherColors.NeonEmerald.copy(alpha = 0.45f), AetherColors.NeonEmerald)
                        }
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCinemaMode) {
                        val progress = if (durationMs > 0L) {
                            (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = AetherColors.CyberCyan,
                            trackColor = AetherColors.DeepSpaceNavy
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${if (isPlaying) "▶" else "⏸"} ${formatMillisToTime(currentPosMs)}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (durationMs > 0L) formatMillisToTime(durationMs) else "--:--",
                                color = AetherColors.IceBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StreamBufferPreset.entries.forEach { preset ->
                                val isPresetActive = preset == currentPreset
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isPresetActive) AetherColors.CyberCyan.copy(alpha = 0.22f) else AetherColors.DeepSpaceNavy)
                                        .border(
                                            width = 1.dp,
                                            color = if (isPresetActive) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onApplyPreset(preset) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = preset.name,
                                        color = if (isPresetActive) AetherColors.CyberCyan else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (audioTracks.isNotEmpty()) {
                                val activeAudio = audioTracks.firstOrNull { it.isSelected } ?: audioTracks.first()
                                DetailBadge("🔊 ${activeAudio.label}", AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.IceBlue)
                            }
                            if (subtitleTracks.isNotEmpty()) {
                                val activeSub = subtitleTracks.firstOrNull { it.isSelected }
                                DetailBadge("💬 ${activeSub?.label ?: "Aus"}", AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.IceBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AetherSeriesDetailModal(
    seriesItem: StreamItemEntity,
    episodes: List<SeriesEpisodeEntity>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPlayEpisode: (SeriesEpisodeEntity) -> Unit
) {
    val seasons = remember(episodes) {
        episodes.map { it.seasonNumber }.distinct().sorted()
    }
    var selectedSeason by remember(seasons) {
        mutableIntStateOf(seasons.firstOrNull() ?: 1)
    }
    val seasonEpisodes = remember(episodes, selectedSeason) {
        episodes.filter { it.seasonNumber == selectedSeason }.sortedBy { it.episodeNumber }
    }

    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { closeFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack.copy(alpha = 0.88f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(seriesItem.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (!seriesItem.plot.isNullOrBlank()) {
                        Text(
                            text = seriesItem.plot,
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                var isCloseFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .focusRequester(closeFocusRequester)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCloseFocused) AetherColors.CyberCyan.copy(alpha = 0.25f) else AetherColors.ObsidianBlack)
                        .border(1.dp, if (isCloseFocused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .onFocusChanged { isCloseFocused = it.isFocused }
                        .focusable()
                        .clickable { onDismiss() }
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                onDismiss(); true
                            } else false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("✕ Schließen", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (seasons.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons, key = { it }) { seasonNum ->
                        var isTabFocused by remember { mutableStateOf(false) }
                        val isSelected = seasonNum == selectedSeason
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isTabFocused -> AetherColors.CyberCyan.copy(alpha = 0.25f)
                                        isSelected -> AetherColors.NeonEmerald.copy(alpha = 0.20f)
                                        else -> AetherColors.ObsidianBlack
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isTabFocused -> AetherColors.CyberCyan
                                        isSelected -> AetherColors.NeonEmerald
                                        else -> AetherColors.CyberCyan.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .onFocusChanged { isTabFocused = it.isFocused }
                                .focusable()
                                .clickable { selectedSeason = seasonNum }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                        selectedSeason = seasonNum; true
                                    } else false
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Staffel $seasonNum",
                                color = if (isSelected) AetherColors.NeonEmerald else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Lade Episoden vom Xtream-Server...", color = AetherColors.CyberCyan, fontSize = 15.sp)
                        }
                    }
                    seasonEpisodes.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Keine Episoden für diese Serie gefunden.", color = AetherColors.IceBlue, fontSize = 14.sp)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(seasonEpisodes, key = { it.episodeKey }) { ep ->
                                var isEpFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isEpFocused) AetherColors.CyberCyan.copy(alpha = 0.20f) else AetherColors.ObsidianBlack.copy(alpha = 0.6f))
                                        .border(
                                            width = if (isEpFocused) 1.5.dp else 1.dp,
                                            color = if (isEpFocused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .onFocusChanged { isEpFocused = it.isFocused }
                                        .focusable()
                                        .clickable { onPlayEpisode(ep) }
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                                onPlayEpisode(ep); true
                                            } else false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "S${ep.seasonNumber} • E${ep.episodeNumber} — ${ep.title}",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (ep.resumePositionMs > 0L) {
                                            Text(
                                                text = "▶ Fortsetzen bei ${formatMillisToTime(ep.resumePositionMs)}",
                                                color = AetherColors.NeonEmerald,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    DetailBadge(
                                        text = (ep.containerExtension ?: "mp4").uppercase(),
                                        borderColor = AetherColors.NeonEmerald.copy(alpha = 0.45f),
                                        textColor = AetherColors.NeonEmerald
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMillisToTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val mStr = minutes.toString().padStart(2, '0')
    val sStr = seconds.toString().padStart(2, '0')
    return if (hours > 0L) {
        val hStr = hours.toString().padStart(2, '0')
        "$hStr:$mStr:$sStr"
    } else {
        "$mStr:$sStr"
    }
}
