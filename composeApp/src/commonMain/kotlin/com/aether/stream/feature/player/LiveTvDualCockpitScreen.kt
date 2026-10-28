package com.aether.stream.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.stream.core.navigation.LiveTvLayoutMode
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.data.local.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

const val SYS_QUICK_FAV = "SYS_QUICK_FAV"
const val SYS_RECENT = "SYS_RECENT"
const val FOLDER_PREFIX = "FOLDER_"

private enum class CockpitViewportStage { STAGE_1_SIDEBAR_EXPANDED, STAGE_2_CATEGORIES_ACTIVE, STAGE_3_CONTENT_MAXIMIZED }
private enum class HudOverlayLayer { TRANSPORT_BAR, QUICK_ACTION_BAR, SUBTITLE_DRAWER, AUDIO_DRAWER }

private object AetherPosterImageCache {
    private const val MAX_CACHE_ENTRIES = 80
    private const val MAX_IMAGE_BYTES = 3_500_000
    private val memoryCache = LinkedHashMap<String, ImageBitmap>()
    private val failedUrls = HashSet<String>()
    private val downloadSemaphore = Semaphore(4)
    private val httpClient by lazy { HttpClient() }

    fun getCached(url: String?): ImageBitmap? {
        val clean = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        val hit = memoryCache[clean] ?: return null
        memoryCache.remove(clean)
        memoryCache[clean] = hit
        return hit
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun fetchOrGet(url: String?): ImageBitmap? {
        val clean = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        getCached(clean)?.let { return it }
        if (failedUrls.contains(clean)) return null

        val decodedBitmap = withContext(Dispatchers.IO) {
            downloadSemaphore.withPermit {
                runCatching {
                    val response = httpClient.get(clean) { header("User-Agent", "TiviMate/4.7.0 (Android 11; FireTV)") }
                    if (!response.status.isSuccess()) return@runCatching null
                    val bytes: ByteArray = response.body()
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@runCatching null
                    bytes.decodeToImageBitmap()
                }.getOrNull()
            }
        }

        if (decodedBitmap != null) {
            if (memoryCache.size >= MAX_CACHE_ENTRIES) memoryCache.keys.firstOrNull()?.let { memoryCache.remove(it) }
            memoryCache[clean] = decodedBitmap
        } else {
            if (failedUrls.size > 500) failedUrls.clear()
            failedUrls.add(clean)
        }
        return decodedBitmap
    }
}

@Composable
private fun rememberAetherAsyncBitmap(imageUrl: String?, debounceMs: Long = 80L): ImageBitmap? {
    var bitmap by remember(imageUrl) { mutableStateOf(AetherPosterImageCache.getCached(imageUrl)) }
    LaunchedEffect(imageUrl) {
        if (bitmap == null && !imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
            if (debounceMs > 0L) delay(debounceMs)
            bitmap = AetherPosterImageCache.fetchOrGet(imageUrl)
        }
    }
    return bitmap
}

private data class EpgTimelineClockSnapshot(val dateTimeLabelDe: String, val slotLabels: List<String>, val playheadFraction: Float, val nowEpochMillis: Long)

@Composable
private fun rememberEpgTimelineClockSnapshot(): EpgTimelineClockSnapshot {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); tick += 1L } }
    return remember(tick) { computeCurrentEpgClockSnapshot() }
}

private fun computeCurrentEpgClockSnapshot(): EpgTimelineClockSnapshot {
    val nowInstant = Clock.System.now()
    val ldt = nowInstant.toLocalDateTime(TimeZone.currentSystemDefault())
    val dayName = when (ldt.dayOfWeek) {
        DayOfWeek.MONDAY -> "Mo."; DayOfWeek.TUESDAY -> "Di."; DayOfWeek.WEDNESDAY -> "Mi."
        DayOfWeek.THURSDAY -> "Do."; DayOfWeek.FRIDAY -> "Fr."; DayOfWeek.SATURDAY -> "Sa."; DayOfWeek.SUNDAY -> "So."; else -> ""
    }
    val monthName = when (ldt.month) {
        Month.JANUARY -> "Jan."; Month.FEBRUARY -> "Feb."; Month.MARCH -> "März"; Month.APRIL -> "Apr."
        Month.MAY -> "Mai"; Month.JUNE -> "Juni"; Month.JULY -> "Juli"; Month.AUGUST -> "Aug."
        Month.SEPTEMBER -> "Sept."; Month.OCTOBER -> "Okt."; Month.NOVEMBER -> "Nov."; Month.DECEMBER -> "Dez."; else -> ""
    }
    val hh = ldt.hour.toString().padStart(2, '0')
    val mm = ldt.minute.toString().padStart(2, '0')
    val baseHour = ldt.hour
    val baseHalf = if (ldt.minute >= 30) 30 else 0
    val minutesIntoSlot = (ldt.minute - baseHalf).coerceIn(0, 29)
    val slots = buildList {
        var h = baseHour; var m = baseHalf
        repeat(4) {
            add("${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}")
            m += 30; if (m >= 60) { m = 0; h = (h + 1) % 24 }
        }
    }
    val fraction = (0.14f + (minutesIntoSlot.toFloat() / 120f)).coerceIn(0.10f, 0.85f)
    return EpgTimelineClockSnapshot("$dayName, ${ldt.dayOfMonth}. $monthName, $hh:$mm", slots, fraction, nowInstant.toEpochMilliseconds())
}

private fun formatEpochToClockTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return runCatching {
        val ldt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
        "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
    }.getOrDefault("")
}

