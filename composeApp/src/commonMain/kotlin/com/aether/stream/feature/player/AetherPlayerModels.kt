package com.aether.stream.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.aether.stream.core.navigation.StreamBufferPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VideoAspectMode(val displayLabel: String) {
    FIT("Normal (Original)"),
    FILL("Vollbild (Strecken)"),
    ZOOM("16:9 Kino-Zoom"),
    FIXED_WIDTH("Breite anpassen"),
    FIXED_HEIGHT("Höhe anpassen")
}

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
    val detectedFrameRate: Float = 50.0f,
    val activeAudioDelayMs: Int = 0
)

interface AetherVideoPlayerEngine {
    val techMetadata: StateFlow<StreamTechMetadata>
    val audioTracks: StateFlow<List<TrackOption>>
    val subtitleTracks: StateFlow<List<TrackOption>>
    val videoTracks: StateFlow<List<TrackOption>>
        get() = MutableStateFlow(emptyList())

    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val isPlaybackEnded: StateFlow<Boolean>

    val videoAspectMode: StateFlow<VideoAspectMode>
        get() = MutableStateFlow(VideoAspectMode.FIT)

    val audioDelayOffsetMs: StateFlow<Int>
        get() = MutableStateFlow(0)

    fun playStream(url: String, startPositionMs: Long = 0L)
    fun applyBufferPreset(preset: StreamBufferPreset)
    fun selectAudioTrack(option: TrackOption)
    fun selectSubtitleTrack(option: TrackOption?)
    fun selectVideoTrack(option: TrackOption?) {}
    fun cycleVideoAspectMode(): VideoAspectMode = VideoAspectMode.FIT
    fun setVideoAspectMode(mode: VideoAspectMode) {}
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun seekRelativeMs(offsetMs: Long)
    fun release()

    fun prebufferAdjacentStreams(prevUrl: String?, nextUrl: String?) {}
    fun applySavedStreamTrackMemory(preferredAudioId: String?, preferredSubtitleId: String?, audioDelayMs: Int = 0) {}
    fun setAudioDelayOffsetMs(delayMs: Int) {}
}

@Composable
expect fun AetherVideoSurface(
    engine: AetherVideoPlayerEngine,
    modifier: Modifier = Modifier
)
