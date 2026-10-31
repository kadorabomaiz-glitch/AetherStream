package com.aether.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aether.stream.core.navigation.AetherNavigationViewModel
import com.aether.stream.core.navigation.AetherScreen
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.platform.EnforceLandscapeCockpitMode
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.theme.AetherTheme
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.data.local.*
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperLockScreen
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.gatekeeper.SecretAdminPanelModal
import com.aether.stream.feature.player.AetherVideoPlayerEngine
import com.aether.stream.feature.player.LiveTvDualCockpitScreen
import com.aether.stream.feature.settings.*
import com.aether.stream.feature.vod.CombinedFavoriteFolderModal
import com.aether.stream.feature.vod.XtreamPlaylistSetupScreen
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

@Composable
fun AetherApp(
    dao: AetherDao,
    playerEngine: AetherVideoPlayerEngine,
    navigationViewModel: AetherNavigationViewModel,
    gatekeeperViewModel: GatekeeperViewModel,
    dashboardViewModel: DashboardAndFilterViewModel
) {
    EnforceLandscapeCockpitMode()

    val navState by navigationViewModel.uiState.collectAsState()
    val activePlaylist by dashboardViewModel.activePlaylist.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedSection by remember { mutableStateOf(AetherSectionType.LIVE) }
    var selectedCategoryKey by remember { mutableStateOf("") }
    var currentPlayingStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var currentPlayingEpisode by remember { mutableStateOf<SeriesEpisodeEntity?>(null) }
    var activeParentSeriesKey by remember { mutableStateOf<String?>(null) }
    var activeSeriesForModal by remember { mutableStateOf<StreamItemEntity?>(null) }
    var isCinemaPlayerActive by remember { mutableStateOf(false) }
    var folderDialogTargetStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var activeChannelEpgList by remember { mutableStateOf<List<EpgProgramEntity>>(emptyList()) }

    var isSearchModalVisible by remember { mutableStateOf(false) }
    var isSettingsModalVisible by remember { mutableStateOf(false) }
    var isLoadingCategoryStreams by remember { mutableStateOf(false) }

    val cockpitRootFocusRequester = remember { FocusRequester() }
    val hudSettingsReturnFocusRequester = remember { FocusRequester() }
    val fetchedCategoryKeys = remember { mutableSetOf<String>() }
    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val visibleCategories by remember(playlistId, selectedSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, selectedSection) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var liveTotalCount by remember { mutableStateOf(18957) }
    var vodTotalCount by remember { mutableStateOf(72231) }
    var seriesTotalCount by remember { mutableStateOf(13926) }

    LaunchedEffect(playlistId, visibleCategories.size, isLoadingCategoryStreams) {
        if (playlistId > 0L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val cLive = dao.getVisibleStreamCountForSection(playlistId, AetherSectionType.LIVE)
                    val cVod = dao.getVisibleStreamCountForSection(playlistId, AetherSectionType.VOD)
                    val cSer = dao.getVisibleStreamCountForSection(playlistId, AetherSectionType.SERIES)
                    if (cLive > 0) liveTotalCount = cLive
                    if (cVod > 0) vodTotalCount = cVod
                    if (cSer > 0) seriesTotalCount = cSer
                }
            }
        }
    }

    LaunchedEffect(currentPlayingStream?.uniqueStreamKey) {
        val stream = currentPlayingStream
        if (stream == null || stream.sectionType != AetherSectionType.LIVE) {
            activeChannelEpgList = emptyList()
        } else {
            val channelKey = stream.epgChannelId?.takeIf { it.isNotBlank() } ?: stream.uniqueStreamKey
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val cached = withContext(Dispatchers.IO) {
                runCatching { dao.getEpgForChannelInWindow(channelKey, nowMs - 3_600_000L, nowMs + 21_600_000L) }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty()) activeChannelEpgList = cached
            val freshRemote = withContext(Dispatchers.IO) {
                runCatching { dashboardViewModel.fetchShortEpgForChannelOnDemand(stream) }.getOrDefault(emptyList())
            }
            if (freshRemote.isNotEmpty()) activeChannelEpgList = freshRemote
        }
    }

    val resolvedPlaylistUrlOrName = remember(activePlaylist) {
        activePlaylist?.serverUrl?.takeIf { it.isNotBlank() } ?: activePlaylist?.playlistName?.takeIf { it.isNotBlank() } ?: "line.trxdnscloud.ru"
    }

    BindAetherSettingsLiveTelemetry(resolvedPlaylistUrlOrName, liveTotalCount, vodTotalCount, seriesTotalCount)

    BindAetherEngineParameters(
        onApplyHotSwapConfig = { hotSwapConfig ->
            val presets = StreamBufferPreset.entries
            if (presets.isNotEmpty()) {
                val mapped = when (hotSwapConfig.bufferSpec.presetLabel) {
                    "Keine" -> presets.first()
                    "Klein" -> presets.getOrElse(1) { presets.first() }
                    "Mittel" -> presets.getOrElse(presets.size / 2) { presets.first() }
                    "Groß" -> presets.getOrElse((presets.size - 2).coerceAtLeast(0)) { presets.last() }
                    else -> presets.last()
                }
                if (mapped != navState.bufferPreset) {
                    navigationViewModel.updateBufferPreset(mapped)
                    playerEngine.applyBufferPreset(mapped)
                }
            }
        }
    )

    LaunchedEffect(visibleCategories, selectedSection) {
        if (visibleCategories.isNotEmpty()) {
            val isSys = selectedCategoryKey == "SYS_QUICK_FAV" || selectedCategoryKey == "SYS_RECENT" || selectedCategoryKey.startsWith("FOLDER_")
            val inSec = isSys || visibleCategories.any { it.uniqueCategoryKey == selectedCategoryKey }
            if (selectedCategoryKey.isEmpty() || !inSec) selectedCategoryKey = visibleCategories.first().uniqueCategoryKey
        } else if (selectedCategoryKey.isEmpty()) {
            selectedCategoryKey = "SYS_QUICK_FAV"
        }
    }

    val rawStreamsFlow = remember(playlistId, selectedSection, selectedCategoryKey) {
        if (playlistId <= 0L || selectedCategoryKey.isEmpty()) flowOf(emptyList())
        else when {
            selectedCategoryKey == "SYS_RECENT" -> dao.observeRecentlyWatched(playlistId, selectedSection)
            selectedCategoryKey == "SYS_QUICK_FAV" -> dao.observeQuickFavorites(playlistId, selectedSection)
            selectedCategoryKey.startsWith("FOLDER_") -> {
                val fId = selectedCategoryKey.removePrefix("FOLDER_").toLongOrNull() ?: 0L
                dao.observeStreamsInFavoriteFolderForSection(fId, selectedSection)
            }
            else -> dao.observeVisibleStreamsInCategory(selectedCategoryKey)
        }
    }

    val streamsInSelectedCategory by rawStreamsFlow.collectAsState(initial = emptyList())

    LaunchedEffect(selectedCategoryKey, selectedSection, visibleCategories) {
        val isSys = selectedCategoryKey == "SYS_QUICK_FAV" || selectedCategoryKey == "SYS_RECENT" || selectedCategoryKey.startsWith("FOLDER_")
        if (!isSys && selectedCategoryKey.isNotEmpty() && selectedSection != AetherSectionType.LIVE) {
            val matchingCat = visibleCategories.firstOrNull { it.uniqueCategoryKey == selectedCategoryKey }
            if (matchingCat != null && fetchedCategoryKeys.add(selectedCategoryKey)) {
                val cnt = withContext(Dispatchers.IO) { dao.getStreamCountInCategory(matchingCat.uniqueCategoryKey) }
                if (cnt == 0) {
                    isLoadingCategoryStreams = true
                    withContext(Dispatchers.IO) { runCatching { dashboardViewModel.fetchCategoryStreamsOnDemand(matchingCat) } }
                    isLoadingCategoryStreams = false
                }
            }
        }
    }

    suspend fun persistVodOrSeriesCheckpoint() {
        if (!isCinemaPlayerActive) return
        val pos = playerEngine.currentPositionMs.value
        val dur = playerEngine.durationMs.value
        if (pos < 10_000L) return
        val effectivePos = if (dur > 0L && pos >= (dur * 0.95).toLong()) 0L else pos
        val now = Clock.System.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) {
            val ep = currentPlayingEpisode; val st = currentPlayingStream
            if (ep != null) {
                dao.updateEpisodeWatchProgress(ep.uniqueEpisodeKey, effectivePos, dur.coerceAtLeast(0L), now)
                activeParentSeriesKey?.let { dao.updateWatchProgress(it, effectivePos, dur.coerceAtLeast(0L), now) }
            } else if (st != null && st.sectionType != AetherSectionType.LIVE) {
                dao.updateWatchProgress(st.uniqueStreamKey, effectivePos, dur.coerceAtLeast(0L), now)
            }
        }
    }

    val isEnginePlaying by playerEngine.isPlaying.collectAsState()
    LaunchedEffect(isEnginePlaying, isCinemaPlayerActive, currentPlayingStream?.uniqueStreamKey, currentPlayingEpisode?.uniqueEpisodeKey) {
        if (isEnginePlaying && isCinemaPlayerActive) {
            var lastPos = -1L
            while (true) {
                delay(5_000L)
                val pos = playerEngine.currentPositionMs.value
                if (pos >= 10_000L && abs(pos - lastPos) >= 4_000L) { lastPos = pos; persistVodOrSeriesCheckpoint() }
            }
        }
    }

    AetherTheme {
        Box(modifier = Modifier.fillMaxSize().focusRequester(cockpitRootFocusRequester)) {
            when (navState.currentScreen) {
                is AetherScreen.GatekeeperLock -> {
                    GatekeeperLockScreen(
                        viewModel = gatekeeperViewModel,
                        onUnlockedSuccess = { navigationViewModel.navigateTo(if (activePlaylist != null) AetherScreen.LiveTv else AetherScreen.PlaylistSetup) },
                        onSecretAdminTriggered = navigationViewModel::openSecretAdminPanel
                    )
                }
                is AetherScreen.PlaylistSetup -> {
                    XtreamPlaylistSetupScreen(
                        onSaveAndSyncPlaylist = { name, url, user, pass ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    dao.insertOrUpdatePlaylist(PlaylistEntity(activePlaylist?.id ?: 1L, name, url.trim(), user.trim(), pass.trim(), true, isInitialSyncCompleted = false, lastSyncEpochMillis = 0L))
                                }
                                dashboardViewModel.triggerManualSync()
                                navigationViewModel.navigateTo(AetherScreen.LiveTv)
                            }
                        },
                        onCancelIfPlaylistExists = if (activePlaylist != null) { { navigationViewModel.navigateTo(AetherScreen.LiveTv) } } else null
                    )
                }
                is AetherScreen.LiveTv, is AetherScreen.Dashboard, is AetherScreen.VodMovies, is AetherScreen.Series -> {
                    LiveTvDualCockpitScreen(
                        playerEngine = playerEngine,
                        layoutMode = navState.liveTvLayoutMode,
                        currentBufferPreset = navState.bufferPreset,
                        categories = visibleCategories,
                        favoriteFolders = favoriteFolders,
                        selectedCategoryKey = selectedCategoryKey,
                        streamsInCategory = streamsInSelectedCategory,
                        currentPlayingStream = currentPlayingStream,
                        currentChannelEpg = activeChannelEpgList,
                        activeSection = selectedSection,
                        startInPureFullscreen = isCinemaPlayerActive,
                        isVodOrSeriesMode = selectedSection != AetherSectionType.LIVE,
                        isLoadingStreams = isLoadingCategoryStreams,
                        onSelectSection = { section ->
                            if (selectedSection != section) { selectedSection = section; selectedCategoryKey = "" }
                        },
                        onSelectCategory = { selectedCategoryKey = it },
                        onSelectStream = { stream ->
                            currentPlayingEpisode = null; activeParentSeriesKey = null; currentPlayingStream = stream
                            val isVod = (stream.sectionType == AetherSectionType.VOD)
                            isCinemaPlayerActive = isVod
                            val startPos = if (isVod) stream.resumePositionMs.coerceAtLeast(0L) else 0L
                            playerEngine.playStream(stream.streamUrl, startPos)
                            scope.launch(Dispatchers.IO) {
                                dao.updateWatchProgress(stream.uniqueStreamKey, startPos, stream.totalDurationMs.coerceAtLeast(0L), Clock.System.now().toEpochMilliseconds())
                            }
                        },
                        onOpenSeriesCockpit = { activeSeriesForModal = it },
                        onQuickFavoriteToggle = { stream -> scope.launch(Dispatchers.IO) { dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite) } },
                        onOpenFavoriteFolderDialog = { folderDialogTargetStream = it },
                        onOpenSettings = {
                            val origin = if (isCinemaPlayerActive) AetherSettingsFocusOrigin.FULLSCREEN_HUD_CAROUSEL else AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL
                            AetherSettingsStore.openDrawer(origin); isSettingsModalVisible = true
                        },
                        onOpenSearch = { isSearchModalVisible = true },
                        onToggleLayoutMode = navigationViewModel::toggleLiveTvLayoutMode,
                        onCycleBufferPreset = {
                            val presets = StreamBufferPreset.entries
                            val next = presets[(presets.indexOf(navState.bufferPreset) + 1) % presets.size]
                            navigationViewModel.updateBufferPreset(next); playerEngine.applyBufferPreset(next)
                        },
                        onZapChannelOffset = { offset ->
                            if (!isCinemaPlayerActive && streamsInSelectedCategory.isNotEmpty()) {
                                val curIdx = streamsInSelectedCategory.indexOfFirst { it.uniqueStreamKey == currentPlayingStream?.uniqueStreamKey }.coerceAtLeast(0)
                                val nextCh = streamsInSelectedCategory[(curIdx + offset + streamsInSelectedCategory.size) % streamsInSelectedCategory.size]
                                currentPlayingStream = nextCh; playerEngine.playStream(nextCh.streamUrl, 0L)
                                scope.launch(Dispatchers.IO) { dao.updateWatchProgress(nextCh.uniqueStreamKey, 0L, 0L, Clock.System.now().toEpochMilliseconds()) }
                            }
                        },
                        onBackToDashboard = {
                            scope.launch { if (isCinemaPlayerActive) persistVodOrSeriesCheckpoint(); isCinemaPlayerActive = false }
                        }
                    )
                }
                is AetherScreen.Settings -> navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
            }

            activeSeriesForModal?.let { seriesItem ->
                AetherSeriesDetailModal(
                    seriesItem = seriesItem,
                    dao = dao,
                    onFetchEpisodes = { dashboardViewModel.fetchSeriesEpisodesOnDemand(seriesItem) },
                    onToggleFavorite = {
                        val nextFav = !seriesItem.isQuickFavorite
                        activeSeriesForModal = seriesItem.copy(isQuickFavorite = nextFav)
                        scope.launch(Dispatchers.IO) { dao.setQuickFavorite(seriesItem.uniqueStreamKey, nextFav) }
                    },
                    onPlayEpisode = { episode, resume ->
                        val parent = seriesItem; activeSeriesForModal = null; isCinemaPlayerActive = true
                        currentPlayingEpisode = episode; activeParentSeriesKey = parent.uniqueStreamKey
                        val title = if (episode.title.contains(parent.name, ignoreCase = true)) episode.title else "${parent.name} • S${episode.seasonNumber}E${episode.episodeNumber} - ${episode.title}"
                        currentPlayingStream = parent.copy(name = title, streamUrl = episode.streamUrl)
                        val startPos = if (resume) episode.resumePositionMs.coerceAtLeast(0L) else 0L
                        playerEngine.playStream(episode.streamUrl, startPos)
                        scope.launch(Dispatchers.IO) { dao.updateWatchProgress(parent.uniqueStreamKey, startPos, episode.durationMs.coerceAtLeast(0L), Clock.System.now().toEpochMilliseconds()) }
                    },
                    onDismiss = { activeSeriesForModal = null }
                )
            }

            if (isSearchModalVisible) {
                AetherGlobalSearchModal(
                    dao = dao,
                    playlistId = playlistId,
                    onTriggerFullSync = {
                        fetchedCategoryKeys.clear()
                        dashboardViewModel.triggerManualSync()
                    },
                    onToggleFavorite = { item -> scope.launch(Dispatchers.IO) { dao.setQuickFavorite(item.uniqueStreamKey, !item.isQuickFavorite) } },
                    onDismiss = { isSearchModalVisible = false },
                    onSelectStream = { stream ->
                        isSearchModalVisible = false
                        val now = Clock.System.now().toEpochMilliseconds()
                        when (stream.sectionType) {
                            AetherSectionType.SERIES -> {
                                selectedSection = AetherSectionType.SERIES
                                activeSeriesForModal = stream
                            }
                            AetherSectionType.VOD -> {
                                selectedSection = AetherSectionType.VOD
                                currentPlayingEpisode = null; activeParentSeriesKey = null; currentPlayingStream = stream
                                isCinemaPlayerActive = true
                                val startPos = stream.resumePositionMs.coerceAtLeast(0L)
                                playerEngine.playStream(stream.streamUrl, startPos)
                                scope.launch(Dispatchers.IO) { dao.updateWatchProgress(stream.uniqueStreamKey, startPos, stream.totalDurationMs.coerceAtLeast(0L), now) }
                            }
                            AetherSectionType.LIVE -> {
                                selectedSection = AetherSectionType.LIVE
                                if (stream.uniqueCategoryKey.isNotBlank()) selectedCategoryKey = stream.uniqueCategoryKey
                                currentPlayingEpisode = null; activeParentSeriesKey = null; currentPlayingStream = stream
                                isCinemaPlayerActive = true
                                playerEngine.playStream(stream.streamUrl, 0L)
                                scope.launch(Dispatchers.IO) { dao.updateWatchProgress(stream.uniqueStreamKey, 0L, 0L, now) }
                            }
                        }
                    }
                )
            }

            if (navState.isAdminModalVisible) SecretAdminPanelModal(gatekeeperViewModel, navigationViewModel::dismissSecretAdminPanel)

            folderDialogTargetStream?.let { targetStream ->
                CombinedFavoriteFolderModal(
                    streamItem = targetStream,
                    folders = favoriteFolders,
                    onToggleQuickFavorite = { s -> scope.launch(Dispatchers.IO) { dao.setQuickFavorite(s.uniqueStreamKey, !s.isQuickFavorite) } },
                    onAssignToFolder = { fId, s -> scope.launch(Dispatchers.IO) { dao.addStreamToFavoriteFolder(FavoriteFolderMappingEntity(fId, s.uniqueStreamKey, Clock.System.now().toEpochMilliseconds())) } },
                    onCreateNewFolder = { fName, s ->
                        scope.launch(Dispatchers.IO) {
                            val newId = dao.createFavoriteFolder(FavoriteFolderEntity(playlistId = playlistId, folderName = fName))
                            dao.addStreamToFavoriteFolder(FavoriteFolderMappingEntity(newId, s.uniqueStreamKey, Clock.System.now().toEpochMilliseconds()))
                        }
                    },
                    onDismiss = { folderDialogTargetStream = null }
                )
            }

            AetherSettingsOverlay(
                isVisible = isSettingsModalVisible,
                focusOrigin = if (isCinemaPlayerActive) AetherSettingsFocusOrigin.FULLSCREEN_HUD_CAROUSEL else AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL,
                navRailFocusRequester = cockpitRootFocusRequester,
                hudCarouselFocusRequester = hudSettingsReturnFocusRequester,
                onDismiss = { isSettingsModalVisible = false },
                onAddPlaylistRequested = { isSettingsModalVisible = false; navigationViewModel.navigateTo(AetherScreen.PlaylistSetup) },
                onRefreshPlaylistsRequested = { fetchedCategoryKeys.clear(); dashboardViewModel.triggerManualSync() },
                onRefreshEpgRequested = {
                    dashboardViewModel.triggerManualSync()
                    currentPlayingStream?.let { activeStream ->
                        if (activeStream.sectionType == AetherSectionType.LIVE) {
                            scope.launch(Dispatchers.IO) {
                                val refreshed = runCatching { dashboardViewModel.fetchShortEpgForChannelOnDemand(activeStream) }.getOrDefault(emptyList())
                                if (refreshed.isNotEmpty()) activeChannelEpgList = refreshed
                            }
                        }
                    }
                },
                onClearEpgRequested = { fetchedCategoryKeys.clear(); activeChannelEpgList = emptyList(); dashboardViewModel.clearEpgCacheNow() },
                onBufferPresetChanged = { presetLabel ->
                    val presets = StreamBufferPreset.entries
                    if (presets.isNotEmpty()) {
                        val mapped = when (presetLabel) {
                            "Keine" -> presets.first(); "Klein" -> presets.getOrElse(1) { presets.first() }
                            "Mittel" -> presets.getOrElse(presets.size / 2) { presets.first() }
                            "Groß" -> presets.getOrElse((presets.size - 2).coerceAtLeast(0)) { presets.last() }; else -> presets.last()
                        }
                        navigationViewModel.updateBufferPreset(mapped); playerEngine.applyBufferPreset(mapped)
                    }
                }
            )
        }
    }
}