data class CockpitCategoryUiItem(val key: String, val title: String, val iconPrefix: String = "", val isSystem: Boolean = false, val isNewlyDiscovered: Boolean = false)

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
    var heroPreviewScrubJob by remember { mutableStateOf<Job?>(null) }
    var focusedPreviewStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var isPureFullscreen by remember { mutableStateOf(startInPureFullscreen) }
    val clockSnapshot = rememberEpgTimelineClockSnapshot()

    val requestCategoryFocus: () -> Unit = remember {
        {
            cockpitStage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE
            val moved = runCatching { activeCategoryFocusRequester.requestFocus() }.isSuccess
            if (!moved) runCatching { firstCategoryFallbackRequester.requestFocus() }
        }
    }
    val requestRailFocus: () -> Unit = remember {
        {
            cockpitStage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED
            runCatching { railFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(startInPureFullscreen, currentPlayingStream?.uniqueStreamKey) {
        if (startInPureFullscreen && currentPlayingStream != null) isPureFullscreen = true
    }

    val categoryUiItems = remember(categories, favoriteFolders, activeSection) {
        buildList {
            add(CockpitCategoryUiItem(SYS_QUICK_FAV, "Meine Liste", "⭐ ", isSystem = true))
            add(CockpitCategoryUiItem(SYS_RECENT, "Verlauf", "🕒 ", isSystem = true))
            favoriteFolders.forEach { folder -> add(CockpitCategoryUiItem("$FOLDER_PREFIX${folder.folderId}", folder.folderName, "📁 ", isSystem = true)) }
            categories.forEach { cat -> add(CockpitCategoryUiItem(cat.uniqueCategoryKey, cat.categoryName, "", false, cat.isNewlyDiscovered)) }
        }
    }
    val activeCategoryTitle = remember(categoryUiItems, selectedCategoryKey) {
        categoryUiItems.firstOrNull { it.key == selectedCategoryKey }?.title ?: ""
    }

    val categoryListState = rememberLazyListState()
    val mediaListState = rememberLazyListState()
    val vodGridState = rememberLazyGridState()

    LaunchedEffect(streamsInCategory, activeSection) {
        if (activeSection != AetherSectionType.LIVE) focusedPreviewStream = streamsInCategory.firstOrNull()
    }
    LaunchedEffect(activeSection) {
        heroPreviewScrubJob?.cancel()
        focusedPreviewStream = streamsInCategory.firstOrNull()
        categoryListState.scrollToItem(0); mediaListState.scrollToItem(0); vodGridState.scrollToItem(0)
    }
    LaunchedEffect(selectedCategoryKey) {
        heroPreviewScrubJob?.cancel()
        mediaListState.scrollToItem(0); vodGridState.scrollToItem(0)
    }

    val isZone2Visible = cockpitStage != CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
    val animatedZone2WidthState = animateDpAsState(if (isZone2Visible) 250.dp else 0.dp, tween(180, easing = FastOutSlowInEasing), label = "Zone2W")
    val animatedZone2AlphaState = animateFloatAsState(if (isZone2Visible) 1f else 0f, tween(140, easing = LinearOutSlowInEasing), label = "Zone2A")
    val isPosterGridSection = activeSection == AetherSectionType.VOD || activeSection == AetherSectionType.SERIES

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    when {
                        isPureFullscreen -> false
                        cockpitStage == CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED -> { requestCategoryFocus(); true }
                        cockpitStage == CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE -> { requestRailFocus(); true }
                        else -> false
                    }
                } else false
            }
    ) {
        if (isPureFullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AetherVideoSurface(engine = playerEngine, modifier = Modifier.fillMaxSize())
                FullscreenPlayerHudOverlay(
                    playerEngine = playerEngine,
                    streamTitle = currentPlayingStream?.name ?: "Aether Stream",
                    isCinemaMode = isVodOrSeriesMode || startInPureFullscreen,
                    currentPreset = currentBufferPreset,
                    onCyclePreset = onCycleBufferPreset,
                    onZapChannelOffset = onZapChannelOffset,
                    onExitFullscreen = { isPureFullscreen = false; onBackToDashboard() }
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                LeftNavigationRailZone1(
                    cockpitStage = cockpitStage,
                    activeSection = activeSection,
                    selectedCategoryKey = selectedCategoryKey,
                    railFocusRequester = railFocusRequester,
                    onStageChangedToRail = { cockpitStage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED },
                    onNavigateRightToCategories = requestCategoryFocus,
                    onSelectSection = { section -> categoryScrubJob?.cancel(); onSelectSection(section) },
                    onSelectMyList = { categoryScrubJob?.cancel(); onSelectCategory(SYS_QUICK_FAV) },
                    onOpenSearch = onOpenSearch,
                    onOpenSettings = onOpenSettings
                )

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .layout { measurable, constraints ->
                            val targetFixedPx = 250.dp.roundToPx()
                            val gapPx = 10.dp.roundToPx()
                            val currentVisiblePx = animatedZone2WidthState.value.roundToPx().coerceIn(0, targetFixedPx)
                            val trailingGapPx = if (currentVisiblePx > 0) ((currentVisiblePx.toFloat() / targetFixedPx.toFloat()) * gapPx).toInt() else 0
                            val placeable = measurable.measure(constraints.copy(minWidth = targetFixedPx, maxWidth = targetFixedPx))
                            layout(currentVisiblePx + trailingGapPx, placeable.height) { placeable.placeRelative(x = currentVisiblePx - targetFixedPx, y = 0) }
                        }
                        .graphicsLayer { alpha = animatedZone2AlphaState.value }
                        .clip(RoundedCornerShape(12.dp))
                        .clipToBounds()
                        .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                        .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .onFocusChanged { if (it.hasFocus) cockpitStage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE }
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when (activeSection) { AetherSectionType.LIVE -> "LIVE TV KATEGORIEN"; AetherSectionType.VOD -> "FILME"; AetherSectionType.SERIES -> "SERIEN" },
                            color = AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false
                        )
                        Text("${categories.size}", color = AetherColors.IceBlue.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(state = categoryListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(items = categoryUiItems, key = { _, item -> "${activeSection.name}_${item.key}" }) { index, item ->
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
                                        categoryScrubJob = coroutineScope.launch { delay(120L); onSelectCategory(focusedKey) }
                                    }
                                },
                                onInstantCommit = { committedKey -> categoryScrubJob?.cancel(); if (committedKey != selectedCategoryKey) onSelectCategory(committedKey) },
                                onNavigateLeftToRail = { categoryScrubJob?.cancel(); requestRailFocus() },
                                onNavigateRightToMediaList = { committedKey ->
                                    categoryScrubJob?.cancel()
                                    if (committedKey != selectedCategoryKey) onSelectCategory(committedKey)
                                    cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                    runCatching { mediaListEntryFocusRequester.requestFocus() }
                                }
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isPosterGridSection) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.82f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            var isMiniFocused by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black)
                                    .border(if (isMiniFocused) 2.dp else 1.dp, if (isMiniFocused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .onFocusChanged { state -> isMiniFocused = state.isFocused; if (state.isFocused) cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED }
                                    .focusable()
                                    .clickable { if (currentPlayingStream != null) isPureFullscreen = true }
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionCenter, Key.Enter -> { if (currentPlayingStream != null) isPureFullscreen = true; true }
                                                Key.DirectionLeft -> { requestCategoryFocus(); true }
                                                Key.DirectionDown -> { runCatching { mediaListEntryFocusRequester.requestFocus() }; true }
                                                else -> false
                                            }
                                        } else false
                                    }
                            ) {
                                AetherVideoSurface(engine = playerEngine, modifier = Modifier.fillMaxSize())
                                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(if (currentPlayingStream != null) "LIVE PREVIEW • OK = VOLLBILD" else "BEREIT", color = AetherColors.CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            TiviMateDetailsPanel(
                                playerEngine = playerEngine,
                                stream = focusedPreviewStream ?: currentPlayingStream,
                                currentEpg = currentChannelEpg,
                                activeCategoryTitle = activeCategoryTitle,
                                nowEpochMillis = clockSnapshot.nowEpochMillis,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    } else {
                        TiviMateVodSeriesHeroPanel(
                            stream = focusedPreviewStream ?: streamsInCategory.firstOrNull(),
                            activeSection = activeSection,
                            activeCategoryTitle = activeCategoryTitle,
                            modifier = Modifier.fillMaxWidth().height(225.dp)
                        )
                    }

                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f))
                            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .onFocusChanged { if (it.hasFocus) cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED }
                            .padding(10.dp)
                    ) {
                        val availableGridWidth = maxWidth
                        if (isLoadingStreams) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AetherColors.CyberCyan, modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("⚡ Lade Einträge dieser Kategorie vom Server...", color = AetherColors.CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (streamsInCategory.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = when (selectedCategoryKey) {
                                        SYS_QUICK_FAV -> "Noch keine Favoriten in 'Meine Liste' markiert (★)."
                                        SYS_RECENT -> "Noch keine Einträge im Verlauf vorhanden."
                                        else -> "Keine Einträge in dieser Kategorie vorhanden."
                                    },
                                    color = AetherColors.IceBlue.copy(alpha = 0.7f), fontSize = 14.sp
                                )
                            }
                        } else if (!isPosterGridSection) {
                            val channelColumnWidth = 285.dp
                            Column(modifier = Modifier.fillMaxSize()) {
                                TiviMateLiveEpgHeader(clockSnapshot = clockSnapshot, channelColumnWidth = channelColumnWidth)
                                Spacer(modifier = Modifier.height(4.dp))
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    val timelineAreaWidth = (maxWidth - channelColumnWidth).coerceAtLeast(100.dp)
                                    val playheadOffsetX = channelColumnWidth + (timelineAreaWidth * clockSnapshot.playheadFraction)
                                    LazyColumn(state = mediaListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                                        itemsIndexed(items = streamsInCategory, key = { _, stream -> stream.uniqueStreamKey }) { index, stream ->
                                            val isPlayingThis = currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey
                                            val isPreviewingThis = (focusedPreviewStream?.uniqueStreamKey ?: currentPlayingStream?.uniqueStreamKey) == stream.uniqueStreamKey
                                            val entryMod = if (index == 0) Modifier.focusRequester(mediaListEntryFocusRequester) else Modifier
                                            val rowEpgList = if (isPreviewingThis || isPlayingThis) currentChannelEpg else emptyList()
                                            Zone4MediaRowItem(
                                                indexNumber = index + 1,
                                                stream = stream,
                                                isCurrentlyPlaying = isPlayingThis,
                                                channelColumnWidth = channelColumnWidth,
                                                rowEpgPrograms = rowEpgList,
                                                modifier = entryMod,
                                                onFocused = { cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED; focusedPreviewStream = stream },
                                                onClickStream = {
                                                    if (isPlayingThis) isPureFullscreen = true
                                                    else { isPureFullscreen = false; onSelectStream(stream) }
                                                },
                                                onToggleFavorite = { onQuickFavoriteToggle(stream) },
                                                onOpenFolderModal = { onOpenFavoriteFolderDialog(stream) },
                                                onNavigateLeftToCategories = requestCategoryFocus
                                            )
                                        }
                                    }
                                    Box(modifier = Modifier.offset(x = playheadOffsetX - 3.dp, y = 0.dp).fillMaxHeight().width(7.dp)) {
                                        Box(modifier = Modifier.align(Alignment.TopCenter).size(7.dp).clip(CircleShape).background(AetherColors.CyberCyan))
                                        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().width(1.5.dp).background(AetherColors.CyberCyan.copy(alpha = 0.65f)))
                                    }
                                }
                            }
                        } else {
                            val columnsCount = remember(availableGridWidth) { (availableGridWidth / 142.dp).toInt().coerceIn(3, 8) }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(activeCategoryTitle.ifBlank { if (activeSection == AetherSectionType.VOD) "Filme" else "Serien" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    Text("${streamsInCategory.size} Titel", color = AetherColors.IceBlue.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(columnsCount),
                                    state = vodGridState,
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(bottom = 12.dp, top = 2.dp, start = 2.dp, end = 2.dp)
                                ) {
                                    gridItemsIndexed(items = streamsInCategory, key = { _, stream -> stream.uniqueStreamKey }) { index, stream ->
                                        val isLeftmostColumn = (index % columnsCount) == 0
                                        val entryMod = if (index == 0) Modifier.focusRequester(mediaListEntryFocusRequester) else Modifier
                                        VodSeriesPosterCardItem(
                                            stream = stream,
                                            isLeftmostColumn = isLeftmostColumn,
                                            modifier = entryMod,
                                            onFocused = {
                                                cockpitStage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
                                                heroPreviewScrubJob?.cancel()
                                                heroPreviewScrubJob = coroutineScope.launch { delay(90L); focusedPreviewStream = stream }
                                            },
                                            onClickStream = {
                                                heroPreviewScrubJob?.cancel()
                                                focusedPreviewStream = stream
                                                when (stream.sectionType) {
                                                    AetherSectionType.SERIES -> onOpenSeriesCockpit(stream)
                                                    else -> { isPureFullscreen = true; onSelectStream(stream) }
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
}

@Composable
private fun TiviMateLiveEpgHeader(clockSnapshot: EpgTimelineClockSnapshot, channelColumnWidth: Dp) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(channelColumnWidth), contentAlignment = Alignment.CenterStart) {
                Text(clockSnapshot.dateTimeLabelDe, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                clockSnapshot.slotLabels.forEach { slotTime ->
                    Text(slotTime, color = AetherColors.IceBlue.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AetherColors.CyberCyan.copy(alpha = 0.30f)))
    }
}

@Composable
private fun TiviMateVodSeriesHeroPanel(stream: StreamItemEntity?, activeSection: AetherSectionType, activeCategoryTitle: String, modifier: Modifier = Modifier) {
    val paletteColor = remember(stream?.uniqueStreamKey) { resolveDeterministicPosterAccent(stream?.name ?: "AETH") }
    val backdropBitmap = rememberAetherAsyncBitmap(imageUrl = stream?.logoUrl, debounceMs = 40L)

    Box(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.88f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clipToBounds()) {
        if (stream == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Wähle einen Titel aus dem Poster-Grid für Details.", color = AetherColors.IceBlue.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        } else {
            val cleanMeta = remember(stream.name, stream.rating, stream.totalDurationMs, stream.containerExtension) { extractHeroMetadataFromStream(stream) }
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).background(Brush.horizontalGradient(listOf(Color.Transparent, paletteColor.copy(alpha = 0.28f), paletteColor.copy(alpha = 0.48f)))),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (backdropBitmap != null) {
                    Image(bitmap = backdropBitmap, contentDescription = stream.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.65f })
                } else {
                    Text(cleanMeta.monogram, color = Color.White.copy(alpha = 0.08f), fontSize = 110.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 18.dp))
                }
                Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(AetherColors.DeepSpaceNavy.copy(alpha = 0.98f), AetherColors.DeepSpaceNavy.copy(alpha = 0.55f), Color.Transparent))))
            }

            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(stream.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
                            if (stream.isQuickFavorite) Text("★ FAVORIT", color = AetherColors.LaserAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            if (activeCategoryTitle.isNotBlank()) DetailBadge(activeCategoryTitle, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.IceBlue)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 7.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                            Text(cleanMeta.ratingBadgeText, color = AetherColors.ObsidianBlack, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                        }
                        Text(cleanMeta.infoLineText, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        if (cleanMeta.qualityTag.isNotBlank()) DetailBadge(cleanMeta.qualityTag, AetherColors.CyberCyan.copy(alpha = 0.5f), AetherColors.CyberCyan)
                        if (!stream.containerExtension.isNullOrBlank()) DetailBadge(stream.containerExtension.uppercase(), AetherColors.NeonEmerald.copy(alpha = 0.45f), AetherColors.NeonEmerald)
                    }
                    val plotText = if (!stream.plotDescription.isNullOrBlank()) stream.plotDescription
                    else if (activeSection == AetherSectionType.SERIES) "On-Demand Serie in '$activeCategoryTitle'. Drücke [OK], um alle Staffeln und Episoden zu durchsuchen und direkt im Vollbild abzuspielen."
                    else "On-Demand Kinofilm in '$activeCategoryTitle'. Drücke [OK] für den direkten Start im Pure Fullscreen mit automatischer Container-Rotation (.mp4/.mkv/.ts)."
                    Text(plotText, color = AetherColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.88f))
                }

                if (stream.resumePositionMs > 0L) {
                    val progressFraction = if (stream.totalDurationMs > 0L) (stream.resumePositionMs.toFloat() / stream.totalDurationMs.toFloat()).coerceIn(0.02f, 1f) else 0.15f
                    Column(modifier = Modifier.fillMaxWidth(0.65f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("▶ Fortsetzen bei ${formatMillisToTime(stream.resumePositionMs)}", color = AetherColors.NeonEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (stream.totalDurationMs > 0L) Text("${(progressFraction * 100).toInt()}% (${formatMillisToTime(stream.totalDurationMs)})", color = AetherColors.IceBlue, fontSize = 11.sp)
                        }
                        LinearProgressIndicator(progress = { progressFraction }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = AetherColors.NeonEmerald, trackColor = AetherColors.ObsidianBlack)
                    }
                } else {
                    Text(
                        text = if (activeSection == AetherSectionType.SERIES) "Drücke [OK] für Staffeln & Episoden • [MENÜ] Favoriten-Ordner • [LINKS/ZURÜCK] Kategorien"
                        else "Drücke [OK] für direkten Vollbild-Start • [MENÜ] Favoriten-Ordner • [LINKS/ZURÜCK] Kategorien",
                        color = AetherColors.CyberCyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun VodSeriesPosterCardItem(
    stream: StreamItemEntity,
    isLeftmostColumn: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClickStream: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenFolderModal: () -> Unit,
    onNavigateLeftToCategories: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(if (isFocused) 1.04f else 1.0f, tween(120, easing = FastOutSlowInEasing), label = "PosterScale")
    val accentColor = remember(stream.uniqueStreamKey) { resolveDeterministicPosterAccent(stream.name) }
    val cleanMeta = remember(stream.name, stream.rating) { extractHeroMetadataFromStream(stream) }
    val posterBitmap = rememberAetherAsyncBitmap(imageUrl = stream.logoUrl, debounceMs = 80L)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(215.dp)
            .graphicsLayer { scaleX = focusScale; scaleY = focusScale }
            .clip(RoundedCornerShape(10.dp))
            .background(AetherColors.ObsidianBlack.copy(alpha = 0.85f))
            .border(if (isFocused) 2.dp else 1.dp, if (isFocused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .onFocusChanged { state -> isFocused = state.isFocused; if (state.isFocused) onFocused() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionLeft -> if (isLeftmostColumn) { onNavigateLeftToCategories(); true } else false
                        Key.DirectionCenter, Key.Enter -> { onClickStream(); true }
                        Key.Menu, Key.Bookmark -> { onOpenFolderModal(); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .clickable { onClickStream() }
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(Brush.verticalGradient(listOf(accentColor.copy(alpha = 0.55f), AetherColors.DeepSpaceNavy.copy(alpha = 0.92f), AetherColors.ObsidianBlack)))) {
            if (posterBitmap != null) {
                Image(bitmap = posterBitmap, contentDescription = stream.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AetherColors.ObsidianBlack.copy(alpha = 0.35f), Color.Transparent, AetherColors.ObsidianBlack.copy(alpha = 0.65f)))))
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(cleanMeta.monogram, color = Color.White.copy(alpha = if (isFocused) 0.35f else 0.20f), fontSize = 36.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(cleanMeta.cleanDisplayTitle, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cleanMeta.qualityTag.isNotBlank()) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.78f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.45f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Text(cleanMeta.qualityTag, color = AetherColors.CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleFavorite() }.padding(4.dp)) {
                Text(if (stream.isQuickFavorite) "★" else "☆", color = if (stream.isQuickFavorite) AetherColors.LaserAmber else Color.White.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1, softWrap = false)
            }
            Box(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0284C7)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(cleanMeta.ratingBadgeText, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
            }
            if (stream.resumePositionMs > 0L) {
                val progressFraction = if (stream.totalDurationMs > 0L) (stream.resumePositionMs.toFloat() / stream.totalDurationMs.toFloat()).coerceIn(0.05f, 1f) else 0.2f
                LinearProgressIndicator(progress = { progressFraction }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp), color = AetherColors.NeonEmerald, trackColor = AetherColors.ObsidianBlack.copy(alpha = 0.7f))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(34.dp).background(if (isFocused) AetherColors.CyberCyan.copy(alpha = 0.22f) else AetherColors.DeepSpaceNavy.copy(alpha = 0.95f)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(stream.name, color = if (isFocused) Color.White else AetherColors.TextPrimary, fontSize = 12.sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class ExtractedHeroMetadata(val cleanDisplayTitle: String, val monogram: String, val ratingBadgeText: String, val infoLineText: String, val qualityTag: String)

private fun extractHeroMetadataFromStream(stream: StreamItemEntity): ExtractedHeroMetadata {
    val rawName = stream.name.trim()
    val strippedTitle = rawName.replace(Regex("^(\\|?[A-Z]{2,3}\\|?\\s*[-|:]?\\s*)"), "").trim().ifBlank { rawName }
    val words = strippedTitle.split(" ").filter { it.isNotBlank() }
    val monogram = when {
        words.size >= 2 -> "${words[0].first().uppercaseChar()}${words[1].first().uppercaseChar()}"
        strippedTitle.length >= 2 -> strippedTitle.take(2).uppercase()
        else -> "AE"
    }
    val ratingClean = stream.rating?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" } ?: if (stream.sectionType == AetherSectionType.SERIES) "SERIE" else "VOD"
    val yearMatch = Regex("\\b(19[7-9]\\d|20[0-2]\\d)\\b").find(rawName)?.value ?: "2025"
    val durationOrType = when {
        stream.totalDurationMs > 0L -> formatDurationReadable(stream.totalDurationMs)
        stream.sectionType == AetherSectionType.SERIES -> "Staffeln verfügbar"
        else -> "Spielfilm"
    }
    val genreLabel = when (stream.sectionType) { AetherSectionType.SERIES -> "Serie / Drama"; AetherSectionType.VOD -> "Kino / On-Demand"; AetherSectionType.LIVE -> "Live TV" }
    val upper = rawName.uppercase()
    val qualityTag = when {
        upper.contains("4K") || upper.contains("UHD") -> "4K UHD"
        upper.contains("FHD") || upper.contains("1080") -> "FHD"
        upper.contains("HEVC") || upper.contains("H265") -> "HEVC"
        upper.contains("HDR") -> "HDR"
        else -> ""
    }
    return ExtractedHeroMetadata(strippedTitle, monogram, if (ratingClean.length > 5) ratingClean.take(4) else ratingClean, "$yearMatch  •  $durationOrType  •  $genreLabel", qualityTag)
}

private fun resolveDeterministicPosterAccent(seed: String): Color {
    val palette = listOf(Color(0xFF0284C7), Color(0xFF2563EB), Color(0xFF0D9488), Color(0xFF4F46E5), Color(0xFF0891B2), Color(0xFF059669))
    val hash = seed.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return palette[hash % palette.size]
}

private fun formatDurationReadable(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L; val mins = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${mins}m" else "${mins} Min"
}

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

    val animatedWidthState = animateDpAsState(targetWidth, tween(180, easing = FastOutSlowInEasing), label = "Z1W")
    val textAlphaState = animateFloatAsState(if (isExpanded) 1f else 0f, tween(if (isExpanded) 150 else 85, easing = LinearOutSlowInEasing), label = "Z1TA")
    val containerAlphaState = animateFloatAsState(if (isVisible) 1f else 0f, tween(140, easing = LinearOutSlowInEasing), label = "Z1CA")
    val isMyListActive = selectedCategoryKey == SYS_QUICK_FAV

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .layout { measurable, constraints ->
                val minRailPx = 68.dp.roundToPx(); val maxRailPx = 230.dp.roundToPx(); val gapPx = 10.dp.roundToPx()
                val currentVisiblePx = animatedWidthState.value.roundToPx().coerceIn(0, maxRailPx)
                val childMeasureWidthPx = currentVisiblePx.coerceIn(minRailPx, maxRailPx)
                val placeable = measurable.measure(constraints.copy(minWidth = childMeasureWidthPx, maxWidth = childMeasureWidthPx))
                val trailingGapPx = if (currentVisiblePx >= minRailPx) gapPx else if (currentVisiblePx > 0) ((currentVisiblePx.toFloat() / minRailPx.toFloat()) * gapPx).toInt() else 0
                layout(currentVisiblePx + trailingGapPx, placeable.height) {
                    val offsetX = if (currentVisiblePx < minRailPx) currentVisiblePx - minRailPx else 0
                    placeable.placeRelative(x = offsetX, y = 0)
                }
            }
            .graphicsLayer { alpha = containerAlphaState.value }
            .clip(RoundedCornerShape(12.dp))
            .clipToBounds()
            .background(Brush.horizontalGradient(listOf(AetherColors.ObsidianBlack, AetherColors.DeepSpaceNavy.copy(alpha = 0.94f))))
            .border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .onFocusChanged { if (it.hasFocus) onStageChangedToRail() }
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(40.dp).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(AetherColors.CyberCyan.copy(alpha = 0.16f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                    Text("AE", color = AetherColors.CyberCyan, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                }
            }
            Row(modifier = Modifier.graphicsLayer { val a = textAlphaState.value; alpha = a; translationX = (1f - a) * (-8.dp.toPx()) }, verticalAlignment = Alignment.CenterVertically) {
                Text("AETH", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
                Spacer(modifier = Modifier.width(6.dp))
                Text("STREAM", color = AetherColors.CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1, softWrap = false)
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RailIconButton("🔍", "Suchen", false, { textAlphaState.value }, Modifier, onNavigateRightToCategories, onOpenSearch)
            RailIconButton("📺", "TV", activeSection == AetherSectionType.LIVE && !isMyListActive, { textAlphaState.value }, if (activeSection == AetherSectionType.LIVE) Modifier.focusRequester(railFocusRequester) else Modifier, onNavigateRightToCategories) { onSelectSection(AetherSectionType.LIVE); onNavigateRightToCategories() }
            RailIconButton("🎬", "Filme", activeSection == AetherSectionType.VOD && !isMyListActive, { textAlphaState.value }, if (activeSection == AetherSectionType.VOD) Modifier.focusRequester(railFocusRequester) else Modifier, onNavigateRightToCategories) { onSelectSection(AetherSectionType.VOD); onNavigateRightToCategories() }
            RailIconButton("🍿", "Serien", activeSection == AetherSectionType.SERIES && !isMyListActive, { textAlphaState.value }, if (activeSection == AetherSectionType.SERIES) Modifier.focusRequester(railFocusRequester) else Modifier, onNavigateRightToCategories) { onSelectSection(AetherSectionType.SERIES); onNavigateRightToCategories() }
            RailIconButton("⏺", "Aufnahmen", false, { textAlphaState.value }, Modifier, onNavigateRightToCategories, onNavigateRightToCategories)
            RailIconButton("🔖", "Meine Liste", isMyListActive, { textAlphaState.value }, Modifier, onNavigateRightToCategories) { onSelectMyList(); onNavigateRightToCategories() }
        }
        Spacer(modifier = Modifier.weight(1f))
        RailIconButton("⚙️", "Einstellungen", false, { textAlphaState.value }, Modifier, onNavigateRightToCategories, onOpenSettings)
    }
}

@Composable
private fun RailIconButton(icon: String, label: String, isActive: Boolean, textAlphaProvider: () -> Float, modifier: Modifier = Modifier, onNavigateRight: () -> Unit, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val focusScale by animateFloatAsState(if (isFocused) 1.03f else 1.0f, tween(120, easing = FastOutSlowInEasing), label = "RailScale")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .graphicsLayer { scaleX = focusScale; scaleY = focusScale }
            .clip(RoundedCornerShape(10.dp))
            .clipToBounds()
            .background(when { isFocused -> AetherColors.CyberCyan.copy(alpha = 0.28f); isActive -> AetherColors.DeepSpaceNavy.copy(alpha = 0.95f); else -> Color.Transparent })
            .border(if (isFocused) 1.5.dp else 1.dp, when { isFocused -> AetherColors.CyberCyan; isActive -> AetherColors.CyberCyan.copy(alpha = 0.35f); else -> Color.Transparent }, RoundedCornerShape(10.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> { onNavigateRight(); true }
                        Key.DirectionLeft -> true
                        Key.DirectionCenter, Key.Enter -> { onClick(); true }
                        else -> false
                    }
                } else false
            }
            .focusable()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Text(text = icon, fontSize = 18.sp, maxLines = 1, softWrap = false)
        }
        Text(
            text = label,
            color = when { isFocused -> Color.White; isActive -> AetherColors.CyberCyan; else -> AetherColors.TextSecondary },
            fontSize = 14.sp,
            fontWeight = if (isFocused || isActive) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(end = 12.dp).graphicsLayer { val a = textAlphaProvider(); alpha = a; translationX = (1f - a) * (-6.dp.toPx()) }
        )
    }
}

@Composable
private fun ObsidianCategoryRowItem(item: CockpitCategoryUiItem, isSelected: Boolean, modifier: Modifier = Modifier, onFocusSettled: (String) -> Unit, onInstantCommit: (String) -> Unit, onNavigateLeftToRail: () -> Unit, onNavigateRightToMediaList: (String) -> Unit) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { val s = if (isFocused) 1.02f else 1.0f; scaleX = s; scaleY = s }
            .clip(RoundedCornerShape(8.dp))
            .background(when { isFocused -> AetherColors.CyberCyan.copy(alpha = 0.18f); isSelected -> AetherColors.DeepSpaceNavy.copy(alpha = 0.90f); else -> AetherColors.ObsidianBlack.copy(alpha = 0.40f) })
            .border(when { isFocused -> 1.5.dp; isSelected -> 1.dp; else -> 0.dp }, when { isFocused -> AetherColors.CyberCyan; isSelected -> AetherColors.CyberCyan.copy(alpha = 0.35f); else -> Color.Transparent }, RoundedCornerShape(8.dp))
            .onFocusChanged { state -> isFocused = state.isFocused; if (state.isFocused) onFocusSettled(item.key) }
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
        Box(modifier = Modifier.width(if (isFocused) 4.dp else 3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(when { isFocused -> AetherColors.CyberCyan; isSelected -> AetherColors.NeonEmerald; else -> Color.Transparent }))
        Spacer(modifier = Modifier.width(8.dp))
        Text("${item.iconPrefix}${item.title}", color = when { isFocused -> Color.White; isSelected -> AetherColors.IceBlue; else -> AetherColors.TextSecondary }, fontSize = 13.sp, fontWeight = when { isFocused -> FontWeight.Bold; isSelected -> FontWeight.SemiBold; else -> FontWeight.Normal }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (item.isNewlyDiscovered) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AetherColors.NeonEmerald.copy(alpha = 0.2f)).padding(horizontal = 5.dp, vertical = 2.dp)) {
                Text("NEU", color = AetherColors.NeonEmerald, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun TiviMateDetailsPanel(playerEngine: AetherVideoPlayerEngine, stream: StreamItemEntity?, currentEpg: List<EpgProgramEntity>, activeCategoryTitle: String = "", nowEpochMillis: Long = 0L, modifier: Modifier = Modifier) {
    val techMeta by playerEngine.techMetadata.collectAsState()

    Column(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.55f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (stream == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Wähle einen Eintrag aus der Liste für Detailinformationen.", color = AetherColors.IceBlue.copy(alpha = 0.65f), fontSize = 13.sp)
            }
        } else {
            val epgNow = currentEpg.firstOrNull()
            val primaryHeaderTitle = if (!epgNow?.title.isNullOrBlank()) epgNow!!.title else stream.name

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(primaryHeaderTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                        Text(if (stream.isQuickFavorite) "★" else "☆", color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.IceBlue.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        if (activeCategoryTitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(activeCategoryTitle, color = AetherColors.IceBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (epgNow != null && epgNow.startEpochMillis > 0L && epgNow.endEpochMillis > epgNow.startEpochMillis) {
                        val startStr = formatEpochToClockTime(epgNow.startEpochMillis)
                        val endStr = formatEpochToClockTime(epgNow.endEpochMillis)
                        val totalDur = (epgNow.endEpochMillis - epgNow.startEpochMillis).coerceAtLeast(1L)
                        val effectiveNow = if (nowEpochMillis > 0L) nowEpochMillis else Clock.System.now().toEpochMilliseconds()
                        val epgProg = ((effectiveNow - epgNow.startEpochMillis).toFloat() / totalDur.toFloat()).coerceIn(0.05f, 1f)
                        val remainMin = ((epgNow.endEpochMillis - effectiveNow) / 60_000L).coerceAtLeast(1L)
                        Text("$startStr – $endStr", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        LinearProgressIndicator(progress = { epgProg }, modifier = Modifier.width(54.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = AetherColors.CyberCyan, trackColor = AetherColors.DeepSpaceNavy)
                        Text("$remainMin Min", color = AetherColors.IceBlue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                    DetailBadge(stream.name, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.CyberCyan)
                    if (techMeta.resolutionLabel.isNotBlank()) DetailBadge(techMeta.resolutionLabel, AetherColors.IceBlue.copy(alpha = 0.4f), AetherColors.IceBlue)
                    if (techMeta.videoCodec.isNotBlank()) DetailBadge(techMeta.videoCodec, AetherColors.CyberCyan.copy(alpha = 0.35f), Color.White)
                }

                val descriptionText = when {
                    !epgNow?.description.isNullOrBlank() -> epgNow!!.description!!
                    !stream.plotDescription.isNullOrBlank() -> stream.plotDescription
                    else -> "Live-Übertragung auf ${stream.name} • 1x OK = Vorschau im Mini-Player, 2x OK = Vollbild."
                }
                Text(descriptionText, color = AetherColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text("Drücke [OK] für Wiedergabe / Vollbild • [MENÜ] Favoriten-Ordner • [LINKS/ZURÜCK] Kategorien", color = AetherColors.CyberCyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun DetailBadge(text: String, borderColor: Color, textColor: Color) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(AetherColors.DeepSpaceNavy).border(1.dp, borderColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Zone4MediaRowItem(
    indexNumber: Int,
    stream: StreamItemEntity,
    isCurrentlyPlaying: Boolean,
    channelColumnWidth: Dp,
    rowEpgPrograms: List<EpgProgramEntity>,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClickStream: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenFolderModal: () -> Unit,
    onNavigateLeftToCategories: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val channelLogoBitmap = rememberAetherAsyncBitmap(imageUrl = stream.logoUrl, debounceMs = 100L)
    val nowProgramTitle = remember(rowEpgPrograms, stream.plotDescription) {
        rowEpgPrograms.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: stream.plotDescription?.takeIf { it.isNotBlank() } ?: "Keine Information"
    }
    val nextProgramTitle = remember(rowEpgPrograms) { rowEpgPrograms.getOrNull(1)?.title?.takeIf { it.isNotBlank() } ?: "Keine Information" }
    val hasRealNowInfo = nowProgramTitle != "Keine Information"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .onFocusChanged { state -> isFocused = state.isFocused; if (state.isFocused) onFocused() }
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
            .clickable { onClickStream() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .width(channelColumnWidth)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(when { isFocused -> AetherColors.DeepSpaceNavy.copy(alpha = 0.95f); isCurrentlyPlaying -> AetherColors.NeonEmerald.copy(alpha = 0.14f); else -> Color.Transparent })
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$indexNumber", color = if (isFocused) Color.White else AetherColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.width(32.dp))
            Box(
                modifier = Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = if (channelLogoBitmap != null) 0.92f else 0.08f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.30f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (channelLogoBitmap != null) Image(bitmap = channelLogoBitmap, contentDescription = stream.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp))
                else Text("TV", color = AetherColors.CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isCurrentlyPlaying) Text("▶", color = AetherColors.NeonEmerald, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 5.dp))
            Text(stream.name, color = when { isFocused -> AetherColors.CyberCyan; isCurrentlyPlaying -> AetherColors.NeonEmerald; else -> Color.White }, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(CircleShape).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleFavorite() }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(if (stream.isQuickFavorite) "★" else "☆", color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }

        Row(modifier = Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(0.56f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(when { isFocused -> Color.White; isCurrentlyPlaying -> AetherColors.CyberCyan.copy(alpha = 0.22f); else -> AetherColors.ObsidianBlack.copy(alpha = 0.55f) })
                    .border(if (isFocused) 1.5.dp else 1.dp, when { isFocused -> AetherColors.CyberCyan; isCurrentlyPlaying -> AetherColors.CyberCyan.copy(alpha = 0.55f); else -> AetherColors.CyberCyan.copy(alpha = 0.16f) }, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(nowProgramTitle, color = when { isFocused -> AetherColors.ObsidianBlack; hasRealNowInfo -> Color.White; else -> AetherColors.TextSecondary.copy(alpha = 0.8f) }, fontSize = 13.sp, fontWeight = if (isFocused || hasRealNowInfo) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Box(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isFocused) AetherColors.CyberCyan.copy(alpha = 0.18f) else AetherColors.ObsidianBlack.copy(alpha = 0.42f))
                    .border(1.dp, if (isFocused) AetherColors.CyberCyan.copy(alpha = 0.45f) else AetherColors.CyberCyan.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(nextProgramTitle, color = if (isFocused) Color.White else AetherColors.TextSecondary.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private data class QuickHudActionItem(val icon: String, val label: String, val onActivate: () -> Unit)

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
    val clockSnapshot = rememberEpgTimelineClockSnapshot()

    var isHudVisible by remember { mutableStateOf(true) }
    var hudLayer by remember { mutableStateOf(HudOverlayLayer.TRANSPORT_BAR) }
    var selectedTransportIdx by remember { mutableIntStateOf(2) }
    var selectedQuickIdx by remember { mutableIntStateOf(0) }
    var selectedDrawerIdx by remember { mutableIntStateOf(0) }

    var audioDelayMs by remember { mutableIntStateOf(0) }
    var aspectModeIdx by remember { mutableIntStateOf(0) }
    var sleepTimerIdx by remember { mutableIntStateOf(0) }
    var selectedSubOverrideIdx by remember { mutableIntStateOf(-1) }
    var selectedAudioOverrideIdx by remember { mutableIntStateOf(0) }
    var globalSubtitlesEnabled by remember { mutableStateOf(true) }

    var interactionTick by remember { mutableLongStateOf(0L) }
    val hudFocusRequester = remember { FocusRequester() }
    val aspectModes = remember { listOf("Normal", "Vollbild", "16:9 Zoom") }
    val sleepModes = remember { listOf("Aus", "30 Min", "60 Min", "90 Min") }

    val activeAudioLabel = remember(audioTracks, selectedAudioOverrideIdx) {
        if (audioTracks.isNotEmpty()) {
            val chosen = audioTracks.getOrNull(selectedAudioOverrideIdx) ?: audioTracks.firstOrNull { it.isSelected } ?: audioTracks.first()
            chosen.codecLabel.ifBlank { chosen.languageLabel.ifBlank { "Stereo" } }
        } else "Stereo"
    }
    val subtitleDrawerOptions = remember(subtitleTracks) {
        buildList {
            add("Aus")
            if (subtitleTracks.isEmpty()) add("Untertitel 1")
            else subtitleTracks.forEachIndexed { idx, track -> add(track.languageLabel.ifBlank { "Untertitel ${idx + 1}" }) }
        }
    }
    val audioDrawerOptions = remember(audioTracks) {
        if (audioTracks.isEmpty()) listOf("Stereo (Standard)")
        else audioTracks.mapIndexed { idx, t -> "${t.languageLabel.ifBlank { "Spur ${idx + 1}" }} (${t.codecLabel.ifBlank { "Stereo" }})" }
    }
    val activeSubtitleLabel = remember(subtitleTracks, selectedSubOverrideIdx, subtitleDrawerOptions) {
        when {
            selectedSubOverrideIdx in subtitleDrawerOptions.indices -> subtitleDrawerOptions[selectedSubOverrideIdx]
            subtitleTracks.any { it.isSelected } -> subtitleTracks.first { it.isSelected }.languageLabel.ifBlank { "Untertitel 1" }
            globalSubtitlesEnabled && subtitleDrawerOptions.size > 1 -> subtitleDrawerOptions[1]
            else -> "Aus"
        }
    }
    val resolutionText = techMeta.resolutionLabel.ifBlank { "1280 × 720" }
    val fpsText = techMeta.fpsLabel.ifBlank { "25 FPS" }

    val quickActionItems = remember(resolutionText, activeAudioLabel, audioDelayMs, activeSubtitleLabel, aspectModeIdx, sleepTimerIdx, currentPreset) {
        listOf(
            QuickHudActionItem("📺", "TV") { onExitFullscreen() },
            QuickHudActionItem("🗔", "Bild in Bild") { onExitFullscreen() },
            QuickHudActionItem("🎥", resolutionText) { onCyclePreset() },
            QuickHudActionItem("🔊", activeAudioLabel) {
                selectedDrawerIdx = selectedAudioOverrideIdx.coerceIn(0, (audioDrawerOptions.size - 1).coerceAtLeast(0))
                hudLayer = HudOverlayLayer.AUDIO_DRAWER
            },
            QuickHudActionItem("↔", "$audioDelayMs ms") { audioDelayMs = when (audioDelayMs) { 0 -> 50; 50 -> 100; 100 -> -50; else -> 0 } },
            QuickHudActionItem("CC", activeSubtitleLabel) {
                selectedDrawerIdx = if (selectedSubOverrideIdx >= 0) selectedSubOverrideIdx else if (globalSubtitlesEnabled) 1 else 0
                hudLayer = HudOverlayLayer.SUBTITLE_DRAWER
            },
            QuickHudActionItem("⛶", aspectModes[aspectModeIdx]) { aspectModeIdx = (aspectModeIdx + 1) % aspectModes.size },
            QuickHudActionItem("⏲", sleepModes[sleepTimerIdx]) { sleepTimerIdx = (sleepTimerIdx + 1) % sleepModes.size },
            QuickHudActionItem("⚙️", "Einstellungen") { onCyclePreset() }
        )
    }

    LaunchedEffect(Unit) { runCatching { hudFocusRequester.requestFocus() } }
    LaunchedEffect(interactionTick, isPlaying, isHudVisible, hudLayer) {
        if (isHudVisible && isPlaying && hudLayer != HudOverlayLayer.SUBTITLE_DRAWER && hudLayer != HudOverlayLayer.AUDIO_DRAWER) {
            delay(5000L); isHudVisible = false; hudLayer = HudOverlayLayer.TRANSPORT_BAR
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(hudFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                interactionTick += 1L
                if (!isHudVisible) {
                    isHudVisible = true
                    hudLayer = if (event.key == Key.DirectionDown) HudOverlayLayer.QUICK_ACTION_BAR else HudOverlayLayer.TRANSPORT_BAR
                    return@onKeyEvent true
                }
                when (hudLayer) {
                    HudOverlayLayer.TRANSPORT_BAR -> when (event.key) {
                        Key.DirectionDown -> { hudLayer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                        Key.DirectionUp, Key.ChannelUp -> { if (!isCinemaMode) onZapChannelOffset(-1); true }
                        Key.ChannelDown -> { if (!isCinemaMode) onZapChannelOffset(1); true }
                        Key.DirectionLeft -> { selectedTransportIdx = (selectedTransportIdx - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { selectedTransportIdx = (selectedTransportIdx + 1).coerceAtMost(5); true }
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                            when (selectedTransportIdx) {
                                0 -> if (isCinemaMode) playerEngine.seekRelativeMs(-currentPosMs) else onZapChannelOffset(-1)
                                1 -> if (isCinemaMode) playerEngine.seekRelativeMs(-15_000L) else onZapChannelOffset(-1)
                                2 -> if (isPlaying) playerEngine.pause() else playerEngine.resume()
                                3 -> if (isCinemaMode) playerEngine.seekRelativeMs(15_000L) else onZapChannelOffset(1)
                                4 -> if (isCinemaMode) playerEngine.seekRelativeMs(60_000L) else onZapChannelOffset(1)
                                5 -> if (isCinemaMode) playerEngine.seekRelativeMs(-currentPosMs) else onCyclePreset()
                            }
                            true
                        }
                        Key.Back, Key.Escape -> { onExitFullscreen(); true }
                        else -> false
                    }
                    HudOverlayLayer.QUICK_ACTION_BAR -> when (event.key) {
                        Key.DirectionLeft -> { selectedQuickIdx = (selectedQuickIdx - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { selectedQuickIdx = (selectedQuickIdx + 1).coerceAtMost(quickActionItems.lastIndex); true }
                        Key.DirectionDown -> true
                        Key.DirectionUp, Key.Back, Key.Escape -> { hudLayer = HudOverlayLayer.TRANSPORT_BAR; selectedTransportIdx = 2; true }
                        Key.DirectionCenter, Key.Enter -> { quickActionItems[selectedQuickIdx].onActivate(); true }
                        else -> false
                    }
                    HudOverlayLayer.SUBTITLE_DRAWER -> when (event.key) {
                        Key.DirectionUp -> { selectedDrawerIdx = (selectedDrawerIdx - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { selectedDrawerIdx = (selectedDrawerIdx + 1).coerceAtMost(subtitleDrawerOptions.size); true }
                        Key.DirectionCenter, Key.Enter -> {
                            if (selectedDrawerIdx < subtitleDrawerOptions.size) selectedSubOverrideIdx = selectedDrawerIdx
                            else globalSubtitlesEnabled = !globalSubtitlesEnabled
                            true
                        }
                        Key.DirectionLeft, Key.Back, Key.Escape -> { hudLayer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                        else -> true
                    }
                    HudOverlayLayer.AUDIO_DRAWER -> when (event.key) {
                        Key.DirectionUp -> { selectedDrawerIdx = (selectedDrawerIdx - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { selectedDrawerIdx = (selectedDrawerIdx + 1).coerceAtMost(audioDrawerOptions.lastIndex); true }
                        Key.DirectionCenter, Key.Enter -> { selectedAudioOverrideIdx = selectedDrawerIdx; hudLayer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                        Key.DirectionLeft, Key.Back, Key.Escape -> { hudLayer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                        else -> true
                    }
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { interactionTick += 1L; isHudVisible = !isHudVisible }
    ) {
        AnimatedVisibility(visible = isHudVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AetherColors.ObsidianBlack.copy(alpha = 0.65f), Color.Transparent, AetherColors.ObsidianBlack.copy(alpha = 0.90f))))) {
                Text(clockSnapshot.dateTimeLabelDe, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 32.dp, vertical = 22.dp))

                if (hudLayer == HudOverlayLayer.TRANSPORT_BAR) {
                    Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 36.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(streamTitle, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                WhiteHudTechBadge(resolutionText.replace(" × ", "x"))
                                WhiteHudTechBadge(fpsText)
                                WhiteHudTechBadge(activeAudioLabel.uppercase())
                            }
                        }
                        val progress = if (durationMs > 0L) (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 1f
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
                            val barWidth = maxWidth
                            Box(modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.30f)))
                            Box(modifier = Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                            val thumbOffset = (barWidth * progress - 6.dp).coerceAtLeast(0.dp)
                            Box(modifier = Modifier.offset(x = thumbOffset).size(12.dp).clip(CircleShape).background(Color.White))
                        }
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (isCinemaMode && durationMs > 0L) "${formatMillisToTime(currentPosMs)} / ${formatMillisToTime(durationMs)}" else "LIVE • Puffer: ${currentPreset.name}",
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart)
                            )
                            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                                    HudTransportCircleButton("⏮", selectedTransportIdx == 0, false) { selectedTransportIdx = 0; if (isCinemaMode) playerEngine.seekRelativeMs(-currentPosMs) else onZapChannelOffset(-1) }
                                    HudTransportCircleButton("⏪", selectedTransportIdx == 1, false) { selectedTransportIdx = 1; if (isCinemaMode) playerEngine.seekRelativeMs(-15_000L) else onZapChannelOffset(-1) }
                                    HudTransportCircleButton(if (isPlaying) "▶" else "⏸", selectedTransportIdx == 2, true) { selectedTransportIdx = 2; if (isPlaying) playerEngine.pause() else playerEngine.resume() }
                                    HudTransportCircleButton("⏩", selectedTransportIdx == 3, false) { selectedTransportIdx = 3; if (isCinemaMode) playerEngine.seekRelativeMs(15_000L) else onZapChannelOffset(1) }
                                    HudTransportCircleButton("⏭", selectedTransportIdx == 4, false) { selectedTransportIdx = 4; if (isCinemaMode) playerEngine.seekRelativeMs(60_000L) else onZapChannelOffset(1) }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("⌄", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { hudLayer = HudOverlayLayer.QUICK_ACTION_BAR })
                            }
                            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                HudTransportCircleButton("↺", selectedTransportIdx == 5, false) { selectedTransportIdx = 5; if (isCinemaMode) playerEngine.seekRelativeMs(-currentPosMs) else onCyclePreset() }
                            }
                        }
                    }
                }

                if (hudLayer == HudOverlayLayer.QUICK_ACTION_BAR) {
                    Row(
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 28.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        quickActionItems.forEachIndexed { idx, actionItem ->
                            val isSelected = idx == selectedQuickIdx
                            val circleScale by animateFloatAsState(if (isSelected) 1.14f else 1.0f, tween(120, easing = FastOutSlowInEasing), label = "QScale")
                            Column(modifier = Modifier.width(96.dp).clickable { selectedQuickIdx = idx; actionItem.onActivate() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .size(50.dp)
                                        .graphicsLayer { scaleX = circleScale; scaleY = circleScale }
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color.White else AetherColors.ObsidianBlack.copy(alpha = 0.50f))
                                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) AetherColors.CyberCyan else Color.White.copy(alpha = 0.22f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(actionItem.icon, color = if (isSelected) AetherColors.ObsidianBlack else Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(actionItem.label, color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                if (hudLayer == HudOverlayLayer.SUBTITLE_DRAWER || hudLayer == HudOverlayLayer.AUDIO_DRAWER) {
                    val isSubDrawer = hudLayer == HudOverlayLayer.SUBTITLE_DRAWER
                    val drawerTitle = if (isSubDrawer) "Untertitel" else "Audio-Spur"
                    val options = if (isSubDrawer) subtitleDrawerOptions else audioDrawerOptions
                    val activeOptionIdx = if (isSubDrawer) { if (selectedSubOverrideIdx >= 0) selectedSubOverrideIdx else if (globalSubtitlesEnabled) 1 else 0 } else selectedAudioOverrideIdx.coerceIn(0, (options.size - 1).coerceAtLeast(0))

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 28.dp, top = 36.dp, bottom = 36.dp)
                            .width(380.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AetherColors.DeepSpaceNavy.copy(alpha = 0.96f))
                            .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                            .padding(22.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(drawerTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            options.forEachIndexed { idx, label ->
                                val isRowFocused = selectedDrawerIdx == idx
                                val isRadioChecked = activeOptionIdx == idx
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isRowFocused) Color.White else Color.Transparent)
                                        .clickable { selectedDrawerIdx = idx; if (isSubDrawer) selectedSubOverrideIdx = idx else selectedAudioOverrideIdx = idx }
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(if (isRadioChecked) "◉" else "○", color = if (isRowFocused) AetherColors.ObsidianBlack else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Text(label, color = if (isRowFocused) AetherColors.ObsidianBlack else Color.White, fontSize = 14.sp, fontWeight = if (isRowFocused || isRadioChecked) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
                                }
                            }
                            if (isSubDrawer) {
                                Text("Untertitel sind trotz der verfügbaren Optionen möglicherweise nicht immer vorhanden", color = AetherColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                val isToggleRowFocused = selectedDrawerIdx == options.size
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isToggleRowFocused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { selectedDrawerIdx = options.size; globalSubtitlesEnabled = !globalSubtitlesEnabled }
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Falls verfügbar, Untertitel für alle\nKanäle anzeigen", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                                    Box(
                                        modifier = Modifier.width(40.dp).height(22.dp).clip(CircleShape).background(if (globalSubtitlesEnabled) AetherColors.CyberCyan.copy(alpha = 0.45f) else AetherColors.ObsidianBlack),
                                        contentAlignment = if (globalSubtitlesEnabled) Alignment.CenterEnd else Alignment.CenterStart
                                    ) {
                                        Box(modifier = Modifier.padding(3.dp).size(16.dp).clip(CircleShape).background(if (globalSubtitlesEnabled) AetherColors.CyberCyan else AetherColors.TextSecondary))
                                    }
                                }
                                Text("Du kannst das Erscheinungsbild von Untertiteln unter „Systemeinstellungen -> Barrierefreiheit -> Untertitel“ anpassen.", color = AetherColors.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhiteHudTechBadge(text: String) {
    Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = AetherColors.ObsidianBlack, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun HudTransportCircleButton(symbol: String, isSelected: Boolean, isLargeCenter: Boolean, onClick: () -> Unit) {
    val sizeDp = if (isLargeCenter) 48.dp else 36.dp
    val bgColor = when { isSelected -> Color.White; isLargeCenter -> Color.White.copy(alpha = 0.85f); else -> Color.Transparent }
    val textColor = if (isSelected || isLargeCenter) AetherColors.ObsidianBlack else Color.White
    Box(
        modifier = Modifier.size(sizeDp).clip(CircleShape).background(bgColor).border(if (isSelected) 2.dp else 0.dp, if (isSelected) AetherColors.CyberCyan else Color.Transparent, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = textColor, fontSize = if (isLargeCenter) 18.sp else 15.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
    }
}

private fun formatMillisToTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L; val minutes = (totalSeconds % 3600L) / 60L; val seconds = totalSeconds % 60L
    val mStr = minutes.toString().padStart(2, '0'); val sStr = seconds.toString().padStart(2, '0')
    return if (hours > 0L) "${hours.toString().padStart(2, '0')}:$mStr:$sStr" else "$mStr:$sStr"
}
