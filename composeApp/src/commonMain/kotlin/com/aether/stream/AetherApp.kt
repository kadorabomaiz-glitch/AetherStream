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
import com.aether.stream.feature.dashboard.DashboardAndFilterViewModel
import com.aether.stream.feature.gatekeeper.GatekeeperLockScreen
import com.aether.stream.feature.gatekeeper.GatekeeperViewModel
import com.aether.stream.feature.gatekeeper.SecretAdminPanelModal
import com.aether.stream.feature.player.AetherVideoPlayerEngine
import com.aether.stream.feature.player.LiveTvDualCockpitScreen
import com.aether.stream.feature.vod.CombinedFavoriteFolderModal
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

    var selectedSection by remember { mutableStateOf(AetherSectionType.LIVE) }
    var selectedCategoryKey by remember { mutableStateOf("") }
    var currentPlayingStream by remember { mutableStateOf<StreamItemEntity?>(null) }
    var currentPlayingEpisode by remember { mutableStateOf<SeriesEpisodeEntity?>(null) }
    var isPureFullscreenActive by remember { mutableStateOf(false) }
    var folderDialogTargetStream by remember { mutableStateOf<StreamItemEntity?>(null) }

    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val visibleCategories by remember(playlistId, selectedSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, selectedSection)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Automatische Auswahl der ersten Kategorie bei Wechsel der Sektion
    LaunchedEffect(visibleCategories, selectedSection) {
        if (visibleCategories.isNotEmpty()) {
            val isCurrentKeyValid = visibleCategories.any { it.uniqueCategoryKey == selectedCategoryKey } ||
                selectedCategoryKey == "SYS_QUICK_FAV" ||
                selectedCategoryKey == "SYS_RECENT" ||
                selectedCategoryKey.startsWith("FOLDER_")

            if (selectedCategoryKey.isEmpty() || !isCurrentKeyValid) {
                selectedCategoryKey = visibleCategories.first().uniqueCategoryKey
            }
        }
    }

    val streamsInSelectedCategory by remember(playlistId, selectedSection, selectedCategoryKey) {
        when {
            playlistId == 0L || selectedCategoryKey.isEmpty() -> flowOf(emptyList())
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
    }.collectAsState(initial = emptyList())

    // 5-Sekunden Auto-Resume Ticker
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
                            navigationViewModel.navigateTo(AetherScreen.LiveTv)
                        }
                    },
                    onCancelIfPlaylistExists = if (activePlaylist != null) {
                        { navigationViewModel.navigateTo(AetherScreen.LiveTv) }
                    } else null
                )
            }

            // ============================================================
            // TIVIMATE DUAL COCKPIT (DAS NEUE HAUPT-DASHBOARD DER APP)
            // ============================================================
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
                    startInPureFullscreen = isPureFullscreenActive,
                    isVodOrSeriesMode = selectedSection != AetherSectionType.LIVE,
                    onSelectSection = { section ->
                        selectedSection = section
                        selectedCategoryKey = ""
                    },
                    onSelectCategory = { selectedCategoryKey = it },
                    onSelectStream = { stream ->
                        currentPlayingEpisode = null
                        currentPlayingStream = stream
                        val startPos = stream.resumePositionMs
                        scope.launch {
                            dao.updateWatchProgress(
                                uniqueStreamKey = stream.uniqueStreamKey,
                                positionMs = startPos,
                                durationMs = stream.totalDurationMs,
                                watchedAt = Clock.System.now().toEpochMilliseconds()
                            )
                        }
                        playerEngine.playStream(stream.streamUrl, startPos)
                    },
                    onQuickFavoriteToggle = { stream ->
                        scope.launch {
                            dao.setQuickFavorite(stream.uniqueStreamKey, !stream.isQuickFavorite)
                        }
                    },
                    onOpenFavoriteFolderDialog = { stream ->
                        folderDialogTargetStream = stream
                    },
                    onOpenSettings = {
                        navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
                    },
                    onOpenSearch = {
                        // Bereit für Schritt 2 der Roadmap (Suche)
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
                        if (streamsInSelectedCategory.isNotEmpty()) {
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
                        isPureFullscreenActive = false
                    }
                )
            }

            is AetherScreen.Settings -> {
                navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
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
