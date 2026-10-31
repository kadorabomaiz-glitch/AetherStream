package com.aether.stream.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

const val SYS_QUICK_FAV = "SYS_QUICK_FAV"
const val SYS_RECENT = "SYS_RECENT"
const val FOLDER_PREFIX = "FOLDER_"
private const val TIMELINE_WINDOW_MINUTES = 120L
private const val TIMELINE_WINDOW_MILLIS = TIMELINE_WINDOW_MINUTES * 60_000L
private const val HALF_HOUR_MILLIS = 30L * 60_000L

private enum class CockpitViewportStage { STAGE_1_SIDEBAR_EXPANDED, STAGE_2_CATEGORIES_ACTIVE, STAGE_3_CONTENT_MAXIMIZED }
private enum class HudOverlayLayer { TRANSPORT_BAR, QUICK_ACTION_BAR, SUBTITLE_DRAWER, AUDIO_DRAWER, VIDEO_DRAWER }

private object AetherPosterImageCache {
    private val memoryCache = LinkedHashMap<String, ImageBitmap>()
    private val failedUrls = HashSet<String>()
    private val sem = Semaphore(4)
    private val http by lazy { HttpClient() }

    fun getCached(url: String?): ImageBitmap? {
        val c = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        return memoryCache[c]?.also { memoryCache.remove(c); memoryCache[c] = it }
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun fetchOrGet(url: String?): ImageBitmap? {
        val c = url?.trim()?.takeIf { it.startsWith("http") } ?: return null
        getCached(c)?.let { return it }
        if (failedUrls.contains(c)) return null
        val bmp = withContext(Dispatchers.IO) {
            sem.withPermit {
                runCatching {
                    val res = http.get(c) { header("User-Agent", "TiviMate/4.7.0 (Android 11; FireTV)") }
                    if (!res.status.isSuccess()) return@runCatching null
                    val bytes: ByteArray = res.body()
                    if (bytes.isEmpty() || bytes.size > 3_500_000) null else bytes.decodeToImageBitmap()
                }.getOrNull()
            }
        }
        if (bmp != null) { if (memoryCache.size >= 80) memoryCache.keys.firstOrNull()?.let { memoryCache.remove(it) }; memoryCache[c] = bmp }
        else { if (failedUrls.size > 500) failedUrls.clear(); failedUrls.add(c) }
        return bmp
    }
}

@Composable
private fun rememberAetherAsyncBitmap(imageUrl: String?, debounceMs: Long = 80L): ImageBitmap? {
    var bmp by remember(imageUrl) { mutableStateOf(AetherPosterImageCache.getCached(imageUrl)) }
    LaunchedEffect(imageUrl) {
        if (bmp == null && !imageUrl.isNullOrBlank() && imageUrl.startsWith("http")) {
            if (debounceMs > 0L) delay(debounceMs)
            bmp = AetherPosterImageCache.fetchOrGet(imageUrl)
        }
    }
    return bmp
}

private data class EpgTimelineClockSnapshot(val dateTimeLabelDe: String, val slotLabels: List<String>, val playheadFraction: Float, val nowEpochMillis: Long, val windowStartEpochMillis: Long, val windowEndEpochMillis: Long)
private data class NormalizedEpgTimelineSlot(val slotId: String, val title: String, val description: String, val startEpochMillis: Long, val endEpochMillis: Long, val visibleStartMillis: Long, val visibleEndMillis: Long, val weightFraction: Float, val hasRealProgramInfo: Boolean) {
    fun toEpgProgramEntity(channelId: String) = EpgProgramEntity(epgChannelId = channelId, title = title, description = description, startEpochMillis = startEpochMillis, endEpochMillis = endEpochMillis)
}

private object AetherEpgMatrixCache {
    private val slotCache = LinkedHashMap<String, List<NormalizedEpgTimelineSlot>>()

    fun getOrBuildNormalizedSlots(stream: StreamItemEntity, rawPrograms: List<EpgProgramEntity>, windowStartMs: Long, windowEndMs: Long): List<NormalizedEpgTimelineSlot> {
        val key = "${stream.uniqueStreamKey}_${windowStartMs}_${rawPrograms.size}"
        slotCache[key]?.let { slotCache.remove(key); slotCache[key] = it; return it }
        val totalMs = (windowEndMs - windowStartMs).coerceAtLeast(60_000L)
        val real = rawPrograms.filter { it.endEpochMillis > windowStartMs && it.startEpochMillis < windowEndMs }.sortedBy { it.startEpochMillis }
        val src = if (real.isNotEmpty()) real else synthesizePrograms(stream, windowStartMs, windowEndMs)
        val norm = ArrayList<NormalizedEpgTimelineSlot>(6)
        var cur = windowStartMs
        for (p in src) {
            val vs = p.startEpochMillis.coerceAtLeast(windowStartMs); val ve = p.endEpochMillis.coerceAtMost(windowEndMs)
            if (ve <= vs) continue
            if (vs > cur) norm.add(NormalizedEpgTimelineSlot("gap_$cur", "Keine Programminformationen", "Für diesen Zeitraum liegen keine Programminformationen vor.", cur, vs, cur, vs, ((vs - cur).toFloat() / totalMs).coerceIn(0.05f, 1f), false))
            val t = p.title.ifBlank { "Keine Programminformationen" }
            norm.add(NormalizedEpgTimelineSlot("${p.startEpochMillis}_${t.hashCode()}", t, p.description?.takeIf { it.isNotBlank() } ?: "Live-Übertragung auf ${stream.name}.", p.startEpochMillis, p.endEpochMillis, vs, ve, ((ve - vs).coerceAtLeast(60_000L).toFloat() / totalMs).coerceIn(0.08f, 1f), t != "Keine Programminformationen"))
            cur = ve
        }
        if (cur < windowEndMs) norm.add(NormalizedEpgTimelineSlot("tail_$cur", "Keine Programminformationen", "Für diesen Zeitraum liegen keine Programminformationen vor.", cur, windowEndMs, cur, windowEndMs, ((windowEndMs - cur).toFloat() / totalMs).coerceIn(0.05f, 1f), false))
        if (slotCache.size >= 300) slotCache.keys.firstOrNull()?.let { slotCache.remove(it) }
        slotCache[key] = norm
        return norm
    }

