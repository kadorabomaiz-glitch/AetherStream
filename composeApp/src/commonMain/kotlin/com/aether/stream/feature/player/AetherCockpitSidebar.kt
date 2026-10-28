package com.aether.stream.feature.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.TimeSource

/**
 * AETHER STREAM ENGINE :: OBSIDIAN NEON GLASS DESIGN TOKENS (L10-V4.2)
 */
@Stable
object AetherCockpitTokens {
    val ObsidianBlack = Color(0xFF07090E)
    val ObsidianSurface = Color(0xFF0D131F)
    val CyberCyan = Color(0xFF00F0FF)
    val ElectricBlue = Color(0xFF38BDF8)
    val EmeraldGreen = Color(0xFF10B981)
    val AmberGold = Color(0xFFF59E0B)
    val GlassBorder = CyberCyan.copy(alpha = 0.35f)
    val MutedText = Color(0xFF94A3B8)
    val CrispWhite = Color(0xFFF8FAFC)

    val SidebarCollapsedWidth: Dp = 72.dp
    val SidebarExpandedWidth: Dp = 240.dp
    const val DPAD_DEBOUNCE_MS: Long = 120L
}

/**
 * Die 3 primären Fokus-Zonen des TiviMate-Blueprints:
 * - ZONE_1_SIDEBAR: Aufgeklappte Hauptnavigation (Bild 1, 2, 4, 5)
 * - ZONE_2_CATEGORIES: Eingeklappte Sidebar (72.dp) + aktive Kategorieliste (Bild 3, 6)
 * - ZONE_3_CONTENT: Vollflächiges EPG-Grid / Content-Showcase (Bild 7)
 */
enum class CockpitFocusZone {
    ZONE_1_SIDEBAR,
    ZONE_2_CATEGORIES,
    ZONE_3_CONTENT
}

/**
 * Alle 7 Hauptnavigationspunkte gemäß den visuellen TiviMate-Blueprints (Bild 1–6).
 */
enum class AetherSidebarDestination(
    val labelDe: String,
    val icon: ImageVector,
    val isBottomPinned: Boolean = false
) {
    SEARCH(labelDe = "Suchen", icon = Icons.Filled.Search),
    LIVE_TV(labelDe = "TV", icon = Icons.Filled.LiveTv),
    VOD_MOVIES(labelDe = "Filme", icon = Icons.Filled.Movie),
    SERIES(labelDe = "Serien", icon = Icons.Filled.VideoLibrary),
    RECORDINGS(labelDe = "Aufnahmen", icon = Icons.Filled.FiberManualRecord),
    MY_LIST(labelDe = "Meine Liste", icon = Icons.Filled.Bookmark),
    SETTINGS(labelDe = "Einstellungen", icon = Icons.Filled.Settings, isBottomPinned = true)
}

/**
 * Deterministischer Fokus-Gedächtnis-Container für Zone 1 & Zone 2.
 * Verhindert Fokus-Verlust während der 180ms Breiten-Animation auf dem Fire TV Cube.
 */
@Stable
class AetherSidebarFocusController(
    initialDestination: AetherSidebarDestination = AetherSidebarDestination.LIVE_TV
) {
    var activeZone by mutableStateOf(CockpitFocusZone.ZONE_2_CATEGORIES)
        internal set

    var selectedDestination by mutableStateOf(initialDestination)
        internal set

    var focusedDestination by mutableStateOf(initialDestination)
        internal set

    private val itemFocusRequesters: Map<AetherSidebarDestination, FocusRequester> =
        AetherSidebarDestination.entries.associateWith { FocusRequester() }

    val zone2EntryFocusRequester: FocusRequester = FocusRequester()

    private val timeSource = TimeSource.Monotonic
    private var lastKeyTimestamp = timeSource.markNow()

    fun canProcessDpadEvent(): Boolean {
        val elapsedMs = lastKeyTimestamp.elapsedNow().inWholeMilliseconds
        return if (elapsedMs >= AetherCockpitTokens.DPAD_DEBOUNCE_MS) {
            lastKeyTimestamp = timeSource.markNow()
            true
        } else {
            false
        }
    }

    fun requesterFor(destination: AetherSidebarDestination): FocusRequester {
        return itemFocusRequesters.getValue(destination)
    }

    /**
     * Zieht den Fokus aus Zone 2 (Kategorien) deterministisch zurück auf das
     * aktuell aktive Hauptmodul in Zone 1 und klappt die Sidebar auf (Zustand A).
     */
    fun requestSidebarFocusOnActiveModule() {
        activeZone = CockpitFocusZone.ZONE_1_SIDEBAR
        runCatching {
            itemFocusRequesters[selectedDestination]?.requestFocus()
        }
    }

    /**
     * Schiebt den Fokus aus Zone 1 nach rechts in die Kategorieliste (Zone 2)
     * und klappt die Sidebar auf 72.dp zusammen (Zustand B).
     */
    fun transferFocusToZone2Categories(onTransferred: (() -> Unit)? = null) {
        activeZone = CockpitFocusZone.ZONE_2_CATEGORIES
        runCatching {
            zone2EntryFocusRequester.requestFocus()
        }
        onTransferred?.invoke()
    }
}

