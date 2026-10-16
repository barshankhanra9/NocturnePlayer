package app.nocturne.player

import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_PLAY -> {
                val uri = intent.getStringExtra(EXTRA_URI)
                if (!uri.isNullOrBlank()) {
                    val currentUri = player.currentMediaItem
                        ?.localConfiguration
                        ?.uri
                        ?.toString()

                    // If this is already the selected track, resume it rather than restarting.
                    if (currentUri == uri && player.mediaItemCount > 0) {
                        player.play()
                    } else {
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                        player.play()
                    }
                }
            }

            ACTION_SET_QUEUE -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS)
                val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

                if (!uris.isNullOrEmpty()) {
                    val items = uris.map { MediaItem.fromUri(it) }
                    val safeIndex = startIndex.coerceIn(items.indices)
                    player.setMediaItems(items, safeIndex, 0L)
                    player.prepare()
                    player.play()
                }
            }

            ACTION_TOGGLE_PLAY -> {
                if (player.isPlaying) {
                    player.pause()
                } else if (player.mediaItemCount > 0) {
                    player.play()
                }
            }

            ACTION_PREVIOUS -> {
                if (player.mediaItemCount > 0) {
                    if (player.currentPosition > 3_000L) {
                        player.seekTo(0L)
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                }
            }

            ACTION_NEXT -> {
                if (player.mediaItemCount > 0) {
                    player.seekToNextMediaItem()
                }
            }

            ACTION_TOGGLE_SHUFFLE -> {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }

            ACTION_CYCLE_REPEAT -> {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }

            ACTION_STOP -> {
                // Stop playback but retain the selected media item and queue.
                player.pause()
                player.seekTo(0L)
            }
        }

        return START_NOT_STICKY
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "app.nocturne.player.PLAY"
        const val ACTION_SET_QUEUE = "app.nocturne.player.SET_QUEUE"
        const val ACTION_TOGGLE_PLAY = "app.nocturne.player.TOGGLE_PLAY"
        const val ACTION_PREVIOUS = "app.nocturne.player.PREVIOUS"
        const val ACTION_NEXT = "app.nocturne.player.NEXT"
        const val ACTION_TOGGLE_SHUFFLE = "app.nocturne.player.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "app.nocturne.player.CYCLE_REPEAT"
        const val ACTION_STOP = "app.nocturne.player.STOP"

        const val EXTRA_URI = "audio_uri"
        const val EXTRA_URIS = "audio_uris"
        const val EXTRA_START_INDEX = "start_index"
    }
}
