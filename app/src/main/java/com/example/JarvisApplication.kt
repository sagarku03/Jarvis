package com.example

import android.app.Application
import com.example.di.AppContainer

class JarvisApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    val container: AppContainer
        get() = appContainer

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}
