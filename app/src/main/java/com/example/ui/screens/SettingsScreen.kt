package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.domain.model.AppLanguage
import com.example.domain.model.SafetyPolicyLevel
import com.example.domain.model.VoiceProfile
import com.example.services.VoiceForegroundService
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraSwitch
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassListRow
import com.example.ui.components.GradientButton
import com.example.ui.components.SectionHeader
import com.example.ui.theme.Bg1
import com.example.ui.theme.Danger
import com.example.ui.theme.GlassFillElevated
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.Lavender
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Violet500
import com.example.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackToHome: () -> Unit,
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToActivityLog: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {}
) {
    BackHandler(onBack = onBackToHome)

    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isVoiceServiceRunning by viewModel.isVoiceServiceRunning.collectAsStateWithLifecycle()
    val exportedDataJson by viewModel.exportedDataJson.collectAsStateWithLifecycle()

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showReleaseChecklistDialog by remember { mutableStateOf(false) }

    if (exportedDataJson != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExportedDataPreview() },
            containerColor = Bg1,
            title = {
                Text(
                    text = "Exported data (JSON)",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = exportedDataJson.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            },
            confirmButton = {
                GradientButton(
                    text = "Done",
                    onClick = { viewModel.clearExportedDataPreview() },
                    testTag = "export_data_done_button"
                )
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyPolicyDialog = false },
            containerColor = Bg1,
            title = {
                Text(
                    text = "Privacy & data policy",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "• Zero raw audio storage: 16kHz PCM frames stay in memory and are never written to disk.\n" +
                        "• Encrypted memory vault: Personal facts are encrypted with Android Keystore AES-256-GCM.\n" +
                        "• Untrusted screen boundary: ScreenReader skips password fields, blocks UPI/banking packages, and only attaches screen context when a UI tool requires it.\n" +
                        "• Authenticated backend proxy: Requests use TLS + Firebase Auth & App Check tokens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                GradientButton(
                    text = "Done",
                    onClick = { showPrivacyPolicyDialog = false }
                )
            }
        )
    }

    if (showReleaseChecklistDialog) {
        AlertDialog(
            onDismissRequest = { showReleaseChecklistDialog = false },
            containerColor = Bg1,
            title = {
                Text(
                    text = "Real-device & OEM checklist",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "• Enable JarvisAccessibilityService on device → run 'Read screen' & verify password fields are omitted.\n" +
                        "• Open GPay/PhonePe → verify SafetyGate blocks tap_element & call_contact immediately.\n" +
                        "• Double-press Volume Down during automation → verify KillSwitch halts mid-gesture.\n" +
                        "• Configure unrestricted battery for Samsung (One UI), Xiaomi (HyperOS), Realme (Realme UI), or Pixel.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                GradientButton(
                    text = "Close",
                    onClick = { showReleaseChecklistDialog = false }
                )
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            containerColor = Bg1,
            title = {
                Text(
                    text = "Delete all local data?",
                    style = MaterialTheme.typography.titleLarge,
                    color = Danger
                )
            },
            text = {
                Text(
                    text = "This permanently deletes all encrypted memories, routines, chat transcripts, audit logs, and resets settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            },
            confirmButton = {
                GradientButton(
                    text = "Delete everything",
                    onClick = {
                        viewModel.wipeAllUserData {
                            showDeleteConfirmDialog = false
                        }
                    },
                    testTag = "confirm_wipe_data_button"
                )
            },
            dismissButton = {
                GhostButton(
                    text = "Cancel",
                    onClick = { showDeleteConfirmDialog = false }
                )
            }
        )
    }

    AuroraBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Preferences",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.displayLarge,
                        color = TextPrimary
                    )
                }
            }

            // Section: Access & Audit Log navigation rows (moved from bottom bar)
            item {
                SectionHeader(title = "Access & security")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    GlassListRow(
                        title = "Permissions",
                        subtitle = "Microphone, contacts, overlay, accessibility & battery",
                        icon = Icons.Default.Security,
                        showChevron = true,
                        onClick = onNavigateToPermissions,
                        modifier = Modifier.testTag("nav_tab_permissions")
                    )
                    GlassListRow(
                        title = "Audit log",
                        subtitle = "Every tool execution and SafetyGate decision",
                        icon = Icons.Default.History,
                        showChevron = true,
                        onClick = onNavigateToActivityLog,
                        modifier = Modifier.testTag("nav_tab_activity")
                    )
                    GlassListRow(
                        title = "Account & sign in",
                        subtitle = "Manage your JARVIS cloud session",
                        icon = Icons.Default.AccountCircle,
                        showChevron = true,
                        onClick = onNavigateToLogin,
                        modifier = Modifier.testTag("settings_login_row")
                    )
                }
            }

            // Section 1: Voice
            item {
                SectionHeader(title = "Voice")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassListRow(
                            title = "Assistant language",
                            subtitle = "Speak and reply in Hinglish, Hindi, or English",
                            icon = Icons.Default.Language
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = settings.language == lang,
                                    onClick = { viewModel.setLanguage(lang) },
                                    label = { Text(lang.displayNameEn) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Violet500.copy(alpha = 0.25f),
                                        selectedLabelColor = TextPrimary,
                                        containerColor = GlassFillElevated,
                                        labelColor = TextSecondary
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = settings.language == lang,
                                        borderColor = GlassStroke,
                                        selectedBorderColor = Lavender
                                    ),
                                    modifier = Modifier.testTag("lang_chip_${lang.code}")
                                )
                            }
                        }

                        GlassListRow(
                            title = "Voice profile",
                            subtitle = settings.voiceProfile.description,
                            icon = Icons.Default.RecordVoiceOver
                        )
                        VoiceProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = settings.voiceProfile == profile,
                                onClick = { viewModel.setVoiceProfile(profile) },
                                label = { Text(profile.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Violet500.copy(alpha = 0.25f),
                                    selectedLabelColor = TextPrimary,
                                    containerColor = GlassFillElevated,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = settings.voiceProfile == profile,
                                    borderColor = GlassStroke,
                                    selectedBorderColor = Lavender
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_profile_${profile.id}")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Voice speed", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text("%.2fx".format(settings.voiceSpeed), color = Lavender, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = settings.voiceSpeed,
                            onValueChange = { viewModel.setVoiceSpeed(it) },
                            valueRange = 0.6f..1.8f,
                            colors = SliderDefaults.colors(
                                thumbColor = Lavender,
                                activeTrackColor = Violet500,
                                inactiveTrackColor = GlassFillElevated
                            ),
                            modifier = Modifier.testTag("voice_speed_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Voice pitch", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text("%.2fx".format(settings.voicePitch), color = Lavender, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = settings.voicePitch,
                            onValueChange = { viewModel.setVoicePitch(it) },
                            valueRange = 0.6f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = Lavender,
                                activeTrackColor = Violet500,
                                inactiveTrackColor = GlassFillElevated
                            ),
                            modifier = Modifier.testTag("voice_pitch_slider")
                        )
                    }
                }
            }

            // Section 2: Wake word
            item {
                SectionHeader(title = "Wake word")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassListRow(
                            title = "Hey Jarvis wake word",
                            subtitle = if (isVoiceServiceRunning && viewModel.isWakeWordConfigured) {
                                "Active • Foreground microphone monitoring"
                            } else {
                                viewModel.wakeWordStatusMessage
                            },
                            icon = Icons.Default.Hearing,
                            trailingContent = {
                                AuroraSwitch(
                                    checked = settings.wakeWordEnabled && viewModel.isWakeWordConfigured,
                                    onCheckedChange = { enabled ->
                                        if (viewModel.isWakeWordConfigured) {
                                            viewModel.setWakeWordEnabled(enabled)
                                            toggleVoiceForegroundService(context, enabled)
                                        }
                                    },
                                    modifier = Modifier.testTag("wake_word_switch")
                                )
                            }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Wake sensitivity", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            Text("${(settings.wakeWordSensitivity * 100).toInt()}%", color = Lavender, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = settings.wakeWordSensitivity,
                            onValueChange = { viewModel.setWakeWordSensitivity(it) },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Lavender,
                                activeTrackColor = Violet500,
                                inactiveTrackColor = GlassFillElevated
                            ),
                            modifier = Modifier.testTag("wake_sensitivity_slider")
                        )

                        GlassListRow(
                            title = "Pause listening",
                            subtitle = "Guarantees microphone never records while paused",
                            icon = Icons.Default.MicOff,
                            trailingContent = {
                                AuroraSwitch(
                                    checked = settings.wakeWordPaused,
                                    onCheckedChange = { paused ->
                                        viewModel.setWakeWordPaused(paused)
                                        notifyPauseStateToVoiceService(context, paused)
                                    },
                                    modifier = Modifier.testTag("wake_pause_switch")
                                )
                            }
                        )

                        GlassListRow(
                            title = "Auto-start on boot",
                            subtitle = "Prompt to resume wake word after phone restart",
                            icon = Icons.Default.PowerSettingsNew,
                            trailingContent = {
                                AuroraSwitch(
                                    checked = settings.autoStartOnBoot,
                                    onCheckedChange = { viewModel.setAutoStartOnBoot(it) },
                                    modifier = Modifier.testTag("boot_autostart_switch")
                                )
                            }
                        )
                    }
                }
            }

            // Section 3: Safety
            item {
                SectionHeader(title = "Safety")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassListRow(
                            title = "Safety policy level",
                            subtitle = settings.safetyPolicyLevel.labelEn,
                            icon = Icons.Default.VerifiedUser
                        )
                        SafetyPolicyLevel.entries.forEach { level ->
                            FilterChip(
                                selected = settings.safetyPolicyLevel == level,
                                onClick = { viewModel.setSafetyPolicyLevel(level) },
                                label = { Text("${level.labelEn} · ${level.labelHi}") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Violet500.copy(alpha = 0.25f),
                                    selectedLabelColor = TextPrimary,
                                    containerColor = GlassFillElevated,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = settings.safetyPolicyLevel == level,
                                    borderColor = GlassStroke,
                                    selectedBorderColor = Lavender
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("safety_level_${level.id.lowercase()}")
                            )
                        }

                        GlassListRow(
                            title = "Full phone control",
                            subtitle = "Allow Accessibility screen reading and gestures",
                            icon = Icons.Default.Lock,
                            trailingContent = {
                                AuroraSwitch(
                                    checked = settings.fullControlModeEnabled,
                                    onCheckedChange = { viewModel.setFullControlMode(it) },
                                    modifier = Modifier.testTag("full_control_mode_switch")
                                )
                            }
                        )
                    }
                }
            }

            // Section 4: Privacy & data
            item {
                SectionHeader(title = "Privacy & data")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassListRow(
                            title = "Diagnostics & crash reporting",
                            subtitle = "Anonymous telemetry without audio or personal text",
                            icon = Icons.Default.GraphicEq,
                            trailingContent = {
                                AuroraSwitch(
                                    checked = settings.analyticsOptIn,
                                    onCheckedChange = { viewModel.setAnalyticsOptIn(it) },
                                    modifier = Modifier.testTag("analytics_opt_in_switch")
                                )
                            }
                        )
                        GlassListRow(
                            title = "Export my data",
                            subtitle = "Download decrypted memories & routines as JSON",
                            icon = Icons.Default.History,
                            showChevron = true,
                            onClick = { viewModel.exportAllUserData() },
                            modifier = Modifier.testTag("settings_export_data_button")
                        )
                        GlassListRow(
                            title = "Privacy policy",
                            subtitle = "What stays on device vs what is sent to the proxy",
                            icon = Icons.Default.PrivacyTip,
                            showChevron = true,
                            onClick = { showPrivacyPolicyDialog = true },
                            modifier = Modifier.testTag("privacy_policy_button")
                        )
                    }
                }
            }

            // Section 5: About
            item {
                SectionHeader(title = "About")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassListRow(
                            title = "OEM battery & survival guide",
                            subtitle = "Samsung, Xiaomi, Oppo, Realme & Pixel setup",
                            icon = Icons.Default.Checklist,
                            showChevron = true,
                            onClick = { showReleaseChecklistDialog = true },
                            modifier = Modifier.testTag("release_checklist_button")
                        )
                        GlassListRow(
                            title = "JARVIS Android",
                            subtitle = "Version ${BuildConfig.VERSION_NAME} • TLS SHA-256 pinned",
                            icon = Icons.Default.Info
                        )
                    }
                }
            }

            // Section 6: Developer (ONLY in dev flavor)
            if (BuildConfig.IS_DEV_FLAVOR) {
                item {
                    SectionHeader(title = "Developer")
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassListRow(
                                title = "Simulate secure banking screen",
                                subtitle = "Test SafetyGate blocking on UPI/banking windows",
                                icon = Icons.Default.BugReport,
                                trailingContent = {
                                    AuroraSwitch(
                                        checked = settings.simulateSecureScreen,
                                        onCheckedChange = { viewModel.setSimulateSecureScreen(it) },
                                        modifier = Modifier.testTag("simulate_secure_screen_switch")
                                    )
                                }
                            )
                            GlassListRow(
                                title = "Show voice latency panel",
                                subtitle = "Display real STT/TTS chunk timestamps on Home",
                                icon = Icons.Default.Speed,
                                trailingContent = {
                                    AuroraSwitch(
                                        checked = settings.showLatencyDebugPanel,
                                        onCheckedChange = { viewModel.setShowLatencyDebugPanel(it) },
                                        modifier = Modifier.testTag("show_latency_panel_switch")
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Delete All Local Data CTA
            item {
                GhostButton(
                    text = stringResource(R.string.settings_delete_all),
                    leadingIcon = Icons.Default.DeleteForever,
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "delete_all_data_button"
                )
            }
        }
    }
}

private fun toggleVoiceForegroundService(context: Context, enable: Boolean) {
    val intent = Intent(context, VoiceForegroundService::class.java).apply {
        action = if (enable) VoiceForegroundService.ACTION_START else VoiceForegroundService.ACTION_STOP
    }
    runCatching {
        context.startService(intent)
    }
}

private fun notifyPauseStateToVoiceService(context: Context, paused: Boolean) {
    val intent = Intent(context, VoiceForegroundService::class.java).apply {
        action = if (paused) VoiceForegroundService.ACTION_PAUSE else VoiceForegroundService.ACTION_RESUME
    }
    runCatching {
        context.startService(intent)
    }
}
