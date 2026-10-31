package com.aether.stream.feature.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val ObsidianBlack = Color(0xFF07090E)
private val ObsidianSurface = Color(0xFF0D131F)
private val ObsidianHeaderSurface = Color(0xFF131C2E)
private val CyberCyan = Color(0xFF00F0FF)
private val ElectricBlue = Color(0xFF38BDF8)
private val PureWhite = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val FocusedTextPrimary = Color(0xFF07090E)
private val FocusedTextSecondary = Color(0xFF334155)

enum class AetherSettingsFocusOrigin { NONE, COCKPIT_NAV_RAIL, FULLSCREEN_HUD_CAROUSEL }

data class AetherBufferLoadControlSpec(
    val presetLabel: String,
    val enginePresetKey: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

data class AetherMedia3HotSwapConfig(
    val bufferSpec: AetherBufferLoadControlSpec,
    val tunneledPlaybackEnabled: Boolean,
    val audioPassthroughEnabled: Boolean,
    val preferSurroundAudio: Boolean,
    val autoFrameRateMode: String,
    val hardwareAudioDecoder: Boolean,
    val hardwareVideoDecoder: Boolean,
    val amlogicFixEnabled: Boolean,
    val userAgent: String
)

data class AetherSettingsState(
    val isDrawerOpen: Boolean = false,
    val focusOrigin: AetherSettingsFocusOrigin = AetherSettingsFocusOrigin.NONE,
    val autoStartOnBoot: Boolean = false,
    val autoWakeFromSleep: Boolean = false,
    val openLastChannelOnStart: Boolean = true,
    val autoPipOnHome: Boolean = true,
    val confirmExitWithDoubleBack: Boolean = true,
    val userAgent: String = "TiviMate/5.1.0 (Linux; Android 11)",
    val udpProxy: String = "Nicht festgelegt",
    val lastBackupStatus: String = "Noch keine Sicherung erstellt",
    val activePlaylistName: String = "line.trxdnscloud.ru",
    val activePlaylistChannelsCount: Int = 18957,
    val activePlaylistMoviesCount: Int = 72231,
    val activePlaylistSeriesCount: Int = 13926,
    val epgSourcesSummary: String = "Primäre Xtream XMLTV-Quelle aktiv",
    val epgPastDaysToKeep: Int = 7,
    val epgStoreProgramDescriptions: Boolean = true,
    val epgUpdateIntervalHours: Int = 2,
    val epgUpdateOnAppStart: Boolean = true,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val epgLastUpdatedChannelsCount: Int = 2969,
    val epgLastUpdatedTimestamp: String = "Bereit zur Synchronisation",
    val language: String = "Deutsch",
    val fontSizePreset: String = "Mittel",
    val colorTheme: String = "Obsidian • Cyan",
    val uiTransparencyPercent: Int = 50,
    val epgTimelineSlotMinutes: Int = 30,
    val playerHudTimeoutSeconds: Int = 5,
    val showChannelNumbersInGroups: Boolean = true,
    val logoPriority: String = "Wiedergabeliste bevorzugen",
    val bufferSizePreset: String = "Klein",
    val audioDecoder: String = "Hardware",
    val videoDecoder: String = "Hardware",
    val autoFrameRateMode: String = "Einschalten",
    val defaultSurroundAudioTrack: Boolean = false,
    val audioPassthrough: Boolean = false,
    val tunneledPlayback: Boolean = false,
    val amlogicFix: String = "Aus",
    val useExternalPlayer: String = "Nein",
    val remoteEpgDpadMode: String = "Standard (Kanalliste & Zeitachse)",
    val remotePlayerOkAction: String = "HUD Transport-Leiste öffnen",
    val useRwFfPauseForSeeking: Boolean = true,
    val useRwToRewindLiveStream: Boolean = false,
    val useLeftRightForSeeking: Boolean = false,
    val useLeftToRewindLiveStream: Boolean = false,
    val useDownUpForCatchupSeeking: Boolean = false,
    val parentalControlEnabled: Boolean = false,
    val parentalPinCode: String = "0000",
    val parentalPinInputMethod: String = "Sammler",
    val parentalPinLockTimeout: String = "Immer erforderlich",
    val parentalNotRequiredForChannels: Boolean = false,
    val parentalPinForSettings: Boolean = false,
    val parentalPinForPlaylists: Boolean = false,
    val parentalPinForEpg: Boolean = false,
    val parentalPinForChannelOptions: Boolean = false,
    val keepSearchHistory: Boolean = true,
    val reminderLeadTimeMinutes: Int = 2,
    val recordingPaddingMinutes: Int = 5,
    val vodAutoResumePlayback: Boolean = true,
    val autoCheckForUpdates: Boolean = true
)

object AetherSettingsStore {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceSaveJob: Job? = null
    private var asyncPersistenceHandler: (suspend (AetherSettingsState) -> Unit)? = null
    private val _state = MutableStateFlow(AetherSettingsState())
    val state: StateFlow<AetherSettingsState> = _state.asStateFlow()

    fun registerPersistenceHandler(handler: suspend (AetherSettingsState) -> Unit) { asyncPersistenceHandler = handler }
    fun hydrateInitialState(loadedState: AetherSettingsState) { _state.value = loadedState }
    fun openDrawer(origin: AetherSettingsFocusOrigin = AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL) { _state.update { it.copy(isDrawerOpen = true, focusOrigin = origin) } }
    fun closeDrawer(): AetherSettingsFocusOrigin { val o = _state.value.focusOrigin; _state.update { it.copy(isDrawerOpen = false, focusOrigin = AetherSettingsFocusOrigin.NONE) }; return o }

    fun updateLivePlaylistMetrics(playlistNameOrUrl: String, channelsCount: Int, moviesCount: Int, seriesCount: Int) {
        val cleanHost = playlistNameOrUrl.trim().removePrefix("https://").removePrefix("http://").removePrefix("rtmp://").substringBefore('/').substringBefore('?').substringBefore(':').trim()
        _state.update { cur ->
            val name = cleanHost.ifBlank { cur.activePlaylistName }
            cur.copy(activePlaylistName = name, activePlaylistChannelsCount = channelsCount.coerceAtLeast(0), activePlaylistMoviesCount = moviesCount.coerceAtLeast(0), activePlaylistSeriesCount = seriesCount.coerceAtLeast(0))
        }
    }

    fun updateEpgSyncTelemetry(channelsCount: Int, formattedTimestamp: String) {
        updateState { cur -> cur.copy(epgLastUpdatedChannelsCount = channelsCount.coerceAtLeast(0), epgLastUpdatedTimestamp = formattedTimestamp.ifBlank { cur.epgLastUpdatedTimestamp }) }
    }

    fun resolveBufferSpec(presetName: String = _state.value.bufferSizePreset): AetherBufferLoadControlSpec = when (presetName) {
        "Keine" -> AetherBufferLoadControlSpec("Keine", "ULTRA_LOW_LATENCY", 1_500, 4_000, 250, 800)
        "Klein" -> AetherBufferLoadControlSpec("Klein", "LOW_LATENCY", 4_000, 12_000, 250, 1_000)
        "Mittel" -> AetherBufferLoadControlSpec("Mittel", "BALANCED", 12_000, 30_000, 500, 1_500)
        "Groß" -> AetherBufferLoadControlSpec("Groß", "HIGH_STABILITY", 25_000, 50_000, 1_000, 2_500)
        else -> AetherBufferLoadControlSpec("Sehr groß", "MAX_RESILIENCE", 40_000, 80_000, 1_500, 3_500)
    }

    fun buildHotSwapConfig(s: AetherSettingsState = _state.value) = AetherMedia3HotSwapConfig(
        bufferSpec = resolveBufferSpec(s.bufferSizePreset), tunneledPlaybackEnabled = s.tunneledPlayback, audioPassthroughEnabled = s.audioPassthrough,
        preferSurroundAudio = s.defaultSurroundAudioTrack, autoFrameRateMode = s.autoFrameRateMode, hardwareAudioDecoder = s.audioDecoder == "Hardware",
        hardwareVideoDecoder = s.videoDecoder == "Hardware", amlogicFixEnabled = s.amlogicFix == "Ein", userAgent = s.userAgent
    )

    fun updateState(transform: (AetherSettingsState) -> AetherSettingsState) {
        _state.update(transform)
        val snapshot = _state.value
        val handler = asyncPersistenceHandler ?: return
        debounceSaveJob?.cancel()
        debounceSaveJob = storeScope.launch { delay(250L); runCatching { handler(snapshot) } }
    }
}

@Composable
fun BindAetherSettingsLiveTelemetry(activePlaylistUrlOrName: String, liveChannelsCount: Int, vodMoviesCount: Int, seriesCount: Int, epgUpdatedChannelsCount: Int = -1, epgUpdatedTimestamp: String = "") {
    LaunchedEffect(activePlaylistUrlOrName, liveChannelsCount, vodMoviesCount, seriesCount) { AetherSettingsStore.updateLivePlaylistMetrics(activePlaylistUrlOrName, liveChannelsCount, vodMoviesCount, seriesCount) }
    LaunchedEffect(epgUpdatedChannelsCount, epgUpdatedTimestamp) { if (epgUpdatedChannelsCount >= 0 && epgUpdatedTimestamp.isNotBlank()) AetherSettingsStore.updateEpgSyncTelemetry(epgUpdatedChannelsCount, epgUpdatedTimestamp) }
}

@Composable
fun BindAetherEngineParameters(onApplyHotSwapConfig: (AetherMedia3HotSwapConfig) -> Unit) {
    val state by AetherSettingsStore.state.collectAsState()
    val cfg = remember(state.bufferSizePreset, state.tunneledPlayback, state.audioPassthrough, state.defaultSurroundAudioTrack, state.autoFrameRateMode, state.audioDecoder, state.videoDecoder, state.amlogicFix, state.userAgent) { AetherSettingsStore.buildHotSwapConfig(state) }
    LaunchedEffect(cfg) { onApplyHotSwapConfig(cfg) }
}

enum class SettingsPageId(val title: String, val level: Int) {
    ROOT("Einstellungen", 1), GENERAL("Allgemein", 2), PLAYLISTS("Wiedergabelisten", 2), EPG("EPG", 2), APPEARANCE("Optik", 2),
    PLAYBACK("Wiedergabe", 2), REMOTE("Fernbedienung", 2), PARENTAL("Kindersicherung", 2), MISC("Sonstiges", 2), ABOUT("Über", 2),
    APPEARANCE_EPG("Optik • EPG", 3), APPEARANCE_PLAYER("Optik • Player", 3), APPEARANCE_GROUPS("Optik • Gruppen", 3), APPEARANCE_LOGOS("Optik • Logos", 3),
    REMOTE_EPG("Fernbedienung • EPG", 3), REMOTE_PLAYER("Fernbedienung • Player", 3), MISC_SEARCH("Sonstiges • Suchen", 3), MISC_REMINDERS("Sonstiges • Erinnerungen", 3),
    MISC_RECORDING("Sonstiges • Aufnahme", 3), MISC_VOD("Sonstiges • VOD", 3), PICKER_USER_AGENT("User-Agent", 3), PICKER_UDP_PROXY("UDP Proxy (Adresse:Port)", 3),
    PICKER_EPG_PAST_DAYS("Vergangene Tage behalten", 3), PICKER_EPG_INTERVAL("Aktualisierungsintervall", 3), PICKER_LANGUAGE("Sprache", 3), PICKER_FONT_SIZE("Schriftgröße", 3),
    PICKER_COLOR_THEME("Farbthema", 3), PICKER_UI_TRANSPARENCY("Transparenz der UI (%)", 3), PICKER_BUFFER_SIZE("Puffergröße", 3), PICKER_AUDIO_DECODER("Audio Decoder", 3),
    PICKER_VIDEO_DECODER("Video Decoder", 3), PICKER_AFR("Framerate-Anpassung (AFR)", 3), PICKER_AMLOGIC_FIX("Amlogic Fix", 3), PICKER_EXTERNAL_PLAYER("Externen Player nutzen", 3),
    PICKER_PIN_METHOD("PIN Eingabemethode", 3), PICKER_PIN_TIMEOUT("PIN Sperrzeitraum", 3)
}

data class SettingsNavFrame(val pageId: SettingsPageId, val focusedRowIndex: Int = 0, val firstVisibleItemIndex: Int = 0, val firstVisibleScrollOffset: Int = 0)

sealed interface SettingsRowModel {
    val id: String; val isFocusable: Boolean
    data class NavRow(override val id: String, val title: String, val subtitle: String? = null, val targetPage: SettingsPageId) : SettingsRowModel { override val isFocusable = true }
    data class ToggleRow(override val id: String, val title: String, val subtitle: String? = null, val checked: Boolean, val onToggle: (Boolean) -> Unit) : SettingsRowModel { override val isFocusable = true }
    data class ValueRow(override val id: String, val title: String, val valueText: String, val targetPickerPage: SettingsPageId? = null, val onClick: (() -> Unit)? = null) : SettingsRowModel { override val isFocusable = true }
    data class SectionHeader(override val id: String, val title: String) : SettingsRowModel { override val isFocusable = false }
    data class StatusOrOptionRow(override val id: String, val title: String, val subtitle: String? = null, val isSelectedOption: Boolean = false, val showCheckmarkCircle: Boolean = false, val onClick: () -> Unit) : SettingsRowModel { override val isFocusable = true }
}

@Composable
fun AetherSettingsOverlay(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    focusOrigin: AetherSettingsFocusOrigin = AetherSettingsFocusOrigin.COCKPIT_NAV_RAIL,
    navRailFocusRequester: FocusRequester? = null,
    hudCarouselFocusRequester: FocusRequester? = null,
    onRestoreFocusToOrigin: ((AetherSettingsFocusOrigin) -> Unit)? = null,
    onAddPlaylistRequested: () -> Unit = {},
    onRefreshPlaylistsRequested: () -> Unit = {},
    onRefreshEpgRequested: () -> Unit = {},
    onClearEpgRequested: () -> Unit = {},
    onBufferPresetChanged: (String) -> Unit = {},
    onUserAgentChanged: (String) -> Unit = {},
    onTunneledPlaybackChanged: (Boolean) -> Unit = {},
    onAudioPassthroughChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settingsState by AetherSettingsStore.state.collectAsState()
    val drawerFocusRequester = remember { FocusRequester() }
    val navStack = remember { mutableStateListOf(SettingsNavFrame(SettingsPageId.ROOT, 0)) }

    val themeAccentColor = remember(settingsState.colorTheme) {
        when (settingsState.colorTheme) {
            "Dunkel • Blau" -> ElectricBlue
            "Schwarz • Smaragd" -> Color(0xFF10B981)
            "Dunkel • Bernstein" -> Color(0xFFF59E0B)
            else -> CyberCyan
        }
    }
    val fontDeltaSp = remember(settingsState.fontSizePreset) {
        when (settingsState.fontSizePreset) { "Klein" -> -1; "Groß" -> 2; "Sehr groß" -> 4; else -> 0 }
    }

    fun executeDismiss() {
        val origin = if (settingsState.focusOrigin != AetherSettingsFocusOrigin.NONE) AetherSettingsStore.closeDrawer() else focusOrigin
        onDismiss()
        onRestoreFocusToOrigin?.invoke(origin)
        when (origin) {
            AetherSettingsFocusOrigin.FULLSCREEN_HUD_CAROUSEL -> runCatching { hudCarouselFocusRequester?.requestFocus() }
            else -> runCatching { navRailFocusRequester?.requestFocus() }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            if (navStack.isEmpty()) navStack.add(SettingsNavFrame(SettingsPageId.ROOT, 0))
            delay(40L); runCatching { drawerFocusRequester.requestFocus() }
        } else { navStack.clear(); navStack.add(SettingsNavFrame(SettingsPageId.ROOT, 0)) }
    }

    val currentFrame = navStack.lastOrNull() ?: SettingsNavFrame(SettingsPageId.ROOT, 0)
    val currentRows = remember(currentFrame.pageId, settingsState) {
        buildRowsForPage(
            pageId = currentFrame.pageId, state = settingsState,
            onPopStack = { if (navStack.size > 1) navStack.removeAt(navStack.lastIndex) },
            onAddPlaylistRequested = { executeDismiss(); onAddPlaylistRequested() },
            onRefreshPlaylistsRequested = onRefreshPlaylistsRequested, onRefreshEpgRequested = onRefreshEpgRequested,
            onClearEpgRequested = onClearEpgRequested, onBufferPresetChanged = onBufferPresetChanged,
            onUserAgentChanged = onUserAgentChanged, onTunneledPlaybackChanged = onTunneledPlaybackChanged, onAudioPassthroughChanged = onAudioPassthroughChanged
        )
    }

    val dynamicSurfaceAlpha = remember(settingsState.uiTransparencyPercent) {
        (0.96f - ((settingsState.uiTransparencyPercent.coerceIn(0, 80) / 100f) * 0.42f)).coerceIn(0.55f, 0.96f)
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it },
        exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(160)),
        modifier = modifier.fillMaxSize().zIndex(60f)
    ) {
        val listState = rememberLazyListState(currentFrame.firstVisibleItemIndex, currentFrame.firstVisibleScrollOffset)
        LaunchedEffect(currentFrame.pageId) { runCatching { listState.scrollToItem(currentFrame.firstVisibleItemIndex, currentFrame.firstVisibleScrollOffset) } }
        LaunchedEffect(currentFrame.focusedRowIndex, currentFrame.pageId) {
            val idx = currentFrame.focusedRowIndex.coerceIn(0, (currentRows.size - 1).coerceAtLeast(0))
            val info = listState.layoutInfo
            val vis = info.visibleItemsInfo.any { it.index == idx && it.offset >= info.viewportStartOffset && (it.offset + it.size) <= info.viewportEndOffset }
            if (!vis && currentRows.isNotEmpty()) runCatching { listState.animateScrollToItem(idx) }
        }

        fun updateFocus(newIdx: Int) {
            if (navStack.isNotEmpty()) {
                val top = navStack.lastIndex
                navStack[top] = navStack[top].copy(focusedRowIndex = newIdx, firstVisibleItemIndex = listState.firstVisibleItemIndex, firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset)
            }
        }

        fun pushPage(target: SettingsPageId) {
            if (navStack.isNotEmpty()) {
                val top = navStack.lastIndex
                navStack[top] = navStack[top].copy(firstVisibleItemIndex = listState.firstVisibleItemIndex, firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset)
            }
            val tRows = buildRowsForPage(target, settingsState, {}, onAddPlaylistRequested, onRefreshPlaylistsRequested, onRefreshEpgRequested, onClearEpgRequested, onBufferPresetChanged, onUserAgentChanged, onTunneledPlaybackChanged, onAudioPassthroughChanged)
            val preIdx = tRows.indexOfFirst { it is SettingsRowModel.StatusOrOptionRow && it.isSelectedOption }.let { if (it >= 0) it else tRows.indexOfFirst { r -> r.isFocusable }.coerceAtLeast(0) }
            navStack.add(SettingsNavFrame(target, preIdx, (preIdx - 2).coerceAtLeast(0)))
        }

        fun triggerRow(row: SettingsRowModel) {
            when (row) {
                is SettingsRowModel.NavRow -> pushPage(row.targetPage)
                is SettingsRowModel.ToggleRow -> row.onToggle(!row.checked)
                is SettingsRowModel.ValueRow -> if (row.targetPickerPage != null) pushPage(row.targetPickerPage) else row.onClick?.invoke()
                is SettingsRowModel.StatusOrOptionRow -> row.onClick()
                is SettingsRowModel.SectionHeader -> Unit
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(ObsidianBlack.copy(alpha = 0.25f), ObsidianBlack.copy(alpha = 0.65f))))
                .clickable(remember { MutableInteractionSource() }, null) { executeDismiss() }
                .focusRequester(drawerFocusRequester).focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    when (ev.key) {
                        Key.DirectionDown -> { updateFocus(findNextFocusableIndex(currentRows, currentFrame.focusedRowIndex, 1)); true }
                        Key.DirectionUp -> { updateFocus(findNextFocusableIndex(currentRows, currentFrame.focusedRowIndex, -1)); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { currentRows.getOrNull(currentFrame.focusedRowIndex)?.takeIf { it.isFocusable }?.let { triggerRow(it) }; true }
                        Key.DirectionRight -> {
                            when (val r = currentRows.getOrNull(currentFrame.focusedRowIndex)) {
                                is SettingsRowModel.NavRow -> pushPage(r.targetPage)
                                is SettingsRowModel.ValueRow -> if (r.targetPickerPage != null) pushPage(r.targetPickerPage)
                                else -> Unit
                            }
                            true
                        }
                        Key.DirectionLeft, Key.Back, Key.Escape -> { if (navStack.size > 1) navStack.removeAt(navStack.lastIndex) else executeDismiss(); true }
                        else -> true
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Surface(
                modifier = Modifier.width(350.dp).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) {}.border(1.5.dp, themeAccentColor.copy(alpha = 0.45f), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)),
                color = ObsidianSurface.copy(alpha = dynamicSurfaceAlpha),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
            ) {
                AnimatedContent(targetState = currentFrame.pageId, label = "SettingsPageAnim") { pageId ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().background(ObsidianHeaderSurface.copy(alpha = 0.90f)).padding(horizontal = 24.dp, vertical = 20.dp)) {
                            Column {
                                Text(pageId.title, color = PureWhite, fontSize = (21 + fontDeltaSp).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (navStack.size > 1) Text("◀ Zurück mit Links / Back", color = themeAccentColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        HorizontalDivider(thickness = 1.dp, color = themeAccentColor.copy(alpha = 0.30f))
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(currentRows, key = { _, r -> "${pageId.name}_${r.id}" }) { idx, rowModel ->
                                val focused = (idx == currentFrame.focusedRowIndex) && rowModel.isFocusable
                                SettingsUnifiedRowItem(rowModel, focused, themeAccentColor, fontDeltaSp) {
                                    if (rowModel.isFocusable) { updateFocus(idx); triggerRow(rowModel) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun findNextFocusableIndex(rows: List<SettingsRowModel>, cur: Int, dir: Int): Int {
    if (rows.isEmpty()) return 0
    var c = cur + dir
    while (c in rows.indices) { if (rows[c].isFocusable) return c; c += dir }
    return cur.coerceIn(0, rows.lastIndex)
}

@Composable
private fun SettingsUnifiedRowItem(row: SettingsRowModel, isFocused: Boolean, accentColor: Color, fontDelta: Int, onRowClick: () -> Unit) {
    when (row) {
        is SettingsRowModel.SectionHeader -> Box(Modifier.fillMaxWidth().padding(14.dp, 14.dp, 14.dp, 4.dp)) { Text(row.title, color = accentColor, fontSize = (13 + fontDelta).sp, fontWeight = FontWeight.Bold) }
        is SettingsRowModel.NavRow -> WhitePillFocusContainer(isFocused, onRowClick) { pCol, sCol ->
            Column(Modifier.weight(1f)) {
                Text(row.title, color = pCol, fontSize = (15 + fontDelta).sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!row.subtitle.isNullOrBlank()) Text(row.subtitle, color = sCol, fontSize = (12 + fontDelta).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        is SettingsRowModel.ValueRow -> WhitePillFocusContainer(isFocused, onRowClick) { pCol, sCol ->
            Column(Modifier.weight(1f)) {
                Text(row.title, color = pCol, fontSize = (15 + fontDelta).sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium, maxLines = 2)
                Text(row.valueText, color = if (isFocused) sCol else accentColor.copy(alpha = 0.9f), fontSize = (12 + fontDelta).sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
        is SettingsRowModel.ToggleRow -> WhitePillFocusContainer(isFocused, onRowClick) { pCol, sCol ->
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(row.title, color = pCol, fontSize = (15 + fontDelta).sp, fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium)
                if (!row.subtitle.isNullOrBlank()) Text(row.subtitle, color = sCol, fontSize = (12 + fontDelta).sp)
            }
            TiviMatePillSwitch(row.checked, isFocused, accentColor)
        }
        is SettingsRowModel.StatusOrOptionRow -> WhitePillFocusContainer(isFocused, onRowClick) { pCol, sCol ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (row.showCheckmarkCircle || row.isSelectedOption) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(if (isFocused) FocusedTextPrimary else accentColor.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Text("✓", color = if (isFocused) PureWhite else accentColor, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(row.title, color = pCol, fontSize = (15 + fontDelta).sp, fontWeight = if (isFocused || row.isSelectedOption) FontWeight.Bold else FontWeight.Medium)
                    if (!row.subtitle.isNullOrBlank()) Text(row.subtitle, color = sCol, fontSize = (12 + fontDelta).sp)
                }
            }
        }
    }
}

@Composable
private fun WhitePillFocusContainer(isFocused: Boolean, onClick: () -> Unit, content: @Composable RowScope.(Color, Color) -> Unit) {
    val bg by animateColorAsState(if (isFocused) PureWhite else Color.Transparent, tween(110), label = "WBg")
    val pCol by animateColorAsState(if (isFocused) FocusedTextPrimary else TextPrimary, tween(110), label = "WP")
    val sCol by animateColorAsState(if (isFocused) FocusedTextSecondary else TextSecondary, tween(110), label = "WS")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { content(pCol, sCol) }
}

@Composable
private fun TiviMatePillSwitch(checked: Boolean, isRowFocused: Boolean, accentColor: Color) {
    val off by animateDpAsState(if (checked) 16.dp else 0.dp, tween(140), label = "SwOff")
    Box(Modifier.width(38.dp).height(22.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.width(34.dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) accentColor.copy(alpha = 0.5f) else Color(0xFF475569)))
        Box(Modifier.offset(x = off).size(20.dp).clip(CircleShape).background(if (checked) accentColor else PureWhite))
    }
}

private fun buildRowsForPage(
    pageId: SettingsPageId, state: AetherSettingsState, onPopStack: () -> Unit,
    onAddPlaylistRequested: () -> Unit, onRefreshPlaylistsRequested: () -> Unit, onRefreshEpgRequested: () -> Unit, onClearEpgRequested: () -> Unit,
    onBufferPresetChanged: (String) -> Unit, onUserAgentChanged: (String) -> Unit, onTunneledPlaybackChanged: (Boolean) -> Unit, onAudioPassthroughChanged: (Boolean) -> Unit
): List<SettingsRowModel> = when (pageId) {
    SettingsPageId.ROOT -> listOf(
        SettingsRowModel.NavRow("r_gen", "Allgemein", "Startverhalten, User-Agent & Backup", SettingsPageId.GENERAL),
        SettingsRowModel.NavRow("r_pl", "Wiedergabelisten", "${state.activePlaylistName} (${state.activePlaylistChannelsCount} TV, ${state.activePlaylistMoviesCount} Filme)", SettingsPageId.PLAYLISTS),
        SettingsRowModel.NavRow("r_epg", "EPG", state.epgLastUpdatedTimestamp, SettingsPageId.EPG),
        SettingsRowModel.NavRow("r_app", "Optik", "${state.colorTheme} • Schrift: ${state.fontSizePreset}", SettingsPageId.APPEARANCE),
        SettingsRowModel.NavRow("r_pb", "Wiedergabe", "Puffer: ${state.bufferSizePreset} • AFR: ${state.autoFrameRateMode}", SettingsPageId.PLAYBACK),
        SettingsRowModel.NavRow("r_rem", "Fernbedienung", "Tastenbelegung & Spulverhalten", SettingsPageId.REMOTE),
        SettingsRowModel.NavRow("r_par", "Kindersicherung", if (state.parentalControlEnabled) "Aktiviert (PIN geschützt)" else "Aus", SettingsPageId.PARENTAL),
        SettingsRowModel.NavRow("r_misc", "Sonstiges", "Suche, Erinnerungen, Aufnahme & VOD", SettingsPageId.MISC),
        SettingsRowModel.NavRow("r_ab", "Über", "Aether Stream Engine L10-V6.0", SettingsPageId.ABOUT)
    )
    SettingsPageId.GENERAL -> listOf(
        SettingsRowModel.ToggleRow("g_boot", "Automatisch nach dem Booten starten", checked = state.autoStartOnBoot) { AetherSettingsStore.updateState { s -> s.copy(autoStartOnBoot = it) } },
        SettingsRowModel.ToggleRow("g_sleep", "Automatisch aus dem Sleep Mode starten", checked = state.autoWakeFromSleep) { AetherSettingsStore.updateState { s -> s.copy(autoWakeFromSleep = it) } },
        SettingsRowModel.ToggleRow("g_last", "Zuletzt angesehenen Sender beim Start anzeigen", checked = state.openLastChannelOnStart) { AetherSettingsStore.updateState { s -> s.copy(openLastChannelOnStart = it) } },
        SettingsRowModel.ToggleRow("g_pip", "Bild-in-Bild Modus beim Drücken von Home", checked = state.autoPipOnHome) { AetherSettingsStore.updateState { s -> s.copy(autoPipOnHome = it) } },
        SettingsRowModel.ToggleRow("g_back", "Doppelt bestätigen zum Verlassen", checked = state.confirmExitWithDoubleBack) { AetherSettingsStore.updateState { s -> s.copy(confirmExitWithDoubleBack = it) } },
        SettingsRowModel.ValueRow("g_ua", "User-Agent (für Stream & CDN)", state.userAgent, SettingsPageId.PICKER_USER_AGENT),
        SettingsRowModel.ValueRow("g_udp", "UDP Proxy (Adresse:Port)", state.udpProxy, SettingsPageId.PICKER_UDP_PROXY),
        SettingsRowModel.StatusOrOptionRow("g_bak", "Daten sichern", state.lastBackupStatus) { AetherSettingsStore.updateState { it.copy(lastBackupStatus = "✓ Lokales Backup erfolgreich gesichert") } },
        SettingsRowModel.StatusOrOptionRow("g_res", "Daten wiederherstellen", "Aus letztem Snapshot laden") { AetherSettingsStore.updateState { it.copy(lastBackupStatus = "✓ Einstellungen wiederhergestellt") } }
    )
    SettingsPageId.PLAYLISTS -> listOf(
        SettingsRowModel.StatusOrOptionRow("p_act", state.activePlaylistName, "TV: ${state.activePlaylistChannelsCount} • Filme: ${state.activePlaylistMoviesCount} • Serien: ${state.activePlaylistSeriesCount}", showCheckmarkCircle = true, onClick = onRefreshPlaylistsRequested),
        SettingsRowModel.StatusOrOptionRow("p_add", "Wiedergabeliste hinzufügen / ändern", "Xtream Codes Server-Login öffnen", onClick = onAddPlaylistRequested),
        SettingsRowModel.StatusOrOptionRow("p_ref", "Aktualisiere alle Wiedergabelisten (TV, Filme & Serien)", "Status: ${state.epgLastUpdatedTimestamp}", onClick = onRefreshPlaylistsRequested)
    )
    SettingsPageId.EPG -> listOf(
        SettingsRowModel.StatusOrOptionRow("e_src", "EPG Quellen", state.epgSourcesSummary, onClick = onRefreshEpgRequested),
        SettingsRowModel.ValueRow("e_days", "Vergangene Tage behalten", "${state.epgPastDaysToKeep} Tage", SettingsPageId.PICKER_EPG_PAST_DAYS),
        SettingsRowModel.ToggleRow("e_desc", "Programmbeschreibungen speichern", checked = state.epgStoreProgramDescriptions) { AetherSettingsStore.updateState { s -> s.copy(epgStoreProgramDescriptions = it) } },
        SettingsRowModel.SectionHeader("e_hdr", "Update-Optionen"),
        SettingsRowModel.ValueRow("e_int", "Aktualisierungsintervall (Stunden)", "${state.epgUpdateIntervalHours} Std.", SettingsPageId.PICKER_EPG_INTERVAL),
        SettingsRowModel.ToggleRow("e_st", "Aktualisieren beim Starten der App", checked = state.epgUpdateOnAppStart) { AetherSettingsStore.updateState { s -> s.copy(epgUpdateOnAppStart = it) } },
        SettingsRowModel.ToggleRow("e_pl", "Aktualisieren bei Playlisten-Änderung", checked = state.epgUpdateOnPlaylistChange) { AetherSettingsStore.updateState { s -> s.copy(epgUpdateOnPlaylistChange = it) } },
        SettingsRowModel.StatusOrOptionRow("e_now", "⚡ EPG & Katalog jetzt aktualisieren", "Drücke [OK] für sofortigen Server-Abruf (${state.epgLastUpdatedTimestamp})", onClick = onRefreshEpgRequested),
        SettingsRowModel.StatusOrOptionRow("e_clr", "EPG-Cache löschen", "Erzwingt kompletten Neuaufbau") { onClearEpgRequested() },
        SettingsRowModel.StatusOrOptionRow("e_stat", "Letzter Aktualisierungsstatus", "Kanäle: ${state.epgLastUpdatedChannelsCount} • Stand: ${state.epgLastUpdatedTimestamp}", onClick = onRefreshEpgRequested)
    )
    SettingsPageId.APPEARANCE -> listOf(
        SettingsRowModel.ValueRow("a_theme", "Farbthema (Live-Akzentfarbe)", state.colorTheme, SettingsPageId.PICKER_COLOR_THEME),
        SettingsRowModel.ValueRow("a_font", "Schriftgröße (Menü & EPG)", state.fontSizePreset, SettingsPageId.PICKER_FONT_SIZE),
        SettingsRowModel.ValueRow("a_trans", "Transparenz der Oberfläche (%)", "${state.uiTransparencyPercent} %", SettingsPageId.PICKER_UI_TRANSPARENCY),
        SettingsRowModel.ValueRow("a_lang", "Sprache", state.language, SettingsPageId.PICKER_LANGUAGE),
        SettingsRowModel.NavRow("a_epg", "EPG-Optik", "Zeitachsen-Intervall: ${state.epgTimelineSlotMinutes} Min.", SettingsPageId.APPEARANCE_EPG),
        SettingsRowModel.NavRow("a_pl", "Player-Optik", "HUD-Einblendzeit: ${state.playerHudTimeoutSeconds} Sek.", SettingsPageId.APPEARANCE_PLAYER),
        SettingsRowModel.NavRow("a_grp", "Gruppen-Optik", if (state.showChannelNumbersInGroups) "Kanalnummern eingeblendet" else "Kanalnummern ausgeblendet", SettingsPageId.APPEARANCE_GROUPS),
        SettingsRowModel.NavRow("a_log", "Logo-Priorität", state.logoPriority, SettingsPageId.APPEARANCE_LOGOS)
    )
    SettingsPageId.PLAYBACK -> listOf(
        SettingsRowModel.ValueRow("pb_buf", "Puffergröße", state.bufferSizePreset, SettingsPageId.PICKER_BUFFER_SIZE),
        SettingsRowModel.ValueRow("pb_adec", "Audio Decoder", state.audioDecoder, SettingsPageId.PICKER_AUDIO_DECODER),
        SettingsRowModel.ValueRow("pb_vdec", "Video Decoder", state.videoDecoder, SettingsPageId.PICKER_VIDEO_DECODER),
        SettingsRowModel.ValueRow("pb_afr", "Automatische Framerate-Anpassung (AFR)", state.autoFrameRateMode, SettingsPageId.PICKER_AFR),
        SettingsRowModel.ToggleRow("pb_sur", "Standardmäßig Surround-Audiospur bevorzugen", checked = state.defaultSurroundAudioTrack) { AetherSettingsStore.updateState { s -> s.copy(defaultSurroundAudioTrack = it) } },
        SettingsRowModel.ToggleRow("pb_pass", "Audio-Passthrough (HDMI Bitstream)", checked = state.audioPassthrough) { AetherSettingsStore.updateState { s -> s.copy(audioPassthrough = it) }; onAudioPassthroughChanged(it) },
        SettingsRowModel.ToggleRow("pb_tun", "Getunnelte Hardware-Wiedergabe", checked = state.tunneledPlayback) { AetherSettingsStore.updateState { s -> s.copy(tunneledPlayback = it) }; onTunneledPlaybackChanged(it) },
        SettingsRowModel.ValueRow("pb_aml", "Amlogic / FireTV Cube Fix", state.amlogicFix, SettingsPageId.PICKER_AMLOGIC_FIX),
        SettingsRowModel.ValueRow("pb_ext", "Externen Player nutzen", state.useExternalPlayer, SettingsPageId.PICKER_EXTERNAL_PLAYER)
    )
    SettingsPageId.REMOTE -> listOf(
        SettingsRowModel.NavRow("rm_epg", "EPG-Tasten", state.remoteEpgDpadMode, SettingsPageId.REMOTE_EPG),
        SettingsRowModel.NavRow("rm_pl", "Player-Tasten", state.remotePlayerOkAction, SettingsPageId.REMOTE_PLAYER),
        SettingsRowModel.SectionHeader("rm_hdr", "Spul- & Suchoptionen"),
        SettingsRowModel.ToggleRow("rm_rw", "RW / FF / Pause zum Spulen & Pausieren nutzen", checked = state.useRwFfPauseForSeeking) { AetherSettingsStore.updateState { s -> s.copy(useRwFfPauseForSeeking = it) } },
        SettingsRowModel.ToggleRow("rm_lr", "Links / Rechts zum Spulen in VOD nutzen", checked = state.useLeftRightForSeeking) { AetherSettingsStore.updateState { s -> s.copy(useLeftRightForSeeking = it) } }
    )
    SettingsPageId.PARENTAL -> listOf(
        SettingsRowModel.ToggleRow("pa_en", "Kindersicherung aktivieren", checked = state.parentalControlEnabled) { AetherSettingsStore.updateState { s -> s.copy(parentalControlEnabled = it) } },
        SettingsRowModel.ValueRow("pa_pin", "PIN-Code wechseln", "Aktuell: ${state.parentalPinCode}") { AetherSettingsStore.updateState { it.copy(parentalPinCode = if (state.parentalPinCode == "0000") "1234" else "0000") } },
        SettingsRowModel.ValueRow("pa_met", "PIN Eingabemethode", state.parentalPinInputMethod, SettingsPageId.PICKER_PIN_METHOD),
        SettingsRowModel.ValueRow("pa_to", "PIN Sperrzeitraum", state.parentalPinLockTimeout, SettingsPageId.PICKER_PIN_TIMEOUT)
    )
    SettingsPageId.MISC -> listOf(
        SettingsRowModel.NavRow("m_s", "Suchen", if (state.keepSearchHistory) "Suchverlauf aktiv" else "Kein Verlauf", SettingsPageId.MISC_SEARCH),
        SettingsRowModel.NavRow("m_r", "Erinnerungen", "Vorlauf: ${state.reminderLeadTimeMinutes} Min.", SettingsPageId.MISC_REMINDERS),
        SettingsRowModel.NavRow("m_rec", "Aufnahme", "Nachlauf: ${state.recordingPaddingMinutes} Min.", SettingsPageId.MISC_RECORDING),
        SettingsRowModel.NavRow("m_vod", "VOD (Filme & Serien)", if (state.vodAutoResumePlayback) "Auto-Fortsetzen aktiv" else "Immer von Anfang", SettingsPageId.MISC_VOD)
    )
    SettingsPageId.ABOUT -> listOf(
        SettingsRowModel.StatusOrOptionRow("ab_1", "Aether Stream Engine", "Version L10-V6.0 (Fire TV Cube Edition)") {},
        SettingsRowModel.StatusOrOptionRow("ab_2", "Native Media3 Pipeline", "AndroidX Media3 1.4.1 • PCM Audio-Sync ±5000ms • AFR") {},
        SettingsRowModel.ToggleRow("ab_3", "Automatisch auf Updates prüfen", checked = state.autoCheckForUpdates) { AetherSettingsStore.updateState { s -> s.copy(autoCheckForUpdates = it) } }
    )
    SettingsPageId.APPEARANCE_EPG -> listOf(
        SettingsRowModel.ValueRow("ae_1", "Zeitachsen-Intervall", "${state.epgTimelineSlotMinutes} Min.") { AetherSettingsStore.updateState { it.copy(epgTimelineSlotMinutes = if (state.epgTimelineSlotMinutes == 30) 60 else 30) } },
        SettingsRowModel.ToggleRow("ae_2", "Programminformationen im Detail-Panel anzeigen", checked = state.epgStoreProgramDescriptions) { AetherSettingsStore.updateState { s -> s.copy(epgStoreProgramDescriptions = it) } }
    )
    SettingsPageId.APPEARANCE_PLAYER -> listOf(
        SettingsRowModel.ValueRow("ap_1", "Anzeigedauer der Vollbild-Steuerleiste", "${state.playerHudTimeoutSeconds} Sekunden") {
            val next = when (state.playerHudTimeoutSeconds) { 3 -> 5; 5 -> 8; 8 -> 10; else -> 3 }
            AetherSettingsStore.updateState { it.copy(playerHudTimeoutSeconds = next) }
        }
    )
    SettingsPageId.APPEARANCE_GROUPS -> listOf(
        SettingsRowModel.ToggleRow("ag_1", "Kanalnummern in Kategorien anzeigen", checked = state.showChannelNumbersInGroups) { AetherSettingsStore.updateState { s -> s.copy(showChannelNumbersInGroups = it) } }
    )
    SettingsPageId.APPEARANCE_LOGOS -> listOf(
        SettingsRowModel.ValueRow("al_1", "Logo-Priorität", state.logoPriority) {
            AetherSettingsStore.updateState { it.copy(logoPriority = if (state.logoPriority == "Wiedergabeliste bevorzugen") "EPG-Logos bevorzugen" else "Wiedergabeliste bevorzugen") }
        }
    )
    SettingsPageId.REMOTE_EPG -> listOf(SettingsRowModel.ValueRow("re_1", "D-Pad im TV-Guide", state.remoteEpgDpadMode) {})
    SettingsPageId.REMOTE_PLAYER -> listOf(SettingsRowModel.ValueRow("rp_1", "OK-Taste im Vollbild", state.remotePlayerOkAction) {})
    SettingsPageId.MISC_SEARCH -> listOf(SettingsRowModel.ToggleRow("ms_1", "Suchverlauf speichern", checked = state.keepSearchHistory) { AetherSettingsStore.updateState { s -> s.copy(keepSearchHistory = it) } })
    SettingsPageId.MISC_REMINDERS -> listOf(SettingsRowModel.ValueRow("mr_1", "Vorlaufzeit", "${state.reminderLeadTimeMinutes} Min.") { AetherSettingsStore.updateState { it.copy(reminderLeadTimeMinutes = if (state.reminderLeadTimeMinutes == 2) 5 else 2) } })
    SettingsPageId.MISC_RECORDING -> listOf(SettingsRowModel.ValueRow("mc_1", "Nachlaufzeit bei Aufnahmen", "${state.recordingPaddingMinutes} Min.") { AetherSettingsStore.updateState { it.copy(recordingPaddingMinutes = if (state.recordingPaddingMinutes == 5) 10 else 5) } })
    SettingsPageId.MISC_VOD -> listOf(SettingsRowModel.ToggleRow("mv_1", "Filme & Serien automatisch fortsetzen", checked = state.vodAutoResumePlayback) { AetherSettingsStore.updateState { s -> s.copy(vodAutoResumePlayback = it) } })
    SettingsPageId.PICKER_USER_AGENT -> buildPickerRows(listOf("TiviMate/5.1.0 (Linux; Android 11)", "TiviMate/4.7.0 (FireTV Cube)", "VLC/3.0.20 LibVLC/3.0.20", "ExoPlayer/1.4.1", "Kodi/21.0 (Linux; Android 11)", "Lavf/60.3.100"), state.userAgent) { AetherSettingsStore.updateState { s -> s.copy(userAgent = it) }; onUserAgentChanged(it); onPopStack() }
    SettingsPageId.PICKER_UDP_PROXY -> buildPickerRows(listOf("Nicht festgelegt", "127.0.0.1:4022", "192.168.1.1:4000", "192.168.178.1:4022"), state.udpProxy) { AetherSettingsStore.updateState { s -> s.copy(udpProxy = it) }; onPopStack() }
    SettingsPageId.PICKER_EPG_PAST_DAYS -> buildPickerRows(listOf("1", "3", "7", "14"), state.epgPastDaysToKeep.toString()) { AetherSettingsStore.updateState { s -> s.copy(epgPastDaysToKeep = it.toIntOrNull() ?: 7) }; onPopStack() }
    SettingsPageId.PICKER_EPG_INTERVAL -> buildPickerRows(listOf("1", "2", "4", "6", "12", "24"), state.epgUpdateIntervalHours.toString()) { AetherSettingsStore.updateState { s -> s.copy(epgUpdateIntervalHours = it.toIntOrNull() ?: 2) }; onPopStack() }
    SettingsPageId.PICKER_LANGUAGE -> buildPickerRows(listOf("System", "Deutsch", "Polski", "English", "Türkçe"), state.language) { AetherSettingsStore.updateState { s -> s.copy(language = it) }; onPopStack() }
    SettingsPageId.PICKER_FONT_SIZE -> buildPickerRows(listOf("Klein", "Mittel", "Groß", "Sehr groß"), state.fontSizePreset) { AetherSettingsStore.updateState { s -> s.copy(fontSizePreset = it) }; onPopStack() }
    SettingsPageId.PICKER_COLOR_THEME -> buildPickerRows(listOf("Obsidian • Cyan", "Dunkel • Blau", "Schwarz • Smaragd", "Dunkel • Bernstein"), state.colorTheme) { AetherSettingsStore.updateState { s -> s.copy(colorTheme = it) }; onPopStack() }
    SettingsPageId.PICKER_UI_TRANSPARENCY -> buildPickerRows(listOf("0", "20", "35", "50", "65", "80"), state.uiTransparencyPercent.toString()) { AetherSettingsStore.updateState { s -> s.copy(uiTransparencyPercent = it.toIntOrNull() ?: 50) }; onPopStack() }
    SettingsPageId.PICKER_BUFFER_SIZE -> buildPickerRows(listOf("Keine", "Klein", "Mittel", "Groß", "Sehr groß"), state.bufferSizePreset) { AetherSettingsStore.updateState { s -> s.copy(bufferSizePreset = it) }; onBufferPresetChanged(it); onPopStack() }
    SettingsPageId.PICKER_AUDIO_DECODER -> buildPickerRows(listOf("Hardware", "Software"), state.audioDecoder) { AetherSettingsStore.updateState { s -> s.copy(audioDecoder = it) }; onPopStack() }
    SettingsPageId.PICKER_VIDEO_DECODER -> buildPickerRows(listOf("Hardware", "Software"), state.videoDecoder) { AetherSettingsStore.updateState { s -> s.copy(videoDecoder = it) }; onPopStack() }
    SettingsPageId.PICKER_AFR -> buildPickerRows(listOf("Aus", "Einschalten", "Einschalten (mit Umschalt-Verzögerung)"), state.autoFrameRateMode) { AetherSettingsStore.updateState { s -> s.copy(autoFrameRateMode = it) }; onPopStack() }
    SettingsPageId.PICKER_AMLOGIC_FIX -> buildPickerRows(listOf("Aus", "Ein"), state.amlogicFix) { AetherSettingsStore.updateState { s -> s.copy(amlogicFix = it) }; onPopStack() }
    SettingsPageId.PICKER_EXTERNAL_PLAYER -> buildPickerRows(listOf("Nein", "Ja", "Nur für VOD"), state.useExternalPlayer) { AetherSettingsStore.updateState { s -> s.copy(useExternalPlayer = it) }; onPopStack() }
    SettingsPageId.PICKER_PIN_METHOD -> buildPickerRows(listOf("Sammler", "Zifferntasten", "Virtuelle Tastatur"), state.parentalPinInputMethod) { AetherSettingsStore.updateState { s -> s.copy(parentalPinInputMethod = it) }; onPopStack() }
    SettingsPageId.PICKER_PIN_TIMEOUT -> buildPickerRows(listOf("Immer erforderlich", "15 Minuten", "30 Minuten", "1 Stunde", "Bis zum App-Neustart"), state.parentalPinLockTimeout) { AetherSettingsStore.updateState { s -> s.copy(parentalPinLockTimeout = it) }; onPopStack() }
}

private fun buildPickerRows(options: List<String>, selected: String, onSelect: (String) -> Unit): List<SettingsRowModel> =
    options.mapIndexed { idx, opt -> SettingsRowModel.StatusOrOptionRow("pk_$idx", opt, isSelectedOption = (opt == selected)) { onSelect(opt) } }
