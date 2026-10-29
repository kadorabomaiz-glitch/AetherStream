package com.aether.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.FavoriteFolderEntity
import com.aether.stream.data.local.FavoriteFolderMappingEntity
import com.aether.stream.data.local.PlaylistEntity
import com.aether.stream.data.local.SeriesEpisodeEntity
import com.aether.stream.data.local.StreamItemEntity
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperLockScreen
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.gatekeeper.SecretAdminPanelModal
import com.aether.stream.feature.player.AetherVideoPlayerEngine
import com.aether.stream.feature.player.LiveTvDualCockpitScreen
import com.aether.stream.feature.settings.AetherSettingsFocusOrigin
import com.aether.stream.feature.settings.AetherSettingsOverlay
import com.aether.stream.feature.settings.AetherSettingsStore
import com.aether.stream.feature.settings.BindAetherEngineParameters
import com.aether.stream.feature.settings.BindAetherSettingsLiveTelemetry
import com.aether.stream.feature.vod.CombinedFavoriteFolderModal
import com.aether.stream.feature.vod.XtreamPlaylistSetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlin.math.abs

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

    var isSearchModalVisible by remember { mutableStateOf(false) }
    var isSettingsModalVisible by remember { mutableStateOf(false) }
    var isLoadingCategoryStreams by remember { mutableStateOf(false) }

    // Focus Handshake Requesters for Zone 1 Rail & Fullscreen HUD return
    val cockpitRootFocusRequester = remember { FocusRequester() }
    val hudSettingsReturnFocusRequester = remember { FocusRequester() }

    val fetchedCategoryKeys = remember { mutableSetOf<String>() }
    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val visibleCategories by remember(playlistId, selectedSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, selectedSection)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Observe all 3 section category lists for real-time Bild 4 telemetry counters
    val liveCategoriesAll by remember(playlistId) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, AetherSectionType.LIVE)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val vodCategoriesAll by remember(playlistId) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, AetherSectionType.VOD)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val seriesCategoriesAll by remember(playlistId) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, AetherSectionType.SERIES)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    var liveTotalCount by remember { mutableStateOf(18957) }
    var vodTotalCount by remember { mutableStateOf(72231) }
    var seriesTotalCount by remember { mutableStateOf(13926) }

    LaunchedEffect(
        playlistId,
        liveCategoriesAll.size,
        vodCategoriesAll.size,
        seriesCategoriesAll.size,
        isLoadingCategoryStreams
    ) {
        if (playlistId > 0L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val computedLive = liveCategoriesAll.sumOf { cat ->
                        dao.getStreamCountInCategory(cat.uniqueCategoryKey)
                    }
                    val computedVod = vodCategoriesAll.sumOf { cat ->
                        dao.getStreamCountInCategory(cat.uniqueCategoryKey)
                    }
                    val computedSeries = seriesCategoriesAll.sumOf { cat ->
                        dao.getStreamCountInCategory(cat.uniqueCategoryKey)
                    }
                    if (computedLive > 0) liveTotalCount = computedLive
                    if (computedVod > 0) vodTotalCount = computedVod
                    if (computedSeries > 0) seriesTotalCount = computedSeries
                }
            }
        }
    }

    // Bind live playlist host and counters into AetherSettingsStore (Bild 4)
    val resolvedPlaylistUrlOrName = remember(activePlaylist) {
        activePlaylist?.serverUrl?.takeIf { it.isNotBlank() }
            ?: activePlaylist?.playlistName?.takeIf { it.isNotBlank() }
            ?: "line.trxdnscloud.ru"
    }

    BindAetherSettingsLiveTelemetry(
        activePlaylistUrlOrName = resolvedPlaylistUrlOrName,
        liveChannelsCount = liveTotalCount,
        vodMoviesCount = vodTotalCount,
        seriesCount = seriesTotalCount
    )

    // Bind hot-swap engine parameters to AetherVideoPlayerEngine (Bild 8 & 9)
    BindAetherEngineParameters(
        onApplyHotSwapConfig = { hotSwapConfig ->
            val availablePresets = StreamBufferPreset.entries
            if (availablePresets.isNotEmpty()) {
                val mappedPreset = when (hotSwapConfig.bufferSpec.presetLabel) {
                    "Keine" -> availablePresets.first()
                    "Klein" -> availablePresets.getOrElse(1) { availablePresets.first() }
                    "Mittel" -> availablePresets.getOrElse(availablePresets.size / 2) { availablePresets.first() }
                    "Groß" -> availablePresets.getOrElse((availablePresets.size - 2).coerceAtLeast(0)) { availablePresets.last() }
                    else -> availablePresets.last()
                }
                if (mappedPreset != navState.bufferPreset) {
                    navigationViewModel.updateBufferPreset(mappedPreset)
                    playerEngine.applyBufferPreset(mappedPreset)
                }
            }
        }
    )

    LaunchedEffect(visibleCategories, selectedSection) {
        if (visibleCategories.isNotEmpty()) {
            val isSystemKey = selectedCategoryKey == "SYS_QUICK_FAV" ||
                selectedCategoryKey == "SYS_RECENT" ||
                selectedCategoryKey.startsWith("FOLDER_")
            val isCurrentKeyInThisSection = isSystemKey ||
                visibleCategories.any { it.uniqueCategoryKey == selectedCategoryKey }

            if (selectedCategoryKey.isEmpty() || !isCurrentKeyInThisSection) {
                selectedCategoryKey = visibleCategories.first().uniqueCategoryKey
            }
        } else if (selectedCategoryKey.isEmpty()) {
            selectedCategoryKey = "SYS_QUICK_FAV"
        }
    }

    val rawStreamsFlow = remember(playlistId, selectedSection, selectedCategoryKey) {
        if (playlistId <= 0L || selectedCategoryKey.isEmpty()) {
            flowOf(emptyList())
        } else {
            when {
                selectedCategoryKey == "SYS_RECENT" ->
                    dao.observeRecentlyWatched(playlistId, selectedSection)
                selectedCategoryKey == "SYS_QUICK_FAV" ->
                    dao.observeQuickFavorites(playlistId, selectedSection)
                selectedCategoryKey.startsWith("FOLDER_") -> {
                    val folderId = selectedCategoryKey.removePrefix("FOLDER_").toLongOrNull() ?: 0L
                    dao.observeStreamsInFavoriteFolder(folderId)
                }
                else -> dao.observeVisibleStreamsInCategory(selectedCategoryKey)
            }
        }
    }

    var streamsInSelectedCategory by remember { mutableStateOf<List<StreamItemEntity>>(emptyList()) }
    LaunchedEffect(rawStreamsFlow, isCinemaPlayerActive) {
        rawStreamsFlow.collect { updatedList ->
            if (!isCinemaPlayerActive || streamsInSelectedCategory.isEmpty()) {
                streamsInSelectedCategory = updatedList
            }
        }
    }

    LaunchedEffect(selectedCategoryKey, selectedSection) {
        val isSystemCategory = selectedCategoryKey == "SYS_QUICK_FAV" ||
            selectedCategoryKey == "SYS_RECENT" ||
            selectedCategoryKey.startsWith("FOLDER_")

        if (!isSystemCategory && selectedCategoryKey.isNotEmpty() && selectedSection != AetherSectionType.LIVE) {
            if (fetchedCategoryKeys.add(selectedCategoryKey)) {
                val matchingCat = visibleCategories.firstOrNull { it.uniqueCategoryKey == selectedCategoryKey }
                if (matchingCat != null) {
                    val streamCount = withContext(Dispatchers.IO) {
                        dao.getStreamCountInCategory(matchingCat.uniqueCategoryKey)
                    }
                    if (streamCount == 0) {
                        isLoadingCategoryStreams = true
                        withContext(Dispatchers.IO) {
                            runCatching { dashboardViewModel.fetchCategoryStreamsOnDemand(matchingCat) }
                        }
                        isLoadingCategoryStreams = false
                    }
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
            val ep = currentPlayingEpisode
            val st = currentPlayingStream
            if (ep != null) {
                dao.updateEpisodeWatchProgress(
                    uniqueEpisodeKey = ep.uniqueEpisodeKey,
                    positionMs = effectivePos,
                    durationMs = dur.coerceAtLeast(0L),
                    watchedAt = now
                )
                activeParentSeriesKey?.let { seriesKey ->
                    dao.updateWatchProgress(
                        uniqueStreamKey = seriesKey,
                        positionMs = effectivePos,
                        durationMs = dur.coerceAtLeast(0L),
                        watchedAt = now
                    )
                }
            } else if (st != null && st.sectionType != AetherSectionType.LIVE) {
                dao.updateWatchProgress(
                    uniqueStreamKey = st.uniqueStreamKey,
                    positionMs = effectivePos,
                    durationMs = dur.coerceAtLeast(0L),
                    watchedAt = now
                )
            }
        }
    }

    val isEnginePlaying by playerEngine.isPlaying.collectAsState()
    LaunchedEffect(isEnginePlaying, isCinemaPlayerActive, currentPlayingStream?.uniqueStreamKey, currentPlayingEpisode?.uniqueEpisodeKey) {
        if (isEnginePlaying && isCinemaPlayerActive) {
            var lastRecordedPos = -1L
            while (true) {
                delay(5_000L)
                val pos = playerEngine.currentPositionMs.value
                if (pos >= 10_000L && abs(pos - lastRecordedPos) >= 4_000L) {
                    lastRecordedPos = pos
                    persistVodOrSeriesCheckpoint()
                }
            }
        }
    }

    AetherTheme {
        // Root Box Container anchoring Z-Layer 0 (Cockpit), Z-Layer 40 (Modals), and Z-Layer 60f (340.dp Settings Overlay)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(cockpitRootFocusRequester)
        ) {
            when (navState.currentScreen) {
                is AetherScreen.GatekeeperLock -> {
                    GatekeeperLockScreen(
                        viewModel = gatekeeperViewModel,
                        onUnlockedSuccess = {
                            val next = if (activePlaylist != null) AetherScreen.LiveTv else AetherScreen.PlaylistSetup
                            navigationViewModel.navigateTo(next)
                        },
                        onSecretAdminTriggered = navigationViewModel::openSecretAdminPanel
                    )
                }

                is AetherScreen.PlaylistSetup -> {
                    XtreamPlaylistSetupScreen(
                        onSaveAndSyncPlaylist = { name, url, user, pass ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    dao.insertOrUpdatePlaylist(
                                        PlaylistEntity(
                                            id = activePlaylist?.id ?: 1L,
                                            playlistName = name,
                                            serverUrl = url.trim(),
                                            username = user.trim(),
                                            password = pass.trim(),
                                            isActive = true,
                                            isInitialSyncCompleted = false,
                                            lastSyncEpochMillis = 0L
                                        )
                                    )
                                }
                                dashboardViewModel.triggerManualSync()
                                navigationViewModel.navigateTo(AetherScreen.LiveTv)
                            }
                        },
                        onCancelIfPlaylistExists = if (activePlaylist != null) {
                            { navigationViewModel.navigateTo(AetherScreen.LiveTv) }
                        } else null
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
                        currentChannelEpg = emptyList(),
                        activeSection = selectedSection,
                        startInPureFullscreen = isCinemaPlayerActive,
                        isVodOrSeriesMode = selectedSection != AetherSectionType.LIVE,
                        isLoadingStreams = isLoadingCategoryStreams,
                        onSelectSection = { section ->
                            if (selectedSection != section) {
                                selectedSection = section
                                selectedCategoryKey = ""
                            }
                        },
                        onSelectCategory = { selectedCategoryKey = it },
                        onSelectStream = { stream ->
                            currentPlayingEpisode = null
                            activeParentSeriesKey = null
                            currentPlayingStream = stream
                            val isVod = (stream.sectionType == AetherSectionType.VOD)
                            isCinemaPlayerActive = isVod
                            val startPos = if (isVod) stream.resumePositionMs.coerceAtLeast(0L) else 0L
                            playerEngine.playStream(stream.streamUrl, startPos)

                            if (isVod) {
                                scope.launch(Dispatchers.IO) {
                                    dao.updateWatchProgress(
                                        uniqueStreamKey = stream.uniqueStreamKey,
                                        positionMs = startPos,
                                        durationMs = stream.totalDurationMs.coerceAtLeast(0L),
                                        watchedAt = Clock.System.now().toEpochMilliseconds()
                                    )
                                }
                            }
                        },
                        onOpenSeriesCockpit = { seriesItem ->
                            activeSeriesForModal = seriesItem
                        },
                        onQuickFavoriteToggle = { stream ->
                            scope.launch(Dispatchers.IO) {
                                dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                            }
                        },
                        onOpenFavoriteFolderDialog = { stream ->
                            folderDialogTargetStream = stream
                        },
                        onOpenSettings = {
                            val origin = if (isCinemaPlayerActive) {
                                AetherSettingsFocusOrigin.FULLSCREEN_HUD_CAROUSEL
                            } else {
                                AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL
                            }
                            AetherSettingsStore.openDrawer(origin)
                            isSettingsModalVisible = true
                        },
                        onOpenSearch = {
                            isSearchModalVisible = true
                        },
                        onToggleLayoutMode = navigationViewModel::toggleLiveTvLayoutMode,
                        onCycleBufferPreset = {
                            val presets = StreamBufferPreset.entries
                            val nextIndex = (presets.indexOf(navState.bufferPreset) + 1) % presets.size
                            val nextPreset = presets[nextIndex]
                            navigationViewModel.updateBufferPreset(nextPreset)
                            playerEngine.applyBufferPreset(nextPreset)
                        },
                        onZapChannelOffset = { offset ->
                            if (!isCinemaPlayerActive && streamsInSelectedCategory.isNotEmpty()) {
                                val currentIndex = streamsInSelectedCategory.indexOfFirst {
                                    it.uniqueStreamKey == currentPlayingStream?.uniqueStreamKey
                                }.coerceAtLeast(0)
                                val targetIndex = (currentIndex + offset + streamsInSelectedCategory.size) %
                                    streamsInSelectedCategory.size
                                val nextChannel = streamsInSelectedCategory[targetIndex]
                                currentPlayingStream = nextChannel
                                playerEngine.playStream(nextChannel.streamUrl, 0L)
                            }
                        },
                        onBackToDashboard = {
                            scope.launch {
                                if (isCinemaPlayerActive) {
                                    persistVodOrSeriesCheckpoint()
                                }
                                isCinemaPlayerActive = false
                            }
                        }
                    )
                }

                is AetherScreen.Settings -> {
                    navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
                }
            }

            activeSeriesForModal?.let { seriesItem ->
                AetherSeriesDetailModal(
                    seriesItem = seriesItem,
                    dao = dao,
                    onFetchEpisodes = { dashboardViewModel.fetchSeriesEpisodesOnDemand(seriesItem) },
                    onPlayEpisode = { episode, resume ->
                        val parentSeries = seriesItem
                        activeSeriesForModal = null
                        isCinemaPlayerActive = true
                        currentPlayingEpisode = episode
                        activeParentSeriesKey = parentSeries.uniqueStreamKey
                        val displayTitle = if (episode.title.contains(parentSeries.name, ignoreCase = true)) {
                            episode.title
                        } else {
                            "${parentSeries.name} • S${episode.seasonNumber}E${episode.episodeNumber} - ${episode.title}"
                        }
                        currentPlayingStream = parentSeries.copy(
                            name = displayTitle,
                            streamUrl = episode.streamUrl
                        )
                        val startPos = if (resume) episode.resumePositionMs.coerceAtLeast(0L) else 0L
                        playerEngine.playStream(episode.streamUrl, startPos)

                        scope.launch(Dispatchers.IO) {
                            dao.updateWatchProgress(
                                uniqueStreamKey = parentSeries.uniqueStreamKey,
                                positionMs = startPos,
                                durationMs = episode.durationMs.coerceAtLeast(0L),
                                watchedAt = Clock.System.now().toEpochMilliseconds()
                            )
                        }
                    },
                    onDismiss = { activeSeriesForModal = null }
                )
            }

            if (isSearchModalVisible) {
                AetherGlobalSearchModal(
                    dao = dao,
                    playlistId = playlistId,
                    onDismiss = { isSearchModalVisible = false },
                    onSelectStream = { stream ->
                        isSearchModalVisible = false
                        when (stream.sectionType) {
                            AetherSectionType.SERIES -> {
                                activeSeriesForModal = stream
                            }
                            AetherSectionType.VOD -> {
                                currentPlayingEpisode = null
                                activeParentSeriesKey = null
                                currentPlayingStream = stream
                                isCinemaPlayerActive = true
                                val startPos = stream.resumePositionMs.coerceAtLeast(0L)
                                playerEngine.playStream(stream.streamUrl, startPos)
                                scope.launch(Dispatchers.IO) {
                                    dao.updateWatchProgress(
                                        uniqueStreamKey = stream.uniqueStreamKey,
                                        positionMs = startPos,
                                        durationMs = stream.totalDurationMs.coerceAtLeast(0L),
                                        watchedAt = Clock.System.now().toEpochMilliseconds()
                                    )
                                }
                            }
                            AetherSectionType.LIVE -> {
                                currentPlayingEpisode = null
                                activeParentSeriesKey = null
                                currentPlayingStream = stream
                                isCinemaPlayerActive = false
                                playerEngine.playStream(stream.streamUrl, 0L)
                            }
                        }
                    }
                )
            }

            if (navState.isAdminModalVisible) {
                SecretAdminPanelModal(
                    viewModel = gatekeeperViewModel,
                    onDismiss = navigationViewModel::dismissSecretAdminPanel
                )
            }

            folderDialogTargetStream?.let { targetStream ->
                CombinedFavoriteFolderModal(
                    streamItem = targetStream,
                    folders = favoriteFolders,
                    onToggleQuickFavorite = { stream ->
                        scope.launch(Dispatchers.IO) {
                            dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                        }
                    },
                    onAssignToFolder = { folderId, stream ->
                        scope.launch(Dispatchers.IO) {
                            dao.addStreamToFavoriteFolder(
                                FavoriteFolderMappingEntity(
                                    folderId = folderId,
                                    uniqueStreamKey = stream.uniqueStreamKey,
                                    addedEpochMillis = Clock.System.now().toEpochMilliseconds()
                                )
                            )
                        }
                    },
                    onCreateNewFolder = { folderName, stream ->
                        scope.launch(Dispatchers.IO) {
                            val newFolderId = dao.createFavoriteFolder(
                                FavoriteFolderEntity(
                                    playlistId = playlistId,
                                    folderName = folderName
                                )
                            )
                            dao.addStreamToFavoriteFolder(
                                FavoriteFolderMappingEntity(
                                    folderId = newFolderId,
                                    uniqueStreamKey = stream.uniqueStreamKey,
                                    addedEpochMillis = Clock.System.now().toEpochMilliseconds()
                                )
                            )
                        }
                    },
                    onDismiss = { folderDialogTargetStream = null }
                )
            }

            // Z-Layer 60f: TiviMate 340.dp Right-Aligned Settings Drawer Overlay (Blueprints 1-12)
            AetherSettingsOverlay(
                isVisible = isSettingsModalVisible,
                focusOrigin = if (isCinemaPlayerActive) {
                    AetherSettingsFocusOrigin.FULLSCREEN_HUD_CAROUSEL
                } else {
                    AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL
                },
                navRailFocusRequester = cockpitRootFocusRequester,
                hudCarouselFocusRequester = hudSettingsReturnFocusRequester,
                onDismiss = {
                    isSettingsModalVisible = false
                },
                onAddPlaylistRequested = {
                    isSettingsModalVisible = false
                    navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
                },
                onRefreshPlaylistsRequested = {
                    fetchedCategoryKeys.clear()
                    dashboardViewModel.triggerManualSync()
                },
                onRefreshEpgRequested = {
                    dashboardViewModel.triggerManualSync()
                },
                onClearEpgRequested = {
                    fetchedCategoryKeys.clear()
                },
                onBufferPresetChanged = { presetLabel ->
                    val availablePresets = StreamBufferPreset.entries
                    if (availablePresets.isNotEmpty()) {
                        val mappedPreset = when (presetLabel) {
                            "Keine" -> availablePresets.first()
                            "Klein" -> availablePresets.getOrElse(1) { availablePresets.first() }
                            "Mittel" -> availablePresets.getOrElse(availablePresets.size / 2) { availablePresets.first() }
                            "Groß" -> availablePresets.getOrElse((availablePresets.size - 2).coerceAtLeast(0)) { availablePresets.last() }
                            else -> availablePresets.last()
                        }
                        navigationViewModel.updateBufferPreset(mappedPreset)
                        playerEngine.applyBufferPreset(mappedPreset)
                    }
                }
            )
        }
    }
}

