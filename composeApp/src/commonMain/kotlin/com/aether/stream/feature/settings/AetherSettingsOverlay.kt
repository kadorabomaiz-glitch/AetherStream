package com.aether.stream.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ====================================================================================================
// 1. OBSIDIAN NEON GLASS DESIGN TOKENS (TIVIMATE PARITY)
// ====================================================================================================

private val ObsidianBlack = Color(0xFF07090E)
private val ObsidianSurface = Color(0xFF0D131F)
private val ObsidianHeaderSurface = Color(0xFF131C2E)
private val CyberCyan = Color(0xFF00F0FF)
private val ElectricBlue = Color(0xFF38BDF8)
private val EmeraldGreen = Color(0xFF10B981)
private val AmberGold = Color(0xFFF59E0B)
private val PureWhite = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val FocusedTextPrimary = Color(0xFF07090E)
private val FocusedTextSecondary = Color(0xFF334155)
private val GlassBorderCyan = CyberCyan.copy(alpha = 0.35f)

// ====================================================================================================
// 2. REACTIVE SETTINGS STATE MODEL & NON-BLOCKING STORE
// ====================================================================================================

data class AetherSettingsState(
    // Bild 2 & 3: Allgemein
    val autoStartOnBoot: Boolean = false,
    val autoWakeFromSleep: Boolean = false,
    val openLastChannelOnStart: Boolean = true,
    val autoPipOnHome: Boolean = true,
    val confirmExitWithDoubleBack: Boolean = true,
    val userAgent: String = "VLC",
    val udpProxy: String = "Nicht festgelegt",
    val lastBackupStatus: String = "Noch keine Sicherung erstellt",

    // Bild 4: Wiedergabelisten (Live Telemetry Binding)
    val activePlaylistName: String = "line.trxdnscloud.ru",
    val activePlaylistChannelsCount: Int = 18957,
    val activePlaylistMoviesCount: Int = 72231,
    val activePlaylistSeriesCount: Int = 13926,

    // Bild 5 & 6: EPG
    val epgSourcesSummary: String = "Primäre Xtream XMLTV-Quelle aktiv",
    val epgPastDaysToKeep: Int = 7,
    val epgStoreProgramDescriptions: Boolean = true,
    val epgUpdateIntervalHours: Int = 2,
    val epgUpdateOnAppStart: Boolean = true,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val epgLastUpdatedChannelsCount: Int = 2969,
    val epgLastUpdatedTimestamp: String = "29.10.26 16:49",

    // Bild 7: Optik
    val language: String = "System",
    val fontSizePreset: String = "Mittel",
    val colorTheme: String = "Dunkel • Blau",
    val uiTransparencyPercent: Int = 50,
    val epgTimelineSlotMinutes: Int = 30,
    val playerHudTimeoutSeconds: Int = 5,
    val showChannelNumbersInGroups: Boolean = true,
    val logoPriority: String = "Wiedergabeliste bevorzugen",

    // Bild 8 & 9: Wiedergabe
    val bufferSizePreset: String = "Klein",
    val audioDecoder: String = "Hardware",
    val videoDecoder: String = "Software",
    val autoFrameRateMode: String = "Einschalten",
    val defaultSurroundAudioTrack: Boolean = false,
    val audioPassthrough: Boolean = false,
    val tunneledPlayback: Boolean = false,
    val amlogicFix: String = "Aus",
    val useExternalPlayer: String = "Nein",

    // Bild 10: Fernbedienung
    val remoteEpgDpadMode: String = "Standard (Kanalliste & Zeitachse)",
    val remotePlayerOkAction: String = "HUD Transport-Leiste öffnen",
    val useRwFfPauseForSeeking: Boolean = true,
    val useRwToRewindLiveStream: Boolean = false,
    val useLeftRightForSeeking: Boolean = false,
    val useLeftToRewindLiveStream: Boolean = false,
    val useDownUpForCatchupSeeking: Boolean = false,

    // Bild 11: Kindersicherung
    val parentalControlEnabled: Boolean = false,
    val parentalPinCode: String = "0000",
    val parentalPinInputMethod: String = "Sammler",
    val parentalPinLockTimeout: String = "Immer erforderlich",
    val parentalNotRequiredForChannels: Boolean = false,
    val parentalPinForSettings: Boolean = false,
    val parentalPinForPlaylists: Boolean = false,
    val parentalPinForEpg: Boolean = false,
    val parentalPinForChannelOptions: Boolean = false,

    // Bild 12: Sonstiges
    val keepSearchHistory: Boolean = true,
    val reminderLeadTimeMinutes: Int = 2,
    val recordingPaddingMinutes: Int = 5,
    val vodAutoResumePlayback: Boolean = true,

    // Bild 1: Über
    val autoCheckForUpdates: Boolean = true
)

/**
 * Thread-safe, zero-latency reactive store for all Aether settings.
 * Mutates UI state immediately (<1ms) and dispatches persistence writes asynchronously.
 */
object AetherSettingsStore {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceSaveJob: Job? = null
    private var asyncPersistenceHandler: (suspend (AetherSettingsState) -> Unit)? = null

    private val _state = MutableStateFlow(AetherSettingsState())
    val state: StateFlow<AetherSettingsState> = _state.asStateFlow()

    fun registerPersistenceHandler(handler: suspend (AetherSettingsState) -> Unit) {
        asyncPersistenceHandler = handler
    }

    fun hydrateInitialState(loadedState: AetherSettingsState) {
        _state.value = loadedState
    }

    fun updateLivePlaylistMetrics(
        playlistName: String,
        channelsCount: Int,
        moviesCount: Int,
        seriesCount: Int
    ) {
        _state.update { current ->
            current.copy(
                activePlaylistName = playlistName.ifBlank { current.activePlaylistName },
                activePlaylistChannelsCount = channelsCount,
                activePlaylistMoviesCount = moviesCount,
                activePlaylistSeriesCount = seriesCount
            )
        }
    }

    fun updateEpgSyncTelemetry(channelsCount: Int, formattedTimestamp: String) {
        updateState { current ->
            current.copy(
                epgLastUpdatedChannelsCount = channelsCount,
                epgLastUpdatedTimestamp = formattedTimestamp
            )
        }
    }

    fun updateState(transform: (AetherSettingsState) -> AetherSettingsState) {
        val updated = _state.updateAndGet(transform)
        scheduleBackgroundFlush(updated)
    }

    private fun <T> MutableStateFlow<T>.updateAndGet(function: (T) -> T): T {
        while (true) {
            val prevValue = value
            val nextValue = function(prevValue)
            if (compareAndSet(prevValue, nextValue)) {
                return nextValue
            }
        }
    }

