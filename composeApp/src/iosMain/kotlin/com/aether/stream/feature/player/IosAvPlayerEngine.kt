package com.aether.stream.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import com.aether.stream.core.navigation.StreamBufferPreset
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.Foundation.NSURL
import platform.QuartzCore.CALayer
import platform.UIKit.UIView

@OptIn(ExperimentalForeignApi::class)
class IosAvPlayerEngine : AetherVideoPlayerEngine {

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
    private var currentPreset: StreamBufferPreset = StreamBufferPreset.BALANCED

    override fun playStream(url: String, startPositionMs: Long) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        val item = AVPlayerItem(uRL = nsUrl).apply {
            preferredForwardBufferDuration = currentPreset.maxBufferMs / 1000.0
        }
        avPlayer.replaceCurrentItemWithPlayerItem(item)
        avPlayer.play()
    }

    override fun applyBufferPreset(preset: StreamBufferPreset) {
        currentPreset = preset
        avPlayer.currentItem?.preferredForwardBufferDuration = preset.maxBufferMs / 1000.0
    }

    override fun selectAudioTrack(option: TrackOption) {}
    override fun selectSubtitleTrack(option: TrackOption?) {}
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
        factory = {
            val containerView = UIView()
            val playerLayer = AVPlayerLayer.playerLayerWithPlayer(iosEngine.avPlayer)
            playerLayer.videoGravity = AVLayerVideoGravityResizeAspect
            containerView.layer.addSublayer(playerLayer)
            containerView
        },
        modifier = modifier,
        update = { view ->
            val sublayers = view.layer.sublayers
            val playerLayer = sublayers?.firstOrNull() as? CALayer
            playerLayer?.setFrame(view.bounds)
        }
    )
}
