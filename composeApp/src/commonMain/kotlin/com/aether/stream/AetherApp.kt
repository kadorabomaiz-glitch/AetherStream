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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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

    val playlistId = activePlaylist?.id ?: 0L

    val favoriteFolders by remember(playlistId) {
        if (playlistId > 0L) dao.observeFavoriteFolders(playlistId) else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val visibleCategories by remember(playlistId, selectedSection) {
        if (playlistId > 0L) dao.observeVisibleCategories(playlistId, selectedSection)
        else flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    // Deterministischer Kategorie-Reset beim Sektionswechsel (LIVE <-> VOD <-> SERIES)
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

    // Zero-Stutter Gate: Unterdrückt Listen-Re-Renders im Hintergrund während VOD/Serien im Vollbild laufen
    var streamsInSelectedCategory by remember { mutableStateOf<List<StreamItemEntity>>(emptyList()) }
    LaunchedEffect(rawStreamsFlow, isCinemaPlayerActive) {
        rawStreamsFlow.collect { updatedList ->
            if (!isCinemaPlayerActive || streamsInSelectedCategory.isEmpty()) {
                streamsInSelectedCategory = updatedList
            }
        }
    }

    // Hilfsfunktion für performantes Speichern auf Dispatchers.IO inkl. 95%-Abspann-Reset
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

    // L10-Schutz: Speichert den Fortschritt alle 5s NUR bei VOD und Serien (Niemals bei Live-TV!)
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
            // TIVIMATE 4-ZONEN DUAL COCKPIT (LIVE TV, VOD MOVIES & SERIEN)
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

                        // Markiert den Stream in "Zuletzt gesehen" (SYS_RECENT) auf Dispatchers.IO
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
                        // Bereit für Phase 2 Schritt 2 (Globale Suche)
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
        // MODALES SERIEN-COCKPIT (Staffel- & Episodenauswahl)
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

                    // Aktualisiert auch die Hauptserie für "Zuletzt gesehen" (SYS_RECENT)
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

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(960.dp)
                .height(580.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(1.5.dp, AetherColors.CyberCyan.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = seriesItem.name,
                            style = MaterialTheme.typography.headlineMedium.copy(color = AetherColors.CyberCyan),
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
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 10.dp) {
                        Text(
                            text = "SCHLIESSEN ✕",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }

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
                                    text = "Lade Staffeln & Episoden...",
                                    style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                                )
                            }
                        }
                    } else if (availableSeasons.isEmpty()) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Keine Episoden für diese Serie gefunden.",
                                style = MaterialTheme.typography.bodyMedium
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
                                            color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary
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
                                                    color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextPrimary
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

                                        Spacer(modifier = Modifier.width(14.dp))

                                        Text(
                                            text = if (hasResume) "▶️ Fortsetzen" else "▶️ Abspielen",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (hasResume) AetherColors.NeonEmerald else AetherColors.CyberCyan
                                            )
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
