package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.OemVendorGuide
import com.example.domain.model.PermissionItemStatus
import com.example.domain.model.PermissionKey
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GradientButton
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusChip
import com.example.ui.theme.Lavender
import com.example.ui.theme.Lime
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Warning
import com.example.ui.viewmodel.PermissionsViewModel

@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel,
    onBackToHome: () -> Unit
) {
    BackHandler(onBack = onBackToHome)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissions by viewModel.permissionsList.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val oemGuides = viewModel.oemVendorGuides

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissionsStatus()
    }

    val grantedCount = permissions.count { it.isGranted }

    AuroraBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$grantedCount of ${permissions.size} granted",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.permissions_header),
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                    }
                    IconButton(
                        onClick = { viewModel.refreshPermissionsStatus() },
                        modifier = Modifier.testTag("refresh_permissions_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh permissions",
                            tint = Lavender
                        )
                    }
                }
            }

            if (!settings.onboardingCompleted) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.permissions_subheader),
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        GradientButton(
                            text = "Continue to JARVIS",
                            onClick = {
                                viewModel.markOnboardingComplete()
                                onBackToHome()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "complete_onboarding_button"
                        )
                    }
                }
            }

            items(permissions, key = { it.key.name }) { item ->
                PermissionStepCard(
                    item = item,
                    onRequestAction = {
                        handlePermissionAction(
                            context = context,
                            key = item.key,
                            runtimeLauncher = { perm -> runtimePermLauncher.launch(perm) }
                        )
                    }
                )
            }

            item {
                SectionHeader(title = "OEM battery & background reliability")
            }

            items(oemGuides, key = { it.vendorName }) { guide ->
                OemBatteryGuideCard(
                    guide = guide,
                    onOpenAppDetails = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionStepCard(
    item: PermissionItemStatus,
    onRequestAction: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.titleEn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = item.titleHi,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Lavender
                    )
                }

                StatusChip(
                    label = if (item.isGranted) "Granted" else "Needed",
                    dotColor = if (item.isGranted) Lime else Warning
                )
            }

            Text(
                text = item.rationaleEn,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = item.rationaleHi,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )

            if (!item.isGranted) {
                Spacer(modifier = Modifier.height(4.dp))
                GradientButton(
                    text = "Grant access · अनुमति दें",
                    onClick = onRequestAction,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "grant_perm_${item.key.name.lowercase()}"
                )
            }
        }
    }
}

@Composable
private fun OemBatteryGuideCard(
    guide: OemVendorGuide,
    onOpenAppDetails: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = guide.vendorName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = guide.osName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Lavender
                    )
                }
                GhostButton(
                    text = "App info",
                    onClick = onOpenAppDetails
                )
            }
            Text(
                text = guide.stepsEn,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = guide.stepsHi,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted
            )
        }
    }
}

private fun handlePermissionAction(
    context: Context,
    key: PermissionKey,
    runtimeLauncher: (String) -> Unit
) {
    when (key) {
        PermissionKey.RECORD_AUDIO -> runtimeLauncher(Manifest.permission.RECORD_AUDIO)

        PermissionKey.POST_NOTIFICATIONS -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runtimeLauncher(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        PermissionKey.CALL_PHONE -> runtimeLauncher(Manifest.permission.CALL_PHONE)

        PermissionKey.READ_CONTACTS -> runtimeLauncher(Manifest.permission.READ_CONTACTS)

        PermissionKey.SYSTEM_ALERT_WINDOW -> {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        PermissionKey.ACCESSIBILITY_SERVICE -> {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        PermissionKey.NOTIFICATION_LISTENER -> {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        PermissionKey.BATTERY_OPTIMIZATION -> {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
        }
    }
}
