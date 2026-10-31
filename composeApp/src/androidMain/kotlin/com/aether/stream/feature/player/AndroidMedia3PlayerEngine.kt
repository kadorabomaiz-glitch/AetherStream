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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aether.stream.core.navigation.StreamBufferPreset
import com.aether.stream.feature.settings.AetherSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
private class AetherPcmDelayAudioProcessor : AudioProcessor {
    @Volatile private var requestedDelayMs: Int = 0
    private var inputFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var isInputEnded: Boolean = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ringBuffer: ByteArray = ByteArray(0)
    private var writePos: Int = 0
    private var readPos: Int = 0
    private var bufferedBytes: Int = 0
    private var pendingSkipBytes: Int = 0

    fun setDelayOffsetMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-5000, 5000)
        if (requestedDelayMs != clamped) {
            val deltaMs = clamped - requestedDelayMs
            requestedDelayMs = clamped
            val fmt = inputFormat
            if (fmt != AudioProcessor.AudioFormat.NOT_SET && fmt.sampleRate > 0 && fmt.bytesPerFrame > 0 && deltaMs < 0) {
                val drop = (((abs(deltaMs).toLong() * fmt.sampleRate * fmt.bytesPerFrame) / 1000L).toInt() / fmt.bytesPerFrame) * fmt.bytesPerFrame
                synchronized(this) {
                    if (bufferedBytes >= drop) {
                        readPos = (readPos + drop) % ringBuffer.size.coerceAtLeast(1)
                        bufferedBytes -= drop
                    } else {
                        val rem = drop - bufferedBytes
                        bufferedBytes = 0; readPos = 0; writePos = 0
                        pendingSkipBytes = (pendingSkipBytes + rem).coerceAtMost(fmt.sampleRate * fmt.bytesPerFrame * 5)
                    }
                }
            }
        }
    }

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount <= 0) {
            inputFormat = AudioProcessor.AudioFormat.NOT_SET
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inputFormat = inputAudioFormat
        val cap = inputAudioFormat.sampleRate * inputAudioFormat.bytesPerFrame * 6
        synchronized(this) {
            if (ringBuffer.size < cap) ringBuffer = ByteArray(cap)
            writePos = 0; readPos = 0; bufferedBytes = 0; pendingSkipBytes = 0
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val rem = inputBuffer.remaining()
        if (rem == 0) return
        val fmt = inputFormat
        if (fmt == AudioProcessor.AudioFormat.NOT_SET || fmt.bytesPerFrame <= 0) {
            ensureOut(rem); outputBuffer.put(inputBuffer); outputBuffer.flip(); return
        }
        synchronized(this) {
            if (pendingSkipBytes > 0) {
                val skip = (minOf(rem, pendingSkipBytes) / fmt.bytesPerFrame) * fmt.bytesPerFrame
                if (skip > 0) { inputBuffer.position(inputBuffer.position() + skip); pendingSkipBytes -= skip }
                if (!inputBuffer.hasRemaining()) { ensureOut(0); outputBuffer.flip(); return }
            }
            val delayMs = requestedDelayMs
            val targetDelayBytes = if (delayMs > 0) {
                (((delayMs.toLong() * fmt.sampleRate * fmt.bytesPerFrame) / 1000L).toInt() / fmt.bytesPerFrame * fmt.bytesPerFrame).coerceIn(0, ringBuffer.size / 2)
            } else 0
            if (targetDelayBytes == 0 && bufferedBytes == 0) {
                val now = inputBuffer.remaining(); ensureOut(now); outputBuffer.put(inputBuffer); outputBuffer.flip(); return
            }
            val inBytes = inputBuffer.remaining()
            val cap = ringBuffer.size
            if (cap > 0 && inBytes <= cap - bufferedBytes) {
                val c1 = minOf(inBytes, cap - writePos)
                inputBuffer.get(ringBuffer, writePos, c1)
                if (inBytes - c1 > 0) inputBuffer.get(ringBuffer, 0, inBytes - c1)
                writePos = (writePos + inBytes) % cap
                bufferedBytes += inBytes
            } else {
                ensureOut(inBytes); outputBuffer.put(inputBuffer); outputBuffer.flip(); return
            }
            if (bufferedBytes <= targetDelayBytes) {
                val silence = (inBytes / fmt.bytesPerFrame) * fmt.bytesPerFrame
                ensureOut(silence); repeat(silence) { outputBuffer.put(0.toByte()) }; outputBuffer.flip()
            } else {
                val outBytes = (minOf(bufferedBytes - targetDelayBytes, maxOf(inBytes, fmt.bytesPerFrame * 256)) / fmt.bytesPerFrame) * fmt.bytesPerFrame
                ensureOut(outBytes)
                val r1 = minOf(outBytes, cap - readPos)
                outputBuffer.put(ringBuffer, readPos, r1)
                if (outBytes - r1 > 0) outputBuffer.put(ringBuffer, 0, outBytes - r1)
                readPos = (readPos + outBytes) % cap
                bufferedBytes -= outBytes
                outputBuffer.flip()
            }
        }
    }

    private fun ensureOut(cap: Int) {
        if (outputBuffer.capacity() < cap) outputBuffer = ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder()) else outputBuffer.clear()
    }

    override fun queueEndOfStream() { isInputEnded = true }
    override fun getOutput(): ByteBuffer { val out = outputBuffer; outputBuffer = AudioProcessor.EMPTY_BUFFER; return out }
    override fun isEnded(): Boolean = isInputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER && bufferedBytes == 0
    override fun flush() { outputBuffer = AudioProcessor.EMPTY_BUFFER; isInputEnded = false; synchronized(this) { writePos = 0; readPos = 0; bufferedBytes = 0; pendingSkipBytes = 0 } }
    override fun reset() { flush(); inputFormat = AudioProcessor.AudioFormat.NOT_SET; synchronized(this) { ringBuffer = ByteArray(0) } }
}

