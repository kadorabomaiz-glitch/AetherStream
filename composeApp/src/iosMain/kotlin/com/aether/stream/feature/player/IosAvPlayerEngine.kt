package com.aether.stream.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.aether.stream.core.navigation.StreamBufferPreset
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.selectMediaOption
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
class IosAvPlayerEngine : AetherVideoPlayerEngine {

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _techMetadata = MutableStateFlow(
        StreamTechMetadata(
            resolutionLabel = "1080p FHD",
            fpsLabel = "50 FPS",
            videoCodec = "HEVC/H.264",
            audioCodec = "Dolby/AAC"
        )
    )
    override val techMetadata: StateFlow<StreamTechMetadata> = _techMetadata.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val audioTracks: StateFlow<List<TrackOption>> = _audioTracks.asStateFlow()

    private val _subtitleTracks = MutableStateFlow<List<TrackOption>>(emptyList())
    override val subtitleTracks: StateFlow<List<TrackOption>> = _subtitleTracks.asStateFlow()

    internal val avPlayer: AVPlayer = AVPlayer()
    private var currentUrl: String? = null
    private var currentPreset: StreamBufferPreset = StreamBufferPreset.BALANCED
    private var reconnectCount = 0

    override fun playStream(url: String, startPositionMs: Long) {
        currentUrl = url
        val nsUrl = NSURL.URLWithString(url) ?: return
        val item = AVPlayerItem(uRL = nsUrl).apply {
            preferredForwardBufferDuration = currentPreset.maxBufferMs / 1000.0
        }

        avPlayer.replaceCurrentItemWithPlayerItem(item)
        if (startPositionMs > 0L) {
            val time = CMTimeMakeWithSeconds(startPositionMs / 1000.0, 600)
            avPlayer.seekToTime(time)
        }
        avPlayer.play()
        extractMediaTracks(item)
    }

    override fun applyBufferPreset(preset: StreamBufferPreset) {
        currentPreset = preset
        avPlayer.currentItem?.preferredForwardBufferDuration = preset.maxBufferMs / 1000.0
    }

    override fun selectAudioTrack(option: TrackOption) {
        val item = avPlayer.currentItem ?: return
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) ?: return
        val options = group.options
        if (option.trackIndex in options.indices) {
            val selectedObj = options[option.trackIndex] as? AVMediaSelectionOption
            item.selectMediaOption(selectedObj, inMediaSelectionGroup = group)
            extractMediaTracks(item)
        }
    }

    override fun selectSubtitleTrack(option: TrackOption?) {
        val item = avPlayer.currentItem ?: return
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) ?: return
        if (option == null) {
            item.selectMediaOption(null, inMediaSelectionGroup = group)
        } else {
            val options = group.options
            if (option.trackIndex in options.indices) {
                val selectedObj = options[option.trackIndex] as? AVMediaSelectionOption
                item.selectMediaOption(selectedObj, inMediaSelectionGroup = group)
            }
        }
        extractMediaTracks(item)
    }

    private fun extractMediaTracks(item: AVPlayerItem) {
        val audioGroup: AVMediaSelectionGroup? =
            item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
        val subGroup: AVMediaSelectionGroup? =
            item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)

        val audioList = mutableListOf<TrackOption>()
        audioGroup?.options?.forEachIndexed { idx, rawOption ->
            val opt = rawOption as? AVMediaSelectionOption
            audioList.add(
                TrackOption(
                    groupIndex = 0,
                    trackIndex = idx,
                    languageLabel = opt?.displayName ?: "Audio ${idx + 1}",
                    codecLabel = "AAC/AC3",
                    isSelected = idx == 0
                )
            )
        }
        _audioTracks.value = audioList

        val subList = mutableListOf<TrackOption>()
        subGroup?.options?.forEachIndexed { idx, rawOption ->
            val opt = rawOption as? AVMediaSelectionOption
            subList.add(
                TrackOption(
                    groupIndex = 1,
                    trackIndex = idx,
                    languageLabel = opt?.displayName ?: "Untertitel ${idx + 1}",
                    codecLabel = "SUB",
                    isSelected = false
                )
            )
        }
        _subtitleTracks.value = subList
    }

    fun triggerAutoReconnectIfStalled() {
        val url = currentUrl ?: return
        if (reconnectCount < 5) {
            reconnectCount++
            _techMetadata.update { it.copy(reconnectAttempt = reconnectCount, isBuffering = true) }
            scope.launch {
                delay(700L * reconnectCount)
                playStream(url)
            }
        }
    }

    override fun pause() = avPlayer.pause()
    override fun resume() = avPlayer.play()
    override fun release() = avPlayer.pause()
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AetherVideoSurface(
    engine: AetherVideoPlayerEngine,
    modifier: Modifier
) {
    val iosEngine = engine as? IosAvPlayerEngine ?: return
    UIKitView(
        modifier = modifier,
        factory = {
            val containerView = UIView()
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(iosEngine.avPlayer).apply {
                videoGravity = AVLayerVideoGravityResizeAspect
            }
            containerView.layer.addSublayer(playerLayer)
            containerView
        },
        update = { view ->
            val sublayers = view.layer.sublayers
            val playerLayer = sublayers?.firstOrNull() as? AVPlayerLayer
            playerLayer?.frame = view.bounds
        }
    )
}