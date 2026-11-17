package com.lumenbook.reader.data

import android.content.Context

/** 手写的依赖容器，全应用一个实例，由 LumenApp 创建。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val db: LibraryDb by lazy { LibraryDb(appContext) }
    val library: LibraryRepository by lazy { LibraryRepository(appContext, db) }
    val prefs: Prefs by lazy { Prefs(appContext) }
}