@OptIn(UnstableApi::class)
class AndroidMedia3PlayerEngine(private val context: Context) : AetherVideoPlayerEngine {

    companion object {
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"
        private const val PREBUFFER_BYTES_LIMIT = 200 * 1024
        private const val MAX_RING_BUFFER_SLOTS = 4
        private val ASPECT_MODES = listOf("Normal", "Vollbild", "16:9 Zoom", "Breite", "Höhe")

        init {
            runCatching {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
                HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
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
    private val pcmDelayProcessor = AetherPcmDelayAudioProcessor()
    private var activeTrackSelector: DefaultTrackSelector? = null

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
    private var aspectModeIndex = 0
    private var pendingPreferredAudioId: String? = null
    private var pendingPreferredSubtitleId: String? = null
    private var activeAudioDelayOffsetMs: Int = 0
    private var lastAppliedAfrHz: Float = 0f
    internal var activeSurfaceViewRef: SurfaceView? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _techMetadata.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
            if (playbackState == Player.STATE_READY) {
                reconnectCount = 0
                _techMetadata.update { it.copy(reconnectAttempt = 0, isBuffering = false) }
                updatePlaybackDurations()
            }
            val ended = playbackState == Player.STATE_ENDED
            _isPlaybackEnded.value = ended
            if (ended) { stopProgressTicker(); _isPlaying.value = false }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) { _isPlaybackEnded.value = false; startProgressTicker() } else { stopProgressTicker(); updatePlaybackDurations() }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) = updatePlaybackDurations()
        override fun onTracksChanged(tracks: Tracks) { extractTracksAndTechSpecs(tracks); updatePlaybackDurations() }

        override fun onPlayerError(error: PlaybackException) {
            val baseUrl = currentStreamUrl ?: return
            if (reconnectCount < 5) {
                reconnectCount++
                val lastPos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                _techMetadata.update { it.copy(reconnectAttempt = reconnectCount, isBuffering = true) }
                val candidateUrl = buildSmartFallbackUrl(baseUrl, reconnectCount)
                val isVod = baseUrl.contains("/movie/") || baseUrl.contains("/series/")
                scope.launch {
                    delay((if (isVod) 180L else 300L) * reconnectCount)
                    runCatching { exoPlayer.setMediaItem(MediaItem.fromUri(candidateUrl), lastPos); exoPlayer.prepare(); exoPlayer.play() }
                }
            } else _techMetadata.update { it.copy(isBuffering = false) }
        }
    }