@Composable
fun rememberAetherSidebarFocusController(
    initialDestination: AetherSidebarDestination = AetherSidebarDestination.LIVE_TV
): AetherSidebarFocusController {
    return remember {
        AetherSidebarFocusController(initialDestination = initialDestination)
    }
}

/**
 * SCHRITT 1: Dynamische Zwei-Stufen-Sidebar (Zone 1).
 *
 * - Zustand A (Aufgeklappt, 240.dp): Wenn der D-Pad-Fokus in Zone 1 liegt (Bild 1 & 4).
 * - Zustand B (Eingeklappt, 72.dp): Wenn der Fokus in Zone 2 liegt (Bild 3 & 6).
 * - Phase Deferral: Liest `animatedWidthState.value` ausschließlich in `Modifier.layout`
 *   und `textAlphaState.value` ausschließlich in `Modifier.graphicsLayer`, um
 *   0 Recompositions während der Animation zu garantieren.
 */
@Composable
fun AetherCollapsibleSidebarZone1(
    controller: AetherSidebarFocusController,
    onSelectDestination: (AetherSidebarDestination) -> Unit,
    onNavigateRightToZone2: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isExpanded = controller.activeZone == CockpitFocusZone.ZONE_1_SIDEBAR

    // Animations-States (werden NICHT in der Composition-Phase dereferenziert!)
    val animatedWidthState = animateDpAsState(
        targetValue = if (isExpanded) {
            AetherCockpitTokens.SidebarExpandedWidth
        } else {
            AetherCockpitTokens.SidebarCollapsedWidth
        },
        animationSpec = tween(
            durationMillis = 180,
            easing = FastOutSlowInEasing
        ),
        label = "Zone1WidthAnimation"
    )

    val textAlphaState = animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isExpanded) 150 else 85,
            easing = LinearOutSlowInEasing
        ),
        label = "Zone1TextAlphaAnimation"
    )

    val primaryDestinations = remember {
        AetherSidebarDestination.entries.filter { !it.isBottomPinned }
    }
    val bottomDestinations = remember {
        AetherSidebarDestination.entries.filter { it.isBottomPinned }
    }
    val allOrderedDestinations = remember {
        AetherSidebarDestination.entries
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            // PHASE DEFERRAL: Breitenberechnung strikt in der Layout-Phase (0 Recompositions)
            .layout { measurable, constraints ->
                val minWidthPx = AetherCockpitTokens.SidebarCollapsedWidth.roundToPx()
                val maxWidthPx = AetherCockpitTokens.SidebarExpandedWidth.roundToPx()
                val currentWidthPx = animatedWidthState.value
                    .roundToPx()
                    .coerceIn(minWidthPx, maxWidthPx)

                val childConstraints = constraints.copy(
                    minWidth = currentWidthPx,
                    maxWidth = currentWidthPx
                )
                val placeable = measurable.measure(childConstraints)
                layout(currentWidthPx, placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }
            .clipToBounds()
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        AetherCockpitTokens.ObsidianBlack,
                        AetherCockpitTokens.ObsidianSurface.copy(alpha = 0.96f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            // 1. DYNAMISCHES AETH LOGO HEADER (Analog zu "tivimate" -> "tv" in Bild 1 & 6)
            AetherDynamicBrandHeader(
                textAlphaProvider = { textAlphaState.value }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 2. HAUPTNAVIGATION (Suchen, TV, Filme, Serien, Aufnahmen, Meine Liste)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                primaryDestinations.forEach { destination ->
                    AetherSidebarItemRow(
                        destination = destination,
                        controller = controller,
                        allDestinations = allOrderedDestinations,
                        textAlphaProvider = { textAlphaState.value },
                        onSelectDestination = onSelectDestination,
                        onNavigateRightToZone2 = onNavigateRightToZone2
                    )
                }
            }

            // Flexibler Abstandhalter drückt "Einstellungen" nach unten (wie in Bild 1–6)
            Spacer(modifier = Modifier.weight(1f))

            // 3. UNTEN VERANKERTE NAVIGATION (Einstellungen)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                bottomDestinations.forEach { destination ->
                    AetherSidebarItemRow(
                        destination = destination,
                        controller = controller,
                        allDestinations = allOrderedDestinations,
                        textAlphaProvider = { textAlphaState.value },
                        onSelectDestination = onSelectDestination,
                        onNavigateRightToZone2 = onNavigateRightToZone2
                    )
                }
            }
        }

        // Rechte Obsidian-Neon-Glass-Trennlinie (CyberCyan 0.35f)
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(1.dp)
                .background(AetherCockpitTokens.GlassBorder)
        )
    }
}

