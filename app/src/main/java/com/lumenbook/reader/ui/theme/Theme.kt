package com.lumenbook.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** 书卷气：标题用衬线，正文和界面控件用系统无衬线。*/
private val LumenTypography: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
        displayMedium = t.displayMedium.copy(fontFamily = FontFamily.Serif),
        displaySmall = t.displaySmall.copy(fontFamily = FontFamily.Serif),
        headlineLarge = t.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = t.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = t.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
        titleSmall = t.titleSmall.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun LumenTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkLumenColors else LightLumenColors
    CompositionLocalProvider(LocalLumenColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = LumenTypography,
            content = content,
        )
    }
}

/** 按用户偏好 + 系统状态决定用哪套。*/
@Composable
fun shouldUseDarkTheme(appearance: com.lumenbook.reader.data.Appearance): Boolean = when (appearance) {
    com.lumenbook.reader.data.Appearance.SYSTEM -> isSystemInDarkTheme()
    com.lumenbook.reader.data.Appearance.LIGHT -> false
    com.lumenbook.reader.data.Appearance.DARK -> true
}

private fun LumenColors.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF241C14),
        secondary = accent,
        background = page,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = textSecondary,
        outline = separator,
        outlineVariant = separator,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        secondary = accent,
        background = page,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceMuted,
        onSurfaceVariant = textSecondary,
        outline = separator,
        outlineVariant = separator,
    )
}
