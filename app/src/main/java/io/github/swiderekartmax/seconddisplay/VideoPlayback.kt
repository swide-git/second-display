package io.github.swiderekartmax.seconddisplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AirPlay video (the YouTube app's AirPlay button): plays the HLS playlist the native core
 * serves on localhost, or a direct http(s) URL, and reports the position back for the
 * sender's progress bar. The video itself is fetched from the internet by this device, not
 * relayed by the sender. Main thread only.
 */
object VideoPlayback {
    private const val TAG = "VideoPlayback"
    private const val INFO_INTERVAL_MS = 500L

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** The player while an AirPlay video is open, for the video screen. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    /** Called with the reason when a video stops on its own (end, error) or is closed here. */
    var onEnded: ((String) -> Unit)? = null

    fun play(context: Context, url: String, startSec: Float) {
        release()
        finished = false
        val item = MediaItem.Builder().setUri(url).apply {
            // The core's playlist URL is the only HLS source; a direct URL is left to ExoPlayer.
            if (url.substringBefore('?').endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        val exo = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .build()
        exo.addListener(listener)
        exo.setMediaItem(item, (startSec.coerceAtLeast(0f) * 1000).toLong())
        exo.playWhenReady = true
        exo.prepare()
        _player.value = exo
        handler.removeCallbacks(reportInfo)
        handler.post(reportInfo)
        Log.i(TAG, "Playing AirPlay video from ${startSec}s")
    }

    fun seek(positionSec: Float) {
        _player.value?.seekTo((positionSec.coerceAtLeast(0f) * 1000).toLong())
    }

    fun setRate(rate: Float) {
        _player.value?.playWhenReady = rate > 0f
    }

    /** Closed on this device (Back, Home): the sender learns it from its next progress poll. */
    fun close(reason: String) {
        if (_player.value == null) return
        Log.i(TAG, "AirPlay video closed: $reason")
        finish(reason)
    }

    /** Stopped by the sender or the core; nothing to report back. */
    fun release() {
        handler.removeCallbacks(reportInfo)
        _player.value?.let {
            it.removeListener(listener)
            it.release()
        }
        _player.value = null
    }

    private fun finish(reason: String) {
        finished = true
        release()
        NativeBridge.nativeSetPlaybackInfo(0.0, -1.0, 0f, false, true)
        onEnded?.invoke(reason)
    }

    private val reportInfo = object : Runnable {
        override fun run() {
            val exo = _player.value ?: return
            val duration = exo.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: 0.0
            NativeBridge.nativeSetPlaybackInfo(
                duration,
                exo.currentPosition / 1000.0,
                if (exo.isPlaying) 1f else 0f,
                exo.playbackState == Player.STATE_BUFFERING,
                finished
            )
            handler.postDelayed(this, INFO_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finish("ended")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "AirPlay video failed: ${error.errorCodeName}", error)
            finish("error: ${error.errorCodeName}")
        }
    }
}
