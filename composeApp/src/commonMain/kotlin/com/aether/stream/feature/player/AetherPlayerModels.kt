package com.aether.stream.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aether.stream.core.navigation.StreamBufferPreset
import kotlinx.coroutines.flow.StateFlow

data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val languageLabel: String,
    val codecLabel: String,
    val isSelected: Boolean,
    val trackId: String = "${groupIndex}_${trackIndex}_${languageLabel}_${codecLabel}"
)

data class StreamTechMetadata(
    val resolutionLabel: String = "LIVE STREAM",
    val fpsLabel: String = "50 FPS",
    val videoCodec: String = "HEVC",
    val audioCodec: String = "Dolby Audio",
    val isBuffering: Boolean = false,
    val reconnectAttempt: Int = 0,
    val detectedFrameRate: Float = 50.0f
)

interface AetherVideoPlayerEngine {
    val techMetadata: StateFlow<StreamTechMetadata>
    val audioTracks: StateFlow<List<TrackOption>>
    val subtitleTracks: StateFlow<List<TrackOption>>

    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val isPlaybackEnded: StateFlow<Boolean>

    fun playStream(url: String, startPositionMs: Long = 0L)
    fun applyBufferPreset(preset: StreamBufferPreset)
    fun selectAudioTrack(option: TrackOption)
    fun selectSubtitleTrack(option: TrackOption?)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun seekRelativeMs(offsetMs: Long)
    fun release()

    // ==========================================
    // MODULE 1, 3 & 4: PREDICTIVE ZAP, TRACK MEMORY & AUDIO DELAY
    // (Default-Implementierungen sichern 100% iOS/KMP Build-Kompatibilität)
    // ==========================================
    fun prebufferAdjacentStreams(prevUrl: String?, nextUrl: String?) {}
    fun applySavedStreamTrackMemory(preferredAudioId: String?, preferredSubtitleId: String?, audioDelayMs: Int = 0) {}
    fun setAudioDelayOffsetMs(delayMs: Int) {}
}

@Composable
expect fun AetherVideoSurface(
    engine: AetherVideoPlayerEngine,
    modifier: Modifier = Modifier
)
