package it.trenirt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import it.trenirt.ui.theme.TreniColors as C

@Composable
fun TreniRTTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg,
            surface = C.card,
            primary = C.accent,
            onBackground = C.text,
            onSurface = C.text,
            onPrimary = C.bg,
            error = C.red,
            surfaceVariant = C.card,
            outline = C.border
        ),
        content = content
    )
}