package com.aether.stream.feature.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.FavoriteFolderEntity
import com.aether.stream.data.local.SeriesEpisodeEntity
import com.aether.stream.data.local.StreamItemEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun VodAndSeriesCinemaScreen(
    sectionType: AetherSectionType,
    dao: AetherDao,
    onRequestSeriesEpisodes: (suspend (StreamItemEntity) -> Boolean)? = null,
    categories: List<CategoryEntity>,
    favoriteFolders: List<FavoriteFolderEntity>,
    selectedCategoryKey: String,
    itemsInGrid: List<StreamItemEntity>,
    onSelectCategory: (String) -> Unit,
    onPlayMediaItem: (StreamItemEntity, Boolean) -> Unit,
    onPlayEpisode: (SeriesEpisodeEntity, StreamItemEntity, Boolean) -> Unit,
    onToggleQuickFavorite: (StreamItemEntity) -> Unit,
    onOpenFavoriteFolderModal: (StreamItemEntity) -> Unit,
    onBackToDashboard: () -> Unit
) {
    var selectedDetailItem by remember { mutableStateOf<StreamItemEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .padding(24.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AetherFocusableGlassCard(onClick = onBackToDashboard, cornerRadius = 10.dp) {
                    Text(
                        text = "◄ DASHBOARD",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                Text(
                    text = if (sectionType == AetherSectionType.VOD) "🎬 AETHER VOD KINO" else "🍿 AETHER SERIEN",
                    style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
                )
            }

            Text(
                text = "Tipp: 1x ★ = Schnell-Favorit  •  OK gedrückt halten = Eigener Ordner 📁",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .width(270.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AetherColors.GlassSurface)
                    .padding(12.dp)
            ) {
                item(key = "SYS_CONTINUE") {
                    CinemaCategoryRow(
                        title = "▶️ Weiter schauen",
                        isSelected = selectedCategoryKey == "SYS_CONTINUE",
                        onClick = { onSelectCategory("SYS_CONTINUE") }
                    )
                }
                item(key = "SYS_QUICK_FAV") {
                    CinemaCategoryRow(
                        title = "⭐ Schnell-Favoriten",
                        isSelected = selectedCategoryKey == "SYS_QUICK_FAV",
                        onClick = { onSelectCategory("SYS_QUICK_FAV") }
                    )
                }

                items(
                    items = favoriteFolders,
                    key = { "FOLDER_${it.folderId}" }
                ) { folder ->
                    val folderKey = "FOLDER_${folder.folderId}"
                    CinemaCategoryRow(
                        title = "📁 ${folder.folderName}",
                        isSelected = selectedCategoryKey == folderKey,
                        onClick = { onSelectCategory(folderKey) }
                    )
                }

                items(
                    items = categories,
                    key = { it.uniqueCategoryKey }
                ) { cat ->
                    CinemaCategoryRow(
                        title = cat.categoryName,
                        isSelected = selectedCategoryKey == cat.uniqueCategoryKey,
                        onClick = { onSelectCategory(cat.uniqueCategoryKey) }
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 165.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                items(
                    items = itemsInGrid,
                    key = { it.uniqueStreamKey }
                ) { mediaItem ->
                    PosterGridCard(
                        item = mediaItem,
                        onClick = { selectedDetailItem = mediaItem },
                        onLongClick = { onOpenFavoriteFolderModal(mediaItem) },
                        onQuickFavClick = { onToggleQuickFavorite(mediaItem) }
                    )
                }
            }
        }
    }

    selectedDetailItem?.let { item ->
        if (item.sectionType == AetherSectionType.SERIES) {
            // ==========================================
            // PHASE 2: SERIEN-DETAIL-COCKPIT
            // ==========================================
            SeriesDetailCockpitModal(
                seriesItem = item,
                dao = dao,
                onRequestEpisodes = onRequestSeriesEpisodes,
                onPlayEpisode = { episode, resume ->
                    onPlayEpisode(episode, item, resume)
                },
                onOpenFolderManager = { onOpenFavoriteFolderModal(item) },
                onDismiss = { selectedDetailItem = null }
            )
        } else {
            // ==========================================
            // VOD FILM-DETAIL-MODAL
            // ==========================================
            VodSeriesDetailModal(
                item = item,
                onPlayFromStart = {
                    selectedDetailItem = null
                    onPlayMediaItem(item, false)
                },
                onResumePlayback = {
                    selectedDetailItem = null
                    onPlayMediaItem(item, true)
                },
                onOpenFolderManager = {
                    onOpenFavoriteFolderModal(item)
                },
                onDismiss = { selectedDetailItem = null }
            )
        }
    }
}

@Composable
private fun CinemaCategoryRow(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        isSelected = isSelected,
        cornerRadius = 10.dp,
        enableScaleOnFocus = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary
            ),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PosterGridCard(
    item: StreamItemEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onQuickFavClick: () -> Unit
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        onLongClick = onLongClick,
        cornerRadius = 16.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
    ) { isFocused ->
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AetherColors.ObsidianBlack.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (!item.rating.isNullOrBlank()) "⭐ ${item.rating}" else "HD",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                AetherFocusableGlassCard(
                    onClick = onQuickFavClick,
                    cornerRadius = 6.dp,
                    enableScaleOnFocus = false
                ) {
                    Text(
                        text = if (item.isQuickFavorite) "★" else "☆",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = if (item.isQuickFavorite) AetherColors.LaserAmber else AetherColors.TextSecondary
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AetherColors.ObsidianBlack.copy(alpha = 0.88f))
                    .padding(10.dp)
            ) {
                Text(
                    text = item.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextPrimary
                    )
                )

                if (item.resumePositionMs > 0L && item.totalDurationMs > 0L) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val progress = (item.resumePositionMs.toFloat() / item.totalDurationMs.toFloat())
                        .coerceIn(0.05f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        color = AetherColors.CyberCyan,
                        trackColor = AetherColors.DeepSpaceNavy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailCockpitModal(
    seriesItem: StreamItemEntity,
    dao: AetherDao,
    onRequestEpisodes: (suspend (StreamItemEntity) -> Boolean)?,
    onPlayEpisode: (SeriesEpisodeEntity, Boolean) -> Unit,
    onOpenFolderManager: () -> Unit,
    onDismiss: () -> Unit
) {
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var availableSeasons by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedSeason by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(seriesItem.uniqueStreamKey) {
        val existingSeasons = dao.getAvailableSeasons(seriesItem.uniqueStreamKey)
        if (existingSeasons.isEmpty() && onRequestEpisodes != null) {
            isLoadingEpisodes = true
            onRequestEpisodes(seriesItem)
            val updatedSeasons = dao.getAvailableSeasons(seriesItem.uniqueStreamKey)
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
                .border(2.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(26.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Linke Spalte: Serien-Metadaten
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

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AetherFocusableGlassCard(onClick = onOpenFolderManager, cornerRadius = 10.dp) {
                            Text(
                                text = "📁 IN FAVORITEN-ORDNER",
                                style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
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
                }

                // Rechte Spalte: Staffeln & Folgen
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
                                text = "Keine Episoden für diese Serie verfügbar.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        // Horizontale Staffel-Pills (D-Pad Links/Rechts)
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

                        // Vertikale Episodenliste
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            items(episodesForSeason, key = { it.uniqueEpisodeKey }) { ep ->
                                EpisodeRowCard(
                                    episode = ep,
                                    onPlayClick = { resume ->
                                        onPlayEpisode(ep, resume)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRowCard(
    episode: SeriesEpisodeEntity,
    onPlayClick: (Boolean) -> Unit
) {
    val hasResume = episode.resumePositionMs > 30_000L
    AetherFocusableGlassCard(
        onClick = { onPlayClick(hasResume) },
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
                    text = "Folge ${episode.episodeNumber}: ${episode.title}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!episode.plotDescription.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episode.plotDescription,
                        style = MaterialTheme.typography.bodySmall.copy(color = AetherColors.TextSecondary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (episode.resumePositionMs > 0L && episode.durationMs > 0L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val progress = (episode.resumePositionMs.toFloat() / episode.durationMs.toFloat()).coerceIn(0.05f, 1f)
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

@Composable
private fun VodSeriesDetailModal(
    item: StreamItemEntity,
    onPlayFromStart: () -> Unit,
    onResumePlayback: () -> Unit,
    onOpenFolderManager: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasResumePoint = item.resumePositionMs > 60_000L
    val resumeMinutes = (item.resumePositionMs / 60_000L).toInt()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(2.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineLarge.copy(color = AetherColors.CyberCyan)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bewertung: ${item.rating ?: "Keine Angabe"} • Container: ${item.containerExtension?.uppercase() ?: "MP4"}",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = item.plotDescription ?: "Bereit zur Wiedergabe in voller Hardware-Beschleunigung.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (hasResumePoint) {
                    AetherFocusableGlassCard(
                        onClick = onResumePlayback,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                        ) {
                            Text(
                                text = "▶️ WEITER SCHAUEN (AB MIN. $resumeMinutes)",
                                style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.NeonEmerald)
                            )
                        }
                    }
                }

                AetherFocusableGlassCard(
                    onClick = onPlayFromStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    ) {
                        Text(
                            text = "🎬 VON ANFANG AN STARTEN",
                            style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                AetherFocusableGlassCard(onClick = onOpenFolderManager, cornerRadius = 10.dp) {
                    Text(
                        text = "📁 IN FAVORITEN-ORDNER VERSCHIEBEN",
                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.IceBlue),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 10.dp) {
                    Text(
                        text = "SCHLIESSEN ✕",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CombinedFavoriteFolderModal(
    streamItem: StreamItemEntity,
    folders: List<FavoriteFolderEntity>,
    onToggleQuickFavorite: (StreamItemEntity) -> Unit,
    onAssignToFolder: (Long, StreamItemEntity) -> Unit,
    onCreateNewFolder: (String, StreamItemEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var newFolderName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(540.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(2.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Text(
                text = "⭐ FAVORITEN & EIGENE ORDNER",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Ausgewählt: ${streamItem.name}",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            AetherFocusableGlassCard(
                onClick = {
                    onToggleQuickFavorite(streamItem)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (streamItem.isQuickFavorite) {
                        "★ Aus 'Schnell-Favoriten' entfernen"
                    } else {
                        "⭐ Sofort zu 'Schnell-Favoriten' hinzufügen"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.LaserAmber),
                    modifier = Modifier.padding(14.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (folders.isNotEmpty()) {
                Text(
                    text = "ODER IN EIGENEN ORDNER EINSORTIEREN:",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.height(140.dp)
                ) {
                    items(folders, key = { it.folderId }) { folder ->
                        AetherFocusableGlassCard(
                            onClick = {
                                onAssignToFolder(folder.folderId, streamItem)
                                onDismiss()
                            },
                            cornerRadius = 10.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "📁 ${folder.folderName}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "➕ NEUEN FAVORITEN-ORDNER ERSTELLEN:",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Ordnername (z.B. Sport, Hauptsender)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )

                AetherFocusableGlassCard(
                    onClick = {
                        val cleaned = newFolderName.trim()
                        if (cleaned.isNotEmpty()) {
                            onCreateNewFolder(cleaned, streamItem)
                            onDismiss()
                        }
                    }
                ) {
                    Text(
                        text = "ANLEGEN",
                        style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun XtreamPlaylistSetupScreen(
    onSaveAndSyncPlaylist: (name: String, serverUrl: String, user: String, pass: String) -> Unit,
    onCancelIfPlaylistExists: (() -> Unit)? = null
) {
    var playlistName by remember { mutableStateOf("Mein Haupt-IPTV") }
    var serverUrl by remember { mutableStateOf("http://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(AetherColors.ObsidianBlack)
            .padding(32.dp)
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.GlassSurface)
                .border(1.5.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Text(
                text = "⚙️ XTREAM CODES PLAYLIST VERBINDEN",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it },
                label = { Text("Name der Liste (z.B. Wohnzimmer TV)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it.trim() },
                label = { Text("Server-URL (z.B. http://line.server.com:80)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Benutzername") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.trim() },
                    label = { Text("Passwort") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onCancelIfPlaylistExists != null) {
                    AetherFocusableGlassCard(
                        onClick = onCancelIfPlaylistExists,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp)
                        ) {
                            Text(text = "ABBRECHEN", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                AetherFocusableGlassCard(
                    onClick = {
                        if (serverUrl.length > 7 && username.isNotEmpty() && password.isNotEmpty()) {
                            onSaveAndSyncPlaylist(playlistName, serverUrl, username, password)
                        }
                    },
                    modifier = Modifier.weight(2f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    ) {
                        Text(
                            text = "VERBINDEN & SENDER LADEN",
                            style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                        )
                    }
                }
            }
        }
    }
}
