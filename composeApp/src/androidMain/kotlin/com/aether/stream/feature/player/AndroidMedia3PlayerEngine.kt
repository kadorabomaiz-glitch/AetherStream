package com.aether.stream.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aether.stream.core.navigation.StreamBufferPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class AndroidMedia3PlayerEngine(
    private val context: Context
) : AetherVideoPlayerEngine {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressTickerJob: Job? = null

    private val _techMetadata = MutableStateFlow(StreamTechMetadata())
    override val techMetadata: StateFlow<StreamTechMetadata> = _techMetadata.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    // ==========================================
    // PHASE 2: REAKTIVE VOD & SERIEN STATES
    // ==========================================
    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPlaybackEnded = MutableStateFlow(false)
    override val isPlaybackEnded: StateFlow<Boolean> = _isPlaybackEnded.asStateFlow()

    private var currentStreamUrl: String? = null
    private var currentPreset: StreamBufferPreset = StreamBufferPreset.BALANCED
    private var reconnectCount = 0

    // 1. playerListener MUSS strikt vor buildExoPlayer deklariert sein (behebt die NullPointerException!)
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val buffering = playbackState == Player.STATE_BUFFERING
            _techMetadata.update { it.copy(isBuffering = buffering) }

            if (playbackState == Player.STATE_READY) {
                reconnectCount = 0
                _techMetadata.update { it.copy(reconnectAttempt = 0) }
                updatePlaybackDurations()
            }

            val ended = playbackState == Player.STATE_ENDED
            _isPlaybackEnded.value = ended
            if (ended) {
                stopProgressTicker()
                _isPlaying.value = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                _isPlaybackEnded.value = false
                startProgressTicker()
            } else {
                stopProgressTicker()
                updatePlaybackDurations()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            updatePlaybackDurations()
        }

        override fun onTracksChanged(tracks: Tracks) {
            extractTracksAndTechSpecs(tracks)
            updatePlaybackDurations()
        }

        override fun onPlayerError(error: PlaybackException) {
            val url = currentStreamUrl ?: return
            if (reconnectCount < 5) {
                reconnectCount++
                _techMetadata.update { it.copy(reconnectAttempt = reconnectCount, isBuffering = true) }
                scope.launch {
                    delay(650L * reconnectCount)
                    exoPlayer.setMediaItem(MediaItem.fromUri(url))
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
        }
    }

    internal var exoPlayer: ExoPlayer = buildExoPlayer(currentPreset)
        private set

    private fun buildExoPlayer(preset: StreamBufferPreset): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                preset.minBufferMs,
                preset.maxBufferMs,
                preset.playbackStartBufferMs,
                preset.playbackStartBufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addListener(playerListener)
            }
    }

    override fun playStream(url: String, startPositionMs: Long) {
        if (currentStreamUrl == url && exoPlayer.isPlaying && startPositionMs == 0L) return
        currentStreamUrl = url
        reconnectCount = 0
        _isPlaybackEnded.value = false
        _currentPositionMs.value = startPositionMs

        exoPlayer.setMediaItem(MediaItem.fromUri(url), startPositionMs)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun seekRelativeMs(offsetMs: Long) {
        val duration = exoPlayer.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val target = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
        _currentPositionMs.value = target
    }

    override fun seekTo(positionMs: Long) {
        val duration = exoPlayer.duration
        val target = if (duration > 0L && duration != C.TIME_UNSET) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        _currentPositionMs.value = target
    }

    private fun updatePlaybackDurations() {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        _currentPositionMs.value = pos
        _durationMs.value = dur
    }

    private fun startProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = scope.launch {
            while (isActive && exoPlayer.isPlaying) {
                updatePlaybackDurations()
                delay(500L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressTickerJob?.cancel()
        progressTickerJob = null
    }

    override fun applyBufferPreset(preset: StreamBufferPreset) {
        if (currentPreset == preset) return
        currentPreset = preset
        val activeUrl = currentStreamUrl
        val currentPos = exoPlayer.currentPosition

        stopProgressTicker()
        exoPlayer.release()
        exoPlayer = buildExoPlayer(preset)
        if (activeUrl != null) {
            playStream(activeUrl, currentPos)
        }
    }

    override fun selectAudioTrack(option: TrackOption) {
        val groups = exoPlayer.currentTracks.groups
        if (option.groupIndex in groups.indices) {
            val group = groups[option.groupIndex].mediaTrackGroup
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
                .build()
        }
    }

    override fun selectSubtitleTrack(option: TrackOption?) {
        if (option == null) {
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        val groups = exoPlayer.currentTracks.groups
        if (option.groupIndex in groups.indices) {
            val group = groups[option.groupIndex].mediaTrackGroup
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
                .build()
        }
    }

    private fun extractTracksAndTechSpecs(tracks: Tracks) {
        val audioList = mutableListOf<TrackOption>()
        val subtitleList = mutableListOf<TrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    if (group.isSelected && group.length > 0) {
                        val format = group.getTrackFormat(0)
                        val res = when {
                            format.height >= 2160 -> "4K UHD"
                            format.height >= 1080 -> "1080p FHD"
                            format.height >= 720 -> "720p HD"
                            format.height > 0 -> "${format.height}p"
                            else -> "LIVE HD"
                        }
                        val fps = if (format.frameRate > 0f) "${format.frameRate.roundToInt()} FPS" else "50 FPS"
                        val vCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
                        _techMetadata.update {
                            it.copy(resolutionLabel = res, fpsLabel = fps, videoCodec = vCodec)
                        }
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase() ?: "SPUR ${audioList.size + 1}"
                        val label = format.label ?: lang
                        val aCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"
                        val selected = group.isTrackSelected(trackIdx)
                        if (selected) {
                            _techMetadata.update { it.copy(audioCodec = aCodec) }
                        }
                        audioList.add(
                            TrackOption(
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                languageLabel = label,
                                codecLabel = aCodec,
                                isSelected = selected
                            )
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase() ?: "UNTERTITEL ${subtitleList.size + 1}"
                        subtitleList.add(
                            TrackOption(
                                groupIndex = groupIdx,
                                trackIndex = trackIdx,
                                languageLabel = format.label ?: lang,
                                codecLabel = "SUB",
                                isSelected = group.isTrackSelected(trackIdx)
                            )
                        )
                    }
                }
            }
        }
        _audioTracks.value = audioList
        _subtitleTracks.value = subtitleList
    }

    override fun pause() {
        stopProgressTicker()
        exoPlayer.pause()
    }

    override fun resume() {
        exoPlayer.play()
    }

    override fun release() {
        stopProgressTicker()
        exoPlayer.release()
    }
}

@OptIn(UnstableApi::class)
@Composable
actual fun AetherVideoSurface(
    engine: AetherVideoPlayerEngine,
    modifier: Modifier
) {
    val androidEngine = engine as? AndroidMedia3PlayerEngine ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = androidEngine.exoPlayer
            }
        },
        update = { view ->
            if (view.player !== androidEngine.exoPlayer) {
                view.player = androidEngine.exoPlayer
            }
        }
    )
}
