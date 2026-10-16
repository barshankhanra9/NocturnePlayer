
package app.nocturne.player

import android.content.Intent
import android.os.Bundle
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

        if (intent?.action == ACTION_PLAY) {
            val uri = intent.getStringExtra(EXTRA_URI)

            if (!uri.isNullOrBlank()) {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
            } else {
                player.stop()
                player.clearMediaItems()
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
        const val EXTRA_URI = "audio_uri"
    }
}
