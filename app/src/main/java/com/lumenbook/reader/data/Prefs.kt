package com.lumenbook.reader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 应用设置。阶段 2 只有外观一项，后面加背景、字号之类都往这里扩。 */
class Prefs(private val context: Context) {

    val appearance: Flow<Appearance> = context.settingsDataStore.data.map { prefs ->
        prefs[KEY_APPEARANCE]?.let { name -> runCatching { Appearance.valueOf(name) }.getOrNull() }
            ?: Appearance.SYSTEM
    }

    suspend fun setAppearance(value: Appearance) {
        context.settingsDataStore.edit { it[KEY_APPEARANCE] = value.name }
    }

    private companion object {
        val KEY_APPEARANCE = stringPreferencesKey("appearance")
    }
}
