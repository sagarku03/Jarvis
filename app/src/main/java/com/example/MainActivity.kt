package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.di.AndroidEntryPoint
import com.example.di.AppContainer
import com.example.services.BootCompletedReceiver
import com.example.services.VoiceForegroundService
import com.example.ui.navigation.JarvisRootApp
import com.example.ui.theme.JarvisTheme
import com.example.ui.viewmodel.JarvisViewModelFactory

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appContainer = (application as? JarvisApplication)?.appContainer
            ?: AppContainer(applicationContext)
        val viewModelFactory = JarvisViewModelFactory(applicationContext, appContainer)

        handleIncomingVoiceIntents(intent)

        setContent {
            JarvisTheme(darkTheme = true) {
                JarvisRootApp(factory = viewModelFactory)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVoiceIntents(intent)
    }

    private fun handleIncomingVoiceIntents(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(BootCompletedReceiver.EXTRA_RESUME_WAKE_WORD_FROM_BOOT, false)) {
            intent.removeExtra(BootCompletedReceiver.EXTRA_RESUME_WAKE_WORD_FROM_BOOT)
            VoiceForegroundService.startService(this)
        }
    }
}
