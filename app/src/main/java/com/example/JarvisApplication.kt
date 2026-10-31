package com.example

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import com.example.di.AppContainer
import com.example.services.RoutineTriggerReceiver

class JarvisApplication : Application() {

    lateinit var appContainer: AppContainer
        private set

    val container: AppContainer
        get() = appContainer

    private val headsetReceiver = RoutineTriggerReceiver()

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)

        // HEADSET_PLUG is a sticky runtime-only broadcast and must be registered dynamically
        runCatching {
            registerReceiver(
                headsetReceiver,
                IntentFilter(Intent.ACTION_HEADSET_PLUG)
            )
        }
    }
}
