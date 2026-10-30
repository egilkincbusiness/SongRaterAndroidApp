package com.songrater

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
    }
}
