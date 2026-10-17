package app.nocturne.player

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private val progressReporter = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return
            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
            val savedQueue = (0 until player.mediaItemCount).mapNotNull { index ->
                player.getMediaItemAt(index).localConfiguration?.uri?.toString()
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_QUEUE, savedQueue.joinToString("\n"))
                .putString(KEY_URI, currentUri)
                .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
                .putInt(KEY_INDEX, player.currentMediaItemIndex.coerceAtLeast(0))
                .apply()
            val progress = Intent(ACTION_PROGRESS).apply {
                setPackage(packageName)
                putExtra(EXTRA_POSITION, player.currentPosition.coerceAtLeast(0L))
                putExtra(EXTRA_DURATION, player.duration.takeIf { it > 0L } ?: 0L)
                putExtra(EXTRA_IS_PLAYING, player.isPlaying)
                putExtra(EXTRA_CURRENT_URI, currentUri)
            }
            sendBroadcast(progress)
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
        restoreLastPlayback()
        handler.post(progressReporter)
    }

    private fun restoreLastPlayback() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val queue = prefs.getString(KEY_QUEUE, null)
            ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
        if (queue.isEmpty()) return
        val items = queue.map { MediaItem.fromUri(Uri.parse(it)) }
        val savedIndex = prefs.getInt(KEY_INDEX, 0).coerceIn(items.indices)
        val savedPosition = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
        player.setMediaItems(items, savedIndex, savedPosition)
        player.prepare()
        player.pause() // Restore the last position without unexpectedly starting music.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> {
                val uri = intent.getStringExtra(EXTRA_URI)
                if (!uri.isNullOrBlank()) {
                    val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (currentUri == uri && player.mediaItemCount > 0) player.play()
                    else {
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
                if (player.isPlaying) player.pause()
                else if (player.mediaItemCount > 0) player.play()
            }
            ACTION_PREVIOUS -> {
                if (player.mediaItemCount > 0) {
                    if (player.currentPosition > 3000L) player.seekTo(0L)
                    else player.seekToPreviousMediaItem()
                }
            }
            ACTION_NEXT -> if (player.mediaItemCount > 0) player.seekToNextMediaItem()
            ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            ACTION_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            ACTION_SEEK_TO -> {
                val position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L).coerceAtLeast(0L)
                if (player.mediaItemCount > 0) {
                    val duration = player.duration
                    player.seekTo(if (duration > 0L) position.coerceAtMost(duration) else position)
                }
            }
            ACTION_STOP -> {
                player.pause()
                player.seekTo(0L)
            }
        }
        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(progressReporter)
        mediaSession?.release()
        mediaSession = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RESTORE = "app.nocturne.player.RESTORE"
        const val ACTION_PLAY = "app.nocturne.player.PLAY"
        const val ACTION_SET_QUEUE = "app.nocturne.player.SET_QUEUE"
        const val ACTION_TOGGLE_PLAY = "app.nocturne.player.TOGGLE_PLAY"
        const val ACTION_PREVIOUS = "app.nocturne.player.PREVIOUS"
        const val ACTION_NEXT = "app.nocturne.player.NEXT"
        const val ACTION_TOGGLE_SHUFFLE = "app.nocturne.player.TOGGLE_SHUFFLE"
        const val ACTION_CYCLE_REPEAT = "app.nocturne.player.CYCLE_REPEAT"
        const val ACTION_SEEK_TO = "app.nocturne.player.SEEK_TO"
        const val ACTION_PROGRESS = "app.nocturne.player.PROGRESS"
        const val ACTION_STOP = "app.nocturne.player.STOP"
        const val EXTRA_URI = "audio_uri"
        const val EXTRA_URIS = "audio_uris"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_CURRENT_URI = "current_uri"
        private const val PREFS_NAME = "aria_playback_state"
        private const val KEY_QUEUE = "queue"
        private const val KEY_URI = "uri"
        private const val KEY_POSITION = "position"
        private const val KEY_INDEX = "index"
    }
}
