package com.example

import android.app.Application
import com.example.di.AppContainer
import com.example.di.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class JarvisApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            runCatching {
                container.flavorEnvironment.seedDemoDataIfDev(
                    memoryRepository = container.memoryRepository,
                    routineRepository = container.routineRepository
                )
            }
        }
    }
}