@Composable
private fun AetherSeriesDetailModal(
    seriesItem: StreamItemEntity,
    dao: AetherDao,
    onFetchEpisodes: suspend () -> Boolean,
    onPlayEpisode: (SeriesEpisodeEntity, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var availableSeasons by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(seriesItem.uniqueStreamKey) {
        val existingSeasons = withContext(Dispatchers.IO) {
            dao.getAvailableSeasons(seriesItem.uniqueStreamKey)
        }
        if (existingSeasons.isEmpty()) {
            isLoadingEpisodes = true
            onFetchEpisodes()
            val updatedSeasons = withContext(Dispatchers.IO) {
                dao.getAvailableSeasons(seriesItem.uniqueStreamKey)
            }
            availableSeasons = updatedSeasons
            selectedSeason = updatedSeasons.firstOrNull()
            isLoadingEpisodes = false
        } else {
            availableSeasons = existingSeasons
            selectedSeason = existingSeasons.firstOrNull()
        }
    }

    val episodesForSeason by remember(seriesItem.uniqueStreamKey, selectedSeason) {
        val season = selectedSeason
        if (season != null) dao.observeEpisodesForSeason(seriesItem.uniqueStreamKey, season)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(1080.dp)
                .height(620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(
                            text = seriesItem.name,
                            style = MaterialTheme.typography.headlineMedium.copy(color = AetherColors.CyberCyan, fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (!seriesItem.rating.isNullOrBlank()) "⭐ ${seriesItem.rating}" else "HD Serie",
                            style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = seriesItem.plotDescription ?: "Keine Inhaltsangabe verfügbar.",
                            style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.TextSecondary),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 10.dp) {
                        Text(
                            text = "SCHLIESSEN ✕",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (isLoadingEpisodes) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AetherColors.CyberCyan)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Lade Staffeln & Episoden vom Xtream-Server...", style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan))
                            }
                        }
                    } else if (availableSeasons.isEmpty()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text("Keine Episoden für diese Serie gefunden.", style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.TextSecondary))
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            items(availableSeasons, key = { it }) { seasonNum ->
                                val isSelected = selectedSeason == seasonNum
                                AetherFocusableGlassCard(
                                    onClick = { selectedSeason = seasonNum },
                                    isSelected = isSelected,
                                    cornerRadius = 8.dp
                                ) {
                                    Text(
                                        text = "Staffel $seasonNum",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                            items(episodesForSeason, key = { it.uniqueEpisodeKey }) { ep ->
                                val hasResume = ep.resumePositionMs > 10_000L
                                AetherFocusableGlassCard(
                                    onClick = { onPlayEpisode(ep, hasResume) },
                                    cornerRadius = 10.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) { isFocused ->
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(12.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Folge ${ep.episodeNumber}: ${ep.title}",
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextPrimary,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!ep.plotDescription.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = ep.plotDescription,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = AetherColors.TextSecondary),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (ep.resumePositionMs > 0L && ep.durationMs > 0L) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                val progress = (ep.resumePositionMs.toFloat() / ep.durationMs.toFloat()).coerceIn(0.05f, 1f)
                                                LinearProgressIndicator(
                                                    progress = { progress },
                                                    color = AetherColors.CyberCyan,
                                                    trackColor = AetherColors.DeepSpaceNavy,
                                                    modifier = Modifier.fillMaxWidth(0.5f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (hasResume) AetherColors.NeonEmerald.copy(alpha = 0.2f) else AetherColors.CyberCyan.copy(alpha = 0.2f))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (hasResume) "▶ Fortsetzen" else "▶ Abspielen",
                                                color = if (hasResume) AetherColors.NeonEmerald else AetherColors.CyberCyan,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
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
}

@Composable
private fun AetherGlobalSearchModal(
    dao: AetherDao,
    playlistId: Long,
    onDismiss: () -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit
) {
    var queryText by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    val firstKeyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstKeyFocusRequester.requestFocus() }
    }

    LaunchedEffect(queryText) {
        delay(280L)
        debouncedQuery = queryText.trim()
    }

    val searchResults by remember(playlistId, debouncedQuery) {
        if (playlistId <= 0L || debouncedQuery.length < 2) {
            flowOf(emptyList())
        } else {
            dao.searchVisibleStreams(playlistId, debouncedQuery)
        }
    }.collectAsState(initial = emptyList())

    val quickKeys = remember {
        listOf(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "LEER", "⌫", "CLR"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(1020.dp)
                .height(640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔍 GLOBALE SUCHE (LIVE TV, FILME & SERIEN)",
                        color = AetherColors.CyberCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )

                    AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 8.dp) {
                        Text(
                            text = "SCHLIESSEN ✕",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    label = { Text("Suchbegriff (Fernbedienungs-Leiste unten oder Systemtastatur)...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = AetherColors.ObsidianBlack,
                        unfocusedContainerColor = AetherColors.ObsidianBlack
                    )
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(quickKeys.size) { idx ->
                        val keyLabel = quickKeys[idx]
                        val keyMod = if (idx == 0) Modifier.focusRequester(firstKeyFocusRequester) else Modifier
                        AetherFocusableGlassCard(
                            onClick = {
                                when (keyLabel) {
                                    "LEER" -> queryText += " "
                                    "⌫" -> if (queryText.isNotEmpty()) queryText = queryText.dropLast(1)
                                    "CLR" -> queryText = ""
                                    else -> queryText += keyLabel
                                }
                            },
                            cornerRadius = 6.dp,
                            modifier = keyMod
                        ) { isFocused ->
                            Text(
                                text = keyLabel,
                                color = if (isFocused) AetherColors.CyberCyan else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        debouncedQuery.length < 2 -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Wähle oben mindestens 2 Buchstaben aus, um Live-TV, Filme & Serien zu durchsuchen.", color = AetherColors.TextSecondary, fontSize = 14.sp)
                            }
                        }
                        searchResults.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Keine Treffer für „$debouncedQuery“ gefunden.", color = AetherColors.IceBlue, fontSize = 14.sp)
                            }
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(searchResults, key = { it.uniqueStreamKey }) { item ->
                                    AetherFocusableGlassCard(
                                        onClick = { onSelectStream(item) },
                                        cornerRadius = 8.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                                val (tag, tagColor) = when (item.sectionType) {
                                                    AetherSectionType.LIVE -> "TV" to AetherColors.CyberCyan
                                                    AetherSectionType.VOD -> "FILM" to AetherColors.NeonEmerald
                                                    AetherSectionType.SERIES -> "SERIE" to AetherColors.LaserAmber
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(tagColor.copy(alpha = 0.2f))
                                                        .border(1.dp, tagColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(tag, color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = item.name,
                                                    color = if (isFocused) Color.White else AetherColors.TextPrimary,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text("▶ Starten", color = AetherColors.CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
