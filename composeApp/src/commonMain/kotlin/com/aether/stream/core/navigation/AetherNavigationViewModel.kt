package com.aether.stream.core.navigation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed class AetherScreen {
    data object GatekeeperLock : AetherScreen()
    data object PlaylistSetup : AetherScreen()
    data object Dashboard : AetherScreen()
    data object LiveTv : AetherScreen()
    data object VodMovies : AetherScreen()
    data object Series : AetherScreen()
    data object Settings : AetherScreen()
}

enum class LiveTvLayoutMode {
    IBO_COCKPIT_3_COLUMN,
    TIVIMATE_FULLSCREEN_HUD
}

enum class StreamBufferPreset(
    val title: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackStartBufferMs: Int
) {
    TURBO_ZAPPING("Turbo-Zapping (0,5 Sek.)", 500, 3000, 400),
    BALANCED("Perfekte Balance (2,5 Sek.)", 2500, 8000, 1500),
    SPORT_STADIUM("Sport & Stadion-Schutz (6,0 Sek.)", 6000, 18000, 3500),
    ULTRA_SHIELD("Maximaler Puffer (10,0 Sek.)", 10000, 30000, 5000)
}

data class AetherAppUiState(
    val currentScreen: AetherScreen = AetherScreen.GatekeeperLock,
    val screenHistory: List<AetherScreen> = emptyList(),
    val isDeviceUnlocked: Boolean = false,
    val hasActivePlaylist: Boolean = false,
    val liveTvLayoutMode: LiveTvLayoutMode = LiveTvLayoutMode.IBO_COCKPIT_3_COLUMN,
    val bufferPreset: StreamBufferPreset = StreamBufferPreset.BALANCED,
    val isAdminModalVisible: Boolean = false,
    val isFilterDropDownVisible: Boolean = false,
    val newlyDetectedGroupsCount: Int = 0
)

class AetherNavigationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AetherAppUiState())
    val uiState: StateFlow<AetherAppUiState> = _uiState.asStateFlow()

    fun initializeStartupState(isUnlocked: Boolean, hasPlaylist: Boolean, newGroupsCount: Int) {
        val startScreen = when {
            !isUnlocked -> AetherScreen.GatekeeperLock
            !hasPlaylist -> AetherScreen.PlaylistSetup
            else -> AetherScreen.Dashboard
        }
        _uiState.update {
            it.copy(
                isDeviceUnlocked = isUnlocked,
                hasActivePlaylist = hasPlaylist,
                currentScreen = startScreen,
                newlyDetectedGroupsCount = newGroupsCount
            )
        }
    }

    fun navigateTo(target: AetherScreen) {
        _uiState.update { state ->
            if (state.currentScreen == target) return@update state
            state.copy(
                screenHistory = state.screenHistory + state.currentScreen,
                currentScreen = target
            )
        }
    }

    fun navigateBack(): Boolean {
        val currentState = _uiState.value

        if (currentState.isAdminModalVisible) {
            dismissSecretAdminPanel()
            return true
        }
        if (currentState.isFilterDropDownVisible) {
            setFilterDropDownVisible(false)
            return true
        }

        if (currentState.screenHistory.isEmpty()) {
            return false
        }

        val previousScreen = currentState.screenHistory.last()
        _uiState.update { state ->
            state.copy(
                currentScreen = previousScreen,
                screenHistory = state.screenHistory.dropLast(1)
            )
        }
        return true
    }

    fun toggleLiveTvLayoutMode() {
        _uiState.update { state ->
            val nextMode = when (state.liveTvLayoutMode) {
                LiveTvLayoutMode.IBO_COCKPIT_3_COLUMN -> LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD
                LiveTvLayoutMode.TIVIMATE_FULLSCREEN_HUD -> LiveTvLayoutMode.IBO_COCKPIT_3_COLUMN
            }
            state.copy(liveTvLayoutMode = nextMode)
        }
    }

    fun updateBufferPreset(preset: StreamBufferPreset) {
        _uiState.update { it.copy(bufferPreset = preset) }
    }

    fun openSecretAdminPanel() {
        _uiState.update { it.copy(isAdminModalVisible = true) }
    }

    fun dismissSecretAdminPanel() {
        _uiState.update { it.copy(isAdminModalVisible = false) }
    }

    fun setFilterDropDownVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                isFilterDropDownVisible = visible,
                newlyDetectedGroupsCount = if (visible) 0 else it.newlyDetectedGroupsCount
            )
        }
    }
}