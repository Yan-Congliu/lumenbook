package com.lumenbook.reader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 阅读主题（纸色 + 字色一套）。SYSTEM = 跟随系统明暗，其余是固定主题。 */
enum class ReaderTheme(val label: String, val dark: Boolean) {
    SYSTEM("跟随系统", false),
    PAPER("米纸", false),
    WHITE("白纸", false),
    GREEN("护眼绿", false),
    BLUE("灰蓝", false),
    INK("墨黑", true),
    BLACK("纯黑", true),
    ;

    /** 底部色块预览色（浅色主题用纸色，深色主题用墨色）。 */
    val swatch: Long
        get() = when (this) {
            SYSTEM -> 0xFFF7F4EF
            PAPER -> 0xFFF7F4EF
            WHITE -> 0xFFFFFFFF
            GREEN -> 0xFFE7EFDD
            BLUE -> 0xFFE6EDF3
            INK -> 0xFF1B1917
            BLACK -> 0xFF000000
        }
}

/**
 * 字体族。用手机自带的字体，不打包字体文件（APK 只有 9 MB，塞几套中文字体会变 40 MB+）。
 * 各家 ROM 自带的中文字体不同，栈里放了常见名字，找不到就退回系统衬线/无衬线。
 */
enum class ReaderFont(val label: String, val stack: String) {
    SONG(
        "宋体",
        "\"Songti SC\", \"Noto Serif CJK SC\", \"Source Han Serif SC\", \"Noto Serif SC\", serif",
    ),
    HEI(
        "黑体",
        "\"PingFang SC\", \"Noto Sans CJK SC\", \"Source Han Sans SC\", \"Noto Sans SC\", sans-serif",
    ),
    KAI(
        "楷体",
        "KaiTi, \"Kaiti SC\", STKaiti, \"AR PL UKai CN\", \"Noto Serif CJK SC\", serif",
    ),
    FANG(
        "仿宋",
        "FangSong, \"FangSong_GB2312\", STFangsong, \"Noto Serif CJK SC\", serif",
    ),
    MONO("等宽", "\"Courier New\", monospace"),
}

/** 正文对齐方式。 */
enum class ReaderAlign(val label: String, val css: String) {
    JUSTIFY("两端对齐", "justify"),
    LEFT("左对齐", "left"),
}

/** 阅读页的排版参数，正文用同一套（Books 里叫「字体与排版」）。 */
data class ReaderStyle(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val margin: Int = 32,
    val font: ReaderFont = ReaderFont.SONG,
    val align: ReaderAlign = ReaderAlign.JUSTIFY,
    /** 首行缩进，单位 em；0 = 不缩进，中文书习惯 2。 */
    val indent: Int = 2,
) {
    companion object {
        val FONT_RANGE = 13f..28f
        val LINE_RANGE = 1.3f..2.4f
        val MARGIN_RANGE = 12f..72f
        val INDENT_RANGE = 0f..3f
    }
}

/** 书库外观。 */
data class LibraryStyle(
    val columns: Int = 3,
    /** 封面圆角，dp。 */
    val coverRadius: Int = 8,
    val showAuthor: Boolean = true,
) {
    companion object {
        val COLUMN_CHOICES = listOf(3, 4)
        val RADIUS_RANGE = 0f..18f
    }
}

/** 应用设置。外观、阅读排版都往这里扩。 */
class Prefs(private val context: Context) {