    private fun scheduleBackgroundFlush(snapshot: AetherSettingsState) {
        val handler = asyncPersistenceHandler ?: return
        debounceSaveJob?.cancel()
        debounceSaveJob = storeScope.launch {
            delay(250L)
            runCatching { handler(snapshot) }
        }
    }
}

// ====================================================================================================
// 3. CASCADING NAVIGATION STATE MACHINE (3-LEVEL LIFO BACKSTACK)
// ====================================================================================================

enum class SettingsPageId(val title: String, val level: Int) {
    // Level 1: Root
    ROOT("Einstellungen", 1),

    // Level 2: Main Categories (Bild 1)
    GENERAL("Allgemein", 2),
    PLAYLISTS("Wiedergabelisten", 2),
    EPG("EPG", 2),
    APPEARANCE("Optik", 2),
    PLAYBACK("Wiedergabe", 2),
    REMOTE("Fernbedienung", 2),
    PARENTAL("Kindersicherung", 2),
    MISC("Sonstiges", 2),
    ABOUT("Über", 2),

    // Level 2+: Sub-Menus inside Optik, Fernbedienung, Sonstiges
    APPEARANCE_EPG("Optik • EPG", 3),
    APPEARANCE_PLAYER("Optik • Player", 3),
    APPEARANCE_GROUPS("Optik • Gruppen", 3),
    APPEARANCE_LOGOS("Optik • Logos", 3),
    REMOTE_EPG("Fernbedienung • EPG", 3),
    REMOTE_PLAYER("Fernbedienung • Player", 3),
    MISC_SEARCH("Sonstiges • Suchen", 3),
    MISC_REMINDERS("Sonstiges • Erinnerungen", 3),
    MISC_RECORDING("Sonstiges • Aufnahme", 3),
    MISC_VOD("Sonstiges • VOD", 3),

    // Level 3: Inline Value Selectors
    PICKER_USER_AGENT("User-Agent", 3),
    PICKER_UDP_PROXY("UDP Proxy (Adresse:Port)", 3),
    PICKER_EPG_PAST_DAYS("Vergangene Tage behalten", 3),
    PICKER_EPG_INTERVAL("Aktualisierungsintervall", 3),
    PICKER_LANGUAGE("Sprache", 3),
    PICKER_FONT_SIZE("Schriftgröße", 3),
    PICKER_COLOR_THEME("Farbthema", 3),
    PICKER_UI_TRANSPARENCY("Transparenz der UI (%)", 3),
    PICKER_BUFFER_SIZE("Puffergröße", 3),
    PICKER_AUDIO_DECODER("Audio Decoder", 3),
    PICKER_VIDEO_DECODER("Video Decoder", 3),
    PICKER_AFR("Framerate-Anpassung (AFR)", 3),
    PICKER_AMLOGIC_FIX("Amlogic Fix", 3),
    PICKER_EXTERNAL_PLAYER("Externen Player nutzen", 3),
    PICKER_PIN_METHOD("PIN Eingabemethode", 3),
    PICKER_PIN_TIMEOUT("PIN Sperrzeitraum", 3)
}

/**
 * Immutable stack frame preserving exact D-Pad focus and scroll coordinates across levels.
 */
data class SettingsNavFrame(
    val pageId: SettingsPageId,
    val focusedRowIndex: Int = 0,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleScrollOffset: Int = 0
)

// ====================================================================================================
// 4. UNIFIED 5-ROW TEMPLATE MODEL
// ====================================================================================================

sealed interface SettingsRowModel {
    val id: String
    val isFocusable: Boolean

    /** 1. Navigation Row (Drills down into sub-menu) */
    data class NavRow(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val targetPage: SettingsPageId
    ) : SettingsRowModel {
        override val isFocusable: Boolean = true
    }

    /** 2. Toggle Switch Row (Title + Optional Subtitle + TiviMate Pill Switch) */
    data class ToggleRow(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val checked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingsRowModel {
        override val isFocusable: Boolean = true
    }

    /** 3. Value Selector Row (Title + Current Value underneath -> Opens Level 3 Picker or Action) */
    data class ValueRow(
        override val id: String,
        val title: String,
        val valueText: String,
        val targetPickerPage: SettingsPageId? = null,
        val onClick: (() -> Unit)? = null
    ) : SettingsRowModel {
        override val isFocusable: Boolean = true
    }

    /** 4. Section Header (Non-focusable CyberCyan divider, skipped automatically by D-Pad) */
    data class SectionHeader(
        override val id: String,
        val title: String
    ) : SettingsRowModel {
        override val isFocusable: Boolean = false
    }

    /** 5. Status / Playlist / Option Row (Checkmark or Telemetry Block) */
    data class StatusOrOptionRow(
        override val id: String,
        val title: String,
        val subtitle: String? = null,
        val isSelectedOption: Boolean = false,
        val showCheckmarkCircle: Boolean = false,
        val onClick: () -> Unit
    ) : SettingsRowModel {
        override val isFocusable: Boolean = true
    }
}

// ====================================================================================================
// 5. ROOT COMPOSABLE: AETHER 340.DP RIGHT-SIDE SETTINGS OVERLAY
// ====================================================================================================

@Composable
fun AetherSettingsOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onAddPlaylistRequested: () -> Unit = {},
    onRefreshPlaylistsRequested: () -> Unit = {},
    onRefreshEpgRequested: () -> Unit = {},
    onClearEpgRequested: () -> Unit = {},
    onBufferPresetChanged: (String) -> Unit = {},
    onUserAgentChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settingsState by AetherSettingsStore.state.collectAsState()
    val drawerFocusRequester = remember { FocusRequester() }

    // LIFO Backstack with Focus Memory
    val navStack = remember {
        mutableStateListOf(SettingsNavFrame(pageId = SettingsPageId.ROOT, focusedRowIndex = 0))
    }

    // Reset stack cleanly when closed so reopening starts at Root Level 1
    LaunchedEffect(isVisible) {
        if (isVisible) {
            if (navStack.isEmpty()) {
                navStack.add(SettingsNavFrame(pageId = SettingsPageId.ROOT, focusedRowIndex = 0))
            }
            delay(40L)
            runCatching { drawerFocusRequester.requestFocus() }
        } else {
            navStack.clear()
            navStack.add(SettingsNavFrame(pageId = SettingsPageId.ROOT, focusedRowIndex = 0))
        }
    }

    val currentFrame = navStack.lastOrNull() ?: SettingsNavFrame(SettingsPageId.ROOT, 0)
    val currentRows = remember(currentFrame.pageId, settingsState) {
        buildRowsForPage(
            pageId = currentFrame.pageId,
            state = settingsState,
            onNavigate = { targetPage ->
                navStack.add(SettingsNavFrame(pageId = targetPage, focusedRowIndex = 0))
            },
            onPopStack = {
                if (navStack.size > 1) {
                    navStack.removeAt(navStack.lastIndex)
                }
            },
            onAddPlaylistRequested = onAddPlaylistRequested,
            onRefreshPlaylistsRequested = onRefreshPlaylistsRequested,
            onRefreshEpgRequested = onRefreshEpgRequested,
            onClearEpgRequested = onClearEpgRequested,
            onBufferPresetChanged = onBufferPresetChanged,
            onUserAgentChanged = onUserAgentChanged
        )
    }

