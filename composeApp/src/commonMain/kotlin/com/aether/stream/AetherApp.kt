package com.aether.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.aether.stream.core.navigation.AetherNavigationViewModel
import com.aether.stream.core.navigation.AetherScreen
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.core.platform.EnforceLandscapeCockpitMode
import com.aether.stream.core.theme.AetherTheme
import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.FavoriteFolderEntity
import com.aether.stream.data.local.FavoriteFolderMappingEntity
import com.aether.stream.data.local.PlaylistEntity
import com.aether.stream.data.local.SeriesEpisodeEntity
import com.aether.stream.data.local.StreamItemEntity
import com.aether.stream.feature.dashboard.AetherIboDashboardScreen
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperLockScreen
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.gatekeeper.SecretAdminPanelModal
import com.aether.stream.feature.player.AetherVideoPlayerEngine
import com.aether.stream.feature.player.LiveTvDualCockpitScreen
import com.aether.stream.feature.vod.CombinedFavoriteFolderModal
import com.aether.stream.feature.vod.VodAndSeriesCinemaScreen
import com.aether.stream.feature.vod.XtreamPlaylistSetupScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

    var selectedCategoryKey by remember { mutableStateOf("") }
    var currentPlayingStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var currentPlayingEpisode by remember { mutableStateOf<SeriesEpisodeEntity?>(null) }
    var isCinemaPlayerActive by remember { mutableStateOf(false) }
    var folderDialogTargetStream by remember { mutableStateOf<StreamItemEntity?>(null) }

    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val activeSection = when (navState.currentScreen) {
        is AetherScreen.VodMovies -> AetherSectionType.VOD
        is AetherScreen.Series -> AetherSectionType.SERIES
        else -> AetherSectionType.LIVE
    }

    val visibleCategories by remember(playlistId, activeSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, activeSection)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Sobald Kategorien für die aktuelle Sektion geladen sind, automatisch die erste echte Kategorie auswählen,
    // damit der Nutzer niemals vor einer leeren "Favoriten"- oder "Weiter schauen"-Liste steht!
    LaunchedEffect(visibleCategories, activeSection) {
        if (visibleCategories.isNotEmpty()) {
            val isCurrentKeyValid = visibleCategories.any { it.uniqueCategoryKey == selectedCategoryKey } ||
                selectedCategoryKey == "SYS_QUICK_FAV" ||
                selectedCategoryKey == "SYS_CONTINUE" ||
                selectedCategoryKey == "SYS_RECENT" ||
                selectedCategoryKey.startsWith("FOLDER_")

            if (selectedCategoryKey.isEmpty() || !isCurrentKeyValid) {
                selectedCategoryKey = visibleCategories.first().uniqueCategoryKey
            }
        }
    }

    val streamsInSelectedCategory by remember(playlistId, activeSection, selectedCategoryKey) {
        when {
            playlistId == 0L || selectedCategoryKey.isEmpty() -> flowOf(emptyList())
            selectedCategoryKey == "SYS_RECENT" || selectedCategoryKey == "SYS_CONTINUE" ->
                dao.observeRecentlyWatched(playlistId, activeSection)
            selectedCategoryKey == "SYS_QUICK_FAV" ->
                dao.observeQuickFavorites(playlistId, activeSection)
            selectedCategoryKey.startsWith("FOLDER_") -> {
                val folderId = selectedCategoryKey.removePrefix("FOLDER_").toLongOrNull() ?: 0L
                dao.observeStreamsInFavoriteFolder(folderId)
            }
            else -> dao.observeVisibleStreamsInCategory(selectedCategoryKey)
        }
    }.collectAsState(initial = emptyList())

    val isEnginePlaying by playerEngine.isPlaying.collectAsState()
    LaunchedEffect(isEnginePlaying, currentPlayingStream, currentPlayingEpisode) {
        if (isEnginePlaying) {
            while (true) {
                delay(5000L)
                val pos = playerEngine.currentPositionMs.value
                val dur = playerEngine.durationMs.value
                if (pos > 10_000L && dur > 0L) {
                    val now = Clock.System.now().toEpochMilliseconds()
                    currentPlayingEpisode?.let { ep ->
                        dao.updateEpisodeWatchProgress(ep.uniqueEpisodeKey, pos, dur, now)
                    } ?: currentPlayingStream?.let { stream ->
                        dao.updateWatchProgress(stream.uniqueStreamKey, pos, dur, now)
                    }
                }
            }
        }
    }

    AetherTheme {
        when (navState.currentScreen) {
            is AetherScreen.GatekeeperLock -> {
                GatekeeperLockScreen(
                    viewModel = gatekeeperViewModel,
                    onUnlockedSuccess = {
                        val next = if (activePlaylist != null) AetherScreen.Dashboard else AetherScreen.PlaylistSetup
                        navigationViewModel.navigateTo(next)
                    },
                    onSecretAdminTriggered = navigationViewModel::openSecretAdminPanel
                )
            }

            is AetherScreen.PlaylistSetup -> {
                XtreamPlaylistSetupScreen(
                    onSaveAndSyncPlaylist = { name, url, user, pass ->
                        scope.launch {
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
                            dashboardViewModel.triggerManualSync()
                            navigationViewModel.navigateTo(AetherScreen.Dashboard)
                        }
                    },
                    onCancelIfPlaylistExists = if (activePlaylist != null) {
                        { navigationViewModel.navigateTo(AetherScreen.Dashboard) }
                    } else null
                )
            }

            is AetherScreen.Dashboard -> {
                AetherIboDashboardScreen(
                    viewModel = dashboardViewModel,
                    isFilterDropDownOpen = navState.isFilterDropDownVisible,
                    onOpenFilterDropDown = { navigationViewModel.setFilterDropDownVisible(true) },
                    onCloseFilterDropDown = { navigationViewModel.setFilterDropDownVisible(false) },
                    onNavigateToLiveTv = {
                        isCinemaPlayerActive = false
                        selectedCategoryKey = ""
                        navigationViewModel.navigateTo(AetherScreen.LiveTv)
                    },
                    onNavigateToVod = {
                        isCinemaPlayerActive = false
                        selectedCategoryKey = ""
                        navigationViewModel.navigateTo(AetherScreen.VodMovies)
                    },
                    onNavigateToSeries = {
                        isCinemaPlayerActive = false
                        selectedCategoryKey = ""
                        navigationViewModel.navigateTo(AetherScreen.Series)
                    },
                    onNavigateToPlaylistSetup = {
                        navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
                    },
                    onSecretAdminTriggered = navigationViewModel::openSecretAdminPanel
                )
            }

            is AetherScreen.LiveTv -> {
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
                    startInPureFullscreen = isCinemaPlayerActive,
                    isVodOrSeriesMode = isCinemaPlayerActive,
                    onSelectCategory = { selectedCategoryKey = it },
                    onSelectStream = { stream ->
                        isCinemaPlayerActive = false
                        currentPlayingEpisode = null
                        currentPlayingStream = stream
                        scope.launch {
                            dao.updateWatchProgress(
                                uniqueStreamKey = stream.uniqueStreamKey,
                                positionMs = 0L,
                                durationMs = 0L,
                                watchedAt = Clock.System.now().toEpochMilliseconds()
                            )
                        }
                    },
                    onQuickFavoriteToggle = { stream ->
                        scope.launch {
                            dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                        }
                    },
                    onOpenFavoriteFolderDialog = { stream ->
                        folderDialogTargetStream = stream
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
                            playerEngine.playStream(nextChannel.streamUrl)
                        }
                    },
                    onBackToDashboard = {
                        if (isCinemaPlayerActive) {
                            playerEngine.pause()
                        }
                        navigationViewModel.navigateBack()
                    }
                )
            }

            is AetherScreen.VodMovies, is AetherScreen.Series -> {
                VodAndSeriesCinemaScreen(
                    sectionType = activeSection,
                    dao = dao,
                    onRequestSeriesEpisodes = { seriesItem ->
                        dashboardViewModel.fetchSeriesEpisodesOnDemand(seriesItem)
                    },
                    categories = visibleCategories,
                    favoriteFolders = favoriteFolders,
                    selectedCategoryKey = selectedCategoryKey,
                    itemsInGrid = streamsInSelectedCategory,
                    onSelectCategory = { selectedCategoryKey = it },
                    onPlayMediaItem = { mediaItem, resume ->
                        isCinemaPlayerActive = true
                        currentPlayingEpisode = null
                        currentPlayingStream = mediaItem
                        val startPos = if (resume) mediaItem.resumePositionMs else 0L
                        val now = Clock.System.now().toEpochMilliseconds()
                        scope.launch {
                            dao.updateWatchProgress(
                                uniqueStreamKey = mediaItem.uniqueStreamKey,
                                positionMs = startPos,
                                durationMs = mediaItem.totalDurationMs,
                                watchedAt = now
                            )
                        }
                        playerEngine.playStream(mediaItem.streamUrl, startPos)
                        navigationViewModel.navigateTo(AetherScreen.LiveTv)
                    },
                    onPlayEpisode = { episode, seriesItem, resume ->
                        isCinemaPlayerActive = true
                        currentPlayingEpisode = episode
                        currentPlayingStream = seriesItem.copy(
                            name = "${seriesItem.name} • S${episode.seasonNumber}E${episode.episodeNumber} - ${episode.title}",
                            streamUrl = episode.streamUrl
                        )
                        val startPos = if (resume) episode.resumePositionMs else 0L
                        val now = Clock.System.now().toEpochMilliseconds()
                        scope.launch {
                            dao.updateWatchProgress(
                                uniqueStreamKey = seriesItem.uniqueStreamKey,
                                positionMs = startPos,
                                durationMs = episode.durationMs,
                                watchedAt = now
                            )
                            dao.updateEpisodeWatchProgress(
                                uniqueEpisodeKey = episode.uniqueEpisodeKey,
                                positionMs = startPos,
                                durationMs = episode.durationMs,
                                watchedAt = now
                            )
                        }
                        playerEngine.playStream(episode.streamUrl, startPos)
                        navigationViewModel.navigateTo(AetherScreen.LiveTv)
                    },
                    onToggleQuickFavorite = { stream ->
                        scope.launch {
                            dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                        }
                    },
                    onOpenFavoriteFolderModal = { stream ->
                        folderDialogTargetStream = stream
                    },
                    onBackToDashboard = { navigationViewModel.navigateBack() }
                )
            }

            is AetherScreen.Settings -> {
                navigationViewModel.navigateTo(AetherScreen.Dashboard)
            }
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
                    scope.launch {
                        dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                    }
                },
                onAssignToFolder = { folderId, stream ->
                    scope.launch {
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
                    scope.launch {
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
    }
}
