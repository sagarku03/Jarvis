package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.di.AndroidEntryPoint
import com.example.di.AppContainer
import com.example.ui.navigation.JarvisRootApp
import com.example.ui.theme.JarvisTheme
import com.example.ui.viewmodel.JarvisViewModelFactory

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as? JarvisApplication)?.container
            ?: AppContainer(applicationContext)
        val viewModelFactory = JarvisViewModelFactory(applicationContext, appContainer)

        setContent {
            JarvisTheme(darkTheme = true) {
                JarvisRootApp(factory = viewModelFactory)
            }
        }
    }
}