    val theme: Flow<ReaderTheme> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_THEME]?.let { name -> runCatching { ReaderTheme.valueOf(name) }.getOrNull() }
            ?: ReaderTheme.SYSTEM
    }

    val readerStyle: Flow<ReaderStyle> = context.settingsDataStore.data.map { prefs ->
        ReaderStyle(
            fontSize = prefs[KEY_FONT_SIZE] ?: 18,
            lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.8f,
            margin = prefs[KEY_MARGIN] ?: 32,
            font = prefs[KEY_FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() }
                ?: ReaderFont.SONG,
            align = prefs[KEY_ALIGN]?.let { runCatching { ReaderAlign.valueOf(it) }.getOrNull() }
                ?: ReaderAlign.JUSTIFY,
            indent = prefs[KEY_INDENT] ?: 2,
        )
    }

    val libraryStyle: Flow<LibraryStyle> = context.settingsDataStore.data.map { prefs ->
        LibraryStyle(
            columns = prefs[KEY_COLUMNS] ?: 3,
            coverRadius = prefs[KEY_COVER_RADIUS] ?: 8,
            showAuthor = prefs[KEY_SHOW_AUTHOR] ?: true,
        )
    }

    /** 自定义页面背景图的绝对路径（存在 filesDir/backgrounds/ 下），null = 用主题纸色。 */
    val backgroundImage: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_IMAGE]?.takeIf { it.isNotBlank() }
    }

    /** 背景图压暗程度 0..0.7，越大字越清楚、图越淡。 */
    val backgroundDim: Flow<Float> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_DIM] ?: 0.30f
    }

    suspend fun setBackgroundImage(path: String?) {
        context.settingsDataStore.edit {
            if (path.isNullOrBlank()) it.remove(KEY_BACKGROUND_IMAGE) else it[KEY_BACKGROUND_IMAGE] = path
        }
    }

    suspend fun setBackgroundDim(value: Float) {
        context.settingsDataStore.edit { it[KEY_BACKGROUND_DIM] = value.coerceIn(0f, 0.7f) }
    }

    suspend fun setTheme(value: ReaderTheme) {
        context.settingsDataStore.edit { it[KEY_THEME] = value.name }
    }

    suspend fun setFont(value: ReaderFont) {
        context.settingsDataStore.edit { it[KEY_FONT] = value.name }
    }

    suspend fun setAlign(value: ReaderAlign) {
        context.settingsDataStore.edit { it[KEY_ALIGN] = value.name }
    }

    suspend fun setIndent(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_INDENT] = value.coerceIn(
                ReaderStyle.INDENT_RANGE.start.toInt(),
                ReaderStyle.INDENT_RANGE.endInclusive.toInt(),
            )
        }
    }

    suspend fun setFontSize(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_FONT_SIZE] = value.coerceIn(
                ReaderStyle.FONT_RANGE.start.toInt(),
                ReaderStyle.FONT_RANGE.endInclusive.toInt(),
            )
        }
    }

    suspend fun setLineHeight(value: Float) {
        context.settingsDataStore.edit {
            it[KEY_LINE_HEIGHT] = value.coerceIn(
                ReaderStyle.LINE_RANGE.start,
                ReaderStyle.LINE_RANGE.endInclusive,
            )
        }
    }

    suspend fun setMargin(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_MARGIN] = value.coerceIn(
                ReaderStyle.MARGIN_RANGE.start.toInt(),
                ReaderStyle.MARGIN_RANGE.endInclusive.toInt(),
            )
        }
    }

    suspend fun setColumns(value: Int) {
        context.settingsDataStore.edit { it[KEY_COLUMNS] = if (value >= 4) 4 else 3 }
    }

    suspend fun setCoverRadius(value: Int) {
        context.settingsDataStore.edit {
            it[KEY_COVER_RADIUS] = value.coerceIn(0, LibraryStyle.RADIUS_RANGE.endInclusive.toInt())
        }
    }

    suspend fun setShowAuthor(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_AUTHOR] = value }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("reader_theme")
        val KEY_FONT = stringPreferencesKey("reader_font")
        val KEY_ALIGN = stringPreferencesKey("reader_align")
        val KEY_INDENT = intPreferencesKey("reader_indent")
        val KEY_FONT_SIZE = intPreferencesKey("reader_font_size")
        val KEY_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val KEY_MARGIN = intPreferencesKey("reader_margin")
        val KEY_COLUMNS = intPreferencesKey("library_columns")
        val KEY_COVER_RADIUS = intPreferencesKey("library_cover_radius")
        val KEY_SHOW_AUTHOR = booleanPreferencesKey("library_show_author")
        val KEY_BACKGROUND_IMAGE = stringPreferencesKey("background_image")
        val KEY_BACKGROUND_DIM = floatPreferencesKey("background_dim")
    }
}
