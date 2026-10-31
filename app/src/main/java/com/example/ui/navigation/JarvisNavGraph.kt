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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.screens.ActivityLogScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.ActivityLogViewModel
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.JarvisViewModelFactory
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.PermissionsViewModel
import com.example.ui.viewmodel.RoutinesViewModel
import com.example.ui.viewmodel.SettingsViewModel

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
    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val activityLogViewModel: ActivityLogViewModel = viewModel(factory = factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = factory)
    val routinesViewModel: RoutinesViewModel = viewModel(factory = factory)
    val permissionsViewModel: PermissionsViewModel = viewModel(factory = factory)
    val authViewModel: AuthViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    var currentTab by rememberSaveable { mutableStateOf(JarvisDestination.HOME) }
    var showPermissionsScreen by rememberSaveable { mutableStateOf(false) }
    var showLoginScreen by rememberSaveable { mutableStateOf(false) }

    if (showPermissionsScreen) {
        BackHandler { showPermissionsScreen = false }
        PermissionsScreen(
            viewModel = permissionsViewModel,
            onNavigateBack = { showPermissionsScreen = false }
        )
        return
    }

    if (showLoginScreen) {
        BackHandler { showLoginScreen = false }
        LoginScreen(
            viewModel = authViewModel,
            onContinueToAssistant = { showLoginScreen = false }
        )
        return
    }

    if (currentTab != JarvisDestination.HOME) {
        BackHandler { currentTab = JarvisDestination.HOME }
    }

    Scaffold(
        containerColor = AuroraColors.BackgroundDeep,
        bottomBar = {
            NavigationBar(
                containerColor = AuroraColors.SurfacePrimary
            ) {
                JarvisDestination.entries.forEach { destination ->
                    val selected = currentTab == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = destination },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = stringResource(destination.labelRes)
                            )
                        },
                        label = {
                            Text(text = stringResource(destination.labelRes))
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AuroraColors.CyanGlow,
                            selectedTextColor = AuroraColors.TextPrimary,
                            indicatorColor = AuroraColors.ElectricViolet.copy(alpha = 0.22f),
                            unselectedIconColor = AuroraColors.TextSecondary,
                            unselectedTextColor = AuroraColors.TextSecondary
                        ),
                        modifier = Modifier.testTag(destination.testTag)
                    )
                }
            }
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
                    onOpenPermissions = { showPermissionsScreen = true }
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
                    onSignOut = {
                        authViewModel.signOut()
                        showLoginScreen = true
                    }
                )
            }
        }
    }
}
