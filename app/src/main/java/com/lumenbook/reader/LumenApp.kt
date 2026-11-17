package com.lumenbook.reader

import android.app.Application
import com.lumenbook.reader.data.AppContainer

class LumenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
