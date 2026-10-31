package com.example.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.model.OrbState
import com.example.ui.components.FloatingGlassNavBar
import com.example.ui.screens.ActivityLogScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.PermissionsScreen
import com.example.ui.screens.RoutinesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.ActivityLogViewModel
import com.example.ui.viewmodel.HomeViewModel
import com.example.ui.viewmodel.JarvisViewModelFactory
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.PermissionsViewModel
import com.example.ui.viewmodel.RoutinesViewModel
import com.example.ui.viewmodel.SettingsViewModel

object JarvisRoutes {
    const val ROUTE_LOGIN = "login"
    const val ROUTE_HOME = "home"
    const val ROUTE_ROUTINES = "routines"
    const val ROUTE_PERMISSIONS = "permissions"
    const val ROUTE_MEMORY = "memory"
    const val ROUTE_ACTIVITY_LOG = "activity_log"
    const val ROUTE_SETTINGS = "settings"
}

@Composable
fun JarvisRootApp(
    factory: JarvisViewModelFactory
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: JarvisRoutes.ROUTE_HOME

    val homeViewModel: HomeViewModel = viewModel(factory = factory)
    val routinesViewModel: RoutinesViewModel = viewModel(factory = factory)
    val permissionsViewModel: PermissionsViewModel = viewModel(factory = factory)
    val memoryViewModel: MemoryViewModel = viewModel(factory = factory)
    val activityLogViewModel: ActivityLogViewModel = viewModel(factory = factory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

    val orbState by homeViewModel.orbState.collectAsStateWithLifecycle()
    var authStatusMessage by remember { mutableStateOf<String?>(null) }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            homeViewModel.startPushToTalk()
        } else {
            navController.navigate(JarvisRoutes.ROUTE_PERMISSIONS) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            NavHost(
                navController = navController,
                startDestination = JarvisRoutes.ROUTE_HOME,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(JarvisRoutes.ROUTE_LOGIN) {
                    LoginScreen(
                        statusMessage = authStatusMessage,
                        onLoginWithEmail = { email, password ->
                            settingsViewModel.signInWithEmail(email, password) { ok, msg ->
                                authStatusMessage = msg
                                if (ok) {
                                    navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                                }
                            }
                        },
                        onCreateAccountWithEmail = { email, password ->
                            settingsViewModel.signUpWithEmail(email, password) { ok, msg ->
                                authStatusMessage = msg
                                if (ok) {
                                    navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                                }
                            }
                        },
                        onForgotPassword = { email ->
                            settingsViewModel.sendPasswordReset(email) { _, msg ->
                                authStatusMessage = msg
                            }
                        },
                        onContinueWithGoogle = {
                            authStatusMessage = "Configure Google Sign-In Web Client ID in google-services.json or use Email/Offline mode."
                        },
                        onContinueOffline = {
                            navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_HOME) {
                    HomeScreen(
                        viewModel = homeViewModel,
                        onNavigateToPermissions = {
                            navController.navigate(JarvisRoutes.ROUTE_PERMISSIONS) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_ROUTINES) {
                    RoutinesScreen(
                        viewModel = routinesViewModel,
                        onBackToHome = {
                            navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_PERMISSIONS) {
                    PermissionsScreen(
                        viewModel = permissionsViewModel,
                        onBackToHome = {
                            if (!navController.popBackStack()) {
                                navController.navigate(JarvisRoutes.ROUTE_HOME)
                            }
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_MEMORY) {
                    MemoryScreen(
                        viewModel = memoryViewModel,
                        onBackToHome = {
                            navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_ACTIVITY_LOG) {
                    ActivityLogScreen(
                        viewModel = activityLogViewModel,
                        onBackToHome = {
                            if (!navController.popBackStack()) {
                                navController.navigate(JarvisRoutes.ROUTE_HOME)
                            }
                        }
                    )
                }
                composable(JarvisRoutes.ROUTE_SETTINGS) {
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onBackToHome = {
                            navController.popBackStack(JarvisRoutes.ROUTE_HOME, inclusive = false)
                        },
                        onNavigateToPermissions = {
                            navController.navigate(JarvisRoutes.ROUTE_PERMISSIONS) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToActivityLog = {
                            navController.navigate(JarvisRoutes.ROUTE_ACTIVITY_LOG) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToLogin = {
                            navController.navigate(JarvisRoutes.ROUTE_LOGIN) {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }

            if (currentRoute != JarvisRoutes.ROUTE_LOGIN) {
                val activeTabRoute = when (currentRoute) {
                    JarvisRoutes.ROUTE_PERMISSIONS, JarvisRoutes.ROUTE_ACTIVITY_LOG -> JarvisRoutes.ROUTE_SETTINGS
                    else -> currentRoute
                }
                FloatingGlassNavBar(
                    currentRoute = activeTabRoute,
                    isListening = orbState == OrbState.LISTENING,
                    onSelectRoute = { targetRoute ->
                        if (currentRoute != targetRoute) {
                            navController.navigate(targetRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onCenterMicTap = {
                        if (currentRoute != JarvisRoutes.ROUTE_HOME) {
                            navController.navigate(JarvisRoutes.ROUTE_HOME) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        when (orbState) {
                            OrbState.SPEAKING -> homeViewModel.bargeIn()
                            OrbState.LISTENING -> homeViewModel.stopPushToTalk()
                            else -> {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    homeViewModel.startPushToTalk()
                                } else {
                                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                )
            }
        }
    }
}
