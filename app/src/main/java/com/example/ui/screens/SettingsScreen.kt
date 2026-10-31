package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConfirmationLevel
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraDangerButton
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.AuroraTextButton
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisMonoStyle
import com.example.ui.viewmodel.SettingsViewModel
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenPermissionsScreen: () -> Unit = {},
    onKeyRemoved: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val maskedApiKey by viewModel.maskedApiKey.collectAsStateWithLifecycle()
    val keyStatusMessage by viewModel.keyStatusMessage.collectAsStateWithLifecycle()
    val isVerifyingKey by viewModel.isVerifyingKey.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableDeviceVoices.collectAsStateWithLifecycle()
    val diagnostics by viewModel.voiceDiagnostics.collectAsStateWithLifecycle()
    val previewPhrase = stringResource(R.string.settings_voice_preview_phrase)

    var showChangeKeyDialog by remember { mutableStateOf(false) }
    var newKeyDraft by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshMaskedKey()
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AuroraSectionHeader(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                trailing = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.nav_permissions),
                        leadingIcon = Icons.Default.Shield,
                        onClick = onOpenPermissionsScreen,
                        testTag = "open_permissions_screen_button"
                    )
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
                // 1. Gemini AI Key & Model Card
                item {
                    AuroraGlassCard(elevated = true) {
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
                                    imageVector = Icons.Default.Key,
                                    contentDescription = stringResource(R.string.settings_ai_title),
                                    tint = AuroraColors.Aqua
                                )
                                Text(
                                    text = stringResource(R.string.settings_ai_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                            Text(
                                text = maskedApiKey.ifBlank { "••••••••" },
                                style = JarvisMonoStyle,
                                color = AuroraColors.Lavender,
                                modifier = Modifier.testTag("settings_masked_api_key")
                            )
                        }

                        if (!keyStatusMessage.isNullOrBlank()) {
                            Text(
                                text = keyStatusMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (keyStatusMessage?.startsWith("Verified") == true) {
                                    AuroraColors.EmeraldSuccess
                                } else {
                                    AuroraColors.CoralDanger
                                },
                                modifier = Modifier.testTag("settings_key_status_message")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AuroraSecondaryButton(
                                text = stringResource(R.string.settings_ai_change_key),
                                leadingIcon = Icons.Default.Edit,
                                onClick = {
                                    newKeyDraft = ""
                                    showChangeKeyDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_change_key_button"
                            )

                            AuroraSecondaryButton(
                                text = stringResource(R.string.settings_ai_verify_again),
                                leadingIcon = Icons.Default.Refresh,
                                loading = isVerifyingKey,
                                onClick = viewModel::reverifyCurrentKey,
                                modifier = Modifier.weight(1f),
                                testTag = "settings_reverify_key_button"
                            )

                            AuroraDangerButton(
                                text = stringResource(R.string.settings_ai_remove_key),
                                leadingIcon = Icons.Default.DeleteForever,
                                onClick = {
                                    viewModel.removeApiKey(onRemoved = onKeyRemoved)
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_remove_key_button"
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.setup_model_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = AuroraColors.TextSecondary
                        )

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuroraColors.SurfacePrimary)
                                    .border(1.dp, AuroraColors.GlassBorderStrong, RoundedCornerShape(14.dp))
                                    .clickable { modelDropdownExpanded = true }
                                    .padding(horizontal = 14.dp)
                                    .testTag("settings_model_selector"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = settings.selectedModelId,
                                    style = JarvisMonoStyle,
                                    color = AuroraColors.TextPrimary
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.setup_model_label),
                                    tint = AuroraColors.TextSecondary
                                )
                            }

                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                                modifier = Modifier.background(AuroraColors.SurfaceElevated)
                            ) {
                                settings.availableModels.forEach { modelId ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = modelId,
                                                style = JarvisMonoStyle,
                                                color = AuroraColors.TextPrimary
                                            )
                                        },
                                        onClick = {
                                            viewModel.setSelectedModelId(modelId)
                                            modelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(
                                R.string.settings_ai_usage_stats,
                                settings.requestsToday,
                                settings.tokensToday
                            ),
                            style = JarvisMonoStyle,
                            color = AuroraColors.TextSecondary,
                            modifier = Modifier.testTag("settings_usage_stats")
                        )
                    }
                }

                // 2. Speech Recognition Locale (English-only: en-IN / en-US / en-GB)
                item {
                    AuroraGlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = stringResource(R.string.settings_language_title),
                                tint = AuroraColors.Aqua
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
                                        selectedContainerColor = AuroraColors.Violet500.copy(alpha = 0.25f),
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

                // 3. Assistant Device Voice, Speed (0.8x..1.3x), Test Voice & Get Better Voices
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
                                    tint = AuroraColors.Violet500
                                )
                                Text(
                                    text = stringResource(R.string.settings_voice_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                            AuroraStatusPill(
                                text = stringResource(R.string.home_offline_voice_chip),
                                color = AuroraColors.Lavender,
                                icon = Icons.Default.VolumeUp,
                                modifier = Modifier.testTag("settings_offline_voice_chip")
                            )
                        }

                        Text(
                            text = stringResource(R.string.settings_voice_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )

                        if (availableVoices.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_voice_default_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Lavender
                            )
                        } else {
                            availableVoices.take(6).forEach { voiceOpt ->
                                FilterChip(
                                    selected = settings.selectedVoiceName == voiceOpt.id,
                                    onClick = { viewModel.setSelectedVoiceName(voiceOpt.id) },
                                    label = {
                                        Text(
                                            text = "${voiceOpt.displayName} (${voiceOpt.localeTag})",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AuroraColors.Aqua.copy(alpha = 0.22f),
                                        selectedLabelColor = AuroraColors.TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("voice_chip_${voiceOpt.id}")
                                )
                            }
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
                            valueRange = 0.8f..1.3f,
                            colors = SliderDefaults.colors(
                                thumbColor = AuroraColors.Aqua,
                                activeTrackColor = AuroraColors.Aqua
                            ),
                            modifier = Modifier.testTag("voice_speed_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AuroraSecondaryButton(
                                text = stringResource(R.string.settings_voice_preview),
                                leadingIcon = Icons.Default.PlayArrow,
                                iconTint = AuroraColors.Aqua,
                                onClick = { viewModel.previewVoice(previewPhrase) },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_voice_preview_button"
                            )

                            AuroraSecondaryButton(
                                text = stringResource(R.string.settings_voice_install_better),
                                leadingIcon = Icons.Default.Download,
                                iconTint = AuroraColors.Lavender,
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(
                                                Intent("com.android.settings.TTS_SETTINGS").apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                            )
                                        }.onFailure {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "settings_install_voice_button"
                            )
                        }
                    }
                }

                // 4. Memory & Auto-Learning + Floating Overlay Orb
                item {
                    AuroraGlassCard {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = stringResource(R.string.settings_memory_title),
                                tint = AuroraColors.EmeraldSuccess
                            )
                            Text(
                                text = stringResource(R.string.settings_memory_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary
                            )
                        }

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_auto_learn_title),
                            subtitle = stringResource(R.string.settings_auto_learn_desc),
                            checked = settings.autoLearnEnabled,
                            enabled = true,
                            onCheckedChange = viewModel::setAutoLearnEnabled,
                            tag = "settings_auto_learn_toggle"
                        )

                        HorizontalDivider(color = AuroraColors.GlassBorder)

                        SettingSwitchRow(
                            title = stringResource(R.string.settings_ask_before_save_title),
                            subtitle = stringResource(R.string.settings_ask_before_save_desc),
                            checked = settings.askBeforeSavingFact,
                            enabled = settings.autoLearnEnabled,
                            onCheckedChange = viewModel::setAskBeforeSavingFact,
                            tag = "settings_ask_before_saving_toggle"
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

                // 5. Safety Gate & KillSwitch
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

                // 6. Dev-only Voice Diagnostics Section (hidden in release builds)
                if (BuildConfig.DEBUG) {
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
                                    tint = AuroraColors.Aqua
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
                                color = AuroraColors.Aqua
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_diagnostics_tts,
                                    "${stringResource(R.string.home_offline_voice_chip)} (${diagnostics.activeVoiceId})"
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
            }
        }

        if (showChangeKeyDialog) {
            AlertDialog(
                onDismissRequest = { showChangeKeyDialog = false },
                containerColor = AuroraColors.SurfaceElevated,
                title = {
                    Text(
                        text = stringResource(R.string.settings_ai_change_key),
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.setup_step1_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        OutlinedTextField(
                            value = newKeyDraft,
                            onValueChange = { newKeyDraft = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.setup_key_hint),
                                    style = JarvisMonoStyle,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            textStyle = JarvisMonoStyle.copy(color = AuroraColors.TextPrimary),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_new_key_input")
                        )
                    }
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.setup_verify_key),
                        loading = isVerifyingKey,
                        enabled = newKeyDraft.isNotBlank(),
                        onClick = {
                            viewModel.verifyAndSaveNewKey(newKeyDraft) {
                                showChangeKeyDialog = false
                            }
                        },
                        testTag = "settings_confirm_new_key_button"
                    )
                },
                dismissButton = {
                    AuroraTextButton(
                        text = stringResource(R.string.safety_confirm_deny),
                        onClick = { showChangeKeyDialog = false },
                        testTag = "settings_cancel_new_key_button"
                    )
                }
            )
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