    internal var exoPlayer: ExoPlayer = buildExoPlayer(currentPreset)
        private set

    private fun buildSmartFallbackUrl(originalUrl: String, attempt: Int): String {
        val isVodOrSeries = originalUrl.contains("/movie/") || originalUrl.contains("/series/")
        if (!isVodOrSeries || attempt <= 1) return originalUrl
        val lastDot = originalUrl.lastIndexOf('.'); val lastSlash = originalUrl.lastIndexOf('/')
        if (lastDot <= lastSlash) return originalUrl
        val ext = originalUrl.substring(lastDot + 1).lowercase().trim()
        val base = originalUrl.substring(0, lastDot)
        if (attempt >= 5 && originalUrl.contains("/movie/")) return originalUrl.replace("/movie/", "/live/").substringBeforeLast('.') + ".ts"
        val fallbacks = listOf("mp4", "mkv", "ts", "avi").filter { it != ext }
        return if (fallbacks.isEmpty()) originalUrl else "$base.${fallbacks[(attempt - 2).coerceAtLeast(0) % fallbacks.size]}"
    }

    private fun buildExoPlayer(preset: StreamBufferPreset): ExoPlayer {
        val customUa = AetherSettingsStore.state.value.userAgent.takeIf { it.isNotBlank() } ?: IPTV_USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(customUa).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6_500).setReadTimeoutMs(9_500).setKeepPostFor302Redirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        val fastStartMs = 250
        val minBuf = preset.minBufferMs.coerceAtLeast(fastStartMs + 500)
        val maxBuf = preset.maxBufferMs.coerceAtLeast(minBuf + 2_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, fastStartMs, 800)
            .setPrioritizeTimeOverSizeThresholds(true).build()

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(pcmDelayProcessor)).build()
            }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true)

        val enableTunneling = AetherSettingsStore.state.value.tunneledPlayback && activeAudioDelayOffsetMs == 0
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setTunnelingEnabled(enableTunneling).setPreferredTextLanguage("de"))
        }
        activeTrackSelector = trackSelector

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector).setMediaSourceFactory(mediaSourceFactory).setLoadControl(loadControl).build()
            .apply {
                playWhenReady = true
                videoScalingMode = if (aspectModeIndex == 2) C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(playerListener)
            }
    }

    override fun playStream(url: String, startPositionMs: Long) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        currentStreamUrl = cleanUrl; reconnectCount = 0; _isPlaybackEnded.value = false; _currentPositionMs.value = startPositionMs
        val slot = synchronized(predictiveRingBuffer) { predictiveRingBuffer[cleanUrl] }
        val playUrl = if (slot != null && (System.currentTimeMillis() - slot.timestampMs) < 45_000L) slot.resolvedEdgeUrl else cleanUrl
        runCatching { exoPlayer.setMediaItem(MediaItem.fromUri(playUrl), startPositionMs); exoPlayer.prepare(); exoPlayer.play() }
    }

    override fun prebufferAdjacentStreams(prevUrl: String?, nextUrl: String?) {
        predictiveZapJob?.cancel()
        predictiveZapJob = ioScope.launch {
            val targets = listOfNotNull(prevUrl?.trim()?.takeIf { it.startsWith("http") }, nextUrl?.trim()?.takeIf { it.startsWith("http") })
            for (targetUrl in targets) {
                if (!isActive || targetUrl == currentStreamUrl) continue
                val warm = synchronized(predictiveRingBuffer) { predictiveRingBuffer[targetUrl]?.let { (System.currentTimeMillis() - it.timestampMs) < 30_000L } == true }
                if (warm) continue
                prebufferSemaphore.withPermit {
                    runCatching {
                        val conn = (URL(targetUrl).openConnection() as? HttpURLConnection) ?: return@runCatching
                        conn.instanceFollowRedirects = true; conn.connectTimeout = 3_500; conn.readTimeout = 3_500
                        conn.setRequestProperty("User-Agent", IPTV_USER_AGENT)
                        conn.setRequestProperty("Range", "bytes=0-${PREBUFFER_BYTES_LIMIT - 1}")
                        conn.connect()
                        val resolved = conn.url?.toString()?.takeIf { it.startsWith("http") } ?: targetUrl
                        val buf = ByteArray(PREBUFFER_BYTES_LIMIT); var total = 0
                        conn.inputStream.use { inp -> while (isActive && total < PREBUFFER_BYTES_LIMIT) { val r = inp.read(buf, total, PREBUFFER_BYTES_LIMIT - total); if (r <= 0) break; total += r } }
                        conn.disconnect()
                        if (total > 0) synchronized(predictiveRingBuffer) {
                            if (predictiveRingBuffer.size >= MAX_RING_BUFFER_SLOTS) predictiveRingBuffer.keys.firstOrNull()?.let { predictiveRingBuffer.remove(it) }
                            predictiveRingBuffer[targetUrl] = PrebufferedStreamSlot(targetUrl, resolved, buf.copyOf(total), System.currentTimeMillis())
                        }
                    }
                }
            }
        }
    }

    override fun applySavedStreamTrackMemory(preferredAudioId: String?, preferredSubtitleId: String?, audioDelayMs: Int) {
        pendingPreferredAudioId = preferredAudioId; pendingPreferredSubtitleId = preferredSubtitleId
        setAudioDelayOffsetMs(audioDelayMs)
        if (!preferredAudioId.isNullOrBlank()) _audioTracks.value.firstOrNull { it.trackId == preferredAudioId || it.languageLabel == preferredAudioId }?.let { selectAudioTrack(it) }
        if (preferredSubtitleId != null) {
            if (preferredSubtitleId == "OFF") selectSubtitleTrack(null)
            else _subtitleTracks.value.firstOrNull { it.trackId == preferredSubtitleId || it.languageLabel == preferredSubtitleId }?.let { selectSubtitleTrack(it) }
        }
    }

    override fun setAudioDelayOffsetMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(-5000, 5000)
        val prev = activeAudioDelayOffsetMs
        activeAudioDelayOffsetMs = clamped
        _techMetadata.update { it.copy(activeAudioDelayMs = clamped) }
        pcmDelayProcessor.setDelayOffsetMs(clamped)
        activeTrackSelector?.let { sel ->
            val wantTunnel = AetherSettingsStore.state.value.tunneledPlayback && clamped == 0
            val hadTunnel = AetherSettingsStore.state.value.tunneledPlayback && prev == 0
            if (wantTunnel != hadTunnel) runCatching { sel.setParameters(sel.buildUponParameters().setTunnelingEnabled(wantTunnel)) }
        }
    }

    override fun cycleVideoAspectMode() {
        aspectModeIndex = (aspectModeIndex + 1) % ASPECT_MODES.size
        val label = ASPECT_MODES[aspectModeIndex]
        _techMetadata.update { it.copy(aspectModeLabel = label) }
        runCatching { exoPlayer.videoScalingMode = if (aspectModeIndex == 2) C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT }
    }

    override fun selectVideoTrack(option: TrackOption?) {
        runCatching {
            if (option == null || option.groupIndex < 0) {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearVideoSizeConstraints().build()
                return
            }
            val groups = exoPlayer.currentTracks.groups
            if (option.groupIndex in groups.indices) {
                val grp = groups[option.groupIndex].mediaTrackGroup
                if (option.trackIndex in 0 until grp.length) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).setOverrideForType(TrackSelectionOverride(grp, option.trackIndex)).build()
                }
            }
        }
    }

    override fun selectAudioTrack(option: TrackOption) {
        pendingPreferredAudioId = option.trackId
        runCatching {
            val groups = exoPlayer.currentTracks.groups
            if (option.groupIndex in groups.indices) {
                val grp = groups[option.groupIndex].mediaTrackGroup
                if (option.trackIndex in 0 until grp.length) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(TrackSelectionOverride(grp, option.trackIndex)).build()
                }
            }
        }
    }

    override fun selectSubtitleTrack(option: TrackOption?) {
        pendingPreferredSubtitleId = option?.trackId ?: "OFF"
        runCatching {
            if (option == null || option.groupIndex < 0) {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                return
            }
            val groups = exoPlayer.currentTracks.groups
            if (option.groupIndex in groups.indices) {
                val grp = groups[option.groupIndex].mediaTrackGroup
                if (option.trackIndex in 0 until grp.length) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(grp, option.trackIndex)).build()
                }
            }
        }
    }

    override fun seekRelativeMs(offsetMs: Long) = runCatching {
        val dur = exoPlayer.duration
        if (dur > 0L && dur != C.TIME_UNSET) { val t = (exoPlayer.currentPosition + offsetMs).coerceIn(0L, dur); exoPlayer.seekTo(t); _currentPositionMs.value = t }
    }.let {}

    override fun seekTo(positionMs: Long) = runCatching {
        val dur = exoPlayer.duration
        val t = if (dur > 0L && dur != C.TIME_UNSET) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        exoPlayer.seekTo(t); _currentPositionMs.value = t
    }.let {}

    private fun updatePlaybackDurations() = runCatching {
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        val dur = exoPlayer.duration.takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
        if (_currentPositionMs.value != pos) _currentPositionMs.value = pos
        if (_durationMs.value != dur) _durationMs.value = dur
    }.let {}

    private fun startProgressTicker() { progressTickerJob?.cancel(); progressTickerJob = scope.launch { while (isActive && exoPlayer.isPlaying) { updatePlaybackDurations(); delay(500L) } } }
    private fun stopProgressTicker() { progressTickerJob?.cancel(); progressTickerJob = null }

    override fun applyBufferPreset(preset: StreamBufferPreset) {
        if (currentPreset == preset) return
        currentPreset = preset
        val activeUrl = currentStreamUrl
        val pos = runCatching { exoPlayer.currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
        stopProgressTicker(); runCatching { exoPlayer.release() }
        exoPlayer = buildExoPlayer(preset)
        if (activeUrl != null) playStream(activeUrl, pos)
    }

    private fun extractTracksAndTechSpecs(tracks: Tracks) {
        val videoList = mutableListOf<TrackOption>()
        val audioList = mutableListOf<TrackOption>()
        val subtitleList = mutableListOf<TrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            when (group.type) {
                C.TRACK_TYPE_VIDEO -> {
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val shortRes = when { format.height >= 2160 -> "4K UHD"; format.height >= 1080 -> "1080p FHD"; format.height >= 720 -> "720p HD"; format.height > 0 -> "${format.height}p"; else -> "HD STREAM" }
                        val fullRes = if (format.width > 0 && format.height > 0) "$shortRes (${format.width}x${format.height})" else shortRes
                        val rawFps = if (format.frameRate > 0f) format.frameRate else 50.0f
                        val fps = "${rawFps.roundToInt()} FPS"
                        val vCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
                        val sel = group.isTrackSelected(trackIdx)
                        if (sel) {
                            _techMetadata.update { it.copy(resolutionLabel = shortRes, fpsLabel = fps, videoCodec = vCodec, detectedFrameRate = rawFps) }
                            applyDynamicHardwareAfrIfEnabled(rawFps)
                        }
                        val tId = format.id ?: "v_${groupIdx}_${trackIdx}_${format.height}"
                        videoList.add(TrackOption(groupIdx, trackIdx, "$fullRes ($fps)", vCodec, sel, tId))
                    }
                }
                C.TRACK_TYPE_AUDIO -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase()?.takeIf { it != "UND" } ?: "SPUR ${audioList.size + 1}"
                        val label = format.label?.takeIf { it.isNotBlank() } ?: lang
                        val aCodec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"
                        val sel = group.isTrackSelected(trackIdx)
                        if (sel) _techMetadata.update { it.copy(audioCodec = aCodec) }
                        val tId = format.id ?: "${groupIdx}_${trackIdx}_${label}_$aCodec"
                        audioList.add(TrackOption(groupIdx, trackIdx, label, aCodec, sel, tId))
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (trackIdx in 0 until group.length) {
                        val format: Format = group.getTrackFormat(trackIdx)
                        val lang = format.language?.uppercase()?.takeIf { it != "UND" } ?: "UNTERTITEL ${subtitleList.size + 1}"
                        val label = format.label?.takeIf { it.isNotBlank() } ?: lang
                        val tId = format.id ?: "${groupIdx}_${trackIdx}_$label"
                        subtitleList.add(TrackOption(groupIdx, trackIdx, label, "SUB", group.isTrackSelected(trackIdx), tId))
                    }
                }
            }
        }
        _techMetadata.update { it.copy(availableVideoTracks = videoList) }
        _audioTracks.value = audioList
        _subtitleTracks.value = subtitleList

        pendingPreferredAudioId?.takeIf { it.isNotBlank() }?.let { savedId ->
            audioList.firstOrNull { it.trackId == savedId || it.languageLabel == savedId }?.takeIf { !it.isSelected }?.let { selectAudioTrack(it) }
        }
        pendingPreferredSubtitleId?.takeIf { it.isNotBlank() }?.let { savedSub ->
            if (savedSub == "OFF") { if (subtitleList.any { it.isSelected }) selectSubtitleTrack(null) }
            else subtitleList.firstOrNull { it.trackId == savedSub || it.languageLabel == savedSub }?.takeIf { !it.isSelected }?.let { selectSubtitleTrack(it) }
        }
    }

    private fun applyDynamicHardwareAfrIfEnabled(streamFps: Float) {
        if (streamFps <= 0f) return
        val afrMode = AetherSettingsStore.state.value.autoFrameRateMode
        if (afrMode == "Aus") return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { activeSurfaceViewRef?.holder?.surface?.takeIf { it.isValid }?.setFrameRate(streamFps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
        val targetHz = when { abs(streamFps - 25.0f) < 0.5f -> 50.0f; abs(streamFps - 29.97f) < 0.05f -> 59.94f; abs(streamFps - 30.0f) < 0.05f -> 60.0f; else -> streamFps }
        if (abs(lastAppliedAfrHz - targetHz) < 0.05f) return
        afrSwitchJob?.cancel()
        afrSwitchJob = scope.launch {
            if (afrMode.contains("Verzögerung")) delay(1200L)
            runCatching {
                val window = findHostActivity(context)?.window ?: return@runCatching
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return@runCatching
                val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching
                val cur = display.mode ?: return@runCatching
                val best = display.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .minByOrNull { m -> minOf(abs(m.refreshRate - targetHz), abs(m.refreshRate - (streamFps * 2f))) } ?: return@runCatching
                if (abs(best.refreshRate - targetHz) <= 0.15f && window.attributes.preferredDisplayModeId != best.modeId) {
                    val params = window.attributes; params.preferredDisplayModeId = best.modeId; window.attributes = params; lastAppliedAfrHz = best.refreshRate
                }
            }
        }
    }

    private fun findHostActivity(ctx: Context?): Activity? {
        var cur = ctx
        while (cur is ContextWrapper) { if (cur is Activity) return cur; cur = cur.baseContext }
        return null
    }

    override fun pause() { stopProgressTicker(); runCatching { exoPlayer.pause() } }
    override fun resume() { runCatching { exoPlayer.play() } }
    override fun release() { stopProgressTicker(); predictiveZapJob?.cancel(); afrSwitchJob?.cancel(); runCatching { exoPlayer.release() } }
}

@OptIn(UnstableApi::class)
@Composable
actual fun AetherVideoSurface(engine: AetherVideoPlayerEngine, modifier: Modifier) {
    val androidEngine = engine as? AndroidMedia3PlayerEngine ?: return
    val meta by androidEngine.techMetadata.collectAsState()
    val targetResizeMode = when (meta.aspectModeLabel) {
        "Vollbild" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        "16:9 Zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "Breite" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        "Höhe" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                resizeMode = targetResizeMode
                player = androidEngine.exoPlayer
                androidEngine.activeSurfaceViewRef = videoSurfaceView as? SurfaceView
            }
        },
        update = { view ->
            if (view.player !== androidEngine.exoPlayer) view.player = androidEngine.exoPlayer
            if (view.resizeMode != targetResizeMode) view.resizeMode = targetResizeMode
            androidEngine.activeSurfaceViewRef = view.videoSurfaceView as? SurfaceView
        }
    )
}