    private fun synthesizePrograms(stream: StreamItemEntity, windowStartMs: Long, windowEndMs: Long): List<EpgProgramEntity> {
        val seed = stream.uniqueStreamKey.fold(0) { a, c -> (a * 31 + c.code) and 0x7FFFFFFF }
        val up = stream.name.uppercase()
        val lbl = stream.name.replace(Regex("^(\\|?[A-Z]{2,3}\\|?\\s*[-|:]?\\s*)"), "").trim().ifBlank { stream.name }
        val tpls = when {
            up.contains("SPORT") || up.contains("DAZN") || up.contains("BUNDESLIGA") -> listOf("$lbl Live-Konferenz" to "Live-Sportübertragung mit Vorberichten und Highlights in Echtzeit.", "Top-Spiel Analyse & Highlights" to "Alle Tore und Experten-Stimmen zum aktuellen Spieltag.", "Sport News Update" to "Aktuelle Transfer-Meldungen und Live-Schalten.")
            up.contains("NEWS") || up.contains("NTV") || up.contains("WELT") -> listOf("Nachrichten & Börse Live" to "Aktuelle Meldungen aus Politik, Wirtschaft und Gesellschaft.", "Brennpunkt & Hintergründe" to "Vertiefende Korrespondentenberichte zu den Themen des Tages.")
            up.contains("CINEMA") || up.contains("FILM") || up.contains("KINO") -> listOf("Blockbuster Premiere: $lbl" to "Spannendes Spielfilm-Highlight in bester Bild- und Tonqualität.", "Prime-Time Thriller Special" to "Hochspannender Spielfilmabend auf $lbl.")
            else -> listOf("$lbl – Live Programm" to (stream.plotDescription?.takeIf { it.isNotBlank() } ?: "Aktuelle Live-Sendung auf $lbl."), "Das Magazin am Abend" to "Unterhaltung, Reportagen und aktuelle Themen auf $lbl.")
        }
        val durs = listOf(45L, 30L, 45L, 60L)
        var cur = (windowStartMs / (120L * 60_000L)) * (120L * 60_000L) - (((seed % 3) * 15L) * 60_000L)
        val out = ArrayList<EpgProgramEntity>(6); var step = 0
        while (cur < windowEndMs) {
            val end = cur + durs[(step + seed) % durs.size] * 60_000L
            val tpl = tpls[(step + seed) % tpls.size]
            if (end > windowStartMs) out.add(EpgProgramEntity(epgChannelId = stream.epgChannelId ?: stream.uniqueStreamKey, title = tpl.first, description = tpl.second, startEpochMillis = cur, endEpochMillis = end))
            cur = end; step++
        }
        return out
    }
}

@Composable
private fun rememberEpgTimelineClockSnapshot(windowOffsetMinutes: Int = 0): EpgTimelineClockSnapshot {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000L); tick++ } }
    return remember(tick, windowOffsetMinutes) {
        val now = Clock.System.now(); val nowMs = now.toEpochMilliseconds(); val ldt = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val d = when (ldt.dayOfWeek) { DayOfWeek.MONDAY -> "Mo."; DayOfWeek.TUESDAY -> "Di."; DayOfWeek.WEDNESDAY -> "Mi."; DayOfWeek.THURSDAY -> "Do."; DayOfWeek.FRIDAY -> "Fr."; DayOfWeek.SATURDAY -> "Sa."; else -> "So." }
        val m = when (ldt.month) { Month.JANUARY -> "Jan."; Month.FEBRUARY -> "Feb."; Month.MARCH -> "März"; Month.APRIL -> "Apr."; Month.MAY -> "Mai"; Month.JUNE -> "Juni"; Month.JULY -> "Juli"; Month.AUGUST -> "Aug."; Month.SEPTEMBER -> "Sept."; Month.OCTOBER -> "Okt."; Month.NOVEMBER -> "Nov."; else -> "Dez." }
        val startMs = ((nowMs / HALF_HOUR_MILLIS) * HALF_HOUR_MILLIS) + (windowOffsetMinutes.toLong() * 60_000L)
        val endMs = startMs + TIMELINE_WINDOW_MILLIS
        val slots = List(4) { i -> formatEpochToClockTime(startMs + i * HALF_HOUR_MILLIS) }
        EpgTimelineClockSnapshot("$d, ${ldt.dayOfMonth}. $m, ${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}", slots, (nowMs - startMs).toFloat() / TIMELINE_WINDOW_MILLIS.toFloat(), nowMs, startMs, endMs)
    }
}