@Composable
private fun AetherSeriesDetailModal(seriesItem: StreamItemEntity, dao: AetherDao, onFetchEpisodes: suspend () -> Boolean, onToggleFavorite: () -> Unit, onPlayEpisode: (SeriesEpisodeEntity, Boolean) -> Unit, onDismiss: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    var seasons by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(seriesItem.uniqueStreamKey) {
        val existing = withContext(Dispatchers.IO) { dao.getAvailableSeasons(seriesItem.uniqueStreamKey) }
        if (existing.isEmpty()) {
            isLoading = true; onFetchEpisodes()
            val updated = withContext(Dispatchers.IO) { dao.getAvailableSeasons(seriesItem.uniqueStreamKey) }
            seasons = updated; selSeason = updated.firstOrNull(); isLoading = false
        } else { seasons = existing; selSeason = existing.firstOrNull() }
    }

    val episodes by remember(seriesItem.uniqueStreamKey, selSeason) {
        selSeason?.let { dao.observeEpisodesForSeason(seriesItem.uniqueStreamKey, it) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(1080.dp).height(620.dp).clip(RoundedCornerShape(24.dp)).background(AetherColors.DeepSpaceNavy).border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(26.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(seriesItem.name, style = MaterialTheme.typography.headlineMedium.copy(color = AetherColors.CyberCyan, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(if (!seriesItem.rating.isNullOrBlank()) "⭐ ${seriesItem.rating}" else "HD Serie", style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue))
                        Spacer(Modifier.height(14.dp))
                        Text(seriesItem.plotDescription ?: "Keine Inhaltsangabe verfügbar.", style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.TextSecondary), maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AetherFocusableGlassCard(onClick = onToggleFavorite, isSelected = seriesItem.isQuickFavorite, cornerRadius = 10.dp) {
                            Text(if (seriesItem.isQuickFavorite) "★ IN MEINER LISTE" else "☆ MERKEN", color = if (seriesItem.isQuickFavorite) AetherColors.LaserAmber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                        AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 10.dp) {
                            Text("SCHLIESSEN ✕", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (isLoading) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = AetherColors.CyberCyan); Spacer(Modifier.height(16.dp)); Text("Lade Staffeln & Episoden...", color = AetherColors.CyberCyan) }
                        }
                    } else if (seasons.isEmpty()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) { Text("Keine Episoden gefunden.", color = AetherColors.TextSecondary) }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            items(seasons, key = { it }) { sNum ->
                                val sel = selSeason == sNum
                                AetherFocusableGlassCard(onClick = { selSeason = sNum }, isSelected = sel, cornerRadius = 8.dp) {
                                    Text("Staffel $sNum", color = if (sel) AetherColors.CyberCyan else AetherColors.TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(episodes, key = { it.uniqueEpisodeKey }) { ep ->
                                val curEp by rememberUpdatedState(ep)
                                val hasRes = curEp.resumePositionMs > 10_000L
                                AetherFocusableGlassCard(onClick = { onPlayEpisode(curEp, hasRes) }, cornerRadius = 10.dp, modifier = Modifier.fillMaxWidth()) { isFocused ->
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Folge ${curEp.episodeNumber}: ${curEp.title}", color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (!curEp.plotDescription.isNullOrBlank()) Text(curEp.plotDescription!!, color = AetherColors.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (hasRes) AetherColors.NeonEmerald.copy(alpha = 0.2f) else AetherColors.CyberCyan.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                            Text(if (hasRes) "▶ Fortsetzen" else "▶ Abspielen", color = if (hasRes) AetherColors.NeonEmerald else AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
}

@Composable
private fun AetherGlobalSearchModal(dao: AetherDao, playlistId: Long, onTriggerFullSync: () -> Unit, onToggleFavorite: (StreamItemEntity) -> Unit, onDismiss: () -> Unit, onSelectStream: (StreamItemEntity) -> Unit) {
    var queryText by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var filterSection by remember { mutableStateOf<AetherSectionType?>(null) }
    var showPolishKeys by remember { mutableStateOf(true) }

    val firstKeyReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstKeyReq.requestFocus() } }
    LaunchedEffect(queryText) { delay(220L); debouncedQuery = queryText.trim() }

    val rawResults by remember(playlistId, debouncedQuery) {
        if (playlistId <= 0L || debouncedQuery.length < 2) flowOf(emptyList())
        else {
            val tokens = debouncedQuery.split(Regex("[\\s._\\-|]+")).filter { it.isNotBlank() }
            val t1 = tokens.getOrElse(0) { debouncedQuery }
            val t2 = tokens.getOrElse(1) { "" }
            val t3 = tokens.getOrElse(2) { "" }
            val t4 = tokens.getOrElse(3) { "" }
            dao.searchVisibleStreamsMultiToken(playlistId, t1, t2, t3, t4)
        }
    }.collectAsState(initial = emptyList())

    val searchResults = remember(rawResults, filterSection) {
        if (filterSection == null) rawResults else rawResults.filter { it.sectionType == filterSection }
    }

    val baseKeys = remember { listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "LEER", "⌫", "CLR") }
    val specialKeys = remember(showPolishKeys) {
        if (showPolishKeys) listOf("PL/DE", "Ą", "Ć", "Ę", "Ł", "Ń", "Ó", "Ś", "Ź", "Ż", "Ä", "Ö", "Ü", "ß")
        else listOf("PL/DE", "Ä", "Ö", "Ü", "ß")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.width(1040.dp).height(650.dp).clip(RoundedCornerShape(24.dp)).background(AetherColors.DeepSpaceNavy).border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp)).padding(22.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("🔍 GLOBALE MULTI-TOKEN SUCHE (TV, FILME & SERIEN)", color = AetherColors.CyberCyan, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AetherFocusableGlassCard(onClick = { filterSection = null }, isSelected = filterSection == null, cornerRadius = 8.dp) { Text("Alle (${rawResults.size})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        AetherFocusableGlassCard(onClick = { filterSection = AetherSectionType.VOD }, isSelected = filterSection == AetherSectionType.VOD, cornerRadius = 8.dp) { Text("🎬 Filme", color = AetherColors.NeonEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        AetherFocusableGlassCard(onClick = { filterSection = AetherSectionType.SERIES }, isSelected = filterSection == AetherSectionType.SERIES, cornerRadius = 8.dp) { Text("🍿 Serien", color = AetherColors.LaserAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        AetherFocusableGlassCard(onClick = { filterSection = AetherSectionType.LIVE }, isSelected = filterSection == AetherSectionType.LIVE, cornerRadius = 8.dp) { Text("📺 TV", color = AetherColors.CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        AetherFocusableGlassCard(onClick = onTriggerFullSync, cornerRadius = 8.dp) { Text("⚡ Katalog-Sync", color = AetherColors.CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                        AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 8.dp) { Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                    }
                }

                OutlinedTextField(
                    value = queryText, onValueChange = { queryText = it },
                    label = { Text("Suchbegriff z.B. 'Ip Man PL' (findet Wörter in jeder Reihenfolge)...") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = AetherColors.ObsidianBlack, unfocusedContainerColor = AetherColors.ObsidianBlack)
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                    items(baseKeys.size) { idx ->
                        val k = baseKeys[idx]
                        AetherFocusableGlassCard(
                            onClick = { when (k) { "LEER" -> queryText += " "; "⌫" -> if (queryText.isNotEmpty()) queryText = queryText.dropLast(1); "CLR" -> queryText = ""; else -> queryText += k } },
                            cornerRadius = 6.dp, modifier = if (idx == 0) Modifier.focusRequester(firstKeyReq) else Modifier
                        ) { isFocused -> Text(k, color = if (isFocused) AetherColors.CyberCyan else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) }
                    }
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    items(specialKeys.size) { idx ->
                        val sk = specialKeys[idx]
                        AetherFocusableGlassCard(
                            onClick = { if (sk == "PL/DE") showPolishKeys = !showPolishKeys else queryText += sk },
                            cornerRadius = 6.dp
                        ) { isFocused -> Text(sk, color = if (isFocused || sk == "PL/DE") AetherColors.LaserAmber else AetherColors.IceBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        debouncedQuery.length < 2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Gib mindestens 2 Zeichen ein (z.B. 'Ip Man PL' oder 'DAZN 1').", color = AetherColors.TextSecondary, fontSize = 14.sp) }
                        searchResults.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Keine Treffer für „$debouncedQuery“. Tipp: Klicke oben rechts auf '⚡ Katalog-Sync', falls Filme noch nicht geladen wurden.", color = AetherColors.IceBlue, fontSize = 13.sp) }
                        else -> {
                            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(searchResults, key = { it.uniqueStreamKey }) { item ->
                                    val currentItem by rememberUpdatedState(item)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        AetherFocusableGlassCard(onClick = { onSelectStream(currentItem) }, cornerRadius = 8.dp, modifier = Modifier.weight(1f)) { isFocused ->
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                    val (tag, col) = when (currentItem.sectionType) { AetherSectionType.LIVE -> "TV" to AetherColors.CyberCyan; AetherSectionType.VOD -> "FILM" to AetherColors.NeonEmerald; AetherSectionType.SERIES -> "SERIE" to AetherColors.LaserAmber }
                                                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(col.copy(alpha = 0.2f)).border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(tag, color = col, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
                                                    Spacer(Modifier.width(10.dp))
                                                    Text(currentItem.name, color = if (isFocused) Color.White else AetherColors.TextPrimary, fontSize = 14.sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text("▶ Starten", color = AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        AetherFocusableGlassCard(onClick = { onToggleFavorite(currentItem) }, cornerRadius = 8.dp) {
                                            Text(if (currentItem.isQuickFavorite) "★" else "☆", color = if (currentItem.isQuickFavorite) AetherColors.LaserAmber else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
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
}
