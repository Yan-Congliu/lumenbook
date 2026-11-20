package com.lumenbook.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenbook.reader.data.ReaderTheme

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

/** 全局圆角统一走 G2（曲率连续）形状，直边与圆角之间不再有曲率突变。 */
private val LumenShapes: Shapes = Shapes(
    extraSmall = SmoothRoundedCornerShape(4.dp),
    small = SmoothRoundedCornerShape(8.dp),
    medium = SmoothRoundedCornerShape(12.dp),
    large = SmoothRoundedCornerShape(16.dp),
    extraLarge = SmoothRoundedCornerShape(28.dp),
)

@Composable
fun LumenTheme(
    colors: LumenColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLumenColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = LumenTypography,
            shapes = LumenShapes,
            content = content,
        )
    }
}

/** 按主题 + 系统明暗决定用哪套配色。*/
@Composable
fun rememberLumenColors(theme: ReaderTheme): LumenColors =
    lumenColorsFor(theme, isSystemInDarkTheme())


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