private fun formatEpochToClockTime(ms: Long): String = if (ms <= 0L) "" else runCatching {
    val l = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    "${l.hour.toString().padStart(2, '0')}:${l.minute.toString().padStart(2, '0')}"
}.getOrDefault("")

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
    val scope = rememberCoroutineScope()
    val railReq = remember { FocusRequester() }; val activeCatReq = remember { FocusRequester() }; val firstCatReq = remember { FocusRequester() }; val mediaReq = remember { FocusRequester() }
    var stage by remember { mutableStateOf(CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE) }
    var catJob by remember { mutableStateOf<Job?>(null) }; var heroJob by remember { mutableStateOf<Job?>(null) }
    var previewStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var isFullscreen by remember { mutableStateOf(startInPureFullscreen) }
    var offsetMin by remember { mutableIntStateOf(0) }
    val clock = rememberEpgTimelineClockSnapshot(offsetMin)
    var cursorMs by remember { mutableLongStateOf(clock.nowEpochMillis) }
    var previewEpg by remember { mutableStateOf<EpgProgramEntity?>(null) }

    LaunchedEffect(clock.windowStartEpochMillis, clock.windowEndEpochMillis) {
        if (cursorMs < clock.windowStartEpochMillis || cursorMs >= clock.windowEndEpochMillis) cursorMs = (clock.windowStartEpochMillis + 5 * 60_000L).coerceAtMost(clock.windowEndEpochMillis - 60_000L)
    }
    val reqCatFocus: () -> Unit = remember { { offsetMin = 0; stage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE; if (!runCatching { activeCatReq.requestFocus() }.isSuccess) runCatching { firstCatReq.requestFocus() } } }
    val reqRailFocus: () -> Unit = remember { { stage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED; runCatching { railReq.requestFocus() } } }
    LaunchedEffect(startInPureFullscreen, currentPlayingStream?.uniqueStreamKey) { if (startInPureFullscreen && currentPlayingStream != null) isFullscreen = true }

    val catItems = remember(categories, favoriteFolders, activeSection) {
        buildList {
            add(CockpitCategoryUiItem(SYS_QUICK_FAV, "Meine Liste", "⭐ ", true))
            add(CockpitCategoryUiItem(SYS_RECENT, "Verlauf", "🕒 ", true))
            favoriteFolders.forEach { f -> add(CockpitCategoryUiItem("$FOLDER_PREFIX${f.folderId}", f.folderName, "📁 ", true)) }
            categories.forEach { c -> add(CockpitCategoryUiItem(c.uniqueCategoryKey, c.categoryName, "", false, c.isNewlyDiscovered)) }
        }
    }
    val activeCatTitle = remember(catItems, selectedCategoryKey) { catItems.firstOrNull { it.key == selectedCategoryKey }?.title ?: "" }
    val catListState = rememberLazyListState(); val mediaListState = rememberLazyListState(); val vodGridState = rememberLazyGridState()

    LaunchedEffect(streamsInCategory, activeSection) { if (activeSection != AetherSectionType.LIVE) previewStream = streamsInCategory.firstOrNull() }
    LaunchedEffect(activeSection) { heroJob?.cancel(); offsetMin = 0; previewStream = streamsInCategory.firstOrNull(); catListState.scrollToItem(0); mediaListState.scrollToItem(0); vodGridState.scrollToItem(0) }
    LaunchedEffect(selectedCategoryKey) { heroJob?.cancel(); offsetMin = 0; mediaListState.scrollToItem(0); vodGridState.scrollToItem(0) }

    val z2Vis = stage != CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
    val z2W = animateDpAsState(if (z2Vis) 250.dp else 0.dp, tween(180, easing = FastOutSlowInEasing), label = "Z2W")
    val z2A = animateFloatAsState(if (z2Vis) 1f else 0f, tween(140, easing = LinearOutSlowInEasing), label = "Z2A")
    val isGrid = activeSection == AetherSectionType.VOD || activeSection == AetherSectionType.SERIES

    Box(
        modifier = Modifier.fillMaxSize().background(AetherColors.ObsidianBlack).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.Back || e.key == Key.Escape)) {
                when { isFullscreen -> false; stage == CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED -> { reqCatFocus(); true }; stage == CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE -> { reqRailFocus(); true }; else -> false }
            } else false
        }
    ) {
        if (isFullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                AetherVideoSurface(engine = playerEngine, modifier = Modifier.fillMaxSize())
                FullscreenPlayerHudOverlay(
                    playerEngine = playerEngine,
                    streamTitle = currentPlayingStream?.name ?: "Aether Stream",
                    isCinemaMode = isVodOrSeriesMode || startInPureFullscreen,
                    currentPreset = currentBufferPreset,
                    onCyclePreset = onCycleBufferPreset,
                    onOpenSettings = onOpenSettings,
                    onZapChannelOffset = onZapChannelOffset,
                    onExitFullscreen = { isFullscreen = false; onBackToDashboard() },
                    streamKey = currentPlayingStream?.uniqueStreamKey ?: currentPlayingStream?.name ?: "DEFAULT_STREAM"
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                LeftNavigationRailZone1(stage, activeSection, selectedCategoryKey, railReq, { stage = CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED }, reqCatFocus, { catJob?.cancel(); onSelectSection(it) }, { catJob?.cancel(); onSelectCategory(SYS_QUICK_FAV) }, onOpenSearch, onOpenSettings)

                Column(
                    modifier = Modifier.fillMaxHeight().layout { m, c ->
                        val fix = 250.dp.roundToPx(); val gap = 10.dp.roundToPx(); val vis = z2W.value.roundToPx().coerceIn(0, fix)
                        val tGap = if (vis > 0) ((vis.toFloat() / fix) * gap).toInt() else 0
                        val p = m.measure(c.copy(minWidth = fix, maxWidth = fix))
                        layout(vis + tGap, p.height) { p.placeRelative(vis - fix, 0) }
                    }.graphicsLayer { alpha = z2A.value }.clip(RoundedCornerShape(12.dp)).clipToBounds().background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).onFocusChanged { if (it.hasFocus) stage = CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE }.padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(when (activeSection) { AetherSectionType.LIVE -> "LIVE TV KATEGORIEN"; AetherSectionType.VOD -> "FILME"; AetherSectionType.SERIES -> "SERIEN" }, color = AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${categories.size}", color = AetherColors.IceBlue.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(state = catListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(items = catItems, key = { _, it -> "${activeSection.name}_${it.key}" }) { idx, item ->
                            val sel = item.key == selectedCategoryKey
                            val mod = when { sel -> Modifier.focusRequester(activeCatReq); idx == 0 -> Modifier.focusRequester(firstCatReq); else -> Modifier }
                            ObsidianCategoryRowItem(item, sel, mod,
                                onFocusSettled = { k -> if (k != selectedCategoryKey) { catJob?.cancel(); catJob = scope.launch { delay(120L); onSelectCategory(k) } } },
                                onInstantCommit = { k -> catJob?.cancel(); if (k != selectedCategoryKey) onSelectCategory(k) },
                                onNavigateLeftToRail = { catJob?.cancel(); reqRailFocus() },
                                onNavigateRightToMediaList = { k -> catJob?.cancel(); if (k != selectedCategoryKey) onSelectCategory(k); stage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED; runCatching { mediaReq.requestFocus() } }
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isGrid) {
                        Row(modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(12.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.82f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            var miniFocus by remember { mutableStateOf(false) }
                            Box(
                                modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black).border(if (miniFocus) 2.dp else 1.dp, if (miniFocus) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                    .onFocusChanged { miniFocus = it.isFocused; if (it.isFocused) stage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED }.focusable().clickable { if (currentPlayingStream != null) isFullscreen = true }
                                    .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) when (e.key) { Key.DirectionCenter, Key.Enter -> { if (currentPlayingStream != null) isFullscreen = true; true }; Key.DirectionLeft -> { reqCatFocus(); true }; Key.DirectionDown -> { runCatching { mediaReq.requestFocus() }; true }; else -> false } else false }
                            ) {
                                AetherVideoSurface(engine = playerEngine, modifier = Modifier.fillMaxSize())
                                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(if (currentPlayingStream != null) "LIVE PREVIEW • OK = VOLLBILD" else "BEREIT", color = AetherColors.CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            TiviMateDetailsPanel(playerEngine, previewStream ?: currentPlayingStream, previewEpg?.let { listOf(it) } ?: currentChannelEpg, activeCatTitle, clock.nowEpochMillis, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        TiviMateVodSeriesHeroPanel(previewStream ?: streamsInCategory.firstOrNull(), activeSection, activeCatTitle, Modifier.fillMaxWidth().height(225.dp))
                    }

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.78f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).onFocusChanged { if (it.hasFocus) stage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED }.padding(10.dp)) {
                        val gridW = maxWidth
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
                                Text(when (selectedCategoryKey) { SYS_QUICK_FAV -> "Noch keine Favoriten in 'Meine Liste' markiert (★)."; SYS_RECENT -> "Noch keine Einträge im Verlauf vorhanden."; else -> "Keine Einträge in dieser Kategorie vorhanden." }, color = AetherColors.IceBlue.copy(alpha = 0.7f), fontSize = 14.sp)
                            }
                        } else if (!isGrid) {
                            val colW = 285.dp; val red = Color(0xFFFF3B5C)
                            Column(modifier = Modifier.fillMaxSize()) {
                                TiviMateLiveEpgHeader(clock, colW, offsetMin)
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(modifier = Modifier.fillMaxSize().drawWithContent {
                                    drawContent()
                                    if (clock.playheadFraction in 0f..1f) {
                                        val lPx = colW.toPx(); val nx = lPx + (size.width - lPx).coerceAtLeast(1f) * clock.playheadFraction
                                        drawLine(AetherColors.CyberCyan.copy(alpha = 0.28f), Offset(nx, 0f), Offset(nx, size.height), 5.dp.toPx())
                                        drawLine(red, Offset(nx, 0f), Offset(nx, size.height), 1.8.dp.toPx())
                                        drawCircle(red, 4.dp.toPx(), Offset(nx, 4.dp.toPx()))
                                    }
                                }) {
                                    LazyColumn(state = mediaListState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                                        itemsIndexed(items = streamsInCategory, key = { _, s -> s.uniqueStreamKey }) { idx, stream ->
                                            val playing = currentPlayingStream?.uniqueStreamKey == stream.uniqueStreamKey
                                            val previewing = (previewStream?.uniqueStreamKey ?: currentPlayingStream?.uniqueStreamKey) == stream.uniqueStreamKey
                                            val slots = remember(stream.uniqueStreamKey, if (previewing || playing) currentChannelEpg else emptyList(), clock.windowStartEpochMillis, clock.windowEndEpochMillis) {
                                                AetherEpgMatrixCache.getOrBuildNormalizedSlots(stream, if (previewing || playing) currentChannelEpg else emptyList(), clock.windowStartEpochMillis, clock.windowEndEpochMillis)
                                            }
                                            Zone4MediaRowItem(
                                                indexNumber = idx + 1, stream = stream, isCurrentlyPlaying = playing, channelColumnWidth = colW, normalizedSlots = slots, focusedTimeCursorMs = cursorMs, timelineOffsetMinutes = offsetMin,
                                                modifier = if (idx == 0) Modifier.focusRequester(mediaReq) else Modifier,
                                                onFocusedChannelAndProgram = { stage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED; previewStream = stream; previewEpg = it },
                                                onMoveTimeCursor = { ms, p -> cursorMs = ms; previewEpg = p },
                                                onShiftTimelineWindow = { d -> val n = (offsetMin + d).coerceIn(0, 360); if (n != offsetMin) { offsetMin = n; cursorMs += d * 60_000L } },
                                                onClickStream = { if (playing) isFullscreen = true else { isFullscreen = false; onSelectStream(stream) } },
                                                onToggleFavorite = { onQuickFavoriteToggle(stream) }, onOpenFolderModal = { onOpenFavoriteFolderDialog(stream) }, onNavigateLeftToCategories = reqCatFocus
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val cols = remember(gridW) { (gridW / 142.dp).toInt().coerceIn(3, 8) }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(activeCatTitle.ifBlank { if (activeSection == AetherSectionType.VOD) "Filme" else "Serien" }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text("${streamsInCategory.size} Titel", color = AetherColors.IceBlue.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyVerticalGrid(columns = GridCells.Fixed(cols), state = vodGridState, modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp, top = 2.dp, start = 2.dp, end = 2.dp)) {
                                    itemsIndexed(items = streamsInCategory, key = { _, s -> s.uniqueStreamKey }) { idx, stream ->
                                        VodSeriesPosterCardItem(
                                            stream = stream, isLeftmostColumn = (idx % cols) == 0, modifier = if (idx == 0) Modifier.focusRequester(mediaReq) else Modifier,
                                            onFocused = { stage = CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED; heroJob?.cancel(); heroJob = scope.launch { delay(90L); previewStream = stream } },
                                            onClickStream = { heroJob?.cancel(); previewStream = stream; if (stream.sectionType == AetherSectionType.SERIES) onOpenSeriesCockpit(stream) else { isFullscreen = true; onSelectStream(stream) } },
                                            onToggleFavorite = { onQuickFavoriteToggle(stream) }, onOpenFolderModal = { onOpenFavoriteFolderDialog(stream) }, onNavigateLeftToCategories = reqCatFocus
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
private fun TiviMateLiveEpgHeader(clock: EpgTimelineClockSnapshot, colW: Dp, offsetMin: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(modifier = Modifier.width(colW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock.dateTimeLabelDe, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (offsetMin > 0) Box(modifier = Modifier.padding(end = 10.dp).clip(RoundedCornerShape(4.dp)).background(AetherColors.CyberCyan.copy(alpha = 0.2f)).padding(horizontal = 5.dp, vertical = 1.dp)) { Text("+${offsetMin}m", color = AetherColors.CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                clock.slotLabels.forEach { t -> Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Box(modifier = Modifier.width(1.5.dp).height(10.dp).background(AetherColors.CyberCyan.copy(alpha = 0.45f))); Spacer(modifier = Modifier.width(6.dp)); Text(t, color = AetherColors.IceBlue.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) } }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AetherColors.CyberCyan.copy(alpha = 0.30f)))
    }
}

@Composable
private fun Zone4MediaRowItem(indexNumber: Int, stream: StreamItemEntity, isCurrentlyPlaying: Boolean, channelColumnWidth: Dp, normalizedSlots: List<NormalizedEpgTimelineSlot>, focusedTimeCursorMs: Long, timelineOffsetMinutes: Int, modifier: Modifier = Modifier, onFocusedChannelAndProgram: (EpgProgramEntity?) -> Unit, onMoveTimeCursor: (Long, EpgProgramEntity?) -> Unit, onShiftTimelineWindow: (Int) -> Unit, onClickStream: () -> Unit, onToggleFavorite: () -> Unit, onOpenFolderModal: () -> Unit, onNavigateLeftToCategories: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val logo = rememberAetherAsyncBitmap(stream.logoUrl, 100L)
    val activeIdx = remember(normalizedSlots, focusedTimeCursorMs) {
        if (normalizedSlots.isEmpty()) 0 else {
            val i = normalizedSlots.indexOfFirst { focusedTimeCursorMs >= it.visibleStartMillis && focusedTimeCursorMs < it.visibleEndMillis }
            when { i >= 0 -> i; focusedTimeCursorMs < normalizedSlots.first().visibleStartMillis -> 0; else -> normalizedSlots.lastIndex }
        }
    }
    LaunchedEffect(focused, activeIdx, normalizedSlots) { if (focused) onFocusedChannelAndProgram(normalizedSlots.getOrNull(activeIdx)?.toEpgProgramEntity(stream.epgChannelId ?: stream.uniqueStreamKey)) }

    Row(
        modifier = modifier.fillMaxWidth().height(42.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusedChannelAndProgram(normalizedSlots.getOrNull(activeIdx)?.toEpgProgramEntity(stream.epgChannelId ?: stream.uniqueStreamKey)) }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) when (e.key) {
                    Key.DirectionRight -> { if (activeIdx < normalizedSlots.lastIndex) { val n = normalizedSlots[activeIdx + 1]; onMoveTimeCursor(n.visibleStartMillis + (n.visibleEndMillis - n.visibleStartMillis) / 2L, n.toEpgProgramEntity(stream.epgChannelId ?: stream.uniqueStreamKey)) } else onShiftTimelineWindow(30); true }
                    Key.DirectionLeft -> { if (activeIdx > 0) { val p = normalizedSlots[activeIdx - 1]; onMoveTimeCursor(p.visibleStartMillis + (p.visibleEndMillis - p.visibleStartMillis) / 2L, p.toEpgProgramEntity(stream.epgChannelId ?: stream.uniqueStreamKey)) } else if (timelineOffsetMinutes > 0) onShiftTimelineWindow(-30) else onNavigateLeftToCategories(); true }
                    Key.DirectionCenter, Key.Enter -> { onClickStream(); true }; Key.Menu, Key.Bookmark -> { onOpenFolderModal(); true }; else -> false
                } else false
            }.focusable().clickable { onClickStream() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.width(channelColumnWidth).fillMaxHeight().padding(end = 6.dp).clip(RoundedCornerShape(6.dp)).background(when { focused -> AetherColors.DeepSpaceNavy.copy(alpha = 0.96f); isCurrentlyPlaying -> AetherColors.NeonEmerald.copy(alpha = 0.14f); else -> Color.Transparent }).border(if (focused) 1.dp else 0.dp, if (focused) AetherColors.CyberCyan.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$indexNumber", color = if (focused) Color.White else AetherColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
            Box(modifier = Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = if (logo != null) 0.92f else 0.08f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.30f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                if (logo != null) Image(logo, stream.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp)) else Text("TV", color = AetherColors.CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (isCurrentlyPlaying) Text("▶", color = AetherColors.NeonEmerald, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(end = 5.dp))
            Text(stream.name, color = when { focused -> AetherColors.CyberCyan; isCurrentlyPlaying -> AetherColors.NeonEmerald; else -> Color.White }, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { onToggleFavorite() }.padding(horizontal = 4.dp, vertical = 2.dp)) { Text(if (stream.isQuickFavorite) "★" else "☆", color = if (stream.isQuickFavorite) AetherColors.LaserAmber else AetherColors.TextSecondary.copy(alpha = 0.6f), fontSize = 13.sp) }
        }
        Row(modifier = Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            normalizedSlots.forEachIndexed { idx, slot ->
                val sf = focused && idx == activeIdx; val live = isCurrentlyPlaying && idx == 0 && timelineOffsetMinutes == 0
                Box(
                    modifier = Modifier.weight(slot.weightFraction.coerceAtLeast(0.05f)).fillMaxHeight().padding(end = 3.dp).clip(RoundedCornerShape(6.dp))
                        .background(when { sf -> Color.White; live -> AetherColors.CyberCyan.copy(alpha = 0.22f); focused -> AetherColors.CyberCyan.copy(alpha = 0.14f); else -> AetherColors.ObsidianBlack.copy(alpha = 0.52f) })
                        .border(if (sf) 1.5.dp else 1.dp, when { sf -> AetherColors.CyberCyan; live -> AetherColors.CyberCyan.copy(alpha = 0.55f); focused -> AetherColors.CyberCyan.copy(alpha = 0.35f); else -> AetherColors.CyberCyan.copy(alpha = 0.14f) }, RoundedCornerShape(6.dp))
                        .clickable { onMoveTimeCursor(slot.visibleStartMillis + (slot.visibleEndMillis - slot.visibleStartMillis) / 2L, slot.toEpgProgramEntity(stream.epgChannelId ?: stream.uniqueStreamKey)); onClickStream() }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) { Text(slot.title, color = when { sf -> AetherColors.ObsidianBlack; slot.hasRealProgramInfo -> Color.White; else -> AetherColors.TextSecondary.copy(alpha = 0.78f) }, fontSize = 12.sp, fontWeight = if (sf) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun TiviMateVodSeriesHeroPanel(stream: StreamItemEntity?, activeSection: AetherSectionType, activeCategoryTitle: String, modifier: Modifier = Modifier) {
    val col = remember(stream?.uniqueStreamKey) { resolveDeterministicPosterAccent(stream?.name ?: "AETH") }
    val bmp = rememberAetherAsyncBitmap(stream?.logoUrl, 40L)
    Box(modifier = modifier.clip(RoundedCornerShape(12.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.88f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clipToBounds()) {
        if (stream == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Wähle einen Titel aus dem Poster-Grid für Details.", color = AetherColors.IceBlue.copy(alpha = 0.7f), fontSize = 14.sp) }
        else {
            val meta = remember(stream.name, stream.rating, stream.totalDurationMs, stream.containerExtension) { extractHeroMetadataFromStream(stream) }
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).background(Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = 0.45f)))), contentAlignment = Alignment.CenterEnd) {
                if (bmp != null) Image(bmp, stream.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.65f })
                else Text(meta.monogram, color = Color.White.copy(alpha = 0.08f), fontSize = 110.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 18.dp))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(AetherColors.DeepSpaceNavy.copy(alpha = 0.98f), Color.Transparent))))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stream.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (activeCategoryTitle.isNotBlank()) DetailBadge(activeCategoryTitle, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.IceBlue)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 7.dp, vertical = 2.dp)) { Text(meta.ratingBadgeText, color = AetherColors.ObsidianBlack, fontSize = 12.sp, fontWeight = FontWeight.Black) }
                        Text(meta.infoLineText, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (meta.qualityTag.isNotBlank()) DetailBadge(meta.qualityTag, AetherColors.CyberCyan.copy(alpha = 0.5f), AetherColors.CyberCyan)
                    }
                    Text(stream.plotDescription?.takeIf { it.isNotBlank() } ?: "Drücke [OK] für direkten Start im Vollbild.", color = AetherColors.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.88f))
                }
                Text("Drücke [OK] für Vollbild-Start • [MENÜ] Favoriten-Ordner • [LINKS/ZURÜCK] Kategorien", color = AetherColors.CyberCyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VodSeriesPosterCardItem(stream: StreamItemEntity, isLeftmostColumn: Boolean, modifier: Modifier = Modifier, onFocused: () -> Unit, onClickStream: () -> Unit, onToggleFavorite: () -> Unit, onOpenFolderModal: () -> Unit, onNavigateLeftToCategories: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "PS")
    val col = remember(stream.uniqueStreamKey) { resolveDeterministicPosterAccent(stream.name) }
    val meta = remember(stream.name, stream.rating) { extractHeroMetadataFromStream(stream) }
    val bmp = rememberAetherAsyncBitmap(stream.logoUrl, 80L)
    Column(
        modifier = modifier.fillMaxWidth().height(215.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(10.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.85f)).border(if (focused) 2.dp else 1.dp, if (focused) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) when (e.key) { Key.DirectionLeft -> if (isLeftmostColumn) { onNavigateLeftToCategories(); true } else false; Key.DirectionCenter, Key.Enter -> { onClickStream(); true }; Key.Menu, Key.Bookmark -> { onOpenFolderModal(); true }; else -> false } else false }.focusable().clickable { onClickStream() }
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Brush.verticalGradient(listOf(col.copy(alpha = 0.55f), AetherColors.ObsidianBlack)))) {
            if (bmp != null) Image(bmp, stream.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(meta.monogram, color = Color.White.copy(alpha = 0.25f), fontSize = 36.sp, fontWeight = FontWeight.Black); Text(meta.cleanDisplayTitle, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2) }
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { onToggleFavorite() }.padding(4.dp)) { Text(if (stream.isQuickFavorite) "★" else "☆", color = if (stream.isQuickFavorite) AetherColors.LaserAmber else Color.White, fontSize = 14.sp) }
        }
        Box(Modifier.fillMaxWidth().height(34.dp).background(if (focused) AetherColors.CyberCyan.copy(alpha = 0.22f) else AetherColors.DeepSpaceNavy.copy(alpha = 0.95f)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(stream.name, color = Color.White, fontSize = 12.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class ExtractedHeroMetadata(val cleanDisplayTitle: String, val monogram: String, val ratingBadgeText: String, val infoLineText: String, val qualityTag: String)
private fun extractHeroMetadataFromStream(s: StreamItemEntity): ExtractedHeroMetadata {
    val raw = s.name.trim(); val strip = raw.replace(Regex("^(\\|?[A-Z]{2,3}\\|?\\s*[-|:]?\\s*)"), "").trim().ifBlank { raw }
    val w = strip.split(" ").filter { it.isNotBlank() }
    val mono = if (w.size >= 2) "${w[0].first().uppercaseChar()}${w[1].first().uppercaseChar()}" else strip.take(2).uppercase().ifBlank { "AE" }
    val rat = s.rating?.trim()?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.take(4) ?: if (s.sectionType == AetherSectionType.SERIES) "SERIE" else "VOD"
    val yr = Regex("\\b(19[7-9]\\d|20[0-2]\\d)\\b").find(raw)?.value ?: "2025"
    val up = raw.uppercase()
    val q = when { up.contains("4K") || up.contains("UHD") -> "4K UHD"; up.contains("FHD") || up.contains("1080") -> "FHD"; up.contains("HEVC") -> "HEVC"; else -> "" }
    return ExtractedHeroMetadata(strip, mono, rat, "$yr  •  ${if (s.sectionType == AetherSectionType.SERIES) "Serie" else "Spielfilm"}", q)
}
private fun resolveDeterministicPosterAccent(seed: String): Color {
    val p = listOf(Color(0xFF0284C7), Color(0xFF2563EB), Color(0xFF0D9488), Color(0xFF4F46E5), Color(0xFF0891B2), Color(0xFF059669))
    return p[(seed.fold(0) { a, c -> (a * 31 + c.code) and 0x7FFFFFFF }) % p.size]
}

@Composable
private fun LeftNavigationRailZone1(stage: CockpitViewportStage, activeSection: AetherSectionType, selectedCategoryKey: String, railReq: FocusRequester, onStageRail: () -> Unit, onNavRight: () -> Unit, onSelectSec: (AetherSectionType) -> Unit, onSelectFav: () -> Unit, onSearch: () -> Unit, onSettings: () -> Unit) {
    val targetW = when (stage) { CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED -> 230.dp; CockpitViewportStage.STAGE_2_CATEGORIES_ACTIVE -> 68.dp; CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED -> 0.dp }
    val exp = stage == CockpitViewportStage.STAGE_1_SIDEBAR_EXPANDED; val vis = stage != CockpitViewportStage.STAGE_3_CONTENT_MAXIMIZED
    val animW = animateDpAsState(targetW, tween(180), label = "Z1W"); val txtA = animateFloatAsState(if (exp) 1f else 0f, tween(120), label = "Z1T"); val conA = animateFloatAsState(if (vis) 1f else 0f, tween(140), label = "Z1C")
    val favAct = selectedCategoryKey == SYS_QUICK_FAV

    Column(
        modifier = Modifier.fillMaxHeight().layout { m, c ->
            val minP = 68.dp.roundToPx(); val maxP = 230.dp.roundToPx(); val gap = 10.dp.roundToPx(); val cur = animW.value.roundToPx().coerceIn(0, maxP)
            val p = m.measure(c.copy(minWidth = cur.coerceIn(minP, maxP), maxWidth = cur.coerceIn(minP, maxP)))
            val tGap = if (cur >= minP) gap else if (cur > 0) ((cur.toFloat() / minP) * gap).toInt() else 0
            layout(cur + tGap, p.height) { p.placeRelative(if (cur < minP) cur - minP else 0, 0) }
        }.graphicsLayer { alpha = conA.value }.clip(RoundedCornerShape(12.dp)).clipToBounds().background(Brush.horizontalGradient(listOf(AetherColors.ObsidianBlack, AetherColors.DeepSpaceNavy.copy(alpha = 0.94f)))).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).onFocusChanged { if (it.hasFocus) onStageRail() }.padding(vertical = 14.dp, horizontal = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Box(Modifier.clip(RoundedCornerShape(8.dp)).background(AetherColors.CyberCyan.copy(alpha = 0.16f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) { Text("AE", color = AetherColors.CyberCyan, fontSize = 14.sp, fontWeight = FontWeight.Black) } }
            Row(Modifier.graphicsLayer { alpha = txtA.value }, verticalAlignment = Alignment.CenterVertically) { Text("AETH", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold); Spacer(Modifier.width(6.dp)); Text("STREAM", color = AetherColors.CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RailIconButton("🔍", "Suchen", false, { txtA.value }, Modifier, onNavRight, onSearch)
            RailIconButton("📺", "TV", activeSection == AetherSectionType.LIVE && !favAct, { txtA.value }, if (activeSection == AetherSectionType.LIVE) Modifier.focusRequester(railReq) else Modifier, onNavRight) { onSelectSec(AetherSectionType.LIVE); onNavRight() }
            RailIconButton("🎬", "Filme", activeSection == AetherSectionType.VOD && !favAct, { txtA.value }, if (activeSection == AetherSectionType.VOD) Modifier.focusRequester(railReq) else Modifier, onNavRight) { onSelectSec(AetherSectionType.VOD); onNavRight() }
            RailIconButton("🍿", "Serien", activeSection == AetherSectionType.SERIES && !favAct, { txtA.value }, if (activeSection == AetherSectionType.SERIES) Modifier.focusRequester(railReq) else Modifier, onNavRight) { onSelectSec(AetherSectionType.SERIES); onNavRight() }
            RailIconButton("⏺", "Aufnahmen", false, { txtA.value }, Modifier, onNavRight, onNavRight)
            RailIconButton("🔖", "Meine Liste", favAct, { txtA.value }, Modifier, onNavRight) { onSelectFav(); onNavRight() }
        }
        Spacer(Modifier.weight(1f))
        RailIconButton("⚙️", "Einstellungen", false, { txtA.value }, Modifier, onNavRight, onSettings)
    }
}

@Composable
private fun RailIconButton(icon: String, label: String, isActive: Boolean, alphaP: () -> Float, modifier: Modifier = Modifier, onNavRight: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(when { focused -> AetherColors.CyberCyan.copy(alpha = 0.28f); isActive -> AetherColors.DeepSpaceNavy.copy(alpha = 0.95f); else -> Color.Transparent }).border(if (focused) 1.5.dp else 1.dp, when { focused -> AetherColors.CyberCyan; isActive -> AetherColors.CyberCyan.copy(alpha = 0.35f); else -> Color.Transparent }, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }.onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) when (e.key) { Key.DirectionRight -> { onNavRight(); true }; Key.DirectionLeft -> true; Key.DirectionCenter, Key.Enter -> { onClick(); true }; else -> false } else false }.focusable().clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(56.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { Text(icon, fontSize = 18.sp) }
        Text(label, color = when { focused -> Color.White; isActive -> AetherColors.CyberCyan; else -> AetherColors.TextSecondary }, fontSize = 14.sp, fontWeight = if (focused || isActive) FontWeight.Bold else FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(end = 12.dp).graphicsLayer { alpha = alphaP() })
    }
}

@Composable
private fun ObsidianCategoryRowItem(item: CockpitCategoryUiItem, isSelected: Boolean, modifier: Modifier = Modifier, onFocusSettled: (String) -> Unit, onInstantCommit: (String) -> Unit, onNavigateLeftToRail: () -> Unit, onNavigateRightToMediaList: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(when { focused -> AetherColors.CyberCyan.copy(alpha = 0.18f); isSelected -> AetherColors.DeepSpaceNavy.copy(alpha = 0.90f); else -> AetherColors.ObsidianBlack.copy(alpha = 0.40f) }).border(if (focused) 1.5.dp else if (isSelected) 1.dp else 0.dp, if (focused) AetherColors.CyberCyan else if (isSelected) AetherColors.CyberCyan.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocusSettled(item.key) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) when (e.key) { Key.DirectionCenter, Key.Enter -> { onInstantCommit(item.key); true }; Key.DirectionRight -> { onNavigateRightToMediaList(item.key); true }; Key.DirectionLeft -> { onNavigateLeftToRail(); true }; else -> false } else false }.focusable().clickable { onInstantCommit(item.key) }.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(when { focused -> AetherColors.CyberCyan; isSelected -> AetherColors.NeonEmerald; else -> Color.Transparent }))
        Spacer(Modifier.width(8.dp))
        Text("${item.iconPrefix}${item.title}", color = when { focused -> Color.White; isSelected -> AetherColors.IceBlue; else -> AetherColors.TextSecondary }, fontSize = 13.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TiviMateDetailsPanel(playerEngine: AetherVideoPlayerEngine, stream: StreamItemEntity?, currentEpg: List<EpgProgramEntity>, activeCategoryTitle: String = "", nowEpochMillis: Long = 0L, modifier: Modifier = Modifier) {
    val techMeta by playerEngine.techMetadata.collectAsState()
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(AetherColors.ObsidianBlack.copy(alpha = 0.55f)).border(1.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        if (stream == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Wähle einen Eintrag aus der Liste für Detailinformationen.", color = AetherColors.IceBlue.copy(alpha = 0.65f), fontSize = 13.sp) }
        else {
            val epg = currentEpg.firstOrNull()
            val title = if (!epg?.title.isNullOrBlank() && epg?.title != "Keine Programminformationen") epg!!.title else stream.name
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(activeCategoryTitle, color = AetherColors.IceBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (epg != null && epg.startEpochMillis > 0L && epg.endEpochMillis > epg.startEpochMillis) {
                        Text("${formatEpochToClockTime(epg.startEpochMillis)} – ${formatEpochToClockTime(epg.endEpochMillis)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DetailBadge(stream.name, AetherColors.CyberCyan.copy(alpha = 0.35f), AetherColors.CyberCyan)
                    if (techMeta.resolutionLabel.isNotBlank()) DetailBadge(techMeta.resolutionLabel, AetherColors.IceBlue.copy(alpha = 0.4f), AetherColors.IceBlue)
                    if (techMeta.videoCodec.isNotBlank()) DetailBadge(techMeta.videoCodec, AetherColors.CyberCyan.copy(alpha = 0.35f), Color.White)
                }
                Text(epg?.description?.takeIf { it.isNotBlank() } ?: stream.plotDescription?.takeIf { it.isNotBlank() } ?: "Live-Übertragung auf ${stream.name}.", color = AetherColors.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text("Drücke [LINKS/RECHTS] für Zeitachse • [OK] Wiedergabe/Vollbild • [MENÜ] Favoriten", color = AetherColors.CyberCyan.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun DetailBadge(text: String, borderColor: Color, textColor: Color) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(AetherColors.DeepSpaceNavy).border(1.dp, borderColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(text, color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

private object AetherPerStreamAudioDelayMatrix {
    private val _map = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delayMapFlow: StateFlow<Map<String, Int>> = _map
    fun getDelayMs(k: String): Int = _map.value[k] ?: 0
    fun applyStep(k: String, dir: Int, hold: Boolean): Int {
        val next = (getDelayMs(k) + dir * (if (hold) 100 else 50)).coerceIn(-5000, 5000)
        setDelayMs(k, next); return next
    }
    fun setDelayMs(k: String, ms: Int) { val c = ms.coerceIn(-5000, 5000); val m = _map.value.toMutableMap(); m[k] = c; _map.value = m }
}

private data class QuickHudActionItem(val icon: String, val title: String, val statusLabel: String, val onActivate: () -> Unit)

@Composable
fun FullscreenPlayerHudOverlay(playerEngine: AetherVideoPlayerEngine, streamTitle: String, isCinemaMode: Boolean, currentPreset: StreamBufferPreset, onCyclePreset: () -> Unit, onOpenSettings: () -> Unit = {}, onZapChannelOffset: (Int) -> Unit, onExitFullscreen: () -> Unit, streamKey: String = streamTitle) {
    val isPlaying by playerEngine.isPlaying.collectAsState()
    val techMeta by playerEngine.techMetadata.collectAsState()
    val audioTracks by playerEngine.audioTracks.collectAsState()
    val subtitleTracks by playerEngine.subtitleTracks.collectAsState()
    val delayMap by AetherPerStreamAudioDelayMatrix.delayMapFlow.collectAsState()
    val clock = rememberEpgTimelineClockSnapshot()
    val effKey = remember(streamKey, streamTitle) { streamKey.ifBlank { streamTitle }.ifBlank { "DEFAULT_STREAM" } }
    val curDelayMs = remember(delayMap, effKey) { delayMap[effKey] ?: 0 }
    val videoTracks = techMeta.availableVideoTracks

    LaunchedEffect(effKey, playerEngine) { playerEngine.setAudioDelayOffsetMs(AetherPerStreamAudioDelayMatrix.getDelayMs(effKey)) }

    var vis by remember { mutableStateOf(true) }
    var layer by remember { mutableStateOf(HudOverlayLayer.TRANSPORT_BAR) }
    var tunerActive by remember { mutableStateOf(false) }
    var transIdx by remember { mutableIntStateOf(2) }; var quickIdx by remember { mutableIntStateOf(0) }; var drawerIdx by remember { mutableIntStateOf(0) }
    var lastKeyMs by remember { mutableLongStateOf(0L) }; var lastDir by remember { mutableIntStateOf(0) }; var holdCnt by remember { mutableIntStateOf(0) }
    var sleepIdx by remember { mutableIntStateOf(0) }; var subIdx by remember { mutableIntStateOf(0) }; var audIdx by remember { mutableIntStateOf(0) }; var vidIdx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(0L) }
    val hudReq = remember { FocusRequester() }
    val sleepModes = remember { listOf("Aus", "30 Min", "60 Min", "90 Min") }

    LaunchedEffect(audioTracks) { val i = audioTracks.indexOfFirst { it.isSelected }; if (i >= 0) audIdx = i }
    LaunchedEffect(subtitleTracks) { val i = subtitleTracks.indexOfFirst { it.isSelected }; subIdx = if (i >= 0) i + 1 else 0 }

    val audLabel = remember(audioTracks, audIdx) { audioTracks.getOrNull(audIdx)?.let { "${it.languageLabel} (${it.codecLabel})" } ?: "Stereo" }
    val subOpts = remember(subtitleTracks) { buildList { add("Aus"); subtitleTracks.forEachIndexed { i, t -> add(t.languageLabel.ifBlank { "Untertitel ${i + 1}" }) } } }
    val audOpts = remember(audioTracks) { if (audioTracks.isEmpty()) listOf("Stereo (Standard)") else audioTracks.mapIndexed { i, t -> "${t.languageLabel.ifBlank { "Spur ${i + 1}" }} (${t.codecLabel})" } }
    val vidOpts = remember(videoTracks, currentPreset) { buildList { add("Automatisch (Beste Qualität)"); videoTracks.forEachIndexed { i, t -> add("${t.languageLabel.ifBlank { "Video ${i + 1}" }} [${t.codecLabel}]") }; add("⚡ Puffer: ${currentPreset.name}") } }
    val subLabel = remember(subIdx, subOpts) { subOpts.getOrElse(subIdx) { "Aus" } }
    val resText = techMeta.resolutionLabel.ifBlank { "1080p FHD" }
    val fmtDelay = if (curDelayMs > 0) "+$curDelayMs ms" else "$curDelayMs ms"

    fun applyAud(i: Int) { audIdx = i; audioTracks.getOrNull(i)?.let { playerEngine.selectAudioTrack(it) } }
    fun applySub(i: Int) { subIdx = i; if (i <= 0) playerEngine.selectSubtitleTrack(null) else playerEngine.selectSubtitleTrack(subtitleTracks.getOrNull(i - 1)) }
    fun applyVid(i: Int) { if (i == 0) { vidIdx = 0; playerEngine.selectVideoTrack(null) } else if (i - 1 in videoTracks.indices) { vidIdx = i; playerEngine.selectVideoTrack(videoTracks[i - 1]) } else onCyclePreset() }

    val quickItems = remember(resText, audLabel, fmtDelay, subLabel, techMeta.aspectModeLabel, sleepIdx, vidOpts) {
        listOf(
            QuickHudActionItem("📺", "TV", "Guide") { onExitFullscreen() },
            QuickHudActionItem("🗔", "Bild in Bild", "Mini-Player") { onExitFullscreen() },
            QuickHudActionItem("🎥", "Auflösung", resText) { tunerActive = false; drawerIdx = vidIdx.coerceIn(0, vidOpts.lastIndex); layer = HudOverlayLayer.VIDEO_DRAWER },
            QuickHudActionItem("🔊", "Audio", audLabel) { tunerActive = false; drawerIdx = audIdx.coerceIn(0, audOpts.lastIndex); layer = HudOverlayLayer.AUDIO_DRAWER },
            QuickHudActionItem("↔", "Audio-Sync", fmtDelay) { tunerActive = !tunerActive; holdCnt = 0 },
            QuickHudActionItem("CC", "Untertitel", subLabel) { tunerActive = false; drawerIdx = subIdx.coerceIn(0, subOpts.lastIndex); layer = HudOverlayLayer.SUBTITLE_DRAWER },
            QuickHudActionItem("⛶", "Bildformat", techMeta.aspectModeLabel) { playerEngine.cycleVideoAspectMode() },
            QuickHudActionItem("⏲", "Sleep-Timer", sleepModes[sleepIdx]) { sleepIdx = (sleepIdx + 1) % sleepModes.size },
            QuickHudActionItem("⚙️", "Einstellungen", "Menü") { onOpenSettings() }
        )
    }

    fun stepDelay(dir: Int) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (dir == lastDir && (now - lastKeyMs) in 1L..195L) holdCnt++ else holdCnt = 0
        lastKeyMs = now; lastDir = dir
        playerEngine.setAudioDelayOffsetMs(AetherPerStreamAudioDelayMatrix.applyStep(effKey, dir, holdCnt >= 1))
    }

    LaunchedEffect(Unit) { runCatching { hudReq.requestFocus() } }
    LaunchedEffect(tick, isPlaying, vis, layer, tunerActive) {
        if (vis && isPlaying && !tunerActive && (layer == HudOverlayLayer.TRANSPORT_BAR || layer == HudOverlayLayer.QUICK_ACTION_BAR)) { delay(5000L); vis = false; layer = HudOverlayLayer.TRANSPORT_BAR }
    }

    Box(
        modifier = Modifier.fillMaxSize().focusRequester(hudReq).focusable().onKeyEvent { e ->
            if (e.type == KeyEventType.KeyUp) { if (e.key == Key.DirectionLeft || e.key == Key.DirectionRight) holdCnt = 0; return@onKeyEvent true }
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            tick++
            if (!vis) { vis = true; layer = if (e.key == Key.DirectionDown) HudOverlayLayer.QUICK_ACTION_BAR else HudOverlayLayer.TRANSPORT_BAR; return@onKeyEvent true }
            if (tunerActive) return@onKeyEvent when (e.key) {
                Key.DirectionLeft -> { stepDelay(-1); true }; Key.DirectionRight -> { stepDelay(1); true }
                Key.DirectionDown -> { AetherPerStreamAudioDelayMatrix.setDelayMs(effKey, 0); playerEngine.setAudioDelayOffsetMs(0); true }
                else -> { tunerActive = false; holdCnt = 0; true }
            }
            when (layer) {
                HudOverlayLayer.TRANSPORT_BAR -> when (e.key) {
                    Key.DirectionDown -> { layer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                    Key.DirectionUp, Key.ChannelUp -> { if (!isCinemaMode) onZapChannelOffset(-1); true }; Key.ChannelDown -> { if (!isCinemaMode) onZapChannelOffset(1); true }
                    Key.DirectionLeft -> { transIdx = (transIdx - 1).coerceAtLeast(0); true }; Key.DirectionRight -> { transIdx = (transIdx + 1).coerceAtMost(5); true }
                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        val pos = playerEngine.currentPositionMs.value
                        when (transIdx) { 0 -> if (isCinemaMode) playerEngine.seekRelativeMs(-pos) else onZapChannelOffset(-1); 1 -> if (isCinemaMode) playerEngine.seekRelativeMs(-15_000L) else onZapChannelOffset(-1); 2 -> if (isPlaying) playerEngine.pause() else playerEngine.resume(); 3 -> if (isCinemaMode) playerEngine.seekRelativeMs(15_000L) else onZapChannelOffset(1); 4 -> if (isCinemaMode) playerEngine.seekRelativeMs(60_000L) else onZapChannelOffset(1); 5 -> if (isCinemaMode) playerEngine.seekRelativeMs(-pos) else onCyclePreset() }
                        true
                    }
                    Key.Back, Key.Escape -> { onExitFullscreen(); true }; else -> false
                }
                HudOverlayLayer.QUICK_ACTION_BAR -> when (e.key) {
                    Key.DirectionLeft -> { quickIdx = (quickIdx - 1).coerceAtLeast(0); true }; Key.DirectionRight -> { quickIdx = (quickIdx + 1).coerceAtMost(quickItems.lastIndex); true }
                    Key.DirectionUp -> { layer = HudOverlayLayer.TRANSPORT_BAR; transIdx = 2; true }; Key.Back, Key.Escape -> { vis = false; layer = HudOverlayLayer.TRANSPORT_BAR; true }
                    Key.DirectionCenter, Key.Enter -> { quickItems[quickIdx].onActivate(); true }; else -> true
                }
                HudOverlayLayer.SUBTITLE_DRAWER -> when (e.key) {
                    Key.DirectionUp -> { drawerIdx = (drawerIdx - 1).coerceAtLeast(0); true }; Key.DirectionDown -> { drawerIdx = (drawerIdx + 1).coerceAtMost(subOpts.lastIndex); true }
                    Key.DirectionCenter, Key.Enter -> { applySub(drawerIdx); layer = HudOverlayLayer.QUICK_ACTION_BAR; true }; else -> { layer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                }
                HudOverlayLayer.AUDIO_DRAWER -> when (e.key) {
                    Key.DirectionUp -> { drawerIdx = (drawerIdx - 1).coerceAtLeast(0); true }; Key.DirectionDown -> { drawerIdx = (drawerIdx + 1).coerceAtMost(audOpts.lastIndex); true }
                    Key.DirectionCenter, Key.Enter -> { applyAud(drawerIdx); layer = HudOverlayLayer.QUICK_ACTION_BAR; true }; else -> { layer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                }
                HudOverlayLayer.VIDEO_DRAWER -> when (e.key) {
                    Key.DirectionUp -> { drawerIdx = (drawerIdx - 1).coerceAtLeast(0); true }; Key.DirectionDown -> { drawerIdx = (drawerIdx + 1).coerceAtMost(vidOpts.lastIndex); true }
                    Key.DirectionCenter, Key.Enter -> { applyVid(drawerIdx); layer = HudOverlayLayer.QUICK_ACTION_BAR; true }; else -> { layer = HudOverlayLayer.QUICK_ACTION_BAR; true }
                }
            }
        }.clickable(remember { MutableInteractionSource() }, null) { tick++; vis = !vis }
    ) {
        AnimatedVisibility(vis, enter = fadeIn(tween(180)), exit = fadeOut(tween(160)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(AetherColors.ObsidianBlack.copy(alpha = 0.65f), Color.Transparent, AetherColors.ObsidianBlack.copy(alpha = 0.92f))))) {
                Text(clock.dateTimeLabelDe, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 32.dp, vertical = 22.dp))

                if (layer == HudOverlayLayer.TRANSPORT_BAR) {
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 36.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                            Text(streamTitle, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WhiteHudTechBadge(resText); WhiteHudTechBadge(techMeta.fpsLabel); WhiteHudTechBadge(audLabel.uppercase()); WhiteHudTechBadge(techMeta.aspectModeLabel.uppercase())
                                if (curDelayMs != 0) WhiteHudTechBadge("SYNC $fmtDelay")
                            }
                        }
                        HudIsolatedTimelineScrubber(playerEngine, isCinemaMode, currentPreset, isPlaying, transIdx, { transIdx = it }, onZapChannelOffset, onCyclePreset) { layer = HudOverlayLayer.QUICK_ACTION_BAR }
                    }
                }

                if (layer == HudOverlayLayer.QUICK_ACTION_BAR) {
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (tunerActive) {
                            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.96f)).border(1.5.dp, AetherColors.CyberCyan, RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text("🔊 Audio-Sync ($streamTitle):", color = AetherColors.IceBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 4.dp)) { Text("◀  $fmtDelay  ▶", color = AetherColors.ObsidianBlack, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold) }
                                Text("Klick: ±50ms • Halten: ±100ms • [UNTEN] = 0ms • [OK] = Fertig", color = AetherColors.CyberCyan, fontSize = 11.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            quickItems.forEachIndexed { idx, item ->
                                val sel = idx == quickIdx
                                val sc by animateFloatAsState(if (sel) 1.14f else 1f, tween(120), label = "QS")
                                Column(Modifier.width(96.dp).clickable { quickIdx = idx; item.onActivate() }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(52.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape).background(if (sel) Color.White else AetherColors.ObsidianBlack.copy(alpha = 0.58f)).border(if (sel) 2.dp else 1.dp, if (sel) AetherColors.CyberCyan else AetherColors.CyberCyan.copy(alpha = 0.32f), CircleShape), contentAlignment = Alignment.Center) {
                                        Text(item.icon, color = if (sel) AetherColors.ObsidianBlack else Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                                    Text(item.statusLabel, color = if (sel) AetherColors.CyberCyan else AetherColors.IceBlue.copy(alpha = 0.72f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                if (layer == HudOverlayLayer.SUBTITLE_DRAWER || layer == HudOverlayLayer.AUDIO_DRAWER || layer == HudOverlayLayer.VIDEO_DRAWER) {
                    val opts = when (layer) { HudOverlayLayer.SUBTITLE_DRAWER -> subOpts; HudOverlayLayer.AUDIO_DRAWER -> audOpts; else -> vidOpts }
                    val actIdx = when (layer) { HudOverlayLayer.SUBTITLE_DRAWER -> subIdx; HudOverlayLayer.AUDIO_DRAWER -> audIdx; else -> vidIdx }
                    val dTitle = when (layer) { HudOverlayLayer.SUBTITLE_DRAWER -> "Untertitel"; HudOverlayLayer.AUDIO_DRAWER -> "Audio-Spur"; else -> "Video-Auflösung & Puffer" }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 28.dp, top = 36.dp, bottom = 36.dp).width(390.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(AetherColors.DeepSpaceNavy.copy(alpha = 0.96f)).border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.40f), RoundedCornerShape(14.dp)).padding(22.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(dTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            opts.forEachIndexed { idx, lbl ->
                                val rf = drawerIdx == idx; val chk = actIdx == idx
                                Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(if (rf) Color.White else Color.Transparent).clickable { drawerIdx = idx; when (layer) { HudOverlayLayer.SUBTITLE_DRAWER -> applySub(idx); HudOverlayLayer.AUDIO_DRAWER -> applyAud(idx); else -> applyVid(idx) }; layer = HudOverlayLayer.QUICK_ACTION_BAR }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (chk) "◉" else "○", color = if (rf) AetherColors.ObsidianBlack else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(14.dp))
                                    Text(lbl, color = if (rf) AetherColors.ObsidianBlack else Color.White, fontSize = 13.sp, fontWeight = if (rf || chk) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
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
private fun HudIsolatedTimelineScrubber(playerEngine: AetherVideoPlayerEngine, isCinemaMode: Boolean, currentPreset: StreamBufferPreset, isPlaying: Boolean, selectedTransportIdx: Int, onSelectTransportIdx: (Int) -> Unit, onZapChannelOffset: (Int) -> Unit, onCyclePreset: () -> Unit, onOpenQuickBar: () -> Unit) {
    val pos by playerEngine.currentPositionMs.collectAsState(); val dur by playerEngine.durationMs.collectAsState()
    val prog = if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 1f
    BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.30f)))
        Box(Modifier.fillMaxWidth(prog).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        Box(Modifier.offset(x = (maxWidth * prog - 6.dp).coerceAtLeast(0.dp)).size(12.dp).clip(CircleShape).background(Color.White))
    }
    Box(Modifier.fillMaxWidth()) {
        Text(if (isCinemaMode && dur > 0L) "${formatMillisToTime(pos)} / ${formatMillisToTime(dur)}" else "LIVE • Puffer: ${currentPreset.name}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                HudTransportCircleButton("⏮", selectedTransportIdx == 0, false) { onSelectTransportIdx(0); if (isCinemaMode) playerEngine.seekRelativeMs(-pos) else onZapChannelOffset(-1) }
                HudTransportCircleButton("⏪", selectedTransportIdx == 1, false) { onSelectTransportIdx(1); if (isCinemaMode) playerEngine.seekRelativeMs(-15_000L) else onZapChannelOffset(-1) }
                HudTransportCircleButton(if (isPlaying) "▶" else "⏸", selectedTransportIdx == 2, true) { onSelectTransportIdx(2); if (isPlaying) playerEngine.pause() else playerEngine.resume() }
                HudTransportCircleButton("⏩", selectedTransportIdx == 3, false) { onSelectTransportIdx(3); if (isCinemaMode) playerEngine.seekRelativeMs(15_000L) else onZapChannelOffset(1) }
                HudTransportCircleButton("⏭", selectedTransportIdx == 4, false) { onSelectTransportIdx(4); if (isCinemaMode) playerEngine.seekRelativeMs(60_000L) else onZapChannelOffset(1) }
            }
            Text("⌄", color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onOpenQuickBar() })
        }
        Box(Modifier.align(Alignment.CenterEnd)) { HudTransportCircleButton("↺", selectedTransportIdx == 5, false) { onSelectTransportIdx(5); if (isCinemaMode) playerEngine.seekRelativeMs(-pos) else onCyclePreset() } }
    }
}

@Composable private fun WhiteHudTechBadge(text: String) { Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.92f)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(text, color = AetherColors.ObsidianBlack, fontSize = 10.sp, fontWeight = FontWeight.Black) } }
@Composable private fun HudTransportCircleButton(symbol: String, isSelected: Boolean, isLargeCenter: Boolean, onClick: () -> Unit) {
    val s = if (isLargeCenter) 48.dp else 36.dp
    Box(Modifier.size(s).clip(CircleShape).background(when { isSelected -> Color.White; isLargeCenter -> Color.White.copy(alpha = 0.85f); else -> Color.Transparent }).border(if (isSelected) 2.dp else 0.dp, if (isSelected) AetherColors.CyberCyan else Color.Transparent, CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { Text(symbol, color = if (isSelected || isLargeCenter) AetherColors.ObsidianBlack else Color.White, fontSize = if (isLargeCenter) 18.sp else 15.sp, fontWeight = FontWeight.Black) }
}
private fun formatMillisToTime(ms: Long): String { val s = (ms / 1000L).coerceAtLeast(0L); val h = s / 3600L; val m = (s % 3600L) / 60L; val sec = s % 60L; return if (h > 0L) "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" }
