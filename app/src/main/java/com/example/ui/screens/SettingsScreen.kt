package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.VoiceProfile
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenPermissionsScreen: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isOfflineVoiceActive by viewModel.isOfflineVoiceActive.collectAsStateWithLifecycle()
    val diagnostics by viewModel.voiceDiagnostics.collectAsStateWithLifecycle()
    val isWakeConfigured = viewModel.isWakeWordConfigured
    val previewPhrase = stringResource(R.string.settings_voice_preview_phrase)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AuroraColors.BackgroundDeep)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
        ) {
            AuroraSectionHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                trailing = {
                    Button(
                        onClick = onOpenPermissionsScreen,
                        colors = ButtonDefaults.buttonColors(containerColor = AuroraColors.ElectricViolet),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("open_permissions_screen_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = stringResource(R.string.settings_permissions_button)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.nav_permissions),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("settings_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // 1. Speech Recognition Locale (English-only: en-IN / en-US / en-GB)
                item {
                    AuroraGlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = stringResource(R.string.settings_language_title),
                                tint = AuroraColors.CyanGlow
                            )
                            Text(
                                text = stringResource(R.string.settings_language_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_language_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = settings.language == lang,
                                    onClick = { viewModel.setLanguage(lang) },
                                    label = {
                                        Text(
                                            text = "${lang.displayName} (${lang.code})",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AuroraColors.ElectricViolet.copy(alpha = 0.25f),
                                        selectedLabelColor = AuroraColors.TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("lang_chip_${lang.code}")
                                )
                            }
                        }
                    }
                }

                // 2. Assistant Voice Profile, Speed (0.8x..1.2x), Offline Chip & Preview Button
                item {
                    AuroraGlassCard {
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
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = stringResource(R.string.settings_voice_title),
                                    tint = AuroraColors.ElectricViolet
                                )
                                Text(
                                    text = stringResource(R.string.settings_voice_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                            if (isOfflineVoiceActive) {
                                AuroraStatusPill(
                                    text = stringResource(R.string.home_offline_voice_chip),
                                    color = AuroraColors.AmberWarning,
                                    icon = Icons.Default.VolumeUp,
                                    modifier = Modifier.testTag("settings_offline_voice_chip")
                                )
                            } else {
                                AuroraStatusPill(
                                    text = stringResource(R.string.home_neural_voice_chip),
                                    color = AuroraColors.ElectricViolet,
                                    icon = Icons.Default.GraphicEq
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.settings_voice_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )

                        VoiceProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = settings.voiceProfile == profile,
                                onClick = { viewModel.setVoiceProfile(profile) },
                                label = {
                                    Text(
                                        text = "${profile.displayName} — ${profile.subtitle}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AuroraColors.CyanGlow.copy(alpha = 0.22f),
                                    selectedLabelColor = AuroraColors.TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("voice_chip_${profile.id}")
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        val formattedSpeed = String.format(Locale.US, "%.2f", settings.speechSpeed)
                        Text(
                            text = stringResource(R.string.settings_voice_speed_label, formattedSpeed),
                            style = MaterialTheme.typography.labelMedium,
                            color = AuroraColors.TextSecondary
                        )
                        Slider(
                            value = settings.speechSpeed,
                            onValueChange = { viewModel.setSpeechSpeed(it) },
                            valueRange = 0.8f..1.2f,
                            colors = SliderDefaults.colors(
                                thumbColor = AuroraColors.CyanGlow,
                                activeTrackColor = AuroraColors.CyanGlow
                            ),
                            modifier = Modifier.testTag("voice_speed_slider")
                        )

                        OutlinedButton(
                            onClick = { viewModel.previewVoice(previewPhrase) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("settings_voice_preview_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.settings_voice_preview),
                                tint = AuroraColors.CyanGlow
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.settings_voice_preview),
                                color = AuroraColors.TextPrimary
                            )
                        }
                    }
                }

                // 3. Wake Word ("Hey Jarvis") + Clear "Wake word not set up" state when key/model missing
                item {
                    AuroraGlassCard {
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
                                    imageVector = Icons.Default.Hearing,
                                    contentDescription = stringResource(R.string.settings_wake_title),
                                    tint = AuroraColors.EmeraldSuccess
                                )
                                Text(
                                    text = stringResource(R.string.settings_wake_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                            if (!isWakeConfigured) {
                                AuroraStatusPill(
                                    text = stringResource(R.string.settings_wake_not_configured),
                                    color = AuroraColors.AmberWarning,
                                    modifier = Modifier.testTag("settings_wake_not_configured_badge")
                                )
                            }
                        }

                        if (!isWakeConfigured) {
                            Text(
                                text = stringResource(R.string.settings_wake_not_configured_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.AmberWarning
                            )
                        }

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_wake_enable),
                            subtitle = stringResource(R.string.fgs_notification_text_active),
                            checked = settings.wakeWordEnabled && isWakeConfigured,
                            enabled = isWakeConfigured,
                            onCheckedChange = viewModel::setWakeWordEnabled,
                            tag = "wake_word_toggle"
                        )

                        HorizontalDivider(color = AuroraColors.GlassBorder)

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_wake_pause),
                            subtitle = stringResource(R.string.fgs_notification_text_paused),
                            checked = settings.wakeWordPaused,
                            enabled = isWakeConfigured && settings.wakeWordEnabled,
                            onCheckedChange = viewModel::setWakeWordPaused,
                            tag = "wake_word_pause_toggle"
                        )

                        Text(
                            text = stringResource(
                                R.string.settings_wake_sensitivity,
                                (settings.wakeWordSensitivity * 100).toInt()
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = AuroraColors.TextSecondary
                        )
                        Slider(
                            value = settings.wakeWordSensitivity,
                            onValueChange = viewModel::setWakeWordSensitivity,
                            valueRange = 0.2f..0.95f,
                            enabled = isWakeConfigured,
                            colors = SliderDefaults.colors(
                                thumbColor = AuroraColors.EmeraldSuccess,
                                activeTrackColor = AuroraColors.EmeraldSuccess
                            ),
                            modifier = Modifier.testTag("wake_sensitivity_slider")
                        )

                        HorizontalDivider(color = AuroraColors.GlassBorder)

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_boot_autostart),
                            subtitle = stringResource(R.string.boot_resume_text),
                            checked = settings.bootAutoStartEnabled,
                            enabled = isWakeConfigured,
                            onCheckedChange = viewModel::setBootAutoStartEnabled,
                            tag = "boot_autostart_toggle"
                        )

                        HorizontalDivider(color = AuroraColors.GlassBorder)

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_overlay_enable),
                            subtitle = stringResource(R.string.overlay_chip_label),
                            checked = settings.floatingOrbEnabled,
                            enabled = true,
                            onCheckedChange = viewModel::setFloatingOrbEnabled,
                            tag = "floating_orb_toggle"
                        )
                    }
                }

                // 4. Safety Gate & KillSwitch
                item {
                    AuroraGlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = stringResource(R.string.settings_safety_title),
                                tint = AuroraColors.AmberWarning
                            )
                            Text(
                                text = stringResource(R.string.settings_safety_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary
                            )
                        }

                        ConfirmationLevel.entries.forEach { level ->
                            FilterChip(
                                selected = settings.confirmationLevel == level,
                                onClick = { viewModel.setConfirmationLevel(level) },
                                label = { Text(level.labelEn) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AuroraColors.AmberWarning.copy(alpha = 0.22f),
                                    selectedLabelColor = AuroraColors.TextPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("confirm_level_${level.code}")
                            )
                        }

                        HorizontalDivider(color = AuroraColors.GlassBorder)

                        SettingSwitchRow(
                            title = stringResource(R.string.home_kill_switch_trigger),
                            subtitle = stringResource(R.string.home_paused_killswitch),
                            checked = settings.killSwitchActive,
                            enabled = true,
                            onCheckedChange = viewModel::setKillSwitch,
                            tag = "settings_kill_switch_toggle"
                        )
                    }
                }

                // 5. Dev-only Voice Diagnostics Section (hidden in release builds)
                if (BuildConfig.IS_DEV_FLAVOR) {
                    item {
                        AuroraGlassCard(
                            modifier = Modifier.testTag("settings_voice_diagnostics_card")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BugReport,
                                    contentDescription = stringResource(R.string.settings_diagnostics_title),
                                    tint = AuroraColors.CyanGlow
                                )
                                Text(
                                    text = stringResource(R.string.settings_diagnostics_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.settings_diagnostics_state,
                                    diagnostics.sessionState.name
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.CyanGlow
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_diagnostics_tts,
                                    if (diagnostics.isOfflineTtsActive) {
                                        stringResource(R.string.home_offline_voice_chip)
                                    } else {
                                        "${stringResource(R.string.home_neural_voice_chip)} (${diagnostics.activeVoiceId})"
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextSecondary
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_diagnostics_stt_error,
                                    diagnostics.lastSttErrorMessage
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextSecondary
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_diagnostics_latency,
                                    diagnostics.sttLatencyMs,
                                    diagnostics.agentLatencyMs,
                                    diagnostics.ttsLatencyMs
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextSecondary
                            )
                        }
                    }
                }

                // 6. Sign Out Action
                item {
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("settings_sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = stringResource(R.string.settings_sign_out),
                            tint = AuroraColors.CoralDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_sign_out),
                            color = AuroraColors.CoralDanger,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) AuroraColors.TextPrimary else AuroraColors.TextMuted,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AuroraColors.TextSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AuroraColors.BackgroundDeep,
                checkedTrackColor = AuroraColors.EmeraldSuccess,
                uncheckedThumbColor = AuroraColors.TextSecondary,
                uncheckedTrackColor = AuroraColors.SurfacePrimary
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}
