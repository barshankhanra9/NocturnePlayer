package app.nocturne.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NocturneColors = darkColorScheme(
    primary = Color(0xFFB9A7FF),
    onPrimary = Color(0xFF1A1230),
    secondary = Color(0xFF8D83B8),
    background = Color(0xFF0B0B10),
    surface = Color(0xFF14141C),
    onBackground = Color(0xFFF3F0FF),
    onSurface = Color(0xFFF3F0FF)
)

@Composable
fun NocturneTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NocturneColors,
        content = content
    )
}
