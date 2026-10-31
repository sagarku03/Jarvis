package com.example.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraFloatingPillNav
import com.example.ui.screens.ActivityLogScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SetupGateScreen
import com.example.ui.viewmodel.ActivityLogViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.JarvisViewModelFactory
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.PermissionsViewModel
import com.example.ui.viewmodel.RoutinesViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SetupViewModel

enum class JarvisDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    HOME("home", R.string.nav_home, Icons.Default.Home, "nav_home"),
    ACTIVITY("activity", R.string.nav_activity, Icons.Default.History, "nav_activity"),
    MEMORY("memory", R.string.nav_memory, Icons.Default.Memory, "nav_memory"),
    ROUTINES("routines", R.string.nav_routines, Icons.Default.Schedule, "nav_routines"),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings, "nav_settings")
}

@Composable
fun JarvisRootApp(
    factory: JarvisViewModelFactory
) {
    val setupViewModel: SetupViewModel = viewModel(factory = factory)
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val activityLogViewModel: ActivityLogViewModel = viewModel(factory = factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = factory)
    val routinesViewModel: RoutinesViewModel = viewModel(factory = factory)
    val permissionsViewModel: PermissionsViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    val isGateUnlocked by setupViewModel.isGateUnlocked.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(JarvisDestination.HOME) }
    var showPermissionsScreen by rememberSaveable { mutableStateOf(false) }

    // Hard setup gate: if setupComplete is false or encrypted key is missing, block all tabs
    if (!isGateUnlocked) {
        SetupGateScreen(
            setupViewModel = setupViewModel,
            permissionsViewModel = permissionsViewModel
        )
        return
    }

    if (showPermissionsScreen) {
        BackHandler { showPermissionsScreen = false }
        PermissionsScreen(
            viewModel = permissionsViewModel,
            onNavigateBack = { showPermissionsScreen = false }
        )
        return
    }

    if (currentTab != JarvisDestination.HOME) {
        BackHandler { currentTab = JarvisDestination.HOME }
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                AuroraFloatingPillNav(
                    currentDestination = currentTab,
                    onSelectDestination = { currentTab = it }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    JarvisDestination.HOME -> HomeScreen(
                        viewModel = homeViewModel,
                        onOpenPermissions = { showPermissionsScreen = true },
                        onOpenSettings = { currentTab = JarvisDestination.SETTINGS }
                    )

                    JarvisDestination.ACTIVITY -> ActivityLogScreen(
                        viewModel = activityLogViewModel
                    )

                    JarvisDestination.MEMORY -> MemoryScreen(
                        viewModel = memoryViewModel
                    )

                    JarvisDestination.ROUTINES -> RoutinesScreen(
                        viewModel = routinesViewModel
                    )

                    JarvisDestination.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onOpenPermissionsScreen = { showPermissionsScreen = true },
                        onKeyRemoved = {
                            setupViewModel.refreshPermissionAndKeyState()
                        }
                    )
                }
            }
        }
    }
}
