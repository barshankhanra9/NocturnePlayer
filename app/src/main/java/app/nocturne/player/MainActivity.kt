
package app.nocturne.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF0B0B10)
private val Glass = Color(0xFF191922)
private val GlassLight = Color(0xFF242431)
private val Accent = Color(0xFFB69CFF)
private val Muted = Color(0xFFA5A3B2)

private enum class PlayerTab(val label: String, val symbol: String) {
    HOME("Home", "⌂"),
    LIBRARY("Library", "♫"),
    SEARCH("Search", "⌕"),
    PLAYLISTS("Playlists", "▤")
}

private data class LibraryTrack(
    val name: String,
    val uri: Uri
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var selectedTab by remember { mutableStateOf(PlayerTab.HOME) }
            var selectedUri by remember { mutableStateOf<Uri?>(null) }
            var trackName by remember { mutableStateOf("Nothing playing") }
            var streamUrl by remember { mutableStateOf("") }
            var favourite by remember { mutableStateOf(false) }
            var searchQuery by remember { mutableStateOf("") }
            val library = remember { mutableStateListOf<LibraryTrack>() }

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }

                    val name = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?.substringAfterLast(':')
                        ?.ifBlank { "Local audio" }
                        ?: "Local audio"

                    if (library.none { it.uri == uri }) {
                        library.add(LibraryTrack(name, uri))
                    }

                    selectedUri = uri
                    trackName = name
                    playAudio(uri.toString())
                    selectedTab = PlayerTab.HOME
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    onPrimary = Night,
                    background = Night,
                    surface = Glass,
                    onSurface = Color.White,
                    secondary = Accent
                )
            ) {
                Scaffold(
                    containerColor = Night,
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color(0xFF15151D),
                            contentColor = Accent
                        ) {
                            PlayerTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    icon = {
                                        Text(
                                            tab.symbol,
                                            fontSize = 23.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    },
                                    label = {
                                        Text(
                                            tab.label,
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Accent,
                                        selectedTextColor = Accent,
                                        indicatorColor = Color(0xFF302642),
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = Muted
                                    )
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Night)
                            .padding(padding)
                    ) {
                        when (selectedTab) {
                            PlayerTab.HOME -> {
                                HomeScreen(
                                    trackName = trackName,
                                    favourite = favourite,
                                    streamUrl = streamUrl,
                                    onStreamUrlChange = { streamUrl = it },
                                    onFavourite = {
                                        favourite = !favourite
                                    },
                                    onPlay = {
                                        selectedUri?.let {
                                            playAudio(it.toString())
                                        }
                                    },
                                    onStop = {
                                        playAudio("")
                                        selectedUri = null
                                        trackName = "Nothing playing"
                                    },
                                    onOpenLibrary = {
                                        selectedTab = PlayerTab.LIBRARY
                                    },
                                    onPlayStream = {
                                        val url = streamUrl.trim()
                                        if (
                                            url.startsWith("https://") ||
                                            url.startsWith("http://")
                                        ) {
                                            selectedUri = null
                                            trackName = url.substringAfterLast('/')
                                                .substringBefore('?')
                                                .ifBlank { "Online stream" }
                                            playAudio(url)
                                        }
                                    }
                                )
                            }

                            PlayerTab.LIBRARY -> {
                                LibraryScreen(
                                    tracks = library,
                                    onOpenFiles = {
                                        picker.launch(arrayOf("audio/*"))
                                    },
                                    onPlayTrack = { track ->
                                        selectedUri = track.uri
                                        trackName = track.name
                                        playAudio(track.uri.toString())
                                        selectedTab = PlayerTab.HOME
                                    }
                                )
                            }

                            PlayerTab.SEARCH -> {
                                SearchScreen(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    tracks = library,
                                    onPlayTrack = { track ->
                                        selectedUri = track.uri
                                        trackName = track.name
                                        playAudio(track.uri.toString())
                                        selectedTab = PlayerTab.HOME
                                    },
                                    onOpenFiles = {
                                        picker.launch(arrayOf("audio/*"))
                                    }
                                )
                            }

                            PlayerTab.PLAYLISTS -> {
                                PlaylistsScreen(
                                    favouriteTrack = if (favourite) trackName else null,
                                    onOpenLibrary = {
                                        selectedTab = PlayerTab.LIBRARY
                                    },
                                    onPlayCurrent = {
                                        selectedUri?.let {
                                            playAudio(it.toString())
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun playAudio(uri: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY
            putExtra(PlaybackService.EXTRA_URI, uri)
        }
        startService(intent)
    }
}

@Composable
private fun HomeScreen(
    trackName: String,
    favourite: Boolean,
    streamUrl: String,
    onStreamUrlChange: (String) -> Unit,
    onFavourite: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlayStream: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 22.dp,
            bottom = 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column {
                Text(
                    "NOCTURNE",
                    color = Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your music.\nYour night.",
                    color = Color.White,
                    fontSize = 34.sp,
                    lineHeight = 39.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A quieter place for your music.",
                    color = Muted,
                    fontSize = 14.sp
                )
            }
        }

        item {
            GlassCard {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .background(
                                Color(0xFF292236),
                                RoundedCornerShape(22.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "♫",
                            color = Accent,
                            fontSize = 86.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        "NOW PLAYING",
                        color = Accent,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        trackName,
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onFavourite) {
                            Text(
                                if (favourite) "♥" else "♡",
                                color = Accent,
                                fontSize = 28.sp
                            )
                        }

                        Button(
                            onClick = onPlay,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Accent
                            )
                        ) {
                            Text("▶  Play", color = Night)
                        }

                        TextButton(onClick = onStop) {
                            Text("■ Stop", color = Color.White)
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("YOUR MUSIC")

            Spacer(Modifier.height(10.dp))

            GlassCard(
                modifier = Modifier.clickable(onClick = onOpenLibrary)
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("＋", color = Accent, fontSize = 32.sp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Open local music",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Browse audio files on your phone",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("ONLINE STREAM")

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = streamUrl,
                onValueChange = onStreamUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Direct audio URL") },
                placeholder = { Text("https://…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri
                ),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = GlassLight,
                    focusedLabelColor = Accent,
                    cursorColor = Accent
                )
            )

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = onPlayStream,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent
                )
            ) {
                Text(
                    "Play online stream",
                    color = Night,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Use a direct audio-file or stream URL. This is not a music catalogue search.",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<LibraryTrack>,
    onOpenFiles: () -> Unit,
    onPlayTrack: (LibraryTrack) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(26.dp))
        SectionTitle("YOUR LIBRARY")
        Spacer(Modifier.height(6.dp))
        Text(
            "Music selected from your device",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(18.dp))

        Button(
            onClick = onOpenFiles,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent
            )
        ) {
            Text("＋  Add music", color = Night)
        }

        Spacer(Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            EmptyState(
                symbol = "♫",
                title = "Your library is waiting",
                message = "Add audio files from your phone to get started."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tracks.size) { index ->
                    val track = tracks[index]
                    GlassCard(
                        modifier = Modifier.clickable {
                            onPlayTrack(track)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("♫", color = Accent, fontSize = 26.sp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                track.name,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("▶", color = Accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    tracks: List<LibraryTrack>,
    onPlayTrack: (LibraryTrack) -> Unit,
    onOpenFiles: () -> Unit
) {
    val results = tracks.filter {
        it.name.contains(query.trim(), ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        SectionTitle("SEARCH")
        Spacer(Modifier.height(8.dp))
        Text(
            "Find music in your local collection",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search your music") },
            placeholder = { Text("Track name") },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = GlassLight,
                focusedLabelColor = Accent,
                cursorColor = Accent
            )
        )

        Spacer(Modifier.height(16.dp))

        if (tracks.isEmpty()) {
            EmptyState(
                symbol = "⌕",
                title = "No music to search",
                message = "Add audio files from your phone to get started."
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onOpenFiles,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("＋ Add music", color = Night)
            }
        } else if (results.isEmpty()) {
            EmptyState(
                symbol = "⌕",
                title = "No matching tracks",
                message = "Try another title or search term."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(results.size) { index ->
                    val track = results[index]
                    GlassCard(
                        modifier = Modifier.clickable { onPlayTrack(track) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("♫", color = Accent, fontSize = 26.sp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                track.name,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("▶", color = Accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsScreen(
    favouriteTrack: String?,
    onOpenLibrary: () -> Unit,
    onPlayCurrent: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        SectionTitle("YOUR PLAYLISTS")
        Spacer(Modifier.height(8.dp))
        Text(
            "Your music, organised your way.",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(22.dp))

        GlassCard {
            Column(Modifier.padding(18.dp)) {
                Text("♡", color = Accent, fontSize = 30.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Liked music",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    favouriteTrack ?: "No liked track selected yet",
                    color = Muted,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (favouriteTrack != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onPlayCurrent) {
                        Text("Play current track", color = Accent)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        GlassCard(
            modifier = Modifier.clickable(onClick = onOpenLibrary)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("♫", color = Accent, fontSize = 28.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "All music",
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Browse your local collection",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Playlist management and saved collections can be added in a later step.",
            color = Muted,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp
    )
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Glass
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(content = content)
    }
}

@Composable
private fun EmptyState(
    symbol: String,
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(symbol, color = Accent, fontSize = 46.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = Muted,
            fontSize = 13.sp
        )
    }
}
