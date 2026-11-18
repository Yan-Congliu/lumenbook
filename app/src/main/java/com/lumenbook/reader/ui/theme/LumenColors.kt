package com.lumenbook.reader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lumenbook.reader.data.ReaderTheme

/**
 * 鎏书的配色。刻意不用 Material 的 dynamic color：书库和阅读页要保持「纸张感」，
 * 跟着壁纸变色调就不像书了。
 *
 * 以后要加「换背景色/背景图」时，从这里的 page/surface 派生，见 [LumenBackground]。
 */
@Immutable
data class LumenColors(
    val isDark: Boolean,
    /** 页面底色（书库、阅读页）*/
    val page: Color,
    /** 顶栏、底栏、卡片这类浮在页面上的面 */
    val surface: Color,
    /** 比 surface 再低一层的面（输入框、分段控件底槽）*/
    val surfaceMuted: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 强调色：进度、选中态、按钮 */
    val accent: Color,
    val separator: Color,
)

/** 浅色：米黄纸。*/
val LightLumenColors = LumenColors(
    isDark = false,
    page = Color(0xFFF7F4EF),
    surface = Color(0xFFFCFAF6),
    surfaceMuted = Color(0xFFEFEAE0),
    text = Color(0xFF1A1917),
    textSecondary = Color(0xFF6E6A63),
    textTertiary = Color(0xFF9A958C),
    accent = Color(0xFFA9764D),
    separator = Color(0xFFE5DFD3),
)

/** 深色：墨黑纸，不是纯黑，阅读时对眼睛友好。*/
val DarkLumenColors = LumenColors(
    isDark = true,
    page = Color(0xFF1B1917),
    surface = Color(0xFF232120),
    surfaceMuted = Color(0xFF2C2927),
    text = Color(0xFFEDE7DD),
    textSecondary = Color(0xFFA29A8F),
    textTertiary = Color(0xFF6F6961),
    accent = Color(0xFFC99B6C),
    separator = Color(0xFF34302B),
)

/** 白纸：纸白偏一点点暖，不像纯白那么刺眼。*/
val WhiteLumenColors = LumenColors(
    isDark = false,
    page = Color(0xFFFDFCFA),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF2F1EE),
    text = Color(0xFF151413),
    textSecondary = Color(0xFF6B6660),
    textTertiary = Color(0xFF9C968D),
    accent = Color(0xFFA9764D),
    separator = Color(0xFFE7E3DC),
)

/** 护眼绿：淡淡的豆绿纸。*/
val GreenLumenColors = LumenColors(
    isDark = false,
    page = Color(0xFFE7EFDD),
    surface = Color(0xFFEFF4E9),
    surfaceMuted = Color(0xFFDDE7D2),
    text = Color(0xFF1F2A1C),
    textSecondary = Color(0xFF5E6B56),
    textTertiary = Color(0xFF8B9781),
    accent = Color(0xFF4F7A46),
    separator = Color(0xFFD3DEC8),
)

/** 灰蓝：冷调的纸。*/
val BlueLumenColors = LumenColors(
    isDark = false,
    page = Color(0xFFE6EDF3),
    surface = Color(0xFFEEF3F8),
    surfaceMuted = Color(0xFFDBE4ED),
    text = Color(0xFF1A2430),
    textSecondary = Color(0xFF5B6875),
    textTertiary = Color(0xFF8A97A5),
    accent = Color(0xFF3F6B8F),
    separator = Color(0xFFD2DDE7),
)

/** 纯黑：OLED 省电、夜里最暗。*/
val BlackLumenColors = LumenColors(
    isDark = true,
    page = Color(0xFF000000),
    surface = Color(0xFF0B0B0B),
    surfaceMuted = Color(0xFF171717),
    text = Color(0xFFE9E9E9),
    textSecondary = Color(0xFF9A9A9A),
    textTertiary = Color(0xFF6A6A6A),
    accent = Color(0xFFC99B6C),
    separator = Color(0xFF262626),
)

/** 按主题 + 系统明暗算出实际配色。SYSTEM 跟着系统走，其余主题跟系统无关。*/
fun lumenColorsFor(theme: ReaderTheme, systemDark: Boolean): LumenColors = when (theme) {
    ReaderTheme.SYSTEM -> if (systemDark) DarkLumenColors else LightLumenColors
    ReaderTheme.PAPER -> LightLumenColors
    ReaderTheme.WHITE -> WhiteLumenColors
    ReaderTheme.GREEN -> GreenLumenColors
    ReaderTheme.BLUE -> BlueLumenColors
    ReaderTheme.INK -> DarkLumenColors
    ReaderTheme.BLACK -> BlackLumenColors
}

val LocalLumenColors = staticCompositionLocalOf { LightLumenColors }

/** 给生成式封面用的两段渐变配色（浅色/深色背景上都能看清）。*/
val CoverPalette: List<Pair<Color, Color>> = listOf(
    Color(0xFF2C3A52) to Color(0xFF455C7B), // 藏青
    Color(0xFF5E7A8A) to Color(0xFF8FA9B4), // 雾蓝
    Color(0xFF7B3B22) to Color(0xFFA85F3E), // 陶土
    Color(0xFF8A6B14) to Color(0xFFB99A3C), // 芥黄
    Color(0xFF1F4F45) to Color(0xFF39766A), // 松绿
    Color(0xFF6E5B4B) to Color(0xFF99836F), // 灰褐
    Color(0xFF4A3359) to Color(0xFF715390), // 紫罗兰
    Color(0xFF4D5A2E) to Color(0xFF74874A), // 橄榄
    Color(0xFF75303A) to Color(0xFFA2525E), // 砖红
    Color(0xFF2F3C4C) to Color(0xFF556577), // 石墨蓝
    Color(0xFF1D3B57) to Color(0xFF39638A), // 深海
    Color(0xFF6B4A2E) to Color(0xFF9C7248), // 焦糖
)

fun coverPaletteFor(key: String): Pair<Color, Color> {
    var h = 0
    for (ch in key) h = h * 31 + ch.code
    return CoverPalette[Math.floorMod(h, CoverPalette.size)]
}
