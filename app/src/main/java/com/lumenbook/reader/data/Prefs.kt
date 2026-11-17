package com.lumenbook.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 阅读页的排版参数，正文用同一套（Books 里叫「字体与排版」）。 */
data class ReaderStyle(
    val fontSize: Int = 18,
    val lineHeight: Float = 1.8f,
    val margin: Int = 32,
) {
    companion object {
        val FONT_RANGE = 13f..28f
        val LINE_RANGE = 1.3f..2.4f
        val MARGIN_RANGE = 12f..72f
    }
}

/** 应用设置。外观、阅读排版都往这里扩。 */
class Prefs(private val context: Context) {

    val appearance: Flow<Appearance> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_APPEARANCE]?.let { name -> runCatching { Appearance.valueOf(name) }.getOrNull() }
            ?: Appearance.SYSTEM
    }

    val readerStyle: Flow<ReaderStyle> = context.settingsDataStore.data.map { prefs ->
        ReaderStyle(
            fontSize = prefs[KEY_FONT_SIZE] ?: 18,
            lineHeight = prefs[KEY_LINE_HEIGHT] ?: 1.8f,
            margin = prefs[KEY_MARGIN] ?: 32,
        )
    }

    /** 自定义页面背景图的绝对路径（存在 filesDir/backgrounds/ 下），null = 用主题纸色。 */
    val backgroundImage: Flow<String?> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_IMAGE]?.takeIf { it.isNotBlank() }
    }

    suspend fun setBackgroundImage(path: String?) {
        context.settingsDataStore.edit {
            if (path.isNullOrBlank()) it.remove(KEY_BACKGROUND_IMAGE) else it[KEY_BACKGROUND_IMAGE] = path
        }
    }

    suspend fun setAppearance(value: Appearance) {
        context.settingsDataStore.edit { it[KEY_APPEARANCE] = value.name }
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

    private companion object {
        val KEY_APPEARANCE = stringPreferencesKey("appearance")
        val KEY_FONT_SIZE = intPreferencesKey("reader_font_size")
        val KEY_LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val KEY_MARGIN = intPreferencesKey("reader_margin")
        val KEY_BACKGROUND_IMAGE = stringPreferencesKey("background_image")
    }
}
