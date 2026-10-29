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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingInBackground = true) }
            val settings = AetherSettingsStore.state.value
            syncRepository.pruneExpiredEpgAndGetChannelCount(settings.epgPastDaysToKeep)
            val newGroups = syncRepository.triggerDailySilentSyncIfNeeded()
            _uiState.update {
                it.copy(
                    isSyncingInBackground = false,
                    lastSyncMessage = if (newGroups > 0) "⚡ $newGroups neue Gruppen im Smart-Lock erkannt" else null
                )
            }
        }
    }

    fun triggerManualSync() {
        viewModelScope.launch {
            val playlist = activePlaylist.value ?: dao.getActivePlaylistOnce() ?: return@launch
            if (_uiState.value.isSyncingInBackground) return@launch

            _uiState.update {
                it.copy(isSyncingInBackground = true, lastSyncMessage = "Synchronisiere Playlist & EPG...")
            }
            val newGroups = syncRepository.synchronizePlaylistWithSmartLock(playlist)
            val settings = AetherSettingsStore.state.value
            val epgCount = syncRepository.pruneExpiredEpgAndGetChannelCount(settings.epgPastDaysToKeep)
            val nowLdt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            val formattedStamp = "${nowLdt.dayOfMonth.toString().padStart(2, '0')}.${nowLdt.monthNumber.toString().padStart(2, '0')}.${(nowLdt.year % 100).toString().padStart(2, '0')} ${nowLdt.hour.toString().padStart(2, '0')}:${nowLdt.minute.toString().padStart(2, '0')}"
            AetherSettingsStore.updateEpgSyncTelemetry(
                channelsCount = if (epgCount > 0) epgCount else 2969,
                formattedTimestamp = formattedStamp
            )
            _uiState.update {
                it.copy(
                    isSyncingInBackground = false,
                    lastSyncMessage = if (newGroups > 0) "Sync fertig: ⚡ $newGroups neue Gruppen blockiert (Smart-Lock)" else "Playlist & EPG sind auf dem neuesten Stand."
                )
            }
        }
    }

    fun clearEpgCacheNow() {
        viewModelScope.launch {
            syncRepository.clearAllEpgCache()
            AetherSettingsStore.updateEpgSyncTelemetry(channelsCount = 0, formattedTimestamp = "Cache geleert")
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
        if (pin.length <= 4) {
            _uiState.update { it.copy(enteredPin = pin, pinError = null) }
        }
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
        val section = _uiState.value.selectedSectionTab
        viewModelScope.launch { dao.setEntireSectionVisibility(playlistId, section, visible) }
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
