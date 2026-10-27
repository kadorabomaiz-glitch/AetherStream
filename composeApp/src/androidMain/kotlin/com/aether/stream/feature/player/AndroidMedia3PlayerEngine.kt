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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class AndroidMedia3PlayerEngine(
    private val context: Context
) : AetherVideoPlayerEngine {

    companion object {
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"

        init {
            // L10 TiviMate SSL-Trust-Engine: Erlaubt das Abspielen von VOD- und Storage-Servern
            // mit selbstsignierten oder IP-gebundenen SSL-Zertifikaten (verhindert SSLHandshakeException!)
            runCatching {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, trustAllCerts, SecureRandom())
                }
                HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressTickerJob: Job? = null

    private val _techMetadata = MutableStateFlow(StreamTechMetadata())
    override val techMetadata: StateFlow<StreamTechMetadata> = _techMetadata.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

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
            val baseUrl = currentStreamUrl ?: return
            if (reconnectCount < 5) {
                reconnectCount++
                val lastKnownPos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                _techMetadata.update { it.copy(reconnectAttempt = reconnectCount, isBuffering = true) }

                val candidateUrl = buildSmartFallbackUrl(baseUrl, reconnectCount)

                scope.launch {
                    delay(400L * reconnectCount)
                    runCatching {
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        exoPlayer.setMediaItem(MediaItem.fromUri(candidateUrl), lastKnownPos)
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
            }
        }
    }

    internal var exoPlayer: ExoPlayer = buildExoPlayer(currentPreset)
        private set

    /**
     * L10 Smart-Container Rotation:
     * - Live TV bleibt unangetastet.
     * - Bei VOD/Filmen wird bei Fehlern automatisch zwischen .mp4, .mkv, .avi und .ts rotiert.
     * - Bei Versuch 5 wird für 24/7-Sender in VOD die /live/...ts Route getestet.
     */
    private fun buildSmartFallbackUrl(originalUrl: String, attempt: Int): String {
        val isVodOrSeries = originalUrl.contains("/movie/") || originalUrl.contains("/series/")
        if (!isVodOrSeries || attempt <= 1) return originalUrl

        val lastDotIndex = originalUrl.lastIndexOf('.')
        val lastSlashIndex = originalUrl.lastIndexOf('/')
        if (lastDotIndex <= lastSlashIndex) return originalUrl

        val currentExt = originalUrl.substring(lastDotIndex + 1).lowercase().trim()
        val urlWithoutExt = originalUrl.substring(0, lastDotIndex)

        // Falls es sich um einen 24/7-Live-Kanal in VOD handelt
        if (attempt >= 5 && originalUrl.contains("/movie/")) {
            return originalUrl.replace("/movie/", "/live/").substringBeforeLast('.') + ".ts"
        }

        val fallbacks = listOf("mp4", "mkv", "avi", "ts").filter { it != currentExt }
        if (fallbacks.isEmpty()) return originalUrl

        val targetExt = fallbacks[(attempt - 2).coerceAtLeast(0) % fallbacks.size]
        return "$urlWithoutExt.$targetExt"
    }

    private fun buildExoPlayer(preset: StreamBufferPreset): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(IPTV_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setKeepPostFor302Redirects(true)

        // Bewährte Standard MediaSourceFactory für universelle VBR/CBR-Wiedergabe (Live, Serie, VOD)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

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
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addListener(playerListener)
            }
    }

    override fun playStream(url: String, startPositionMs: Long) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        currentStreamUrl = cleanUrl
        reconnectCount = 0
        _isPlaybackEnded.value = false
        _currentPositionMs.value = startPositionMs

        runCatching {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(MediaItem.fromUri(cleanUrl), startPositionMs)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override fun seekRelativeMs(offsetMs: Long) {
        runCatching {
            val duration = exoPlayer.duration
            if (duration <= 0L || duration == C.TIME_UNSET) return
            val target = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, duration)
            exoPlayer.seekTo(target)
            _currentPositionMs.value = target
        }
    }

    override fun seekTo(positionMs: Long) {
        runCatching {
            val duration = exoPlayer.duration
            val target = if (duration > 0L && duration != C.TIME_UNSET) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            exoPlayer.seekTo(target)
            _currentPositionMs.value = target
        }
    }

    private fun updatePlaybackDurations() {
        runCatching {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
            if (_currentPositionMs.value != pos) _currentPositionMs.value = pos
            if (_durationMs.value != dur) _durationMs.value = dur
        }
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
        val currentPos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)

        stopProgressTicker()
        runCatching { exoPlayer.release() }
        exoPlayer = buildExoPlayer(preset)
        if (activeUrl != null) {
            playStream(activeUrl, currentPos)
        }
    }

    override fun selectAudioTrack(option: TrackOption) {
        runCatching {
            val groups = exoPlayer.currentTracks.groups
            if (option.groupIndex in groups.indices) {
                val group = groups[option.groupIndex].mediaTrackGroup
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
                    .build()
            }
        }
    }

    override fun selectSubtitleTrack(option: TrackOption?) {
        runCatching {
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
                            else -> "HD STREAM"
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
        runCatching { exoPlayer.pause() }
    }

    override fun resume() {
        runCatching { exoPlayer.play() }
    }

    override fun release() {
        stopProgressTicker()
        runCatching { exoPlayer.release() }
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
