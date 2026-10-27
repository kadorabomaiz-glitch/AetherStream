package com.aether.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
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
    var activeSeriesForModal by remember { mutableStateOf<StreamItemEntity?>(null) }
    var isCinemaPlayerActive by remember { mutableStateOf(false) }
    var folderDialogTargetStream by remember { mutableStateOf<StreamItemEntity?>(null) }

    var isSearchModalVisible by remember { mutableStateOf(false) }
    var isLoadingCategoryStreams by remember { mutableStateOf(false) }

    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val visibleCategories by remember(playlistId, selectedSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, selectedSection)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Deterministischer Kategorie-Reset beim Sektionswechsel
    LaunchedEffect(visibleCategories, selectedSection) {
        if (visibleCategories.isNotEmpty()) {
            val isCurrentKeyValid = visibleCategories.any { it.uniqueCategoryKey == selectedCategoryKey } ||
                selectedCategoryKey == "SYS_QUICK_FAV" ||
                selectedCategoryKey == "SYS_RECENT" ||
                selectedCategoryKey.startsWith("FOLDER_")

            if (selectedCategoryKey.isEmpty() || !isCurrentKeyValid) {
                selectedCategoryKey = visibleCategories.first().uniqueCategoryKey
            }
        } else if (selectedCategoryKey.isEmpty()) {
            selectedCategoryKey = "SYS_QUICK_FAV"
        }
    }

    val rawStreamsFlow = remember(playlistId, selectedSection, selectedCategoryKey) {
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
    }

    // Zero-Stutter Gate während Fullscreen
    var streamsInSelectedCategory by remember { mutableStateOf<List<StreamItemEntity>>(emptyList()) }
    LaunchedEffect(rawStreamsFlow, isCinemaPlayerActive) {
        rawStreamsFlow.collect { updatedList ->
            if (!isCinemaPlayerActive || streamsInSelectedCategory.isEmpty()) {
                streamsInSelectedCategory = updatedList
            }
        }
    }

    // =========================================================================
    // LEVEL-10 ON-DEMAND NACHLADER FÜR LEERE VOD- & SERIEN-KATEGORIEN
    // =========================================================================
    LaunchedEffect(selectedCategoryKey, selectedSection, visibleCategories) {
        val isSystemCategory = selectedCategoryKey == "SYS_QUICK_FAV" ||
            selectedCategoryKey == "SYS_RECENT" ||
            selectedCategoryKey.startsWith("FOLDER_")

        if (!isSystemCategory && selectedCategoryKey.isNotEmpty() && selectedSection != AetherSectionType.LIVE) {
            val matchingCat = visibleCategories.firstOrNull { it.uniqueCategoryKey == selectedCategoryKey }
            if (matchingCat != null) {
                val streamCount = withContext(Dispatchers.IO) {
                    dao.getStreamCountInCategory(matchingCat.uniqueCategoryKey)
                }
                if (streamCount == 0) {
                    isLoadingCategoryStreams = true
                    withContext(Dispatchers.IO) {
                        runCatching {
                            dashboardViewModel.fetchCategoryStreamsOnDemand(matchingCat)
                        }
                    }
                    isLoadingCategoryStreams = false
                }
            }
        }
    }

    // Resume-Checkpoint Speicherung mit 95%-Abspann-Reset
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

    // 5-Sekunden-Ticker nur für VOD und Serien
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

            // ============================================================
            // TIVIMATE 4-ZONEN DUAL COCKPIT
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
                        currentPlayingStream = stream
                        val isVod = (stream.sectionType == AetherSectionType.VOD)
                        isCinemaPlayerActive = isVod
                        val startPos = if (isVod) stream.resumePositionMs.coerceAtLeast(0L) else 0L
                        playerEngine.playStream(stream.streamUrl, startPos)

                        scope.launch(Dispatchers.IO) {
                            val now = Clock.System.now().toEpochMilliseconds()
                            dao.updateWatchProgress(
                                uniqueStreamKey = stream.uniqueStreamKey,
                                positionMs = stream.resumePositionMs,
                                durationMs = stream.totalDurationMs,
                                watchedAt = now
                            )
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
                        navigationViewModel.navigateTo(AetherScreen.PlaylistSetup)
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

        // ============================================================
        // MODALES SERIEN-COCKPIT (Volle 1080dp Breite ohne TV-Drosselung)
        // ============================================================
        activeSeriesForModal?.let { seriesItem ->
            AetherSeriesDetailModal(
                seriesItem = seriesItem,
                dao = dao,
                onFetchEpisodes = { dashboardViewModel.fetchSeriesEpisodesOnDemand(seriesItem) },
                onPlayEpisode = { episode, resume ->
                    activeSeriesForModal = null
                    isCinemaPlayerActive = true
                    currentPlayingEpisode = episode
                    val displayTitle = if (episode.title.contains(seriesItem.name, ignoreCase = true)) {
                        episode.title
                    } else {
                        "${seriesItem.name} • S${episode.seasonNumber}E${episode.episodeNumber} - ${episode.title}"
                    }
                    currentPlayingStream = seriesItem.copy(
                        name = displayTitle,
                        streamUrl = episode.streamUrl
                    )
                    val startPos = if (resume) episode.resumePositionMs.coerceAtLeast(0L) else 0L
                    playerEngine.playStream(episode.streamUrl, startPos)

                    scope.launch(Dispatchers.IO) {
                        val now = Clock.System.now().toEpochMilliseconds()
                        dao.updateWatchProgress(
                            uniqueStreamKey = seriesItem.uniqueStreamKey,
                            positionMs = startPos,
                            durationMs = episode.durationMs,
                            watchedAt = now
                        )
                    }
                },
                onDismiss = { activeSeriesForModal = null }
            )
        }

        // ============================================================
        // GLOBALE SUCHE MODAL (300ms Debounce über Live, VOD & Serien)
        // ============================================================
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
                            currentPlayingStream = stream
                            isCinemaPlayerActive = true
                            playerEngine.playStream(stream.streamUrl, stream.resumePositionMs.coerceAtLeast(0L))
                        }
                        AetherSectionType.LIVE -> {
                            currentPlayingEpisode = null
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
    }
}

