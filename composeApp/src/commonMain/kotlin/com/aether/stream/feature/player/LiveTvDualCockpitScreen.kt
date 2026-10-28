package com.aether.stream.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.stream.core.navigation.LiveTvLayoutMode
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.data.local.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SYS_QUICK_FAV = "SYS_QUICK_FAV"
const val SYS_RECENT = "SYS_RECENT"
const val FOLDER_PREFIX = "FOLDER_"

/**
 * Drei-Stufen-Kaskade nach den visuellen TiviMate-Blueprints:
 * - STAGE_1_SIDEBAR_EXPANDED (Bild 1 & 4): Zone 1 = 230.dp, Zone 2 = 250.dp
 * - STAGE_2_CATEGORIES_ACTIVE (Bild 3 & 6): Zone 1 = 68.dp, Zone 2 = 250.dp
 * - STAGE_3_CONTENT_MAXIMIZED (Bild 7): Zone 1 = 0.dp, Zone 2 = 0.dp (100% 16:9 Breite)
 */
private enum class CockpitViewportStage {
    STAGE_1_SIDEBAR_EXPANDED,
    STAGE_2_CATEGORIES_ACTIVE,
    STAGE_3_CONTENT_MAXIMIZED
}

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
    layoutMode: LiveTvLayoutMode,
    currentBufferPreset: StreamBufferPreset,
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    streamsInCategory: List<StreamItemEntity>,
    currentPlayingStream: StreamItemEntity?,
    currentChannelEpg: List<EpgProgramEntity>,
    activeSection: AetherSectionType = AetherSectionType.LIVE,
    startInPureFullscreen: Boolean = false,
    isVodOrSeriesMode: Boolean = false,
    isLoadingStreams: Boolean = false,
    onSelectSection: (AetherSectionType) -> Unit = {},
    onSelectCategory: (String) -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit,
    onOpenSeriesCockpit: (StreamItemEntity) -> Unit = {},
    onQuickFavoriteToggle: (StreamItemEntity) -> Unit,
    onOpenFavoriteFolderDialog: (StreamItemEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onToggleLayoutMode: () -> Unit,
    onCycleBufferPreset: () -> Unit,
    onZapChannelOffset: (Int) -> Unit,
    onBackToDashboard: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    val railFocusRequester = remember { FocusRequester() }
    val activeCategoryFocusRequester = remember { FocusRequester() }
    val firstCategoryFallbackRequester = remember { FocusRequester() }
    val mediaListEntryFocusRequester = remember { FocusRequester() }

    var cockpitStage by remember { mutableStateOf(CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE) }
    var categoryScrubJob by remember { mutableStateOf<Job?>(null) }
    var focusedPreviewStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var isPureFullscreen by remember { mutableStateOf(startInPureFullscreen) }

    val requestCategoryFocus: () -> Unit = remember {
        {
            cockpitStage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE
            val movedToSelected = runCatching {
                activeCategoryFocusRequester.requestFocus()
            }.isSuccess
            if (!movedToSelected) {
                runCatching { firstCategoryFallbackRequester.requestFocus() }
            }
        }
    }

    val requestRailFocus: () -> Unit = remember {
        {
            cockpitStage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED
            runCatching { railFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(startInPureFullscreen, currentPlayingStream?.uniqueStreamKey) {
        if (startInPureFullscreen && currentPlayingStream != null) {
            isPureFullscreen = true
        }
    }

    val categoryUiItems = remember(categories, favoriteFolders, activeSection) {
        buildList {
            add(CockpitCategoryUiItem(SYS_QUICK_FAV, "Favoriten", "⭐ ", isSystem = true))
            add(CockpitCategoryUiItem(SYS_RECENT, "Zuletzt gesehen", "🕒 ", isSystem = true))
            favoriteFolders.forEach { folder ->
                add(
                    CockpitCategoryUiItem(
                        key = "$FOLDER_PREFIX${folder.folderId}",
                        title = folder.folderName,
                        iconPrefix = "📁 ",
                        isSystem = true
                    )
                )
            }
            categories.forEach { cat ->
                add(
                    CockpitCategoryUiItem(
                        key = cat.uniqueCategoryKey,
                        title = cat.categoryName,
                        iconPrefix = "",
                        isSystem = false,
                        isNewlyDiscovered = cat.isNewlyDiscovered
                    )
                )
            }
        }
    }

    val activeCategoryTitle = remember(categoryUiItems, selectedCategoryKey) {
        categoryUiItems.firstOrNull { it.key == selectedCategoryKey }?.title ?: ""
    }

    val categoryListState = rememberLazyListState()
    val mediaListState = rememberLazyListState()

    LaunchedEffect(activeSection) {
        focusedPreviewStream = null
        categoryListState.scrollToItem(0)
        mediaListState.scrollToItem(0)
    }

    LaunchedEffect(selectedCategoryKey) {
        mediaListState.scrollToItem(0)
    }

    // SCHRITT 2 ANIMATIONS-STATES FÜR ZONE 2 (250.dp in Stufe 1 & 2 <-> 0.dp in Stufe 3 / Bild 7)
    val isZone2Visible = cockpitStage != CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
    val animatedZone2WidthState = animateDpAsState(
        targetValue = if (isZone2Visible) 250.dp else 0.dp,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "Zone2CategoryWidth"
    )
    val animatedZone2AlphaState = animateFloatAsState(
        targetValue = if (isZone2Visible) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = LinearOutSlowInEasing),
        label = "Zone2CategoryAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    when {
                        isPureFullscreen -> {
                            isPureFullscreen = false
                            onBackToDashboard()
                            true
                        }
                        cockpitStage == CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED -> {
                            // Aus Vollbreite (Bild 7) zurück in die Kategorieliste (Bild 6)
                            requestCategoryFocus()
                            true
                        }
                        cockpitStage == CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE -> {
                            // Aus Kategorieliste (Bild 6) zurück in die aufgeklappte Hauptnavigation (Bild 1)
                            requestRailFocus()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        if (isPureFullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AetherVideoSurface(
                    engine = playerEngine,
                    modifier = Modifier.fillMaxSize()
                )

                FullscreenPlayerHudOverlay(
                    playerEngine = playerEngine,
                    streamTitle = currentPlayingStream?.name ?: "Aether Stream",
                    isCinemaMode = isVodOrSeriesMode || startInPureFullscreen,
                    currentPreset = currentBufferPreset,
                    onCyclePreset = onCycleBufferPreset,
                    onZapChannelOffset = onZapChannelOffset,
                    onExitFullscreen = {
                        isPureFullscreen = false
                        onBackToDashboard()
                    }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                // ZONE 1: Drei-Stufen-Sidebar (230.dp in Stufe 1 <-> 68.dp in Stufe 2 <-> 0.dp in Stufe 3)
                LeftNavigationRailZone1(
                    cockpitStage = cockpitStage,
                    activeSection = activeSection,
                    selectedCategoryKey = selectedCategoryKey,
                    railFocusRequester = railFocusRequester,
                    onStageChangedToRail = {
                        cockpitStage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED
                    },
                    onNavigateRightToCategories = requestCategoryFocus,
                    onSelectSection = { section ->
                        categoryScrubJob?.cancel()
                        onSelectSection(section)
                    },
                    onSelectMyList = {
                        categoryScrubJob?.cancel()
                        onSelectCategory(SYS_QUICK_FAV)
                    },
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings
                )

                // ZONE 2: Category Column (250.dp in Stufe 1 & 2 <-> 0.dp Schubladen-Collapse in Stufe 3 / Bild 7)
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        // PHASE DEFERRAL: Misst intern stabil mit 250.dp, animiert aber den Slot inkl. 10.dp Abstand auf 0.dp
                        .layout { measurable, constraints ->
                            val targetFixedPx = 250.dp.roundToPx()
                            val gapPx = 10.dp.roundToPx()
                            val currentVisiblePx = animatedZone2WidthState.value
                                .roundToPx()
                                .coerceIn(0, targetFixedPx)

                            val trailingGapPx = if (currentVisiblePx > 0) {
                                ((currentVisiblePx.toFloat() / targetFixedPx.toFloat()) * gapPx).toInt()
                            } else {
                                0
                            }

                            val placeable = measurable.measure(
                                constraints.copy(
                                    minWidth = targetFixedPx,
                                    maxWidth = targetFixedPx
                                )
                            )

                            val totalSlotWidth = currentVisiblePx + trailingGapPx
                            layout(totalSlotWidth, placeable.height) {
                                // Schiebt die Spalte beim Schließen wie eine Schublade nach links aus dem Viewport
                                placeable.placeRelative(x = currentVisiblePx - targetFixedPx, y = 0)
                            }
                        }
                        .graphicsLayer {
                            alpha = animatedZone2AlphaState.value
                        }
                        .clip(RoundedCornerShape(12.dp))
                        .clipToBounds()
                        .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                        .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .onFocusChanged { focusState ->
                            if (focusState.hasFocus) {
                                cockpitStage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE
                            }
                        }
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
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
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "${categories.size}",
                            color = AetherColors.IceBlue.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        state = categoryListState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        itemsIndexed(
                            items = categoryUiItems,
                            key = { _, item -> "${activeSection.name}_${item.key}" }
                        ) { index, item ->
                            val isSelected = item.key == selectedCategoryKey
                            val itemMod = when {
                                isSelected -> Modifier.focusRequester(activeCategoryFocusRequester)
                                index == 0 -> Modifier.focusRequester(firstCategoryFallbackRequester)
                                else -> Modifier
                            }

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
                                    if (committedKey != selectedCategoryKey) {
                                        onSelectCategory(committedKey)
                                    }
                                },
                                onNavigateLeftToRail = {
                                    categoryScrubJob?.cancel()
                                    requestRailFocus()
                                },
                                onNavigateRightToMediaList = { committedKey ->
                                    categoryScrubJob?.cancel()
                                    if (committedKey != selectedCategoryKey) {
                                        onSelectCategory(committedKey)
                                    }
                                    cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                    runCatching { mediaListEntryFocusRequester.requestFocus() }
                                }
                            )
                        }
                    }
                }

                // RECHTE BÜHNE: ZONE 3 + ZONE 4 (Nutzt in Stufe 3 / Bild 7 volle 100% der 16:9-Breite!)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ZONE 3: Mini-Player + TiviMateDetailsPanel
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
                                    color = if (isMiniFocused) {
                                        AetherColors.CyberCyan
                                    } else {
                                        AetherColors.CyberCyan.copy(alpha = 0.35f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .onFocusChanged { state ->
                                    isMiniFocused = state.isFocused
                                    if (state.isFocused) {
                                        cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                    }
                                }
                                .focusable()
                                .clickable {
                                    if (currentPlayingStream != null) {
                                        isPureFullscreen = true
                                    }
                                }
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionCenter, Key.Enter -> {
                                                if (currentPlayingStream != null) {
                                                    isPureFullscreen = true
                                                }
                                                true
                                            }
                                            Key.DirectionLeft -> {
                                                requestCategoryFocus()
                                                true
                                            }
                                            Key.DirectionDown -> {
                                                runCatching { mediaListEntryFocusRequester.requestFocus() }
                                                true
                                            }
                                            else -> false
                                        }
                                    } else {
                                        false
                                    }
                                }
                        ) {
                            AetherVideoSurface(
                                engine = playerEngine,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AetherColors.ObsidianBlack.copy(alpha = 0.75f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (currentPlayingStream != null) "LIVE PREVIEW • OK = VOLLBILD" else "BEREIT",
                                    color = AetherColors.CyberCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        TiviMateDetailsPanel(
                            playerEngine = playerEngine,
                            stream = focusedPreviewStream ?: currentPlayingStream,
                            currentEpg = currentChannelEpg,
                            activeCategoryTitle = activeCategoryTitle,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }

                    // ZONE 4: Media-Liste
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .onFocusChanged { focusState ->
                                if (focusState.hasFocus) {
                                    cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                }
                            }
                            .padding(8.dp)
                    ) {
                        if (isLoadingStreams) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AetherColors.CyberCyan, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "⚡ Lade Filme dieser Kategorie vom Server...",
                                        color = AetherColors.CyberCyan,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else if (streamsInCategory.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (selectedCategoryKey) {
                                        SYS_QUICK_FAV -> "Noch keine Favoriten in diesem Bereich markiert (★)."
                                        SYS_RECENT -> "Noch keine zuletzt gesehenen Einträge in diesem Bereich."
                                        else -> "Keine Einträge in dieser Kategorie vorhanden."
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
                                    items = streamsInCategory,
                                    key = { _, stream -> stream.uniqueStreamKey }
                                ) { index, stream ->
                                    val isPlayingThis = currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey
                                    val entryMod = if (index == 0) {
                                        Modifier.focusRequester(mediaListEntryFocusRequester)
                                    } else {
                                        Modifier
                                    }

                                    Zone4MediaRowItem(
                                        indexNumber = index + 1,
                                        stream = stream,
                                        isCurrentlyPlaying = isPlayingThis,
                                        modifier = entryMod,
                                        onFocused = {
                                            cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                            focusedPreviewStream = stream
                                        },
                                        onClickStream = {
                                            when (stream.sectionType) {
                                                AetherSectionType.SERIES -> {
                                                    onOpenSeriesCockpit(stream)
                                                }
                                                AetherSectionType.VOD -> {
                                                    isPureFullscreen = true
                                                    onSelectStream(stream)
                                                }
                                                AetherSectionType.LIVE -> {
                                                    if (isPlayingThis) {
                                                        isPureFullscreen = true
                                                    } else {
                                                        isPureFullscreen = false
                                                        onSelectStream(stream)
                                                    }
                                                }
                                            }
                                        },
                                        onToggleFavorite = { onQuickFavoriteToggle(stream) },
                                        onOpenFolderModal = { onOpenFavoriteFolderDialog(stream) },
                                        onNavigateLeftToCategories = requestCategoryFocus
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

/**
 * SCHRITT 1 & 2: Dynamische Drei-Stufen-Sidebar (Zone 1) nach TiviMate-Blueprint (Bild 1, 6 & 7).
 * - Stufe 1 (Bild 1 & 4): Aufgeklappt auf 230.dp (mit Text-Labels).
 * - Stufe 2 (Bild 3 & 6): Eingeklappt auf 68.dp Icon-Schiene.
 * - Stufe 3 (Bild 7): Komplett ausgeblendet auf 0.dp (für 100% 16:9-Breite der rechten Bühne).
 */
@Composable
private fun LeftNavigationRailZone1(
    cockpitStage: CockpitViewportStage,
    activeSection: AetherSectionType,
    selectedCategoryKey: String,
    railFocusRequester: FocusRequester,
    onStageChangedToRail: () -> Unit,
    onNavigateRightToCategories: () -> Unit,
    onSelectSection: (AetherSectionType) -> Unit,
    onSelectMyList: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val targetWidth = when (cockpitStage) {
        CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED -> 230.dp
        CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE -> 68.dp
        CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED -> 0.dp
    }

    val isExpanded = cockpitStage == CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED
    val isVisible = cockpitStage != CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED

    val animatedWidthState = animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "Zone1SidebarWidth"
    )

    val textAlphaState = animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isExpanded) 150 else 85,
            easing = LinearOutSlowInEasing
        ),
        label = "Zone1SidebarTextAlpha"
    )

    val containerAlphaState = animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = LinearOutSlowInEasing),
        label = "Zone1ContainerAlpha"
    )

    val isMyListActive = selectedCategoryKey == SYS_QUICK_FAV

    Column(
        modifier = Modifier
            .fillMaxHeight()
            // PHASE DEFERRAL: Misst mindestens mit 68.dp (damit Icons nie gequetscht werden),
            // reduziert aber in Stufe 3 den äußeren Layout-Slot + 10.dp Abstand auf 0px.
            .layout { measurable, constraints ->
                val minRailPx = 68.dp.roundToPx()
                val maxRailPx = 230.dp.roundToPx()
                val gapPx = 10.dp.roundToPx()

                val currentVisiblePx = animatedWidthState.value
                    .roundToPx()
                    .coerceIn(0, maxRailPx)

                val childMeasureWidthPx = currentVisiblePx.coerceIn(minRailPx, maxRailPx)
                val placeable = measurable.measure(
                    constraints.copy(
                        minWidth = childMeasureWidthPx,
                        maxWidth = childMeasureWidthPx
                    )
                )

                val trailingGapPx = if (currentVisiblePx >= minRailPx) {
                    gapPx
                } else if (currentVisiblePx > 0) {
                    ((currentVisiblePx.toFloat() / minRailPx.toFloat()) * gapPx).toInt()
                } else {
                    0
                }

                val totalSlotWidth = currentVisiblePx + trailingGapPx
                layout(totalSlotWidth, placeable.height) {
                    val offsetX = if (currentVisiblePx < minRailPx) {
                        currentVisiblePx - minRailPx
                    } else {
                        0
                    }
                    placeable.placeRelative(x = offsetX, y = 0)
                }
            }
            .graphicsLayer {
                alpha = containerAlphaState.value
            }
            .clip(RoundedCornerShape(12.dp))
            .clipToBounds()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        AetherColors.ObsidianBlack,
                        AetherColors.DeepSpaceNavy.copy(alpha = 0.94f)
                    )
                )
            )
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .onFocusChanged { focusState ->
                if (focusState.hasFocus) {
                    onStageChangedToRail()
                }
            }
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // 1. DYNAMISCHER AETH BRAND HEADER (Analog zu "tivimate" <-> "tv" in Bild 1 & 6)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clipToBounds(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AetherColors.CyberCyan.copy(alpha = 0.16f))
                        .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "AE",
                        color = AetherColors.CyberCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            Row(
                modifier = Modifier
                    .graphicsLayer {
                        val a = textAlphaState.value
                        alpha = a
                        translationX = (1f - a) * (-8.dp.toPx())
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AETH",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STREAM",
                    color = AetherColors.CyberCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 2. HAUPTNAVIGATION (Bild 1–6: Suchen, TV, Filme, Serien, Aufnahmen, Meine Liste)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RailIconButton(
                icon = "🔍",
                label = "Suchen",
                isActive = false,
                textAlphaProvider = { textAlphaState.value },
                modifier = Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = onOpenSearch
            )

            RailIconButton(
                icon = "📺",
                label = "TV",
                isActive = activeSection == AetherSectionType.LIVE && !isMyListActive,
                textAlphaProvider = { textAlphaState.value },
                modifier = if (activeSection == AetherSectionType.LIVE) {
                    Modifier.focusRequester(railFocusRequester)
                } else {
                    Modifier
                },
                onNavigateRight = onNavigateRightToCategories,
                onClick = {
                    onSelectSection(AetherSectionType.LIVE)
                    onNavigateRightToCategories()
                }
            )

            RailIconButton(
                icon = "🎬",
                label = "Filme",
                isActive = activeSection == AetherSectionType.VOD && !isMyListActive,
                textAlphaProvider = { textAlphaState.value },
                modifier = if (activeSection == AetherSectionType.VOD) {
                    Modifier.focusRequester(railFocusRequester)
                } else {
                    Modifier
                },
                onNavigateRight = onNavigateRightToCategories,
                onClick = {
                    onSelectSection(AetherSectionType.VOD)
                    onNavigateRightToCategories()
                }
            )

            RailIconButton(
                icon = "🍿",
                label = "Serien",
                isActive = activeSection == AetherSectionType.SERIES && !isMyListActive,
                textAlphaProvider = { textAlphaState.value },
                modifier = if (activeSection == AetherSectionType.SERIES) {
                    Modifier.focusRequester(railFocusRequester)
                } else {
                    Modifier
                },
                onNavigateRight = onNavigateRightToCategories,
                onClick = {
                    onSelectSection(AetherSectionType.SERIES)
                    onNavigateRightToCategories()
                }
            )

            RailIconButton(
                icon = "⏺",
                label = "Aufnahmen",
                isActive = false,
                textAlphaProvider = { textAlphaState.value },
                modifier = Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = onNavigateRightToCategories
            )

            RailIconButton(
                icon = "🔖",
                label = "Meine Liste",
                isActive = isMyListActive,
                textAlphaProvider = { textAlphaState.value },
                modifier = Modifier,
                onNavigateRight = onNavigateRightToCategories,
                onClick = {
                    onSelectMyList()
                    onNavigateRightToCategories()
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 3. UNTEN VERANKERTER EINSTELLUNGS-BUTTON
        RailIconButton(
            icon = "⚙️",
            label = "Einstellungen",
            isActive = false,
            textAlphaProvider = { textAlphaState.value },
            modifier = Modifier,
            onNavigateRight = onNavigateRightToCategories,
            onClick = onOpenSettings
        )
    }
}

@Composable
private fun RailIconButton(
    icon: String,
    label: String,
    isActive: Boolean,
    textAlphaProvider: () -> Float,
    modifier: Modifier = Modifier,
    onNavigateRight: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val focusScale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "RailRowScale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
            }
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds()
            .background(
                when {
                    isFocused -> AetherColors.CyberCyan.copy(alpha = 0.28f)
                    isActive -> AetherColors.DeepSpaceNavy.copy(alpha = 0.95f)
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
                        Key.DirectionRight -> {
                            onNavigateRight()
                            true
                        }
                        Key.DirectionLeft -> {
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            onClick()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .focusable()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 18.sp,
                maxLines = 1,
                softWrap = false
            )
        }

        Text(
            text = label,
            color = when {
                isFocused -> Color.White
                isActive -> AetherColors.CyberCyan
                else -> AetherColors.TextSecondary
            },
            fontSize = 14.sp,
            fontWeight = if (isFocused || isActive) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .padding(end = 12.dp)
                .graphicsLayer {
                    val a = textAlphaProvider()
                    alpha = a
                    translationX = (1f - a) * (-6.dp.toPx())
                }
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
                else -> AetherColors.TextSecondary
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
    currentEpg: List<EpgProgramEntity>,
    activeCategoryTitle: String = "",
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
                    verticalAlignment = Alignment.Top,
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

                    // TiviMate Bild 7 Oben-Rechts-Kontext: Favoriten-Stern + Aktive Kategorie ("DE| SPORTS")
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text(
                            text = if (stream.isQuickFavorite) "★" else "☆",
                            color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.IceBlue.copy(alpha = 0.8f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (activeCategoryTitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeCategoryTitle,
                                color = AetherColors.IceBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Badges-Leiste ohne vertikales Zusammenquetschen
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

                val epgNow = currentEpg.firstOrNull()
                val descriptionText = when {
                    !stream.plotDescription.isNullOrBlank() -> stream.plotDescription
                    epgNow != null -> "EPG: ${epgNow.title} — ${epgNow.description ?: ""}"
                    stream.sectionType == AetherSectionType.LIVE ->
                        "Direkter Live-Stream • 1x OK = Mini-Player, 2x OK = Vollbild."
                    stream.sectionType == AetherSectionType.VOD ->
                        "On-Demand Kinofilm • 1x OK startet direkt im Pure Fullscreen (.mkv/.mp4/.ts Fallback)."
                    else ->
                        "On-Demand Serie • 1x OK öffnet die Staffel- und Episodenauswahl."
                }

                Text(
                    text = descriptionText,
                    color = AetherColors.TextSecondary,
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
                        AetherSectionType.VOD -> "Drücke [OK] für direkten Vollbild-Start • [LINKS/ZURÜCK] Kategorien"
                        AetherSectionType.SERIES -> "Drücke [OK] für Episoden-Auswahl • [LINKS/ZURÜCK] Kategorien"
                        AetherSectionType.LIVE -> "Drücke [OK] für Wiedergabe / Vollbild • [LINKS/ZURÜCK] Kategorien"
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
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
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
    onOpenFolderModal: () -> Unit,
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
                        Key.Menu, Key.Bookmark -> { onOpenFolderModal(); true }
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
            color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextSecondary,
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
            Text(text = badgeText, color = badgeColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
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
                    else -> AetherColors.TextPrimary
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
                text = if (stream.isQuickFavorite) "★" else "☆",
                color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.TextSecondary,
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
    onCyclePreset: () -> Unit,
    onZapChannelOffset: (Int) -> Unit,
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
                        Key.DirectionUp, Key.ChannelUp -> {
                            if (!isCinemaMode) onZapChannelOffset(-1)
                            true
                        }
                        Key.DirectionDown, Key.ChannelDown -> {
                            if (!isCinemaMode) onZapChannelOffset(1)
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
                                "LIVE TV FULLSCREEN • [AUF/AB] Zapping • [OK] Play/Pause • [ZURÜCK] Mini-Player"
                            },
                            color = AetherColors.CyberCyan,
                            fontSize = 12.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (techMeta.resolutionLabel.isNotBlank()) {
                            DetailBadge(techMeta.resolutionLabel, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.CyberCyan)
                        }
                        if (techMeta.fpsLabel.isNotBlank()) {
                            DetailBadge(techMeta.fpsLabel, AetherColors.IceBlue.copy(alpha = 0.4f), AetherColors.IceBlue)
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
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AetherColors.DeepSpaceNavy)
                                .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .clickable { onCyclePreset() }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "PUFFER: ${currentPreset.name}",
                                color = AetherColors.CyberCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (audioTracks.isNotEmpty()) {
                                val activeAudio = audioTracks.firstOrNull { it.isSelected } ?: audioTracks.first()
                                DetailBadge(
                                    text = "🔊 ${activeAudio.languageLabel} (${activeAudio.codecLabel})",
                                    borderColor = AetherColors.CyberCyan.copy(alpha = 0.35f),
                                    textColor = AetherColors.IceBlue
                                )
                            }
                            if (subtitleTracks.isNotEmpty()) {
                                val activeSub = subtitleTracks.firstOrNull { it.isSelected }
                                DetailBadge(
                                    text = "💬 ${activeSub?.languageLabel ?: "Aus"}",
                                    borderColor = AetherColors.CyberCyan.copy(alpha = 0.35f),
                                    textColor = AetherColors.IceBlue
                                )
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
