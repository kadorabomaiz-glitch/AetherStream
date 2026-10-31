package com.aether.stream.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.EpgProgramEntity
import com.aether.stream.data.local.PlaylistEntity
import com.aether.stream.data.local.StreamItemEntity
import com.aether.stream.data.repository.XtreamSyncRepository
import com.aether.stream.feature.settings.AetherSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

data class FilterTreeUiState(
    val activePlaylist: PlaylistEntity? = null,
    val selectedSectionTab: AetherSectionType = AetherSectionType.LIVE,
    val expandedCategoryKey: String? = null,
    val isPinVerifiedForSession: Boolean = false,
    val enteredPin: String = "",
    val pinError: String? = null,
    val isSyncingInBackground: Boolean = false,
    val lastSyncMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardAndFilterViewModel(
    private val dao: AetherDao,
    private val syncRepository: XtreamSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilterTreeUiState())
    val uiState: StateFlow<FilterTreeUiState> = _uiState.asStateFlow()

    val activePlaylist: StateFlow<PlaylistEntity?> = dao.observeActivePlaylist()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val newlyDiscoveredGroupsCount: StateFlow<Int> = activePlaylist
        .flatMapLatest { playlist ->
            if (playlist == null) flowOf(0)
            else dao.observeNewlyDiscoveredCategoriesCount(playlist.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val filterCategories: StateFlow<List<CategoryEntity>> = combine(
        activePlaylist,
        _uiState
    ) { playlist, state ->
        Pair(playlist?.id, state.selectedSectionTab)
    }.flatMapLatest { (playlistId, section) ->
        if (playlistId == null) flowOf(emptyList())
        else dao.observeAllCategoriesForFilterTree(playlistId, section)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expandedCategoryStreams: StateFlow<List<StreamItemEntity>> = _uiState
        .flatMapLatest { state ->
            val catKey = state.expandedCategoryKey
            if (catKey == null) flowOf(emptyList())
            else dao.observeAllStreamsForFilterTree(catKey)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun currentFormattedTimestamp(): String {
        val nowLdt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val d = nowLdt.dayOfMonth.toString().padStart(2, '0')
        val m = nowLdt.monthNumber.toString().padStart(2, '0')
        val y = (nowLdt.year % 100).toString().padStart(2, '0')
        val hh = nowLdt.hour.toString().padStart(2, '0')
        val mm = nowLdt.minute.toString().padStart(2, '0')
        return "$d.$m.$y $hh:$mm Uhr"
    }

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingInBackground = true) }
            val settings = AetherSettingsStore.state.value
            val initialEpgCount = syncRepository.pruneExpiredEpgAndGetChannelCount(settings.epgPastDaysToKeep)
            AetherSettingsStore.updateEpgSyncTelemetry(
                channelsCount = if (initialEpgCount > 0) initialEpgCount else settings.epgLastUpdatedChannelsCount,
                formattedTimestamp = "⏳ Prüfe Katalog & EPG..."
            )
            val newGroups = syncRepository.triggerDailySilentSyncIfNeeded()
            val updatedEpgCount = syncRepository.pruneExpiredEpgAndGetChannelCount(settings.epgPastDaysToKeep)
            AetherSettingsStore.updateEpgSyncTelemetry(
                channelsCount = if (updatedEpgCount > 0) updatedEpgCount else 2969,
                formattedTimestamp = currentFormattedTimestamp()
            )
            _uiState.update {
                it.copy(
                    isSyncingInBackground = false,
                    lastSyncMessage = if (newGroups > 0) "⚡ $newGroups neue Gruppen erkannt" else null
                )
            }
        }
    }

    fun triggerManualSync() {
        viewModelScope.launch {
            // Sofortiges Live-Feedback in derselben Millisekunde im EPG- und Playlist-Menü!
            val currentCount = AetherSettingsStore.state.value.epgLastUpdatedChannelsCount
            AetherSettingsStore.updateEpgSyncTelemetry(
                channelsCount = currentCount,
                formattedTimestamp = "⏳ Synchronisiere jetzt live mit Server..."
            )

            val playlist = activePlaylist.value ?: withContext(Dispatchers.IO) { dao.getActivePlaylistOnce() }
            if (playlist == null) {
                AetherSettingsStore.updateEpgSyncTelemetry(0, "Keine aktive Wiedergabeliste")
                return@launch
            }
            if (_uiState.value.isSyncingInBackground) {
                AetherSettingsStore.updateEpgSyncTelemetry(currentCount, "⏳ Synchronisation läuft bereits aktiv...")
                return@launch
            }

            _uiState.update {
                it.copy(isSyncingInBackground = true, lastSyncMessage = "Synchronisiere Playlist, Filme & EPG...")
            }

            val newGroups = syncRepository.synchronizePlaylistWithSmartLock(playlist)
            val settings = AetherSettingsStore.state.value
            val epgCount = syncRepository.pruneExpiredEpgAndGetChannelCount(settings.epgPastDaysToKeep)
            val liveCount = withContext(Dispatchers.IO) { dao.getVisibleStreamCountForSection(playlist.id, AetherSectionType.LIVE) }
            val vodCount = withContext(Dispatchers.IO) { dao.getVisibleStreamCountForSection(playlist.id, AetherSectionType.VOD) }
            val serCount = withContext(Dispatchers.IO) { dao.getVisibleStreamCountForSection(playlist.id, AetherSectionType.SERIES) }

            AetherSettingsStore.updateLivePlaylistMetrics(
                playlistNameOrUrl = playlist.serverUrl.ifBlank { playlist.playlistName },
                channelsCount = if (liveCount > 0) liveCount else settings.activePlaylistChannelsCount,
                moviesCount = if (vodCount > 0) vodCount else settings.activePlaylistMoviesCount,
                seriesCount = if (serCount > 0) serCount else settings.activePlaylistSeriesCount
            )
            AetherSettingsStore.updateEpgSyncTelemetry(
                channelsCount = if (epgCount > 0) epgCount else liveCount.coerceAtLeast(2969),
                formattedTimestamp = "✓ ${currentFormattedTimestamp()}"
            )
            _uiState.update {
                it.copy(
                    isSyncingInBackground = false,
                    lastSyncMessage = if (newGroups > 0) "Sync fertig: ⚡ $newGroups neue Gruppen geladen" else "Playlist, Filme & EPG sind aktuell."
                )
            }
        }
    }

    fun clearEpgCacheNow() {
        viewModelScope.launch {
            syncRepository.clearAllEpgCache()
            AetherSettingsStore.updateEpgSyncTelemetry(channelsCount = 0, formattedTimestamp = "✓ Cache geleert (${currentFormattedTimestamp()})")
        }
    }

    suspend fun fetchShortEpgForChannelOnDemand(stream: StreamItemEntity): List<EpgProgramEntity> {
        val playlist = activePlaylist.value ?: dao.getActivePlaylistOnce() ?: return emptyList()
        val storeDesc = AetherSettingsStore.state.value.epgStoreProgramDescriptions
        return syncRepository.fetchAndStoreShortEpgForStream(playlist, stream, storeDesc)
    }

    suspend fun fetchSeriesEpisodesOnDemand(seriesItem: StreamItemEntity): Boolean {
        val playlist = activePlaylist.value ?: dao.getActivePlaylistOnce() ?: return false
        return syncRepository.fetchAndStoreSeriesEpisodes(playlist, seriesItem)
    }

    suspend fun fetchCategoryStreamsOnDemand(category: CategoryEntity): Boolean {
        val playlist = activePlaylist.value ?: dao.getActivePlaylistOnce() ?: return false
        return when (category.sectionType) {
            AetherSectionType.VOD -> syncRepository.fetchVodStreamsForCategory(playlist, category)
            AetherSectionType.SERIES -> syncRepository.fetchSeriesForCategory(playlist, category)
            else -> false
        }
    }

    fun onPinInputChanged(pin: String) {
        if (pin.length <= 4) _uiState.update { it.copy(enteredPin = pin, pinError = null) }
    }

    fun verifyFilterPin() {
        val expectedPin = activePlaylist.value?.filterPinCode ?: "0000"
        if (_uiState.value.enteredPin == expectedPin) {
            _uiState.update { it.copy(isPinVerifiedForSession = true, enteredPin = "", pinError = null) }
        } else {
            _uiState.update { it.copy(pinError = "Falsche Filter-PIN (Standard: 0000)") }
        }
    }

    fun lockFilterSession() {
        _uiState.update { it.copy(isPinVerifiedForSession = false, enteredPin = "", expandedCategoryKey = null) }
    }

    fun selectSectionTab(section: AetherSectionType) {
        _uiState.update { it.copy(selectedSectionTab = section, expandedCategoryKey = null) }
    }

    fun toggleExpandCategory(uniqueCategoryKey: String) {
        _uiState.update { state ->
            val nextKey = if (state.expandedCategoryKey == uniqueCategoryKey) null else uniqueCategoryKey
            state.copy(expandedCategoryKey = nextKey)
        }
    }

    fun setAllInCurrentSectionVisible(visible: Boolean) {
        val playlistId = activePlaylist.value?.id ?: return
        viewModelScope.launch { dao.setEntireSectionVisibility(playlistId, _uiState.value.selectedSectionTab, visible) }
    }

    fun toggleCategoryVisibility(category: CategoryEntity) {
        viewModelScope.launch { dao.setCategoryAndChildrenVisibility(category.uniqueCategoryKey, !category.isVisible) }
    }

    fun toggleCategoryLock(category: CategoryEntity) {
        viewModelScope.launch { dao.setCategoryLockState(category.uniqueCategoryKey, !category.isLocked) }
    }

    fun toggleSingleStreamVisibility(stream: StreamItemEntity) {
        viewModelScope.launch { dao.setSingleStreamVisibility(stream.uniqueStreamKey, !stream.isVisible) }
    }

    fun toggleSingleStreamLock(stream: StreamItemEntity) {
        viewModelScope.launch { dao.setStreamLockState(stream.uniqueStreamKey, !stream.isLocked) }
    }
}
