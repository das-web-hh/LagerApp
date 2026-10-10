package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Оформление: "light" (по умолчанию), "dark", "system". Хранится на телефоне и в настройках аккаунта. */
object Ui {
    var mode by mutableStateOf("light")

    fun load(ctx: Context) {
        mode = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "light") ?: "light"
    }

    fun set(ctx: Context, m: String) {
        if (m != "light" && m != "dark" && m != "system") return
        mode = m
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", m).apply()
    }
}

val BrandBlue = Color(0xFF016AFD)
val BrandBlueDark = Color(0xFF0A4FC4)
val BrandYellow = Color(0xFFFFC12E)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF001B4D),
    secondary = Color(0xFF475A82),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6FA),
    onSecondaryContainer = Color(0xFF0F1F3D),
    tertiary = Color(0xFFB57F00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBB5),
    onTertiaryContainer = Color(0xFF3A2800),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE4EAF6),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF8C98AE),
    outlineVariant = Color(0xFFCFD8E8),
    error = Color(0xFFD93025),
    onError = Color.White,
    errorContainer = Color(0xFFFADAD7),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = BrandBlue,
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF1F5F9),
    inversePrimary = Color(0xFF9DBBFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFE),
    surfaceContainer = Color(0xFFEFF3FA),
    surfaceContainerHigh = Color(0xFFE9EEF8),
    surfaceContainerHighest = Color(0xFFE3E9F5)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00205E),
    primaryContainer = Color(0xFF0A3FA8),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFFB3C5EC),
    onSecondary = Color(0xFF1B2F55),
    secondaryContainer = Color(0xFF2A3A5C),
    onSecondaryContainer = Color(0xFFDCE6FA),
    tertiary = Color(0xFFFFC94D),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF5A4000),
    onTertiaryContainer = Color(0xFFFFEBB5),
    background = Color(0xFF0E131C),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF141A24),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF263042),
    onSurfaceVariant = Color(0xFFAEB9CC),
    outline = Color(0xFF7C889E),
    outlineVariant = Color(0xFF334057),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0xFF8AB4FF),
    inverseSurface = Color(0xFFE6EAF2),
    inverseOnSurface = Color(0xFF1E293B),
    inversePrimary = BrandBlue,
    surfaceContainerLowest = Color(0xFF0A0E15),
    surfaceContainerLow = Color(0xFF121823),
    surfaceContainer = Color(0xFF18202C),
    surfaceContainerHigh = Color(0xFF1F2836),
    surfaceContainerHighest = Color(0xFF263042)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (Ui.mode) {
        "dark" -> true
        "system" -> isSystemInDarkTheme()
        else -> false
    }
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        var c: Context? = view.context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val window = (c as? Activity)?.window
        if (window != null) {
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = colors.background, content = content)
    }
}
