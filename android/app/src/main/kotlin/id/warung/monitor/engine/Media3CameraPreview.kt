package id.warung.monitor.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import id.warung.monitor.core.model.CameraProfile
import id.warung.monitor.core.model.CameraSource
import id.warung.monitor.feature.home.domain.CameraPreviewCoordinator
import id.warung.monitor.feature.home.domain.PreviewState
import id.warung.monitor.feature.home.domain.StreamErrorKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * One ExoPlayer for the active camera.
 *
 * RTSP TCP is forced: UDP is usually blocked by AP isolation. Audio is muted.
 * The player is owned by this singleton (application scope), so rotating the
 * activity does not reopen the camera. Only one camera is ever open: [open]
 * stops the previous source first.
 *
 * Password handling: [open] accepts a char array, builds the RTSP URI in
 * memory, and never logs or stores it. The MVP scan flow has no password entry
 * yet, so it passes `null` and the stream fails honestly if auth is required.
 */
@OptIn(UnstableApi::class)
@Singleton
class Media3CameraPreview @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CameraPreviewCoordinator {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Idle)
    override val state: StateFlow<PreviewState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)

    /**
     * The player once it exists, so Compose can observe it instead of sampling
     * it once during composition (which left the video black forever when the
     * first frame was composed before `open()` finished).
     */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    override suspend fun open(camera: CameraProfile, secret: CharArray?) {
        withContext(Dispatchers.Main) {
            closeInternal()
            when (val source = camera.source) {
                CameraSource.Demo -> _state.value = PreviewState.Playing
                is CameraSource.Rtsp -> openRtsp(camera, source, secret)
            }
        }
        secret?.fill('\u0000')
    }

    private fun openRtsp(camera: CameraProfile, source: CameraSource.Rtsp, secret: CharArray?) {
        _state.value = PreviewState.Connecting
        val uri = buildUri(source, secret)
        val rtspFactory = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(5_000)
        val mediaSource: MediaSource = rtspFactory.createMediaSource(MediaItem.fromUri(uri))
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(rtspFactory)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 5_000, 500, 500)
                    .build(),
            )
            .build()
        exo.volume = 0f
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (_player.value !== exo) return
                _state.value = when (playbackState) {
                    Player.STATE_READY -> PreviewState.Playing
                    Player.STATE_BUFFERING -> PreviewState.Connecting
                    Player.STATE_ENDED -> PreviewState.Failed(StreamErrorKind.Closed)
                    else -> _state.value
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (_player.value !== exo) return
                _state.value = PreviewState.Failed(error.toKind())
            }
        })
        _player.value = exo
        exo.setMediaSource(mediaSource)
        exo.prepare()
        exo.playWhenReady = true
        // Keep the parameter used so a future credential implementation cannot
        // silently drop it; no logging of `camera` or `uri` here.
        require(camera.id.value.isNotBlank())
    }

    override suspend fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        _player.value?.let { exo ->
            exo.stop()
            exo.release()
        }
        _player.value = null
        _state.value = PreviewState.Idle
    }

    private fun buildUri(source: CameraSource.Rtsp, secret: CharArray?): Uri {
        val builder = Uri.Builder()
            .scheme("rtsp")
            .encodedAuthority(source.displayEndpoint)
        if (source.username != null && secret != null && secret.isNotEmpty()) {
            val userInfo = Uri.encode(source.username) + ":" + Uri.encode(String(secret))
            builder.encodedAuthority("$userInfo@${source.displayEndpoint}")
        }
        builder.encodedPath(source.path)
        return builder.build()
    }

    private fun androidx.media3.common.PlaybackException.toKind(): StreamErrorKind = when (errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT,
        -> StreamErrorKind.Timeout

        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> StreamErrorKind.Unreachable

        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> StreamErrorKind.AuthFailed

        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> StreamErrorKind.Protocol

        else -> StreamErrorKind.Unknown
    }
}