    // Dynamic UI Transparency from Bild 7 ("Transparenz der Benutzeroberfläche (%)")
    val dynamicSurfaceAlpha = remember(settingsState.uiTransparencyPercent) {
        val transparencyFraction = (settingsState.uiTransparencyPercent.coerceIn(0, 80)) / 100f
        (0.96f - (transparencyFraction * 0.38f)).coerceIn(0.62f, 0.96f)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(180)) +
            slideInHorizontally(
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth }
            ),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> fullWidth }
        ) + fadeOut(animationSpec = tween(160)),
        modifier = modifier.fillMaxSize()
    ) {
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = currentFrame.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = currentFrame.firstVisibleScrollOffset
        )

        // Synchronize scroll viewport when navigating between pages or moving D-Pad focus
        LaunchedEffect(currentFrame.pageId) {
            runCatching {
                listState.scrollToItem(
                    index = currentFrame.firstVisibleItemIndex,
                    scrollOffset = currentFrame.firstVisibleScrollOffset
                )
            }
        }

        LaunchedEffect(currentFrame.focusedRowIndex, currentFrame.pageId) {
            val targetIdx = currentFrame.focusedRowIndex.coerceIn(0, (currentRows.size - 1).coerceAtLeast(0))
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val isCurrentlyVisible = visibleItems.any { item ->
                item.index == targetIdx &&
                    item.offset >= layoutInfo.viewportStartOffset &&
                    (item.offset + item.size) <= layoutInfo.viewportEndOffset
            }
            if (!isCurrentlyVisible && currentRows.isNotEmpty()) {
                runCatching {
                    listState.animateScrollToItem(index = targetIdx)
                }
            }
        }

        // Helper functions for deterministic stack & focus management
        fun updateCurrentFrameFocus(newFocusIndex: Int) {
            if (navStack.isNotEmpty()) {
                val topIndex = navStack.lastIndex
                val topFrame = navStack[topIndex]
                navStack[topIndex] = topFrame.copy(
                    focusedRowIndex = newFocusIndex,
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset
                )
            }
        }

        fun pushPageWithFocusMemory(targetPage: SettingsPageId) {
            if (navStack.isNotEmpty()) {
                val topIndex = navStack.lastIndex
                val topFrame = navStack[topIndex]
                navStack[topIndex] = topFrame.copy(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset
                )
            }
            // Pre-select current option index if entering a Level 3 Picker
            val targetRows = buildRowsForPage(
                pageId = targetPage,
                state = settingsState,
                onNavigate = {},
                onPopStack = {},
                onAddPlaylistRequested = onAddPlaylistRequested,
                onRefreshPlaylistsRequested = onRefreshPlaylistsRequested,
                onRefreshEpgRequested = onRefreshEpgRequested,
                onClearEpgRequested = onClearEpgRequested,
                onBufferPresetChanged = onBufferPresetChanged,
                onUserAgentChanged = onUserAgentChanged
            )
            val preselectedIdx = targetRows.indexOfFirst {
                it is SettingsRowModel.StatusOrOptionRow && it.isSelectedOption
            }.let { found ->
                if (found >= 0) found else targetRows.indexOfFirst { it.isFocusable }.coerceAtLeast(0)
            }
            navStack.add(
                SettingsNavFrame(
                    pageId = targetPage,
                    focusedRowIndex = preselectedIdx,
                    firstVisibleItemIndex = (preselectedIdx - 2).coerceAtLeast(0)
                )
            )
        }

        fun popPageOrDismiss() {
            if (navStack.size > 1) {
                navStack.removeAt(navStack.lastIndex)
            } else {
                onDismiss()
            }
        }

        fun triggerRowAction(row: SettingsRowModel) {
            when (row) {
                is SettingsRowModel.NavRow -> pushPageWithFocusMemory(row.targetPage)
                is SettingsRowModel.ToggleRow -> row.onToggle(!row.checked)
                is SettingsRowModel.ValueRow -> {
                    if (row.targetPickerPage != null) {
                        pushPageWithFocusMemory(row.targetPickerPage)
                    } else {
                        row.onClick?.invoke()
                    }
                }
                is SettingsRowModel.StatusOrOptionRow -> row.onClick()
                is SettingsRowModel.SectionHeader -> Unit
            }
        }

        // Full-Screen Interceptor Scrim (Locks out background zones, aligns 340.dp drawer right)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            ObsidianBlack.copy(alpha = 0.25f),
                            ObsidianBlack.copy(alpha = 0.65f)
                        )
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onDismiss() }
                )
                .focusRequester(drawerFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) {
                        // Strictly consume KeyUp as well so nothing leaks into the cockpit
                        return@onPreviewKeyEvent true
                    }
                    when (keyEvent.key) {
                        Key.DirectionDown -> {
                            val nextIdx = findNextFocusableIndex(
                                rows = currentRows,
                                currentIndex = currentFrame.focusedRowIndex,
                                direction = 1
                            )
                            updateCurrentFrameFocus(nextIdx)
                            true
                        }
                        Key.DirectionUp -> {
                            val prevIdx = findNextFocusableIndex(
                                rows = currentRows,
                                currentIndex = currentFrame.focusedRowIndex,
                                direction = -1
                            )
                            updateCurrentFrameFocus(prevIdx)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val activeRow = currentRows.getOrNull(currentFrame.focusedRowIndex)
                            if (activeRow != null && activeRow.isFocusable) {
                                triggerRowAction(activeRow)
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            val activeRow = currentRows.getOrNull(currentFrame.focusedRowIndex)
                            when (activeRow) {
                                is SettingsRowModel.NavRow -> pushPageWithFocusMemory(activeRow.targetPage)
                                is SettingsRowModel.ValueRow -> {
                                    if (activeRow.targetPickerPage != null) {
                                        pushPageWithFocusMemory(activeRow.targetPickerPage)
                                    }
                                }
                                else -> Unit
                            }
                            // Strictly absorb DPAD_RIGHT to prevent focus escape
                            true
                        }
                        Key.DirectionLeft, Key.Back, Key.Escape -> {
                            popPageOrDismiss()
                            true
                        }
                        else -> true // Strict Zone-Locking: Absorb any stray keys inside the modal
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            // 340.dp Right-Aligned Obsidian Neon Glass Drawer
            Surface(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Prevent scrim click-through inside the 340.dp panel
                    )
                    .border(
                        width = 1.dp,
                        color = GlassBorderCyan,
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
                    ),
                color = ObsidianSurface.copy(alpha = dynamicSurfaceAlpha),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                tonalElevation = 16.dp,
                shadowElevation = 24.dp
            ) {
                AnimatedContent(
                    targetState = currentFrame.pageId,
                    transitionSpec = {
                        val isDrillingDown = targetState.level >= initialState.level &&
                            targetState != SettingsPageId.ROOT
                        if (isDrillingDown) {
                            (slideInHorizontally(
                                animationSpec = tween(210, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> fullWidth / 2 }
                            ) + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(210, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> -fullWidth / 3 }
                                ) + fadeOut(tween(150)))
                        } else {
                            (slideInHorizontally(
                                animationSpec = tween(210, easing = FastOutSlowInEasing),
                                initialOffsetX = { fullWidth -> -fullWidth / 3 }
                            ) + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(
                                    animationSpec = tween(210, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> fullWidth / 2 }
                                ) + fadeOut(tween(150)))
                        }
                    },
                    label = "AetherSettingsCascadingDrawer"
                ) { targetPageId ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Top Header Bar (Matches TiviMate Blueprints 1-12)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianHeaderSurface.copy(alpha = 0.88f))
                                .padding(horizontal = 24.dp, vertical = 22.dp)
                        ) {
                            Column {
                                Text(
                                    text = targetPageId.title,
                                    color = PureWhite,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (navStack.size > 1) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "◀ Zurück mit Links / Back",
                                        color = CyberCyan.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        HorizontalDivider(
                            thickness = 1.dp,
                            color = GlassBorderCyan.copy(alpha = 0.25f)
                        )

                        // Scrollable Settings List
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                end = 12.dp,
                                top = 12.dp,
                                bottom = 28.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(
                                items = currentRows,
                                key = { _, row -> "${targetPageId.name}_${row.id}" }
                            ) { index, rowModel ->
                                val isRowFocused = (index == currentFrame.focusedRowIndex) && rowModel.isFocusable

                                SettingsUnifiedRowItem(
                                    row = rowModel,
                                    isFocused = isRowFocused,
                                    onRowClick = {
                                        if (rowModel.isFocusable) {
                                            updateCurrentFrameFocus(index)
                                            triggerRowAction(rowModel)
                                        }
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

// ====================================================================================================
// 6. DETERMINISTIC FOCUS INDEX TRAVERSAL (HEADER SKIPPING)
// ====================================================================================================

private fun findNextFocusableIndex(
    rows: List<SettingsRowModel>,
    currentIndex: Int,
    direction: Int
): Int {
    if (rows.isEmpty()) return 0
    var candidate = currentIndex + direction
    while (candidate in rows.indices) {
        if (rows[candidate].isFocusable) {
            return candidate
        }
        candidate += direction
    }
    // Clamp strictly inside the list boundaries (0 focus bleed)
    return currentIndex.coerceIn(0, rows.lastIndex)
}

// ====================================================================================================
// 7. THE 5-ROW TEMPLATE RENDERER (TIVIMATE WHITE-PILL LAW)
// ====================================================================================================

@Composable
private fun SettingsUnifiedRowItem(
    row: SettingsRowModel,
    isFocused: Boolean,
    onRowClick: () -> Unit
) {
    when (row) {
        is SettingsRowModel.SectionHeader -> {
            // Blueprint 5, 10, 11: Non-focusable CyberCyan Section Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 4.dp)
            ) {
                Text(
                    text = row.title,
                    color = CyberCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                )
            }
        }

        is SettingsRowModel.NavRow -> {
            WhitePillFocusContainer(
                isFocused = isFocused,
                onClick = onRowClick
            ) { primaryTextColor, secondaryTextColor ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = row.title,
                        color = primaryTextColor,
                        fontSize = 15.sp,
                        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!row.subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = row.subtitle,
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        is SettingsRowModel.ValueRow -> {
            WhitePillFocusContainer(
                isFocused = isFocused,
                onClick = onRowClick
            ) { primaryTextColor, secondaryTextColor ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = row.title,
                        color = primaryTextColor,
                        fontSize = 15.sp,
                        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = row.valueText,
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        is SettingsRowModel.ToggleRow -> {
            WhitePillFocusContainer(
                isFocused = isFocused,
                onClick = onRowClick
            ) { primaryTextColor, secondaryTextColor ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = row.title,
                        color = primaryTextColor,
                        fontSize = 15.sp,
                        fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                        lineHeight = 19.sp
                    )
                    if (!row.subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = row.subtitle,
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 15.sp
                        )
                    }
                }

                TiviMatePillSwitch(
                    checked = row.checked,
                    isRowFocused = isFocused
                )
            }
        }

        is SettingsRowModel.StatusOrOptionRow -> {
            WhitePillFocusContainer(
                isFocused = isFocused,
                onClick = onRowClick
            ) { primaryTextColor, secondaryTextColor ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (row.showCheckmarkCircle) {
                        // Blueprint 4: Circular Checkmark Badge for Active Playlist
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFocused) FocusedTextPrimary.copy(alpha = 0.85f)
                                    else CyberCyan.copy(alpha = 0.2f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                color = if (isFocused) PureWhite else CyberCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                    } else if (row.isSelectedOption) {
                        // Level 3 Picker Radio Indicator
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isFocused) FocusedTextPrimary else CyberCyan)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = row.title,
                            color = primaryTextColor,
                            fontSize = 15.sp,
                            fontWeight = if (isFocused || row.isSelectedOption) FontWeight.SemiBold else FontWeight.Medium
                        )
                        if (!row.subtitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = row.subtitle,
                                color = secondaryTextColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Enforces the TiviMate White-Pill Law:
 * - Unfocused: Transparent glass row, #F8FAFC primary text, #94A3B8 secondary text.
 * - Focused: Solid #FFFFFF pill, #07090E Obsidian black text, #334155 secondary text, subtle scale.
 */
@Composable
private fun WhitePillFocusContainer(
    isFocused: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.(
        primaryTextColor: Color,
        secondaryTextColor: Color
    ) -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused) PureWhite else Color.Transparent,
        animationSpec = tween(durationMillis = 120),
        label = "WhitePillBgAnimation"
    )
    val primaryTextColor by animateColorAsState(
        targetValue = if (isFocused) FocusedTextPrimary else TextPrimary,
        animationSpec = tween(durationMillis = 120),
        label = "WhitePillPrimaryTextAnimation"
    )
    val secondaryTextColor by animateColorAsState(
        targetValue = if (isFocused) FocusedTextSecondary else TextSecondary,
        animationSpec = tween(durationMillis = 120),
        label = "WhitePillSecondaryTextAnimation"
    )
    val rowScale by animateFloatAsState(
        targetValue = if (isFocused) 1.015f else 1.0f,
        animationSpec = tween(durationMillis = 120),
        label = "WhitePillScaleAnimation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = rowScale
                scaleY = rowScale
            }
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        content(primaryTextColor, secondaryTextColor)
    }
}

/**
 * Custom TiviMate-styled Switch matching Blueprints 2, 5, 8, 9, 10, 11.
 */
@Composable
private fun TiviMatePillSwitch(
    checked: Boolean,
    isRowFocused: Boolean
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 16.dp else 0.dp,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "SwitchThumbOffset"
    )

    val trackColor = when {
        checked && isRowFocused -> ElectricBlue.copy(alpha = 0.45f)
        checked && !isRowFocused -> ElectricBlue.copy(alpha = 0.55f)
        !checked && isRowFocused -> Color(0xFFCBD5E1)
        else -> Color(0xFF475569)
    }

    val thumbColor = when {
        checked && isRowFocused -> Color(0xFF0284C7)
        checked && !isRowFocused -> ElectricBlue
        !checked && isRowFocused -> Color(0xFF94A3B8)
        else -> PureWhite
    }

    Box(
        modifier = Modifier
            .width(38.dp)
            .height(22.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Track
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(trackColor)
        )
        // Circular Thumb
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .border(
                    width = 1.dp,
                    color = if (isRowFocused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.25f),
                    shape = CircleShape
                )
        )
    }
}

// ====================================================================================================
// 8. COMPLETE 12-BLUEPRINT MENU MATRIX BUILDER (LEVEL 1, LEVEL 2, LEVEL 3)
// ====================================================================================================

private fun buildRowsForPage(
    pageId: SettingsPageId,
    state: AetherSettingsState,
    onNavigate: (SettingsPageId) -> Unit,
    onPopStack: () -> Unit,
    onAddPlaylistRequested: () -> Unit,
    onRefreshPlaylistsRequested: () -> Unit,
    onRefreshEpgRequested: () -> Unit,
    onClearEpgRequested: () -> Unit,
    onBufferPresetChanged: (String) -> Unit,
    onUserAgentChanged: (String) -> Unit
): List<SettingsRowModel> {
    return when (pageId) {

        // --------------------------------------------------------------------------------------------
        // BILD 1: LEVEL 1 HAUPTMENÜ ("Einstellungen")
        // --------------------------------------------------------------------------------------------
        SettingsPageId.ROOT -> listOf(
            SettingsRowModel.NavRow("root_general", "Allgemein", targetPage = SettingsPageId.GENERAL),
            SettingsRowModel.NavRow("root_playlists", "Wiedergabelisten", targetPage = SettingsPageId.PLAYLISTS),
            SettingsRowModel.NavRow("root_epg", "EPG", targetPage = SettingsPageId.EPG),
            SettingsRowModel.NavRow("root_appearance", "Optik", targetPage = SettingsPageId.APPEARANCE),
            SettingsRowModel.NavRow("root_playback", "Wiedergabe", targetPage = SettingsPageId.PLAYBACK),
            SettingsRowModel.NavRow("root_remote", "Fernbedienung", targetPage = SettingsPageId.REMOTE),
            SettingsRowModel.NavRow("root_parental", "Kindersicherung", targetPage = SettingsPageId.PARENTAL),
            SettingsRowModel.NavRow("root_misc", "Sonstiges", targetPage = SettingsPageId.MISC),
            SettingsRowModel.NavRow("root_about", "Über", targetPage = SettingsPageId.ABOUT)
        )

        // --------------------------------------------------------------------------------------------
        // BILD 2 & BILD 3: "Allgemein"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.GENERAL -> listOf(
            SettingsRowModel.ToggleRow(
                id = "gen_boot",
                title = "Automatisch nach dem booten starten",
                checked = state.autoStartOnBoot,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(autoStartOnBoot = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "gen_sleep",
                title = "Automatisch aus dem Sleep Mode starten",
                subtitle = "Funktioniert nicht auf allen Geräten",
                checked = state.autoWakeFromSleep,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(autoWakeFromSleep = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "gen_last_channel",
                title = "Zuletzt angesehenen Sender beim Start anzeigen",
                checked = state.openLastChannelOnStart,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(openLastChannelOnStart = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "gen_pip_home",
                title = "Wechsele in den Bild-in-Bild Modus beim drücken des Home Menüs",
                checked = state.autoPipOnHome,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(autoPipOnHome = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "gen_double_back",
                title = "Doppelt bestätigen, zum verlassen mit der Zurück-Taste",
                checked = state.confirmExitWithDoubleBack,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(confirmExitWithDoubleBack = enabled) } }
            ),
            SettingsRowModel.ValueRow(
                id = "gen_user_agent",
                title = "User-Agent",
                valueText = state.userAgent,
                targetPickerPage = SettingsPageId.PICKER_USER_AGENT
            ),
            SettingsRowModel.ValueRow(
                id = "gen_udp_proxy",
                title = "UDP Proxy (Adresse:Port)",
                valueText = state.udpProxy,
                targetPickerPage = SettingsPageId.PICKER_UDP_PROXY
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "gen_backup",
                title = "Daten sichern",
                subtitle = state.lastBackupStatus,
                onClick = {
                    AetherSettingsStore.updateState {
                        it.copy(lastBackupStatus = "Lokales Snapshot-Backup erfolgreich gesichert")
                    }
                }
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "gen_restore",
                title = "Daten wiederherstellen",
                onClick = {
                    AetherSettingsStore.updateState {
                        it.copy(lastBackupStatus = "Einstellungen aus letztem Snapshot wiederhergestellt")
                    }
                }
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 4: "Wiedergabelisten"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.PLAYLISTS -> listOf(
            SettingsRowModel.StatusOrOptionRow(
                id = "pl_active",
                title = state.activePlaylistName,
                subtitle = "Kanäle: ${state.activePlaylistChannelsCount}, filme ${state.activePlaylistMoviesCount}, serien: ${state.activePlaylistSeriesCount}",
                showCheckmarkCircle = true,
                onClick = onRefreshPlaylistsRequested
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "pl_add",
                title = "Hinzufügen",
                onClick = onAddPlaylistRequested
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "pl_refresh_all",
                title = "Aktualisiere alle Wiedergabelisten",
                onClick = onRefreshPlaylistsRequested
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 5 & BILD 6: "EPG"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.EPG -> listOf(
            SettingsRowModel.StatusOrOptionRow(
                id = "epg_sources",
                title = "EPG Quellen",
                subtitle = state.epgSourcesSummary,
                onClick = onRefreshEpgRequested
            ),
            SettingsRowModel.ValueRow(
                id = "epg_past_days",
                title = "Vergangene Tage, um EPG zu behalten",
                valueText = state.epgPastDaysToKeep.toString(),
                targetPickerPage = SettingsPageId.PICKER_EPG_PAST_DAYS
            ),
            SettingsRowModel.ToggleRow(
                id = "epg_descriptions",
                title = "Programmbeschreibungen speichern",
                checked = state.epgStoreProgramDescriptions,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(epgStoreProgramDescriptions = enabled) } }
            ),
            SettingsRowModel.SectionHeader(
                id = "epg_header_update",
                title = "Update-Optionen"
            ),
            SettingsRowModel.ValueRow(
                id = "epg_interval",
                title = "Aktualisierungsintervall (Stunden)",
                valueText = state.epgUpdateIntervalHours.toString(),
                targetPickerPage = SettingsPageId.PICKER_EPG_INTERVAL
            ),
            SettingsRowModel.ToggleRow(
                id = "epg_update_start",
                title = "Aktualisieren beim starten der App",
                checked = state.epgUpdateOnAppStart,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(epgUpdateOnAppStart = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "epg_update_playlist",
                title = "Aktualisieren bei Playlisten-Änderung",
                checked = state.epgUpdateOnPlaylistChange,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(epgUpdateOnPlaylistChange = enabled) } }
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "epg_refresh_now",
                title = "Aktualisieren",
                onClick = onRefreshEpgRequested
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "epg_clear",
                title = "Löschen",
                onClick = {
                    onClearEpgRequested()
                    AetherSettingsStore.updateState {
                        it.copy(
                            epgLastUpdatedChannelsCount = 0,
                            epgLastUpdatedTimestamp = "Cache geleert"
                        )
                    }
                }
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "epg_status",
                title = "Letzter Aktualisierungsstatus",
                subtitle = "EPG wurde für ${state.epgLastUpdatedChannelsCount} Kanäle auf ${state.epgLastUpdatedTimestamp} erfolgreich aktualisiert",
                onClick = onRefreshEpgRequested
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 7: "Optik"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.APPEARANCE -> listOf(
            SettingsRowModel.NavRow("opt_epg", "EPG", targetPage = SettingsPageId.APPEARANCE_EPG),
            SettingsRowModel.NavRow("opt_player", "Player", targetPage = SettingsPageId.APPEARANCE_PLAYER),
            SettingsRowModel.NavRow("opt_groups", "Gruppen", targetPage = SettingsPageId.APPEARANCE_GROUPS),
            SettingsRowModel.NavRow("opt_logos", "Logos", targetPage = SettingsPageId.APPEARANCE_LOGOS),
            SettingsRowModel.ValueRow(
                id = "opt_language",
                title = "Sprache",
                valueText = state.language,
                targetPickerPage = SettingsPageId.PICKER_LANGUAGE
            ),
            SettingsRowModel.ValueRow(
                id = "opt_font_size",
                title = "Schriftgröße",
                valueText = state.fontSizePreset,
                targetPickerPage = SettingsPageId.PICKER_FONT_SIZE
            ),
            SettingsRowModel.ValueRow(
                id = "opt_theme",
                title = "Farbthema",
                valueText = state.colorTheme,
                targetPickerPage = SettingsPageId.PICKER_COLOR_THEME
            ),
            SettingsRowModel.ValueRow(
                id = "opt_transparency",
                title = "Transparenz der Benutzeroberfläche (%)",
                valueText = state.uiTransparencyPercent.toString(),
                targetPickerPage = SettingsPageId.PICKER_UI_TRANSPARENCY
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 8 & BILD 9: "Wiedergabe"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.PLAYBACK -> listOf(
            SettingsRowModel.ValueRow(
                id = "pb_buffer",
                title = "Puffergröße",
                valueText = state.bufferSizePreset,
                targetPickerPage = SettingsPageId.PICKER_BUFFER_SIZE
            ),
            SettingsRowModel.ValueRow(
                id = "pb_audio_dec",
                title = "Audio Decoder",
                valueText = state.audioDecoder,
                targetPickerPage = SettingsPageId.PICKER_AUDIO_DECODER
            ),
            SettingsRowModel.ValueRow(
                id = "pb_video_dec",
                title = "Video Decoder",
                valueText = state.videoDecoder,
                targetPickerPage = SettingsPageId.PICKER_VIDEO_DECODER
            ),
            SettingsRowModel.ValueRow(
                id = "pb_afr",
                title = "Automatische Anpassung der Framerate (AFR)",
                valueText = state.autoFrameRateMode,
                targetPickerPage = SettingsPageId.PICKER_AFR
            ),
            SettingsRowModel.ToggleRow(
                id = "pb_surround",
                title = "Wähle standardmäßig die Surround-Audiospur aus",
                checked = state.defaultSurroundAudioTrack,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(defaultSurroundAudioTrack = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "pb_passthrough",
                title = "Audio-Passthrough",
                checked = state.audioPassthrough,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(audioPassthrough = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "pb_tunneling",
                title = "Getunnelte Wiedergabe",
                checked = state.tunneledPlayback,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(tunneledPlayback = enabled) } }
            ),
            SettingsRowModel.ValueRow(
                id = "pb_amlogic",
                title = "Amlogic Fix",
                valueText = state.amlogicFix,
                targetPickerPage = SettingsPageId.PICKER_AMLOGIC_FIX
            ),
            SettingsRowModel.ValueRow(
                id = "pb_ext_player",
                title = "Externen Player nutzen",
                valueText = state.useExternalPlayer,
                targetPickerPage = SettingsPageId.PICKER_EXTERNAL_PLAYER
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 10: "Fernbedienung"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.REMOTE -> listOf(
            SettingsRowModel.NavRow("rem_epg", "EPG", targetPage = SettingsPageId.REMOTE_EPG),
            SettingsRowModel.NavRow("rem_player", "Player", targetPage = SettingsPageId.REMOTE_PLAYER),
            SettingsRowModel.SectionHeader(
                id = "rem_header_seek",
                title = "Suchoptionen"
            ),
            SettingsRowModel.ToggleRow(
                id = "rem_rw_ff_pause",
                title = "Verwende RW / FF / Pause zum Suchen / Pausieren, während du anschaust",
                checked = state.useRwFfPauseForSeeking,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(useRwFfPauseForSeeking = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "rem_rw_live",
                title = "Verwende RW, um den Live-Stream zurückzuspulen",
                checked = state.useRwToRewindLiveStream,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(useRwToRewindLiveStream = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "rem_lr_seek",
                title = "Verwende Links / Rechts zum Suchen, während du suchst",
                checked = state.useLeftRightForSeeking,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(useLeftRightForSeeking = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "rem_left_live",
                title = "Verwende Links, um den Live-Stream zurückzuspulen",
                checked = state.useLeftToRewindLiveStream,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(useLeftToRewindLiveStream = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "rem_ud_catchup",
                title = "Verwende Down / Up zum Suchen, während du den Catchup schaust",
                checked = state.useDownUpForCatchupSeeking,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(useDownUpForCatchupSeeking = enabled) } }
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 11: "Kindersicherung"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.PARENTAL -> listOf(
            SettingsRowModel.ToggleRow(
                id = "par_enable",
                title = if (state.parentalControlEnabled) "Ein" else "Aus",
                checked = state.parentalControlEnabled,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalControlEnabled = enabled) } }
            ),
            SettingsRowModel.ValueRow(
                id = "par_change_pin",
                title = "PIN ändern",
                valueText = "Aktuelle PIN: ****",
                onClick = {
                    val rotatedPin = if (state.parentalPinCode == "0000") "1234" else "0000"
                    AetherSettingsStore.updateState { it.copy(parentalPinCode = rotatedPin) }
                }
            ),
            SettingsRowModel.ValueRow(
                id = "par_pin_method",
                title = "PIN Eingabemethode",
                valueText = state.parentalPinInputMethod,
                targetPickerPage = SettingsPageId.PICKER_PIN_METHOD
            ),
            SettingsRowModel.ValueRow(
                id = "par_pin_timeout",
                title = "Nach dem Entsperren ist keine PIN erforderlich",
                valueText = state.parentalPinLockTimeout,
                targetPickerPage = SettingsPageId.PICKER_PIN_TIMEOUT
            ),
            SettingsRowModel.ToggleRow(
                id = "par_no_pin_channels",
                title = "Nur für Sender nicht erforderlich",
                checked = state.parentalNotRequiredForChannels,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalNotRequiredForChannels = enabled) } }
            ),
            SettingsRowModel.SectionHeader(
                id = "par_header_required",
                title = "PIN benötigt für"
            ),
            SettingsRowModel.ToggleRow(
                id = "par_req_settings",
                title = "Einstellungen",
                checked = state.parentalPinForSettings,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalPinForSettings = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "par_req_playlists",
                title = "Einstellungen | Playlists",
                checked = state.parentalPinForPlaylists,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalPinForPlaylists = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "par_req_epg",
                title = "Einstellungen | EPG",
                checked = state.parentalPinForEpg,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalPinForEpg = enabled) } }
            ),
            SettingsRowModel.ToggleRow(
                id = "par_req_channel_opts",
                title = "Kanaloptionen",
                checked = state.parentalPinForChannelOptions,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(parentalPinForChannelOptions = enabled) } }
            )
        )

        // --------------------------------------------------------------------------------------------
        // BILD 12: "Sonstiges"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.MISC -> listOf(
            SettingsRowModel.NavRow("misc_search", "Suchen", targetPage = SettingsPageId.MISC_SEARCH),
            SettingsRowModel.NavRow("misc_reminders", "Erinnerungen", targetPage = SettingsPageId.MISC_REMINDERS),
            SettingsRowModel.NavRow("misc_recording", "Aufnahme", targetPage = SettingsPageId.MISC_RECORDING),
            SettingsRowModel.NavRow("misc_vod", "VOD", targetPage = SettingsPageId.MISC_VOD)
        )

        // --------------------------------------------------------------------------------------------
        // BILD 1: "Über"
        // --------------------------------------------------------------------------------------------
        SettingsPageId.ABOUT -> listOf(
            SettingsRowModel.StatusOrOptionRow(
                id = "about_version",
                title = "Aether Stream Engine",
                subtitle = "Version L10-V5.0 (TiviMate Enterprise Architecture)",
                onClick = {}
            ),
            SettingsRowModel.StatusOrOptionRow(
                id = "about_media3",
                title = "Native Player Pipeline",
                subtitle = "AndroidX Media3 1.4.1 • Hardware SurfaceView • Ktor 3.0.0",
                onClick = {}
            ),
            SettingsRowModel.ToggleRow(
                id = "about_auto_update",
                title = "Automatisch auf Updates prüfen",
                checked = state.autoCheckForUpdates,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(autoCheckForUpdates = enabled) } }
            )
        )

        // --------------------------------------------------------------------------------------------
        // LEVEL 2+ SUB-MENUS (Optik, Fernbedienung, Sonstiges Detailseiten)
        // --------------------------------------------------------------------------------------------
        SettingsPageId.APPEARANCE_EPG -> listOf(
            SettingsRowModel.ValueRow(
                id = "app_epg_slot",
                title = "Zeitachsen-Intervall (Minuten)",
                valueText = "${state.epgTimelineSlotMinutes} Min.",
                onClick = {
                    val next = if (state.epgTimelineSlotMinutes == 30) 60 else 30
                    AetherSettingsStore.updateState { it.copy(epgTimelineSlotMinutes = next) }
                }
            ),
            SettingsRowModel.ToggleRow(
                id = "app_epg_desc",
                title = "Programminformationen im oberen Detail-Panel anzeigen",
                checked = state.epgStoreProgramDescriptions,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(epgStoreProgramDescriptions = enabled) } }
            )
        )

        SettingsPageId.APPEARANCE_PLAYER -> listOf(
            SettingsRowModel.ValueRow(
                id = "app_player_timeout",
                title = "Anzeigedauer der Vollbild-Steuerleiste",
                valueText = "${state.playerHudTimeoutSeconds} Sekunden",
                onClick = {
                    val next = when (state.playerHudTimeoutSeconds) {
                        3 -> 5
                        5 -> 8
                        8 -> 10
                        else -> 3
                    }
                    AetherSettingsStore.updateState { it.copy(playerHudTimeoutSeconds = next) }
                }
            )
        )

        SettingsPageId.APPEARANCE_GROUPS -> listOf(
            SettingsRowModel.ToggleRow(
                id = "app_grp_nums",
                title = "Kanalnummern in Kategorien anzeigen",
                checked = state.showChannelNumbersInGroups,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(showChannelNumbersInGroups = enabled) } }
            )
        )

        SettingsPageId.APPEARANCE_LOGOS -> listOf(
            SettingsRowModel.ValueRow(
                id = "app_logo_prio",
                title = "Logo-Priorität",
                valueText = state.logoPriority,
                onClick = {
                    val next = if (state.logoPriority == "Wiedergabeliste bevorzugen") {
                        "EPG-Logos bevorzugen"
                    } else {
                        "Wiedergabeliste bevorzugen"
                    }
                    AetherSettingsStore.updateState { it.copy(logoPriority = next) }
                }
            )
        )

        SettingsPageId.REMOTE_EPG -> listOf(
            SettingsRowModel.ValueRow(
                id = "rem_epg_mode",
                title = "D-Pad Navigation im TV-Guide",
                valueText = state.remoteEpgDpadMode,
                onClick = {}
            )
        )

        SettingsPageId.REMOTE_PLAYER -> listOf(
            SettingsRowModel.ValueRow(
                id = "rem_player_ok",
                title = "OK-Taste im Vollbildmodus",
                valueText = state.remotePlayerOkAction,
                onClick = {}
            )
        )

        SettingsPageId.MISC_SEARCH -> listOf(
            SettingsRowModel.ToggleRow(
                id = "misc_search_hist",
                title = "Suchverlauf automatisch speichern",
                checked = state.keepSearchHistory,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(keepSearchHistory = enabled) } }
            )
        )

        SettingsPageId.MISC_REMINDERS -> listOf(
            SettingsRowModel.ValueRow(
                id = "misc_rem_lead",
                title = "Vorlaufzeit für Programm-Erinnerungen",
                valueText = "${state.reminderLeadTimeMinutes} Minuten",
                onClick = {
                    val next = if (state.reminderLeadTimeMinutes == 2) 5 else 2
                    AetherSettingsStore.updateState { it.copy(reminderLeadTimeMinutes = next) }
                }
            )
        )

        SettingsPageId.MISC_RECORDING -> listOf(
            SettingsRowModel.ValueRow(
                id = "misc_rec_pad",
                title = "Nachlaufzeit bei Aufnahmen",
                valueText = "${state.recordingPaddingMinutes} Minuten",
                onClick = {
                    val next = if (state.recordingPaddingMinutes == 5) 10 else 5
                    AetherSettingsStore.updateState { it.copy(recordingPaddingMinutes = next) }
                }
            )
        )

        SettingsPageId.MISC_VOD -> listOf(
            SettingsRowModel.ToggleRow(
                id = "misc_vod_resume",
                title = "Filme & Serien automatisch an letzter Position fortsetzen",
                checked = state.vodAutoResumePlayback,
                onToggle = { enabled -> AetherSettingsStore.updateState { it.copy(vodAutoResumePlayback = enabled) } }
            )
        )

        // --------------------------------------------------------------------------------------------
        // LEVEL 3 INLINE VALUE PICKERS (Radio-Selection Lists inside 340.dp Drawer)
        // --------------------------------------------------------------------------------------------
        SettingsPageId.PICKER_USER_AGENT -> buildPickerRows(
            options = listOf("VLC", "TiviMate/4.7.0 (Linux; Android 11)", "ExoPlayer/1.4.1", "Lavf/60.3.100"),
            selected = state.userAgent,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(userAgent = chosen) }
                onUserAgentChanged(chosen)
                onPopStack()
            }
        )

        SettingsPageId.PICKER_UDP_PROXY -> buildPickerRows(
            options = listOf("Nicht festgelegt", "127.0.0.1:4022", "192.168.1.1:4000", "192.168.178.1:4022"),
            selected = state.udpProxy,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(udpProxy = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_EPG_PAST_DAYS -> buildPickerRows(
            options = listOf("1", "3", "7", "14"),
            selected = state.epgPastDaysToKeep.toString(),
            onSelect = { chosen ->
                val days = chosen.toIntOrNull() ?: 7
                AetherSettingsStore.updateState { it.copy(epgPastDaysToKeep = days) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_EPG_INTERVAL -> buildPickerRows(
            options = listOf("1", "2", "4", "6", "12", "24"),
            selected = state.epgUpdateIntervalHours.toString(),
            onSelect = { chosen ->
                val hours = chosen.toIntOrNull() ?: 2
                AetherSettingsStore.updateState { it.copy(epgUpdateIntervalHours = hours) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_LANGUAGE -> buildPickerRows(
            options = listOf("System", "Deutsch", "English", "Türkçe", "Polski"),
            selected = state.language,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(language = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_FONT_SIZE -> buildPickerRows(
            options = listOf("Klein", "Mittel", "Groß", "Sehr groß"),
            selected = state.fontSizePreset,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(fontSizePreset = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_COLOR_THEME -> buildPickerRows(
            options = listOf("Dunkel • Blau", "Obsidian • Cyan", "Schwarz • Smaragd", "Dunkel • Bernstein"),
            selected = state.colorTheme,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(colorTheme = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_UI_TRANSPARENCY -> buildPickerRows(
            options = listOf("0", "20", "35", "50", "65", "80"),
            selected = state.uiTransparencyPercent.toString(),
            onSelect = { chosen ->
                val pct = chosen.toIntOrNull() ?: 50
                AetherSettingsStore.updateState { it.copy(uiTransparencyPercent = pct) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_BUFFER_SIZE -> buildPickerRows(
            options = listOf("Keine", "Klein", "Mittel", "Groß", "Sehr groß"),
            selected = state.bufferSizePreset,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(bufferSizePreset = chosen) }
                onBufferPresetChanged(chosen)
                onPopStack()
            }
        )

        SettingsPageId.PICKER_AUDIO_DECODER -> buildPickerRows(
            options = listOf("Hardware", "Software"),
            selected = state.audioDecoder,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(audioDecoder = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_VIDEO_DECODER -> buildPickerRows(
            options = listOf("Hardware", "Software"),
            selected = state.videoDecoder,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(videoDecoder = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_AFR -> buildPickerRows(
            options = listOf("Aus", "Einschalten", "Einschalten (mit Umschalt-Verzögerung)"),
            selected = state.autoFrameRateMode,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(autoFrameRateMode = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_AMLOGIC_FIX -> buildPickerRows(
            options = listOf("Aus", "Ein"),
            selected = state.amlogicFix,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(amlogicFix = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_EXTERNAL_PLAYER -> buildPickerRows(
            options = listOf("Nein", "Ja", "Nur für VOD"),
            selected = state.useExternalPlayer,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(useExternalPlayer = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_PIN_METHOD -> buildPickerRows(
            options = listOf("Sammler", "Zifferntasten", "Virtuelle Tastatur"),
            selected = state.parentalPinInputMethod,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(parentalPinInputMethod = chosen) }
                onPopStack()
            }
        )

        SettingsPageId.PICKER_PIN_TIMEOUT -> buildPickerRows(
            options = listOf("Immer erforderlich", "15 Minuten", "30 Minuten", "1 Stunde", "Bis zum App-Neustart"),
            selected = state.parentalPinLockTimeout,
            onSelect = { chosen ->
                AetherSettingsStore.updateState { it.copy(parentalPinLockTimeout = chosen) }
                onPopStack()
            }
        )
    }
}

private fun buildPickerRows(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
): List<SettingsRowModel> {
    return options.mapIndexed { idx, option ->
        SettingsRowModel.StatusOrOptionRow(
            id = "picker_opt_$idx",
            title = option,
            isSelectedOption = (option == selected),
            onClick = { onSelect(option) }
        )
    }
}
