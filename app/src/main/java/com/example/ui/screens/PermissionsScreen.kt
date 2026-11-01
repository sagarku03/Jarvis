package com.example.ui.screens

import android.Manifest
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.domain.model.OemBatteryGuide
import com.example.domain.model.PermissionItemStatus
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.PermissionsViewModel

@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel,
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionsList by viewModel.permissionsList.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatuses()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshStatuses()
    }

    val manufacturer = Build.MANUFACTURER.lowercase()
    val sortedGuides = remember(viewModel.oemGuides) {
        viewModel.oemGuides.sortedByDescending { guide ->
            val oemLower = guide.oemName.lowercase()
            when {
                manufacturer.contains("samsung") && oemLower.contains("samsung") -> 10
                (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) && oemLower.contains("xiaomi") -> 10
                (manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme")) && oemLower.contains("oneplus") -> 10
                manufacturer.contains("vivo") && oemLower.contains("vivo") -> 10
                else -> 0
            }
        }
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuroraIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.permissions_back_button),
                    testTag = "permissions_back_button",
                    onClick = onNavigateBack
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.permissions_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = AuroraColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.permissions_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("permissions_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(permissionsList, key = { it.id }) { item ->
                    PermissionCard(
                        item = item,
                        onGrantClick = {
                            when (item.id) {
                                "RECORD_AUDIO" -> runtimePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                "READ_CONTACTS" -> runtimePermLauncher.launch(Manifest.permission.READ_CONTACTS)
                                "CALL_PHONE" -> runtimePermLauncher.launch(Manifest.permission.CALL_PHONE)
                                "POST_NOTIFICATIONS" -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        runtimePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }
                                "SYSTEM_ALERT_WINDOW" -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )
                                    }
                                }
                                "ACCESSIBILITY_SERVICE" -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                                "NOTIFICATION_LISTENER" -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                                "BATTERY_OPTIMIZATION" -> {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryChargingFull,
                            contentDescription = stringResource(R.string.permissions_oem_title),
                            tint = AuroraColors.EmeraldSuccess
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.permissions_oem_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary
                            )
                            Text(
                                text = stringResource(R.string.permissions_oem_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextSecondary
                            )
                        }
                    }
                }

                items(sortedGuides, key = { it.oemName }) { guide ->
                    val isDetected = when {
                        manufacturer.contains("samsung") && guide.oemName.lowercase().contains("samsung") -> true
                        (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) && guide.oemName.lowercase().contains("xiaomi") -> true
                        (manufacturer.contains("oneplus") || manufacturer.contains("oppo") || manufacturer.contains("realme")) && guide.oemName.lowercase().contains("oneplus") -> true
                        manufacturer.contains("vivo") && guide.oemName.lowercase().contains("vivo") -> true
                        else -> false
                    }
                    OemBatteryCard(
                        guide = guide,
                        isDetected = isDetected,
                        onOpenSettings = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    item: PermissionItemStatus,
    onGrantClick: () -> Unit
) {
    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = item.titleEn,
                    tint = if (item.isGranted) AuroraColors.EmeraldSuccess else AuroraColors.AmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = item.titleEn,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary
                )
            }

            if (item.isGranted) {
                AuroraStatusPill(
                    text = stringResource(R.string.permissions_status_granted),
                    color = AuroraColors.EmeraldSuccess
                )
            } else {
                AuroraPrimaryButton(
                    text = stringResource(R.string.permissions_grant_button),
                    onClick = onGrantClick,
                    testTag = "grant_permission_${item.id}"
                )
            }
        }

        Text(
            text = item.rationaleEn,
            style = MaterialTheme.typography.bodySmall,
            color = AuroraColors.TextSecondary
        )
    }
}

@Composable
private fun OemBatteryCard(
    guide: OemBatteryGuide,
    isDetected: Boolean = false,
    onOpenSettings: () -> Unit
) {
    AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = guide.oemName,
                    tint = AuroraColors.Aqua,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = guide.oemName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuroraColors.Aqua,
                    fontWeight = FontWeight.Bold
                )
                if (isDetected) {
                    AuroraStatusPill(
                        text = "Detected",
                        color = AuroraColors.Success
                    )
                }
            }
            AuroraSecondaryButton(
                text = stringResource(R.string.permissions_open_battery_settings),
                onClick = onOpenSettings,
                testTag = "oem_battery_settings_${guide.oemName.take(6).lowercase()}"
            )
        }
        Text(
            text = guide.stepsEn,
            style = MaterialTheme.typography.bodySmall,
            color = AuroraColors.TextPrimary
        )
    }
}
