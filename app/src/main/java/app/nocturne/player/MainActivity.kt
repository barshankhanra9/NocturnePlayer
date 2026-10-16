
package app.nocturne.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF101014)
private val Panel = Color(0xFF1C1C24)
private val Accent = Color(0xFFB49AFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var selectedUri by remember { mutableStateOf<Uri?>(null) }
            var trackName by remember { mutableStateOf("Nothing playing") }
            var streamUrl by remember { mutableStateOf("") }
            var favourite by remember { mutableStateOf(false) }

            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    selectedUri = uri
                    trackName = uri.lastPathSegment
                        ?.substringAfterLast('/')
                        ?: "Local audio"
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    startPlayback(uri.toString())
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Night,
                    surface = Panel,
                    primary = Accent
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Night)
                        .padding(20.dp)
                ) {
                    Text(
                        "NOCTURNE",
                        color = Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your music. Your night.",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(22.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Panel
                        ),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .background(
                                        Color(0xFF292333),
                                        RoundedCornerShape(18.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("♫", color = Accent, fontSize = 76.sp)
                            }
                            Spacer(Modifier.height(18.dp))
                            Text(
                                trackName,
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "NOCTURNE PLAYER",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { favourite = !favourite }
                                ) {
                                    Text(
                                        if (favourite) "♥" else "♡",
                                        color = Accent,
                                        fontSize = 26.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (selectedUri != null) {
                                            startPlayback(selectedUri.toString())
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Accent
                                    )
                                ) {
                                    Text("▶ Play", color = Night)
                                }
                                TextButton(
                                    onClick = {
                                        startPlayback("")
                                        trackName = "Nothing playing"
                                        selectedUri = null
                                    }
                                ) {
                                    Text("■ Stop", color = Color.White)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "YOUR LIBRARY",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Panel
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                picker.launch(arrayOf("audio/*"))
                            }
                    ) {
                        Row(
                            Modifier.padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("＋", color = Accent, fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Open local music", color = Color.White)
                                Text(
                                    "Choose an audio file on your phone",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "ONLINE STREAM",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = streamUrl,
                        onValueChange = { streamUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Direct HTTPS audio URL") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val url = streamUrl.trim()
                            if (url.startsWith("https://")) {
                                selectedUri = null
                                trackName = url.substringAfterLast('/')
                                    .ifBlank { "Online stream" }
                                startPlayback(url)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Play online stream")
                    }

                    Spacer(Modifier.weight(1f))
                    Text(
                        "A quieter place for your music.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }

    private fun startPlayback(uri: String) {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY
            putExtra(PlaybackService.EXTRA_URI, uri)
        }
        startService(intent)
    }
}