/**
 * Modales Serien-Cockpit: Durch usePlatformDefaultWidth = false auf volle 1080dp TV-Breite entfesselt!
 */
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
        if (season != null) {
            dao.observeEpisodesForSeason(seriesItem.uniqueStreamKey, season)
        } else {
            flowOf(emptyList())
        }
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Linke Spalte: Details & Schließen
                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = seriesItem.name,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = AetherColors.CyberCyan,
                                fontWeight = FontWeight.Bold
                            ),
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

                // Rechte Spalte: Staffeln & Episoden
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (isLoadingEpisodes) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = AetherColors.CyberCyan)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Lade Staffeln & Episoden vom Xtream-Server...",
                                    style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                                )
                            }
                        }
                    } else if (availableSeasons.isEmpty()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Keine Episoden für diese Serie gefunden.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.TextSecondary)
                            )
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
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

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
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
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp)
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
                                                    modifier = Modifier
                                                        .fillMaxWidth(0.5f)
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (hasResume) AetherColors.NeonEmerald.copy(alpha = 0.2f)
                                                    else AetherColors.CyberCyan.copy(alpha = 0.2f)
                                                )
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

/**
 * Globales Such-Overlay (300ms Debounce über alle 3 Sektionen)
 */
@Composable
private fun AetherGlobalSearchModal(
    dao: AetherDao,
    playlistId: Long,
    onDismiss: () -> Unit,
    onSelectStream: (StreamItemEntity) -> Unit
) {
    var queryText by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(queryText) {
        delay(300L)
        debouncedQuery = queryText.trim()
    }

    val searchResults by remember(playlistId, debouncedQuery) {
        if (playlistId <= 0L || debouncedQuery.length < 2) {
            flowOf(emptyList())
        } else {
            dao.searchVisibleStreams(playlistId, debouncedQuery)
        }
    }.collectAsState(initial = emptyList())

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(960.dp)
                .height(600.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GLOBALE SUCHE (LIVE TV, VOD & SERIEN)",
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
                    label = { Text("Titel oder Kanal suchen...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = AetherColors.ObsidianBlack,
                        unfocusedContainerColor = AetherColors.ObsidianBlack
                    )
                )

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        debouncedQuery.length < 2 -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Gib mindestens 2 Zeichen ein, um zu suchen.",
                                    color = AetherColors.TextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        searchResults.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Keine Treffer für „$debouncedQuery“ gefunden.",
                                    color = AetherColors.IceBlue,
                                    fontSize = 14.sp
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(searchResults, key = { it.uniqueStreamKey }) { item ->
                                    AetherFocusableGlassCard(
                                        onClick = { onSelectStream(item) },
                                        cornerRadius = 8.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { isFocused ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                val (tag, tagColor) = when (item.sectionType) {
                                                    AetherSectionType.LIVE -> "TV" to AetherColors.CyberCyan
                                                    AetherSectionType.VOD -> "VOD" to AetherColors.NeonEmerald
                                                    AetherSectionType.SERIES -> "SERIE" to AetherColors.LaserAmber
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(tagColor.copy(alpha = 0.2f))
                                                        .border(1.dp, tagColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        color = tagColor,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
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

                                            Text(
                                                text = "▶ Starten",
                                                color = AetherColors.CyberCyan,
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
