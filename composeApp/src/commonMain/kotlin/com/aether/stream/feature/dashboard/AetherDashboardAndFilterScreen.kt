package com.aether.stream.feature.dashboard

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.core.ui.AetherPrismaLogo
import com.aether.stream.core.ui.AetherSmartLockBadge
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.StreamItemEntity

@Composable
fun AetherIboDashboardScreen(
    viewModel: DashboardAndFilterViewModel,
    isFilterDropDownOpen: Boolean,
    onOpenFilterDropDown: () -> Unit,
    onCloseFilterDropDown: () -> Unit,
    onNavigateToLiveTv: () -> Unit,
    onNavigateToVod: () -> Unit,
    onNavigateToSeries: () -> Unit,
    onNavigateToPlaylistSetup: () -> Unit,
    onSecretAdminTriggered: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val playlist by viewModel.activePlaylist.collectAsState()
    val newGroupsCount by viewModel.newlyDiscoveredGroupsCount.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 28.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AetherPrismaLogo(onSecretAdminTrigger = onSecretAdminTriggered)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AetherSmartLockBadge(
                        newGroupCount = newGroupsCount,
                        onClick = onOpenFilterDropDown
                    )

                    AetherFocusableGlassCard(
                        onClick = viewModel::triggerManualSync,
                        cornerRadius = 12.dp
                    ) {
                        Text(
                            text = if (uiState.isSyncingInBackground) "⏳ SYNC LÄUFT..." else "🔄 AKTUALISIEREN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = AetherColors.TextPrimary
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }

                    AetherFocusableGlassCard(
                        onClick = onOpenFilterDropDown,
                        cornerRadius = 12.dp
                    ) {
                        Text(
                            text = "▾ SENDERLISTEN & FILTER (🔒)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = AetherColors.CyberCyan
                            ),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }

                    AetherFocusableGlassCard(
                        onClick = onNavigateToPlaylistSetup,
                        cornerRadius = 12.dp
                    ) {
                        Text(
                            text = "⚙️ XTREAM LOGIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = AetherColors.IceBlue
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 28.dp)
            ) {
                DashboardPortalCard(
                    title = "LIVE TV",
                    subtitle = "3-Spalten-Cockpit & TiviMate-HUD",
                    badgeText = "ULTRA LOW LATENCY",
                    accentColor = AetherColors.CyberCyan,
                    onClick = onNavigateToLiveTv,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                DashboardPortalCard(
                    title = "VOD / FILME",
                    subtitle = "Kino-Poster & Weiter schauen",
                    badgeText = "4K • DOLBY AUDIO",
                    accentColor = AetherColors.IceBlue,
                    onClick = onNavigateToVod,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                DashboardPortalCard(
                    title = "SERIEN",
                    subtitle = "Staffeln, Episoden & Auto-Resume",
                    badgeText = "SMART TRACKING",
                    accentColor = AetherColors.NeonEmerald,
                    onClick = onNavigateToSeries,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AetherColors.GlassSurface)
                    .border(1.dp, AetherColors.DeepCyanBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Aktive Playlist: ${playlist?.playlistName ?: "Keine verbunden"} • Benutzer: ${playlist?.username ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = uiState.lastSyncMessage ?: "Smart-Lock Schutz: AKTIV",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (isFilterDropDownOpen) {
            ChannelFilterDropDownModal(
                viewModel = viewModel,
                onDismiss = {
                    viewModel.lockFilterSession()
                    onCloseFilterDropDown()
                }
            )
        }
    }
}

@Composable
private fun DashboardPortalCard(
    title: String,
    subtitle: String,
    badgeText: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AetherFocusableGlassCard(
        onClick = onClick,
        cornerRadius = 26.dp,
        modifier = modifier
    ) { isFocused ->
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall.copy(color = accentColor)
                )
                Text(
                    text = if (isFocused) "► STARTEN" else "●",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isFocused) AetherColors.CyberCyan else AetherColors.TextMuted
                    )
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        color = if (isFocused) accentColor else AetherColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ChannelFilterDropDownModal(
    viewModel: DashboardAndFilterViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.filterCategories.collectAsState()
    val expandedStreams by viewModel.expandedCategoryStreams.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(860.dp)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(2.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "▾ SENDERLISTEN-MANAGER & GEISTER-TRESOR",
                    style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
                )
                AetherFocusableGlassCard(onClick = onDismiss, cornerRadius = 10.dp) {
                    Text(
                        text = "FERTIG ✕",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!uiState.isPinVerifiedForSession) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "🔒 FILTER & GESPERRTE SENDER ENTSPERREN",
                        style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Gib deine 4-stellige Filter-PIN ein, um Sender ein-/auszublenden oder Schlösser zu ändern.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uiState.enteredPin,
                        onValueChange = viewModel::onPinInputChanged,
                        label = { Text("4-stellige PIN (Standard: 0000)") },
                        singleLine = true,
                        modifier = Modifier.width(320.dp)
                    )

                    if (uiState.pinError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.pinError!!,
                            style = MaterialTheme.typography.bodyMedium.copy(color = AetherColors.CrimsonAlert)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    AetherFocusableGlassCard(
                        onClick = viewModel::verifyFilterPin,
                        modifier = Modifier.width(320.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = "FILTER ÖFFNEN",
                                style = MaterialTheme.typography.titleMedium.copy(color = AetherColors.CyberCyan)
                            )
                        }
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AetherSectionType.entries.forEach { section ->
                        val isSelected = uiState.selectedSectionTab == section
                        val label = when (section) {
                            AetherSectionType.LIVE -> "📺 LIVE TV"
                            AetherSectionType.VOD -> "🎬 VOD / FILME"
                            AetherSectionType.SERIES -> "🍿 SERIEN"
                        }
                        AetherFocusableGlassCard(
                            onClick = { viewModel.selectSectionTab(section) },
                            isSelected = isSelected,
                            cornerRadius = 12.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = if (isSelected) AetherColors.CyberCyan else AetherColors.TextPrimary
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AetherColors.ObsidianBlack)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "1-Klick-Auswahl für ${uiState.selectedSectionTab.name}:",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AetherFocusableGlassCard(
                            onClick = { viewModel.setAllInCurrentSectionVisible(true) },
                            cornerRadius = 8.dp
                        ) {
                            Text(
                                text = "☑ ALLE AUSWÄHLEN",
                                style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.NeonEmerald),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        AetherFocusableGlassCard(
                            onClick = { viewModel.setAllInCurrentSectionVisible(false) },
                            cornerRadius = 8.dp
                        ) {
                            Text(
                                text = "☐ ALLE ABWÄHLEN",
                                style = MaterialTheme.typography.labelSmall.copy(color = AetherColors.LaserAmber),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = categories,
                        key = { it.uniqueCategoryKey }
                    ) { category ->
                        val isExpanded = uiState.expandedCategoryKey == category.uniqueCategoryKey

                        CategoryFilterRow(
                            category = category,
                            isExpanded = isExpanded,
                            onToggleExpand = { viewModel.toggleExpandCategory(category.uniqueCategoryKey) },
                            onToggleVisibility = { viewModel.toggleCategoryVisibility(category) },
                            onToggleLock = { viewModel.toggleCategoryLock(category) }
                        )

                        if (isExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 28.dp, top = 6.dp, bottom = 8.dp)
                            ) {
                                expandedStreams.forEach { stream ->
                                    StreamFilterChildRow(
                                        stream = stream,
                                        onToggleVisibility = { viewModel.toggleSingleStreamVisibility(stream) },
                                        onToggleLock = { viewModel.toggleSingleStreamLock(stream) }
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

@Composable
private fun CategoryFilterRow(
    category: CategoryEntity,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AetherColors.GlassSurface)
            .border(
                width = 1.dp,
                color = if (category.isNewlyDiscovered) AetherColors.CyberCyan else AetherColors.DeepCyanBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            AetherFocusableGlassCard(
                onClick = onToggleVisibility,
                cornerRadius = 8.dp,
                enableScaleOnFocus = false
            ) {
                Text(
                    text = if (category.isVisible) "☑ AN" else "☐ AUS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (category.isVisible) AetherColors.NeonEmerald else AetherColors.TextMuted
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            AetherFocusableGlassCard(
                onClick = onToggleExpand,
                cornerRadius = 8.dp,
                enableScaleOnFocus = false,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = buildString {
                            append(if (isExpanded) "▾ " else "▸ ")
                            append(category.categoryName)
                            if (category.isNewlyDiscovered) append("  [⚡ NEU]")
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = if (category.isVisible && !category.isLocked) {
                                AetherColors.TextPrimary
                            } else {
                                AetherColors.TextSecondary
                            }
                        )
                    )
                    Text(
                        text = if (isExpanded) "Sender einklappen" else "Einzelsender wählen",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        AetherFocusableGlassCard(
            onClick = onToggleLock,
            cornerRadius = 8.dp,
            enableScaleOnFocus = false
        ) {
            Text(
                text = if (category.isLocked) "🔒 GESPERRT" else "🔓 OFFEN",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (category.isLocked) AetherColors.CrimsonAlert else AetherColors.IceBlue
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun StreamFilterChildRow(
    stream: StreamItemEntity,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AetherColors.ObsidianBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            AetherFocusableGlassCard(
                onClick = onToggleVisibility,
                cornerRadius = 6.dp,
                enableScaleOnFocus = false
            ) {
                Text(
                    text = if (stream.isVisible) "☑" else "☐",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = if (stream.isVisible) AetherColors.CyberCyan else AetherColors.TextMuted
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Text(
                text = "${stream.customChannelNumber}. ${stream.name}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (stream.isVisible && !stream.isLocked) {
                        AetherColors.TextPrimary
                    } else {
                        AetherColors.TextMuted
                    },
                    fontWeight = FontWeight.Medium
                )
            )
        }

        AetherFocusableGlassCard(
            onClick = onToggleLock,
            cornerRadius = 6.dp,
            enableScaleOnFocus = false
        ) {
            Text(
                text = if (stream.isLocked) "🔒" else "🔓",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}