package com.lumenbook.reader.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
