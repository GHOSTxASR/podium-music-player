package app.podium.poc

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

class PocPlayer(context: Context) {

    data class PlaybackEvent(
        val state: String,
        val isPlaying: Boolean,
        val currentPositionMs: Long,
        val durationMs: Long,
        val error: String? = null
    )

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(PocDownloader.USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)

    private val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setHandleAudioBecomingNoisy(true)
        .build()

    var onEvent: ((PlaybackEvent) -> Unit)? = null

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                notifyEvent(stateName, null)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                notifyEvent(if (isPlaying) "PLAYING" else "PAUSED", null)
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorDetails = "${error.errorCodeName} (${error.errorCode}): ${error.message}"
                notifyEvent("ERROR", errorDetails)
            }
        })
    }

    private fun notifyEvent(state: String, error: String?) {
        onEvent?.invoke(
            PlaybackEvent(
                state = state,
                isPlaying = exoPlayer.isPlaying,
                currentPositionMs = exoPlayer.currentPosition,
                durationMs = exoPlayer.duration,
                error = error
            )
        )
    }

    fun play(url: String) {
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    fun resume() {
        exoPlayer.playWhenReady = true
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun release() {
        exoPlayer.release()
    }
}