/**
 * Dynamischer Brand-Header:
 * - Die linken 72.dp zeigen immer zentriert das kompakte "AE" Neon-Emblem.
 * - Rechts daneben blendet im aufgeklappten Zustand (Zustand A) "TH" + "STREAM" ein,
 *   ohne dass das "AE"-Emblem seine horizontale Position verändert.
 */
@Composable
private fun AetherDynamicBrandHeader(
    textAlphaProvider: () -> Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clipToBounds(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Fester 72.dp Anker-Slot (exakt deckungsgleich mit der Icon-Achse darunter)
        Box(
            modifier = Modifier
                .width(AetherCockpitTokens.SidebarCollapsedWidth)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AE",
                    color = AetherCockpitTokens.CyberCyan,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Aufklappender Zusatz ("TH" + Badge) mit Phase-Deferred Alpha & Slide
        Row(
            modifier = Modifier
                .graphicsLayer {
                    val currentAlpha = textAlphaProvider()
                    alpha = currentAlpha
                    translationX = (1f - currentAlpha) * (-10.dp.toPx())
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TH",
                color = AetherCockpitTokens.CrispWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
                maxLines = 1,
                softWrap = false
            )

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                color = AetherCockpitTokens.CyberCyan.copy(alpha = 0.14f),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, AetherCockpitTokens.GlassBorder)
            ) {
                Text(
                    text = "STREAM",
                    color = AetherCockpitTokens.CyberCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Einzelne Navigationszeile innerhalb von Zone 1.
 * Beherrscht die 3 visuellen TiviMate-Zustände:
 * 1. Aktiver D-Pad-Fokus in Zone 1 (Leuchtende Fokus-Pille über die volle Breite).
 * 2. Passiver Modul-Indikator in Zustand B (wenn Fokus in Zone 2 liegt, bleibt das aktive Icon markiert).
 * 3. Inaktiver Zustand.
 */
@Composable
private fun AetherSidebarItemRow(
    destination: AetherSidebarDestination,
    controller: AetherSidebarFocusController,
    allDestinations: List<AetherSidebarDestination>,
    textAlphaProvider: () -> Float,
    onSelectDestination: (AetherSidebarDestination) -> Unit,
    onNavigateRightToZone2: () -> Unit
) {
    var hasDirectFocus by remember { mutableStateOf(false) }

    val isSidebarExpanded = controller.activeZone == CockpitFocusZone.ZONE_1_SIDEBAR
    val isActivelyFocused = hasDirectFocus && isSidebarExpanded
    val isSelectedModule = controller.selectedDestination == destination

    val focusScale by animateFloatAsState(
        targetValue = if (isActivelyFocused) 1.02f else 1.0f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "SidebarRowScale"
    )

    val rowBackgroundColor = when {
        // Zustand A: D-Pad-Fokus liegt direkt auf diesem Element (vgl. weiße/helle Pille in Bild 1 & 4)
        isActivelyFocused -> AetherCockpitTokens.CrispWhite
        // Zustand B: Fokus liegt in Zone 2, aber dieses Modul ist aktiv ausgewählt
        isSelectedModule -> AetherCockpitTokens.CyberCyan.copy(alpha = 0.16f)
        else -> Color.Transparent
    }

    val rowBorderColor = when {
        isActivelyFocused -> AetherCockpitTokens.CyberCyan
        isSelectedModule -> AetherCockpitTokens.GlassBorder
        else -> Color.Transparent
    }

    val contentColor = when {
        isActivelyFocused -> AetherCockpitTokens.ObsidianBlack
        isSelectedModule -> AetherCockpitTokens.CyberCyan
        else -> AetherCockpitTokens.MutedText
    }

    val currentIndex = remember(destination, allDestinations) {
        allDestinations.indexOf(destination)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                scaleX = focusScale
                scaleY = focusScale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(rowBackgroundColor)
            .border(
                width = if (isActivelyFocused || isSelectedModule) 1.dp else 0.dp,
                color = rowBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .focusRequester(controller.requesterFor(destination))
            .onFocusChanged { focusState ->
                hasDirectFocus = focusState.isFocused
                if (focusState.isFocused) {
                    controller.activeZone = CockpitFocusZone.ZONE_1_SIDEBAR
                    controller.focusedDestination = destination
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }

                when (keyEvent.key) {
                    Key.DirectionUp -> {
                        if (!controller.canProcessDpadEvent()) return@onPreviewKeyEvent true
                        if (currentIndex > 0) {
                            val prev = allDestinations[currentIndex - 1]
                            controller.selectedDestination = prev
                            onSelectDestination(prev)
                            runCatching { controller.requesterFor(prev).requestFocus() }
                        }
                        // Vertikales Clamping: Fokus bricht oben nicht aus Zone 1 aus
                        true
                    }

                    Key.DirectionDown -> {
                        if (!controller.canProcessDpadEvent()) return@onPreviewKeyEvent true
                        if (currentIndex < allDestinations.lastIndex) {
                            val next = allDestinations[currentIndex + 1]
                            controller.selectedDestination = next
                            onSelectDestination(next)
                            runCatching { controller.requesterFor(next).requestFocus() }
                        }
                        // Vertikales Clamping: Fokus bricht unten nicht aus Zone 1 aus
                        true
                    }

                    Key.DirectionRight -> {
                        if (!controller.canProcessDpadEvent()) return@onPreviewKeyEvent true
                        controller.selectedDestination = destination
                        onSelectDestination(destination)
                        controller.transferFocusToZone2Categories(
                            onTransferred = onNavigateRightToZone2
                        )
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!controller.canProcessDpadEvent()) return@onPreviewKeyEvent true
                        controller.selectedDestination = destination
                        onSelectDestination(destination)
                        // Bei Bestätigung (OK) ebenfalls direkt in die Kategorien (Zone 2) springen
                        controller.transferFocusToZone2Categories(
                            onTransferred = onNavigateRightToZone2
                        )
                        true
                    }

                    Key.DirectionLeft -> {
                        // Links-Wand: verhindert Fokus-Verlust am linken Bildschirmrand
                        true
                    }

                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // FESTER ICON-SLOT (56.dp + 2x8.dp Außenpadding = exakt 72.dp Gesamtbreite)
            // Dadurch bewegt sich das Icon beim Auf-/Zuklappen um 0 Pixel!
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = destination.labelDe,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            // TEXT-LABEL: strikt einzeilig (softWrap = false) + Phase-Deferred Alpha
            Text(
                text = destination.labelDe,
                color = contentColor,
                fontSize = 15.sp,
                fontWeight = if (isActivelyFocused || isSelectedModule) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .padding(end = 14.dp)
                    .graphicsLayer {
                        val currentAlpha = textAlphaProvider()
                        alpha = currentAlpha
                        translationX = (1f - currentAlpha) * (-6.dp.toPx())
                    }
            )
        }
    }
}
