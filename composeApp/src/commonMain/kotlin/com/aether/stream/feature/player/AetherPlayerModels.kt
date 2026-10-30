package com.aether.stream.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.SurfaceView
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
import com.aether.stream.feature.settings.AetherSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class PrebufferedStreamSlot(
    val originalUrl: String,
    val resolvedEdgeUrl: String,
    val headerChunkBytes: ByteArray,
    val timestampMs: Long
)

@OptIn(UnstableApi::class)
class AndroidMedia3PlayerEngine(
    private val context: Context
) : AetherVideoPlayerEngine {

    companion object {
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"
        private const val PREBUFFER_BYTES_LIMIT = 200 * 1024 // 200 KB Ring-Buffer Slot
        private const val MAX_RING_BUFFER_SLOTS = 4

        init {
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressTickerJob: Job? = null
    private var predictiveZapJob: Job? = null
    private var afrSwitchJob: Job? = null
    private val prebufferSemaphore = Semaphore(1)
    private val predictiveRingBuffer = LinkedHashMap<String, PrebufferedStreamSlot>()

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

    // Module 3: Active Stream Track & Audio-Delay Auto-Memory
    private var pendingPreferredAudioId: String? = null
    private var pendingPreferredSubtitleId: String? = null
    private var activeAudioDelayOffsetMs: Int = 0
    private var lastAppliedAfrHz: Float = 0f
    internal var activeSurfaceViewRef: SurfaceView? = null

    // STRICT INVARIANT: playerListener MUST be initialized prior to buildExoPlayer(currentPreset)
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
                    delay(300L * reconnectCount)
                    runCatching {
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

    private fun buildSmartFallbackUrl(originalUrl: String, attempt: Int): String {
        val isVodOrSeries = originalUrl.contains("/movie/") || originalUrl.contains("/series/")
        if (!isVodOrSeries || attempt <= 1) return originalUrl

        val lastDotIndex = originalUrl.lastIndexOf('.')
        val lastSlashIndex = originalUrl.lastIndexOf('/')
        if (lastDotIndex <= lastSlashIndex) return originalUrl

        val currentExt = originalUrl.substring(lastDotIndex + 1).lowercase().trim()
        val urlWithoutExt = originalUrl.substring(0, lastDotIndex)

        if (attempt >= 5 && originalUrl.contains("/movie/")) {
            return originalUrl.replace("/movie/", "/live/").substringBeforeLast('.') + ".ts"
        }

        val fallbacks = listOf("mp4", "mkv", "avi", "ts").filter { it != currentExt }
        if (fallbacks.isEmpty()) return originalUrl

        val targetExt = fallbacks[(attempt - 2).coerceAtLeast(0) % fallbacks.size]
        return "$urlWithoutExt.$targetExt"
    }

    private fun buildExoPlayer(preset: StreamBufferPreset): ExoPlayer {
        val customUa = AetherSettingsStore.state.value.userAgent.takeIf { it.isNotBlank() } ?: IPTV_USER_AGENT
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(customUa)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(20_000)
            .setKeepPostFor302Redirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        // Sub-Second Instant-Zap LoadControl (<100ms First-Frame Start when prebuffered)
        val fastStartBufferMs = 350
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                preset.minBufferMs,
                preset.maxBufferMs,
                fastStartBufferMs,
                1200
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
                if (AetherSettingsStore.state.value.tunneledPlayback) {
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setTunnelingEnabled(true)
                        .build()
                }
            }
    }

    override fun playStream(url: String, startPositionMs: Long) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        currentStreamUrl = cleanUrl
        reconnectCount = 0
        _isPlaybackEnded.value = false
        _currentPositionMs.value = startPositionMs

        // Module 1: Check if predictive ring-buffer already resolved the 302 Edge CDN URL & warmed socket
        val prebufferedSlot = synchronized(predictiveRingBuffer) { predictiveRingBuffer[cleanUrl] }
        val effectivePlayUrl = if (prebufferedSlot != null &&
            (System.currentTimeMillis() - prebufferedSlot.timestampMs) < 45_000L
        ) {
            prebufferedSlot.resolvedEdgeUrl
        } else {
            cleanUrl
        }

        runCatching {
            exoPlayer.setMediaItem(MediaItem.fromUri(effectivePlayUrl), startPositionMs)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    /**
     * MODULE 1: Predictive Pre-Buffering Engine (First 200KB Ring-Buffer + 302 Redirect Resolution)
     * Triggered after >300ms D-Pad dwell on a channel row for adjacent channels (Index - 1, Index + 1).
     */
    override fun prebufferAdjacentStreams(prevUrl: String?, nextUrl: String?) {
        predictiveZapJob?.cancel()
        predictiveZapJob = ioScope.launch {
            val targets = listOfNotNull(prevUrl?.trim()?.takeIf { it.startsWith("http") }, nextUrl?.trim()?.takeIf { it.startsWith("http") })
            for (targetUrl in targets) {
                if (!isActive || targetUrl == currentStreamUrl) continue
                val alreadyWarm = synchronized(predictiveRingBuffer) {
                    val existing = predictiveRingBuffer[targetUrl]
                    existing != null && (System.currentTimeMillis() - existing.timestampMs) < 30_000L
                }
                if (alreadyWarm) continue

                prebufferSemaphore.withPermit {
                    runCatching {
                        val conn = (URL(targetUrl).openConnection() as? HttpURLConnection) ?: return@runCatching
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 3_500
                        conn.readTimeout = 3_500
                        conn.setRequestProperty("User-Agent", IPTV_USER_AGENT)
                        conn.setRequestProperty("Range", "bytes=0-${PREBUFFER_BYTES_LIMIT - 1}")
                        conn.connect()

                        val resolvedUrl = conn.url?.toString()?.takeIf { it.startsWith("http") } ?: targetUrl
                        val buffer = ByteArray(PREBUFFER_BYTES_LIMIT)
                        var totalRead = 0
                        conn.inputStream.use { input ->
                            while (isActive && totalRead < PREBUFFER_BYTES_LIMIT) {
                                val read = input.read(buffer, totalRead, PREBUFFER_BYTES_LIMIT - totalRead)
                                if (read <= 0) break
                                totalRead += read
                            }
                        }
                        conn.disconnect()

                        if (totalRead > 0) {
                            synchronized(predictiveRingBuffer) {
                                if (predictiveRingBuffer.size >= MAX_RING_BUFFER_SLOTS) {
                                    predictiveRingBuffer.keys.firstOrNull()?.let { predictiveRingBuffer.remove(it) }
                                }
                                predictiveRingBuffer[targetUrl] = PrebufferedStreamSlot(
                                    originalUrl = targetUrl,
                                    resolvedEdgeUrl = resolvedUrl,
                                    headerChunkBytes = buffer.copyOf(totalRead),
                                    timestampMs = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * MODULE 3: Restores saved Audio Track ID, Subtitle Track ID, and Audio Delay for the tuned channel.
     */
    override fun applySavedStreamTrackMemory(
        preferredAudioId: String?,
        preferredSubtitleId: String?,
        audioDelayMs: Int
    ) {
        pendingPreferredAudioId = preferredAudioId
        pendingPreferredSubtitleId = preferredSubtitleId
        setAudioDelayOffsetMs(audioDelayMs)

        val currentAudio = _audioTracks.value
        if (!preferredAudioId.isNullOrBlank() && currentAudio.isNotEmpty()) {
            currentAudio.firstOrNull { it.trackId == preferredAudioId || it.languageLabel == preferredAudioId }?.let {
                selectAudioTrack(it)
            }
        }
        val currentSubs = _subtitleTracks.value
        if (preferredSubtitleId != null && currentSubs.isNotEmpty()) {
            if (preferredSubtitleId == "OFF") {
                selectSubtitleTrack(null)
            } else {
                currentSubs.firstOrNull { it.trackId == preferredSubtitleId || it.languageLabel == preferredSubtitleId }?.let {
                    selectSubtitleTrack(it)
                }
            }
        }
    }

    override fun setAudioDelayOffsetMs(delayMs: Int) {
        activeAudioDelayOffsetMs = delayMs.coerceIn(-5000, 5000)
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
            val target = if (duration > 0L && duration != C.TIME_UNSET) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
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
        pendingPreferredAudioId = option.trackId
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
        pendingPreferredSubtitleId = option?.trackId ?: "OFF"
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
                        val rawFps = if (format.frameRate > 0f) format.frameRate else 50.0f
                        val fps = "${rawFps.roundToInt()} FPS"
                        val vCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
                        _techMetadata.update {
                            it.copy(resolutionLabel = res, fpsLabel = fps, videoCodec = vCodec, detectedFrameRate = rawFps)
                        }
                        // Module 4: Trigger Dynamic Automatic Frame Rate (AFR) HDMI matching
                        applyDynamicHardwareAfrIfEnabled(rawFps)
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase() ?: "SPUR ${audioList.size + 1}"
                        val label = format.label ?: lang
                        val aCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"
                        val selected = group.isTrackSelected(trackIdx)
                        if (selected) _techMetadata.update { it.copy(audioCodec = aCodec) }
                        val tId = format.id ?: "${groupIdx}_${trackIdx}_${label}_$aCodec"
                        audioList.add(TrackOption(groupIdx, trackIdx, label, aCodec, selected, tId))
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase() ?: "UNTERTITEL ${subtitleList.size + 1}"
                        val label = format.label ?: lang
                        val tId = format.id ?: "${groupIdx}_${trackIdx}_$label"
                        subtitleList.add(TrackOption(groupIdx, trackIdx, label, "SUB", group.isTrackSelected(trackIdx), tId))
                    }
                }
            }
        }
        _audioTracks.value = audioList
        _subtitleTracks.value = subtitleList

        // Module 3: Auto-Restitution of preferred Audio/Subtitle tracks on stream start
        val savedAudioId = pendingPreferredAudioId
        if (!savedAudioId.isNullOrBlank()) {
            val matchedAudio = audioList.firstOrNull { it.trackId == savedAudioId || it.languageLabel == savedAudioId }
            if (matchedAudio != null && !matchedAudio.isSelected) {
                selectAudioTrack(matchedAudio)
            }
        }
        val savedSubId = pendingPreferredSubtitleId
        if (!savedSubId.isNullOrBlank()) {
            if (savedSubId == "OFF") {
                if (subtitleList.any { it.isSelected }) selectSubtitleTrack(null)
            } else {
                val matchedSub = subtitleList.firstOrNull { it.trackId == savedSubId || it.languageLabel == savedSubId }
                if (matchedSub != null && !matchedSub.isSelected) {
                    selectSubtitleTrack(matchedSub)
                }
            }
        }
    }

    /**
     * MODULE 4: Dynamic Automatic Frame Rate (AFR) Matching via DisplayManager & Surface API
     * Switches Fire TV HDMI output refresh rate (Hz) to match native stream FPS (23.976, 24, 25->50, 29.97->59.94, 50, 60).
     */
    private fun applyDynamicHardwareAfrIfEnabled(streamFps: Float) {
        if (streamFps <= 0f) return
        val afrMode = AetherSettingsStore.state.value.autoFrameRateMode
        if (afrMode == "Aus") return

        // SurfaceFlinger Hint on Android 11+ (API 30+ / Fire OS 8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                activeSurfaceViewRef?.holder?.surface?.takeIf { it.isValid }?.setFrameRate(
                    streamFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            }
        }

        // Normalize 25fps -> 50Hz and 29.97/30fps -> 59.94/60Hz for HDMI TVs
        val targetHz = when {
            abs(streamFps - 25.0f) < 0.5f -> 50.0f
            abs(streamFps - 29.97f) < 0.05f -> 59.94f
            abs(streamFps - 30.0f) < 0.05f -> 60.0f
            else -> streamFps
        }
        if (abs(lastAppliedAfrHz - targetHz) < 0.05f) return

        afrSwitchJob?.cancel()
        afrSwitchJob = scope.launch {
            if (afrMode.contains("Verzögerung")) delay(1200L)
            runCatching {
                val activity = findHostActivity(context) ?: return@runCatching
                val window = activity.window ?: return@runCatching
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@runCatching
                val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching
                val currentMode = display.mode ?: return@runCatching

                val sameResolutionModes = display.supportedModes.filter {
                    it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
                }
                val bestMode = sameResolutionModes.minByOrNull { mode ->
                    val directDiff = abs(mode.refreshRate - targetHz)
                    val harmonicDiff = abs(mode.refreshRate - (streamFps * 2f))
                    minOf(directDiff, harmonicDiff)
                } ?: return@runCatching

                if (abs(bestMode.refreshRate - targetHz) <= 0.15f && window.attributes.preferredDisplayModeId != bestMode.modeId) {
                    val params = window.attributes
                    params.preferredDisplayModeId = bestMode.modeId
                    window.attributes = params
                    lastAppliedAfrHz = bestMode.refreshRate
                }
            }
        }
    }

    private fun findHostActivity(ctx: Context?): Activity? {
        var current = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
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
        predictiveZapJob?.cancel()
        afrSwitchJob?.cancel()
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
                androidEngine.activeSurfaceViewRef = videoSurfaceView as? SurfaceView
            }
        },
        update = { view ->
            if (view.player !== androidEngine.exoPlayer) {
                view.player = androidEngine.exoPlayer
            }
            androidEngine.activeSurfaceViewRef = view.videoSurfaceView as? SurfaceView
        }
    )
}
