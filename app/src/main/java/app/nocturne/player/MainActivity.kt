package app.nocturne.player

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.Activity
import android.Manifest
import android.content.ContentUris
import android.os.Build
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF0B0B10)
private val Glass = Color(0xFF191922)
private val GlassLight = Color(0xFF242431)
private val Accent = Color(0xFFB69CFF)
private val Muted = Color(0xFFA5A3B2)

private enum class PlayerTab(val label: String, val symbol: String) {
    HOME("Home", "⌂"), LIBRARY("Library", "♫"), SEARCH("Search", "⌕")
}

private data class TrackInfo(val title: String, val artist: String, val artwork: Bitmap?)
private data class LibraryTrack(val name: String, val uri: Uri, val artist: String = "Unknown artist", val album: String = "Unknown album")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var selectedTab by remember { mutableStateOf(PlayerTab.HOME) }
            var selectedUri by remember { mutableStateOf<Uri?>(null) }
            var trackName by remember { mutableStateOf("Nothing playing") }
            var artistName by remember { mutableStateOf("Choose music from your library") }
            var artwork by remember { mutableStateOf<Bitmap?>(null) }
            val preferences = remember { getSharedPreferences("aria_player_preferences", MODE_PRIVATE) }
            val favouriteUris = remember {
                mutableStateListOf<String>().apply {
                    addAll(preferences.getStringSet("favourite_uris", emptySet()) ?: emptySet())
                }
            }
            val favourite = selectedUri?.toString()?.let { it in favouriteUris } ?: false
            fun toggleCurrentFavourite() {
                selectedUri?.toString()?.let { key ->
                    if (key in favouriteUris) favouriteUris.remove(key) else favouriteUris.add(key)
                    preferences.edit().putStringSet("favourite_uris", favouriteUris.toSet()).apply()
                }
            }
            var searchQuery by remember { mutableStateOf("") }
            var librarySection by remember { mutableStateOf("Songs") }
            var shuffleEnabled by remember { mutableStateOf(false) }
            var repeatMode by remember { mutableIntStateOf(0) }
            var queueIndex by remember { mutableIntStateOf(0) }
            var showNowPlaying by remember { mutableStateOf(false) }
            var queueNotice by remember { mutableStateOf<String?>(null) }
            var isPlaying by remember { mutableStateOf(false) }
            var playbackPosition by remember { mutableLongStateOf(0L) }
            var playbackDuration by remember { mutableLongStateOf(0L) }
            val context = this@MainActivity
            val library = remember { mutableStateListOf<LibraryTrack>() }
            DisposableEffect(Unit) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == PlaybackService.ACTION_PROGRESS) {
                            playbackPosition = intent.getLongExtra(PlaybackService.EXTRA_POSITION, 0L)
                            playbackDuration = intent.getLongExtra(PlaybackService.EXTRA_DURATION, 0L)
                            isPlaying = intent.getBooleanExtra(PlaybackService.EXTRA_IS_PLAYING, false)
                            val restoredUri = intent.getStringExtra(PlaybackService.EXTRA_CURRENT_URI)
                            if (!restoredUri.isNullOrBlank()) {
                                val uri = Uri.parse(restoredUri)
                                selectedUri = uri
                                library.firstOrNull { it.uri.toString() == restoredUri }?.let { track ->
                                    trackName = track.name
                                    artistName = track.artist
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter(PlaybackService.ACTION_PROGRESS)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
            }

            fun scanDeviceAudio() {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return

                val found = mutableListOf<LibraryTrack>()
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.IS_MUSIC
                )
                try {
                    contentResolver.query(
                        collection, projection,
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                        "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val title = cursor.getString(titleColumn)
                                ?.takeIf { it.isNotBlank() }
                                ?: cursor.getString(nameColumn)?.substringBeforeLast('.')
                                ?: "Local audio"
                            val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                            val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown album"
                            found.add(LibraryTrack(title, ContentUris.withAppendedId(collection, id), artist, album))
                        }
                    }
                } catch (_: Exception) { }

                val previousSelected = selectedUri?.toString()
                library.clear()
                library.addAll(found)
                if (previousSelected != null) {
                    val restoredIndex = library.indexOfFirst { it.uri.toString() == previousSelected }
                    if (restoredIndex >= 0) queueIndex = restoredIndex
                }
            }

            val audioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> if (granted) scanDeviceAudio() }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* Android controls notification display according to the user's choice. */ }

            LaunchedEffect(Unit) {
                // Ask explicitly on Android 13+; the manifest declaration alone is not enough.
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // Start the playback service so it can restore the last saved track and position.
                startService(Intent(this@MainActivity, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_RESTORE
                })
            }

            LaunchedEffect(Unit) {
                val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    scanDeviceAudio()
                } else {
                    audioPermissionLauncher.launch(permission)
                }
            }

            fun loadTrackInfo(uri: Uri, fallback: String) {
                var title = fallback
                var artist = "Unknown artist"
                var art: Bitmap? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@MainActivity, uri)
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() } ?: fallback
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?.takeIf { it.isNotBlank() }
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                            ?.takeIf { it.isNotBlank() }
                        ?: "Unknown artist"
                    retriever.embeddedPicture?.let { bytes ->
                        art = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (_: Exception) {
                    // Some formats or providers do not expose embedded metadata.
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
                trackName = title
                artistName = artist
                artwork = art
            }

            fun fallbackName(uri: Uri): String {
                var result: String? = null
                try {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) result = cursor.getString(idx)
                            }
                        }
                } catch (_: Exception) {}
                return result?.substringBeforeLast('.', result ?: "")
                    ?.takeIf { it.isNotBlank() } ?: "Local audio"
            }

            fun playQueue(index: Int) {
                if (library.isEmpty()) return
                val safeIndex = index.coerceIn(0, library.lastIndex)
                queueIndex = safeIndex
                isPlaying = true
                val track = library[safeIndex]
                selectedUri = track.uri
                loadTrackInfo(track.uri, track.name)
                val intent = Intent(this@MainActivity, PlaybackService::class.java).apply {
                    action = PlaybackService.ACTION_SET_QUEUE
                    putStringArrayListExtra(
                        PlaybackService.EXTRA_URIS,
                        ArrayList(library.map { it.uri.toString() })
                    )
                    putExtra(PlaybackService.EXTRA_START_INDEX, safeIndex)
                }
                startService(intent)
            }

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}
                    val name = fallbackName(uri)
                    if (library.none { it.uri == uri }) library.add(LibraryTrack(name, uri))
                    val index = library.indexOfFirst { it.uri == uri }.coerceAtLeast(0)
                    selectedTab = PlayerTab.HOME
                    playQueue(index)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, onPrimary = Night, background = Night,
                    surface = Glass, onSurface = Color.White, secondary = Accent
                )
            ) {
                Scaffold(
                    containerColor = Night,
                    bottomBar = {
                        if (!showNowPlaying) Column {
                            if (selectedUri != null) {
                                MiniPlayer(
                                    title = trackName,
                                    artist = artistName,
                                    artwork = artwork,
                                    isPlaying = isPlaying,
                                    onOpen = { showNowPlaying = true },
                                    onTogglePlay = {
                                        sendPlaybackAction(PlaybackService.ACTION_TOGGLE_PLAY)
                                        isPlaying = !isPlaying
                                    },
                                    onNext = {
                                        if (library.isNotEmpty()) {
                                            playQueue(if (shuffleEnabled) library.indices.random() else (queueIndex + 1) % library.size)
                                        } else sendPlaybackAction(PlaybackService.ACTION_NEXT)
                                    }
                                )
                            }
                            NavigationBar(containerColor = Color(0xFF15151D), contentColor = Accent) {
                                PlayerTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selectedTab == tab,
                                        onClick = { selectedTab = tab; showNowPlaying = false },
                                        icon = { Text(tab.symbol, fontSize = 23.sp, fontWeight = FontWeight.Medium) },
                                        label = { Text(tab.label, fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Accent, selectedTextColor = Accent,
                                            indicatorColor = Color(0xFF302642),
                                            unselectedIconColor = Muted, unselectedTextColor = Muted
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    if (showNowPlaying) {
                        NowPlayingScreen(
                            trackName = trackName,
                            artistName = artistName,
                            artwork = artwork,
                            favourite = favourite,
                            isPlaying = isPlaying,
                            trackUri = selectedUri?.toString().orEmpty(),
                            shuffleEnabled = shuffleEnabled,
                            repeatMode = repeatMode,
                            playbackPosition = playbackPosition,
                            playbackDuration = playbackDuration,
                            onSeek = { position -> sendPlaybackSeek(position) },
                            onBack = { showNowPlaying = false },
                            onFavourite = { toggleCurrentFavourite() },
                            onAddToQueue = {
                                // The library is the current playback queue; selecting this item later
                                // will play it without changing the active track now.
                                queueNotice = "Track is available in your library queue"
                            },
                            onTogglePlay = {
                                sendPlaybackAction(PlaybackService.ACTION_TOGGLE_PLAY)
                                isPlaying = !isPlaying
                            },
                            onPrevious = {
                                if (library.isNotEmpty()) {
                                    playQueue(if (queueIndex > 0) queueIndex - 1 else library.lastIndex)
                                } else sendPlaybackAction(PlaybackService.ACTION_PREVIOUS)
                            },
                            onNext = {
                                if (library.isNotEmpty()) {
                                    playQueue(if (shuffleEnabled) library.indices.random() else (queueIndex + 1) % library.size)
                                } else sendPlaybackAction(PlaybackService.ACTION_NEXT)
                            },
                            onShuffle = {
                                shuffleEnabled = !shuffleEnabled
                                sendPlaybackAction(PlaybackService.ACTION_TOGGLE_SHUFFLE)
                            },
                            onRepeat = {
                                repeatMode = (repeatMode + 1) % 3
                                sendPlaybackAction(PlaybackService.ACTION_CYCLE_REPEAT)
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().background(Night).padding(padding)
                        ) {
                            when (selectedTab) {
                                PlayerTab.HOME -> HomeScreen(
                                    tracks = library,
                                    trackName = trackName,
                                    artistName = artistName,
                                    artwork = artwork,
                                    favourite = favourite,
                                    onFavourite = { toggleCurrentFavourite() },
                                    onOpenPlayer = { if (selectedUri != null) showNowPlaying = true },
                                    onOpenLibrary = { selectedTab = PlayerTab.LIBRARY },
                                    onAddMusic = { picker.launch(arrayOf("audio/*")) },
                                    onPlayTrack = { track -> playQueue(library.indexOf(track).coerceAtLeast(0)) }
                                )
                                PlayerTab.LIBRARY -> LibraryScreen(
                                    tracks = library,
                                    favouriteUris = favouriteUris,
                                    section = librarySection,
                                    onSectionChange = { librarySection = it },
                                    onToggleFavourite = { track ->
                                        val key = track.uri.toString()
                                        if (key in favouriteUris) favouriteUris.remove(key) else favouriteUris.add(key)
                                        preferences.edit().putStringSet("favourite_uris", favouriteUris.toSet()).apply()
                                    },
                                    onOpenFiles = { picker.launch(arrayOf("audio/*")) },
                                    onPlayTrack = { track ->
                                        selectedTab = PlayerTab.HOME
                                        playQueue(library.indexOf(track).coerceAtLeast(0))
                                    }
                                )
                                PlayerTab.SEARCH -> SearchScreen(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    tracks = library,
                                    onPlayTrack = { track ->
                                        selectedTab = PlayerTab.HOME
                                        playQueue(library.indexOf(track).coerceAtLeast(0))
                                    },
                                    onOpenFiles = { picker.launch(arrayOf("audio/*")) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendPlaybackAction(actionName: String) {
        startService(Intent(this, PlaybackService::class.java).setAction(actionName))
    }

    private fun sendPlaybackSeek(positionMs: Long) {
        startService(Intent(this, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_SEEK_TO)
            .putExtra(PlaybackService.EXTRA_SEEK_POSITION, positionMs))
    }
}

@Composable
private fun HomeScreen(
    tracks: List<LibraryTrack>,
    trackName: String,
    artistName: String,
    artwork: Bitmap?,
    favourite: Boolean,
    onFavourite: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenLibrary: () -> Unit,
    onAddMusic: () -> Unit,
    onPlayTrack: (LibraryTrack) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ARIA", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Every note,\na feeling.", color = Color.White, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("Your music, in its own atmosphere.", color = Muted, fontSize = 14.sp)
                }
                Box(
                    Modifier.size(54.dp).background(Glass, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) { Text("♫", color = Accent, fontSize = 28.sp) }
            }
        }
        item {
            GlassCard(modifier = Modifier.clickable { if (tracks.isNotEmpty()) onOpenPlayer() else onOpenLibrary() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(artwork, Modifier.size(76.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (tracks.isEmpty()) "START LISTENING" else "CONTINUE LISTENING", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(if (tracks.isEmpty()) "Your next favourite song" else trackName, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(if (tracks.isEmpty()) "Add music from your phone" else artistName, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (tracks.isEmpty()) "＋" else "▶", color = Accent, fontSize = 22.sp)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("YOUR LIBRARY")
                    Spacer(Modifier.height(4.dp))
                    Text("${tracks.size} local ${if (tracks.size == 1) "track" else "tracks"}", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = onOpenLibrary) { Text("See all  ›", color = Accent) }
            }
            Spacer(Modifier.height(8.dp))
            if (tracks.isEmpty()) {
                GlassCard(modifier = Modifier.clickable(onClick = onAddMusic)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("＋", color = Accent, fontSize = 30.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Add your first songs", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text("Choose audio files stored on your phone", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    tracks.takeLast(4).asReversed().forEach { track ->
                        TrackRowArtwork(track, if (track.name == trackName) artistName else "Local audio") { onPlayTrack(track) }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("RECENTLY ADDED")
                Spacer(Modifier.weight(1f))
                Text("♫", color = Accent, fontSize = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            if (tracks.isEmpty()) {
                EmptyState("♫", "Nothing here yet", "Songs you add will appear here.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tracks.takeLast(3).asReversed().forEach { track ->
                        Column(
                            Modifier.weight(1f).clickable { onPlayTrack(track) },
                            horizontalAlignment = Alignment.Start
                        ) {
                            TrackArtwork(track.uri, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(8.dp))
                            Text(track.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (track.name == trackName) artistName else "Local audio", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("FAVORITES")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.clickable { if (favourite) onOpenPlayer() else onFavourite() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).background(Color(0xFF35202D), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Text("♥", color = Color(0xFFFF6A91), fontSize = 26.sp)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Liked music", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(if (favourite) trackName else "Save the song you're listening to", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (favourite) "▶" else "♡", color = Accent, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    artwork: Bitmap?,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .background(Color(0xFF24212F), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(artwork, Modifier.size(44.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onTogglePlay) { Text(if (isPlaying) "Ⅱ" else "▶", color = Accent, fontSize = 20.sp) }
        TextButton(onClick = onNext) { Text("⏭", color = Color.White, fontSize = 19.sp) }
    }
}

@Composable
private fun NowPlayingScreen(
    trackName: String,
    artistName: String,
    artwork: Bitmap?,
    favourite: Boolean,
    isPlaying: Boolean,
    trackUri: String,
    onAddToQueue: () -> Unit,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    playbackPosition: Long,
    playbackDuration: Long,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onFavourite: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingProgress by remember(trackName) { mutableStateOf<Float?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var showSongDetails by remember { mutableStateOf(false) }
    var showQueueNotice by remember { mutableStateOf(false) }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            containerColor = Glass,
            titleContentColor = Color.White,
            textContentColor = Muted,
            title = { Text("Song options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showOptions = false; showSongDetails = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("♫   Song details", color = Color.White)
                    }
                    TextButton(onClick = { showOptions = false; onAddToQueue(); showQueueNotice = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("＋   Add to queue", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptions = false }) { Text("Close", color = Accent) }
            }
        )
    }

    if (showSongDetails) {
        AlertDialog(
            onDismissRequest = { showSongDetails = false },
            containerColor = Glass,
            titleContentColor = Color.White,
            textContentColor = Muted,
            title = { Text("Song details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Title", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(trackName, color = Color.White)
                    Text("Artist", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(artistName, color = Color.White)
                    Text("Source", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text("Local audio", color = Color.White)
                    Text("File URI", color = Accent, fontWeight = FontWeight.SemiBold)
                    Text(trackUri.ifBlank { "Unavailable" }, color = Muted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showSongDetails = false }) { Text("Done", color = Accent) }
            }
        )
    }

    if (showQueueNotice) {
        AlertDialog(
            onDismissRequest = { showQueueNotice = false },
            containerColor = Glass,
            title = { Text("Queue", color = Color.White) },
            text = { Text("This song is already included in the current library playback queue. Custom queue ordering is not implemented yet.", color = Muted) },
            confirmButton = {
                TextButton(onClick = { showQueueNotice = false }) { Text("OK", color = Accent) }
            }
        )
    }

    val safeDuration = playbackDuration.coerceAtLeast(0L)
    val actualProgress = if (safeDuration > 0L) (playbackPosition.toFloat() / safeDuration).coerceIn(0f, 1f) else 0f
    val displayedProgress = draggingProgress ?: actualProgress

    Column(
        modifier.background(Night).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("⌄", color = Accent, fontSize = 27.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("LOCAL MUSIC", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showOptions = true }) {
                Text("⋮", color = Muted, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.weight(0.65f))

        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .padding(horizontal = 8.dp)
                .background(GlassLight, RoundedCornerShape(22.dp))
                .padding(7.dp),
            contentAlignment = Alignment.Center
        ) {
            AlbumArt(artwork, Modifier.fillMaxSize())
        }

        Spacer(Modifier.height(26.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(trackName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(artistName, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFavourite) {
                Text(if (favourite) "♥" else "♡", color = if (favourite) Accent else Muted, fontSize = 23.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        Slider(
            value = displayedProgress,
            onValueChange = { draggingProgress = it },
            onValueChangeFinished = {
                draggingProgress?.let { fraction ->
                    if (safeDuration > 0L) onSeek((fraction * safeDuration).toLong())
                }
                draggingProgress = null
            },
            enabled = safeDuration > 0L,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = GlassLight,
                disabledThumbColor = Muted,
                disabledActiveTrackColor = Accent
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(if (draggingProgress != null && safeDuration > 0L) (displayedProgress * safeDuration).toLong() else playbackPosition), color = Muted, fontSize = 11.sp)
            Text(formatPlaybackTime(safeDuration), color = Muted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onShuffle) {
                Text("⤨", color = if (shuffleEnabled) Accent else Muted, fontSize = 27.sp, fontWeight = if (shuffleEnabled) FontWeight.Black else FontWeight.Normal)
            }
            IconButton(onClick = onPrevious) {
                Text("|◀", color = Color.White, fontSize = 24.sp)
            }
            Button(
                onClick = onTogglePlay,
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(if (isPlaying) "Ⅱ" else "▶", color = Night, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onNext) {
                Text("▶|", color = Color.White, fontSize = 24.sp)
            }
            IconButton(onClick = onRepeat) {
                Text(
                    when (repeatMode) { 1 -> "↻"; 2 -> "↻¹"; else -> "↻" },
                    color = if (repeatMode == 0) Muted else Accent,
                    fontSize = 24.sp,
                    fontWeight = if (repeatMode != 0) FontWeight.Black else FontWeight.Normal
                )
            }
        }

        Spacer(Modifier.weight(0.65f))
        Text(
            (when (repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" }) +
                "  ·  " + if (shuffleEnabled) "Shuffle on" else "Shuffle off",
            color = Muted, fontSize = 12.sp
        )
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun TrackArtwork(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes ->
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }
    }
    AlbumArt(bitmap, modifier)
}

@Composable
private fun AlbumArt(artwork: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF292236), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (artwork != null) {
            Image(
                bitmap = artwork.asImageBitmap(),
                contentDescription = "Album artwork",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("♫", color = Accent, fontSize = 42.sp)
        }
    }
}

@Composable
private fun TrackRowArtwork(track: LibraryTrack, artist: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(track.uri, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("▶", color = Accent, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TrackRow(title: String, artist: String, artwork: Bitmap?, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(artwork, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("▶", color = Accent, fontSize = 16.sp)
        }
    }
}

@Composable
private fun IconTextButton(symbol: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(symbol, color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<LibraryTrack>,
    favouriteUris: Set<String>,
    section: String,
    onSectionChange: (String) -> Unit,
    onToggleFavourite: (LibraryTrack) -> Unit,
    onOpenFiles: () -> Unit,
    onPlayTrack: (LibraryTrack) -> Unit
) {
    val sections = listOf("Songs", "Playlists", "Favourites", "Artists", "Albums")
    val displayedTracks = when (section) {
        "Favourites" -> tracks.filter { it.uri.toString() in favouriteUris }
        else -> tracks
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("YOUR LIBRARY", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("${tracks.size} tracks · organised your way", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(15.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            sections.take(3).forEach { item ->
                FilterChip(selected = section == item, onClick = { onSectionChange(item) }, label = { Text(item, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF302642), selectedLabelColor = Accent, labelColor = Muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            sections.drop(3).forEach { item ->
                FilterChip(selected = section == item, onClick = { onSectionChange(item) }, label = { Text(item) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF302642), selectedLabelColor = Accent, labelColor = Muted))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpenFiles, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("＋ Add music", color = Night)
            }
            OutlinedButton(onClick = { onSectionChange("Songs") }, modifier = Modifier.weight(1f)) { Text("All songs", color = Accent) }
        }
        Spacer(Modifier.height(12.dp))
        when (section) {
            "Playlists" -> {
                GlassCard(modifier = Modifier.clickable { onSectionChange("Favourites") }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(Color(0xFF302642), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text("♥", color = Accent, fontSize = 25.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Liked Songs", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${tracks.count { it.uri.toString() in favouriteUris }} saved tracks", color = Muted, fontSize = 12.sp)
                        }
                        Text("›", color = Accent, fontSize = 24.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                GlassCard {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(54.dp).background(GlassLight, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text("♫", color = Accent, fontSize = 25.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("All Songs", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Your complete local collection", color = Muted, fontSize = 12.sp)
                        }
                        Text("${tracks.size}", color = Accent)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Custom playlist creation isn't available in this version yet.", color = Muted, fontSize = 12.sp)
            }
            "Artists" -> {
                val artists = tracks.groupBy { it.artist }.toList().sortedBy { it.first.lowercase() }
                if (artists.isEmpty()) EmptyState("♬", "No artists yet", "Add local audio with artist tags to organise by artist.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(artists) { (artist, artistTracks) ->
                        GlassCard(modifier = Modifier.clickable { artistTracks.firstOrNull()?.let(onPlayTrack) }) {
                            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(48.dp).background(GlassLight, RoundedCornerShape(50)), contentAlignment = Alignment.Center) { Text("♫", color = Accent, fontSize = 22.sp) }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(artist, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${artistTracks.size} tracks", color = Muted, fontSize = 12.sp)
                                }
                                Text("▶", color = Accent)
                            }
                        }
                    }
                }
            }
            "Albums" -> {
                val albums = tracks.groupBy { it.album }.toList().sortedBy { it.first.lowercase() }
                if (albums.isEmpty()) EmptyState("▣", "No albums yet", "Add local audio with album tags to organise by album.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(albums) { (album, albumTracks) ->
                        GlassCard(modifier = Modifier.clickable { albumTracks.firstOrNull()?.let(onPlayTrack) }) {
                            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                TrackArtwork(albumTracks.first().uri, Modifier.size(50.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(album, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${albumTracks.first().artist} · ${albumTracks.size} tracks", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("▶", color = Accent)
                            }
                        }
                    }
                }
            }
            else -> {
                if (displayedTracks.isEmpty()) EmptyState(if (section == "Favourites") "♡" else "♫",
                    if (section == "Favourites") "No favourites yet" else "Your library is waiting",
                    if (section == "Favourites") "Tap the heart on a song to save it here." else "Add audio files from your phone to get started.")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(displayedTracks) { track ->
                        GlassCard(modifier = Modifier.clickable { onPlayTrack(track) }) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                TrackArtwork(track.uri, Modifier.size(50.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onToggleFavourite(track) }) {
                                    Text(if (track.uri.toString() in favouriteUris) "♥" else "♡", color = if (track.uri.toString() in favouriteUris) Accent else Muted, fontSize = 22.sp)
                                }
                                Text("▶", color = Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(query: String, onQueryChange: (String) -> Unit, tracks: List<LibraryTrack>, onPlayTrack: (LibraryTrack) -> Unit, onOpenFiles: () -> Unit) {
    val results = tracks.filter { it.name.contains(query.trim(), ignoreCase = true) || it.artist.contains(query.trim(), ignoreCase = true) || it.album.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        SectionTitle("SEARCH")
        Spacer(Modifier.height(8.dp))
        Text("Find music in your local collection", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Search your music") }, placeholder = { Text("Track name") },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, unfocusedBorderColor = GlassLight, focusedLabelColor = Accent, cursorColor = Accent)
        )
        Spacer(Modifier.height(16.dp))
        if (tracks.isEmpty()) {
            EmptyState("⌕", "No music to search", "Add audio files from your phone to get started.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenFiles, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("＋ Add music", color = Night) }
        } else if (results.isEmpty()) {
            EmptyState("⌕", "No matching tracks", "Try another title or search term.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(results) { track ->
                    GlassCard(modifier = Modifier.clickable { onPlayTrack(track) }) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackArtwork(track.uri, Modifier.size(48.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(track.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("▶", color = Accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(favouriteTrack: String?, onOpenLibrary: () -> Unit, onPlayCurrent: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        SectionTitle("YOUR PLAYLISTS")
        Spacer(Modifier.height(8.dp))
        Text("Your music, organised your way.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        GlassCard {
            Column(Modifier.padding(18.dp)) {
                Text("♡", color = Accent, fontSize = 30.sp)
                Spacer(Modifier.height(10.dp))
                Text("Liked music", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(favouriteTrack ?: "No liked track selected yet", color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (favouriteTrack != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onPlayCurrent) { Text("Play current track", color = Accent) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        GlassCard(modifier = Modifier.clickable(onClick = onOpenLibrary)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("♫", color = Accent, fontSize = 28.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("All music", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("Browse your local collection", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Playlist management and saved collections can be added in a later step.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xA61B1925)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x665D4D78))
    ) { Column(content = content) }
}

@Composable
private fun EmptyState(symbol: String, title: String, message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(symbol, color = Accent, fontSize = 46.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Muted, fontSize = 13.sp)
    }
}
