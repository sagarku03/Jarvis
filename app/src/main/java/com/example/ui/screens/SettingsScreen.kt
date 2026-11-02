package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.data.local.DiagnosticsLogger
import com.example.domain.model.AppLanguage
import com.example.domain.model.ConfirmationLevel
import com.example.domain.model.GeminiVoice
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraDangerButton
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.AuroraTextButton
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisMonoStyle
import com.example.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenPermissionsScreen: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onKeyRemoved: () -> Unit = {}
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val maskedApiKey by viewModel.maskedApiKey.collectAsStateWithLifecycle()
    val keyStatusMessage by viewModel.keyStatusMessage.collectAsStateWithLifecycle()
    val isVerifyingKey by viewModel.isVerifyingKey.collectAsStateWithLifecycle()
    val isTestingConnection by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val testConnectionMessage by viewModel.testConnectionMessage.collectAsStateWithLifecycle()
    val dualConnectionReport by viewModel.dualConnectionReport.collectAsStateWithLifecycle()
    val isRunningSelfTest by viewModel.isRunningSelfTest.collectAsStateWithLifecycle()
    val selfTestReport by viewModel.selfTestReport.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableDeviceVoices.collectAsStateWithLifecycle()
    val diagnostics by viewModel.voiceDiagnostics.collectAsStateWithLifecycle()

    val voicePreviewStatus by viewModel.voicePreviewStatus.collectAsStateWithLifecycle()

    var showChangeKeyDialog by remember { mutableStateOf(false) }
    var newKeyInput by remember { mutableStateOf("") }
    var showRemoveKeyDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showWakeConsentDialog by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var voiceModelDropdownExpanded by remember { mutableStateOf(false) }
    var textModelDropdownExpanded by remember { mutableStateOf(false) }
    var liveVoiceDropdownExpanded by remember { mutableStateOf(false) }
    var phoneVoiceDropdownExpanded by remember { mutableStateOf(false) }

    val availableVoiceModels = remember(settings.availableModels) {
        buildList {
            add("gemini-3.8-live")
            add("gemini-3.8-flash")
            addAll(settings.availableModels.filter { it.contains("live") || it.contains("flash") })
        }.distinct().filterNot { it.contains("2.0") }
    }

    val availableTextModels = remember(settings.availableModels) {
        buildList {
            add("gemini-3.8-flash")
            add("gemini-3.8-pro")
            addAll(settings.availableModels.filterNot { it.contains("tts") || it.contains("live") || it.contains("2.0") })
        }.distinct()
    }

    AuroraBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("settings_screen_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                }
            }

            // SECTION 1: AI Connection
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_ai),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.settings_ai_key_masked_label, maskedApiKey),
                        style = JarvisMonoStyle.copy(color = AuroraColors.Lavender)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuroraSecondaryButton(
                            text = stringResource(R.string.settings_ai_change_key),
                            icon = Icons.Default.Edit,
                            onClick = {
                                newKeyInput = ""
                                showChangeKeyDialog = true
                            },
                            modifier = Modifier.widthIn(min = 88.dp).weight(1f)
                        )
                        AuroraPrimaryButton(
                            text = if (isTestingConnection) {
                                stringResource(R.string.settings_test_connection_testing)
                            } else {
                                stringResource(R.string.settings_test_connection)
                            },
                            enabled = !isTestingConnection,
                            onClick = { viewModel.testAiConnection() },
                            modifier = Modifier.widthIn(min = 88.dp).weight(1.2f).testTag("settings_test_connection_button")
                        )
                        AuroraDangerButton(
                            text = stringResource(R.string.settings_ai_remove_key),
                            onClick = { showRemoveKeyDialog = true },
                            modifier = Modifier.widthIn(min = 88.dp)
                        )
                    }

                    if (dualConnectionReport != null) {
                        val report = dualConnectionReport!!
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (report.isOverallSuccess) AuroraColors.GlassFillLight else AuroraColors.Danger.copy(alpha = 0.12f))
                                .border(1.dp, if (report.isOverallSuccess) AuroraColors.Success.copy(alpha = 0.5f) else AuroraColors.Danger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Text Model Ping
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (report.textPing.success) Icons.Default.CheckCircle else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (report.textPing.success) AuroraColors.Success else AuroraColors.Danger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Text Model (${report.textPing.modelId}): ${if (report.textPing.success) report.textPing.details else (report.textPing.errorSnippet ?: report.textPing.details)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (report.textPing.success) AuroraColors.TextPrimary else AuroraColors.Danger
                                )
                            }
                            // TTS Voice Model Ping
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (report.ttsPing.success) Icons.Default.CheckCircle else Icons.Default.Close,
                                    contentDescription = null,
                                    tint = if (report.ttsPing.success) AuroraColors.Success else AuroraColors.Danger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Natural TTS (${report.ttsPing.modelId}): ${if (report.ttsPing.success) report.ttsPing.details else (report.ttsPing.errorSnippet ?: report.ttsPing.details)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (report.ttsPing.success) AuroraColors.TextPrimary else AuroraColors.Danger
                                )
                            }
                        }
                    } else if (testConnectionMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val isConnOk = testConnectionMessage?.startsWith("Connected", ignoreCase = true) == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (isConnOk) Icons.Default.CheckCircle else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isConnOk) AuroraColors.Success else AuroraColors.Danger,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = testConnectionMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isConnOk) AuroraColors.Success else AuroraColors.Danger
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    AuroraSecondaryButton(
                        text = if (isRunningSelfTest) "Running 5 Live Checks..." else "Run Full Self-Test",
                        enabled = !isRunningSelfTest,
                        onClick = { viewModel.runFullSelfTest() },
                        modifier = Modifier.fillMaxWidth().testTag("settings_run_self_test_button")
                    )

                    if (selfTestReport != null) {
                        val report = selfTestReport!!
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AuroraColors.GlassFillLight)
                                .border(1.dp, if (report.allPassed) AuroraColors.Success else AuroraColors.Lavender, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Self-Test Diagnostic Suite",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (report.allPassed) AuroraColors.Success else AuroraColors.Lavender
                                )
                                Text(
                                    if (report.allPassed) "ALL PASS" else "CHECK DETAILS",
                                    style = JarvisMonoStyle.copy(fontSize = 11.sp, color = if (report.allPassed) AuroraColors.Success else AuroraColors.Danger)
                                )
                            }
                            report.steps.forEach { step ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (step.passed) Icons.Default.CheckCircle else Icons.Default.Close,
                                        contentDescription = null,
                                        tint = if (step.passed) AuroraColors.Success else AuroraColors.Danger,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "${step.stepIndex}. ${step.title}: ${step.detail}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (step.passed) AuroraColors.TextPrimary else AuroraColors.Danger
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                AuroraSecondaryButton(
                                    text = "Copy Diagnostics",
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        val summaryText = buildString {
                                            appendLine("### JARVIS Self-Test Report")
                                            appendLine("Overall: ${if (report.allPassed) "PASSED" else "FAILED"}")
                                            report.steps.forEach {
                                                appendLine("- [${if (it.passed) "PASS" else "FAIL"}] ${it.title}: ${it.detail} (${it.latencyMs}ms)")
                                            }
                                        }
                                        val clip = android.content.ClipData.newPlainText("JARVIS Self-Test", summaryText)
                                        clipboard?.setPrimaryClip(clip)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Voice Model Selection
                    Text(
                        text = stringResource(R.string.model_voice_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuroraColors.GlassFillLight)
                                .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { voiceModelDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val displayName = GeminiClient.friendlyModelName(settings.selectedVoiceModelId)
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = AuroraColors.Lavender)
                        }
                        DropdownMenu(
                            expanded = voiceModelDropdownExpanded,
                            onDismissRequest = { voiceModelDropdownExpanded = false }
                        ) {
                            availableVoiceModels.forEach { modelId ->
                                DropdownMenuItem(
                                    text = { Text(GeminiClient.friendlyModelName(modelId)) },
                                    onClick = {
                                        viewModel.setSelectedVoiceModelId(modelId)
                                        voiceModelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Text Model Selection
                    Text(
                        text = stringResource(R.string.model_text_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuroraColors.GlassFillLight)
                                .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { textModelDropdownExpanded = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val displayName = GeminiClient.friendlyModelName(settings.selectedTextModelId)
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary
                            )
                            Icon(Icons.Default.ArrowDropDown, null, tint = AuroraColors.Lavender)
                        }
                        DropdownMenu(
                            expanded = textModelDropdownExpanded,
                            onDismissRequest = { textModelDropdownExpanded = false }
                        ) {
                            availableTextModels.forEach { modelId ->
                                DropdownMenuItem(
                                    text = { Text(GeminiClient.friendlyModelName(modelId)) },
                                    onClick = {
                                        viewModel.setSelectedTextModelId(modelId)
                                        textModelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 2: Voice
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_voice),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingToggleRow(
                        title = "Real-time Live Mode (WebSocket)",
                        subtitle = "Direct 2-way streaming via bidiGenerateContent with auto-fallback to cascade",
                        checked = settings.isRealtimeLiveMode,
                        onCheckedChange = { viewModel.setRealtimeLiveMode(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.voice_provider_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    // Voice Engine FilterChips: Natural (Google) vs Phone voice
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.voiceEngine == com.example.domain.model.VoiceEngineOption.NATURAL_GOOGLE,
                            onClick = { viewModel.setVoiceEngine(com.example.domain.model.VoiceEngineOption.NATURAL_GOOGLE) },
                            label = { Text(stringResource(R.string.voice_provider_natural)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AuroraColors.Violet,
                                selectedLabelColor = AuroraColors.TextPrimary
                            )
                        )
                        FilterChip(
                            selected = settings.voiceEngine == com.example.domain.model.VoiceEngineOption.PHONE_VOICE,
                            onClick = { viewModel.setVoiceEngine(com.example.domain.model.VoiceEngineOption.PHONE_VOICE) },
                            label = { Text(stringResource(R.string.voice_provider_phone)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AuroraColors.Violet,
                                selectedLabelColor = AuroraColors.TextPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (settings.voiceEngine == com.example.domain.model.VoiceEngineOption.NATURAL_GOOGLE) {
                        // Voice Gender Selection
                        Text(
                            text = stringResource(R.string.voice_gender_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = settings.selectedVoiceGender.equals("MALE", ignoreCase = true),
                                onClick = { viewModel.setSelectedVoiceGender("MALE") },
                                label = { Text(stringResource(R.string.voice_gender_male)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AuroraColors.Violet,
                                    selectedLabelColor = AuroraColors.TextPrimary
                                )
                            )
                            FilterChip(
                                selected = settings.selectedVoiceGender.equals("FEMALE", ignoreCase = true),
                                onClick = { viewModel.setSelectedVoiceGender("FEMALE") },
                                label = { Text(stringResource(R.string.voice_gender_female)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AuroraColors.Violet,
                                    selectedLabelColor = AuroraColors.TextPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.settings_voice_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        val availableLiveVoices = GeminiVoice.getVoicesForGender(settings.selectedVoiceGender)
                        val currentLiveVoice = GeminiVoice.fromVoiceName(settings.selectedLiveVoiceName)

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AuroraColors.GlassFillLight)
                                    .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { liveVoiceDropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentLiveVoice.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextPrimary
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = AuroraColors.Lavender)
                            }
                            DropdownMenu(
                                expanded = liveVoiceDropdownExpanded,
                                onDismissRequest = { liveVoiceDropdownExpanded = false }
                            ) {
                                availableLiveVoices.forEach { voice ->
                                    DropdownMenuItem(
                                        text = { Text(voice.displayName) },
                                        onClick = {
                                            viewModel.setSelectedLiveVoiceName(voice.voiceName)
                                            liveVoiceDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        AuroraSecondaryButton(
                            text = "Aawaz Test Karein / Test Voice",
                            icon = Icons.Default.VolumeUp,
                            onClick = { viewModel.testCurrentVoice() },
                            modifier = Modifier.fillMaxWidth().testTag("settings_test_voice_button")
                        )

                        if (voicePreviewStatus != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = AuroraColors.Lavender,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = voicePreviewStatus.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.Lavender
                                )
                            }
                        }
                    } else {
                        // Phone Voice Engine
                        Text(
                            text = stringResource(R.string.settings_voice_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AuroraColors.GlassFillLight)
                                    .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
                                    .clickable { phoneVoiceDropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val activeVoiceName = settings.selectedVoiceName.ifBlank { stringResource(R.string.voice_name_device_default) }
                                Text(
                                    text = activeVoiceName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextPrimary
                                )
                                Icon(Icons.Default.ArrowDropDown, null, tint = AuroraColors.Lavender)
                            }
                            DropdownMenu(
                                expanded = phoneVoiceDropdownExpanded,
                                onDismissRequest = { phoneVoiceDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.voice_name_device_default)) },
                                    onClick = {
                                        viewModel.setSelectedVoiceName("")
                                        phoneVoiceDropdownExpanded = false
                                    }
                                )
                                availableVoices.forEachIndexed { idx, v ->
                                    DropdownMenuItem(
                                        text = { Text(v.displayName.ifBlank { "Voice ${idx + 1}" }) },
                                        onClick = {
                                            viewModel.setSelectedVoiceName(v.id)
                                            phoneVoiceDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        AuroraTextButton(
                            text = stringResource(R.string.voice_get_better_voices),
                            onClick = {
                                runCatching {
                                    val ttsIntent = Intent("com.android.settings.TTS_SETTINGS").apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    if (ttsIntent.resolveActivity(context.packageManager) != null) {
                                        context.startActivity(ttsIntent)
                                    } else {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Voice Speed Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_voice_speed_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Text(
                            text = when {
                                settings.speechSpeed < 0.95f -> stringResource(R.string.settings_speed_slow)
                                settings.speechSpeed > 1.15f -> stringResource(R.string.settings_speed_fast)
                                else -> stringResource(R.string.settings_speed_normal)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = AuroraColors.Lavender,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = settings.speechSpeed,
                        onValueChange = { viewModel.setSpeechSpeed(it) },
                        valueRange = 0.8f..1.3f,
                        steps = 4,
                        colors = SliderDefaults.colors(
                            thumbColor = AuroraColors.Violet,
                            activeTrackColor = AuroraColors.Violet,
                            inactiveTrackColor = AuroraColors.GlassBorder
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AuroraSecondaryButton(
                        text = stringResource(R.string.voice_preview_button),
                        icon = Icons.Default.PlayArrow,
                        onClick = { viewModel.previewVoice(context.getString(R.string.settings_voice_preview_phrase)) },
                        modifier = Modifier.widthIn(min = 88.dp)
                    )

                    if (voicePreviewStatus != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AuroraColors.Lavender,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = voicePreviewStatus.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Lavender
                            )
                        }
                    }
                }
            }

            // SECTION 2B: Wake Phrase ("Hey Jarvis")
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.wake_phrase_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.wake_phrase_title),
                        subtitle = stringResource(R.string.wake_phrase_desc),
                        checked = settings.wakePhraseEnabled,
                        onCheckedChange = { willEnable ->
                            if (willEnable) {
                                showWakeConsentDialog = true
                            } else {
                                viewModel.setWakePhraseEnabled(false)
                            }
                        }
                    )

                    if (settings.wakePhraseEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))

                        SettingToggleRow(
                            title = stringResource(R.string.wake_phrase_screen_on_only),
                            subtitle = "Conserves battery by pausing when screen is off",
                            checked = settings.wakePhraseScreenOnOnly,
                            onCheckedChange = { viewModel.setWakePhraseScreenOnOnly(it) }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingToggleRow(
                            title = stringResource(R.string.wake_phrase_stop_after_30m),
                            subtitle = "Automatically stops listening after 30 minutes",
                            checked = settings.wakePhraseStopAfter30m,
                            onCheckedChange = { viewModel.setWakePhraseStopAfter30m(it) }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        AuroraSecondaryButton(
                            text = stringResource(R.string.wake_phrase_test_button),
                            icon = Icons.Default.PlayArrow,
                            onClick = {
                                viewModel.previewVoice("Say 'Hey Jarvis' to test wake detection.")
                            },
                            modifier = Modifier.widthIn(min = 88.dp)
                        )
                    }
                }
            }

            // SECTION 3: Language
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_language),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = settings.language == AppLanguage.EN_IN,
                            onClick = { viewModel.setLanguage(AppLanguage.EN_IN) },
                            label = { Text("English (India)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AuroraColors.Violet,
                                selectedLabelColor = AuroraColors.TextPrimary
                            )
                        )
                        FilterChip(
                            selected = settings.language == AppLanguage.EN_US,
                            onClick = { viewModel.setLanguage(AppLanguage.EN_US) },
                            label = { Text("English (US)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AuroraColors.Violet,
                                selectedLabelColor = AuroraColors.TextPrimary
                            )
                        )
                        FilterChip(
                            selected = settings.language == AppLanguage.EN_GB,
                            onClick = { viewModel.setLanguage(AppLanguage.EN_GB) },
                            label = { Text("English (UK)") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AuroraColors.Violet,
                                selectedLabelColor = AuroraColors.TextPrimary
                            )
                        )
                    }
                }
            }

            // SECTION 4: Memory
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_memory),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.settings_auto_learn_title),
                        subtitle = stringResource(R.string.settings_auto_learn_desc),
                        checked = settings.autoLearnEnabled,
                        onCheckedChange = { viewModel.setAutoLearnEnabled(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.settings_ask_before_save_title),
                        subtitle = stringResource(R.string.settings_ask_before_save_desc),
                        checked = settings.askBeforeSavingFact,
                        onCheckedChange = { viewModel.setAskBeforeSavingFact(it) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    AuroraPrimaryButton(
                        text = stringResource(R.string.settings_manage_memory_button),
                        icon = Icons.Default.Memory,
                        onClick = onOpenMemory,
                        modifier = Modifier.widthIn(min = 88.dp).testTag("settings_manage_memory_button")
                    )
                }
            }

            // SECTION 5: Safety & Permissions
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_safety),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.settings_confirm_actions_title),
                        subtitle = stringResource(R.string.settings_confirm_actions_desc),
                        checked = settings.confirmationLevel == ConfirmationLevel.STRICT,
                        onCheckedChange = {
                            viewModel.setConfirmationLevel(
                                if (it) ConfirmationLevel.STRICT else ConfirmationLevel.STANDARD
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.settings_kill_switch_title),
                        subtitle = stringResource(R.string.settings_kill_switch_desc),
                        checked = settings.killSwitchActive,
                        onCheckedChange = { viewModel.setKillSwitch(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    SettingToggleRow(
                        title = stringResource(R.string.settings_floating_orb_title),
                        subtitle = stringResource(R.string.settings_floating_orb_desc),
                        checked = settings.floatingOrbEnabled,
                        onCheckedChange = { viewModel.setFloatingOrbEnabled(it) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    AuroraSecondaryButton(
                        text = stringResource(R.string.settings_permissions_button),
                        icon = Icons.Default.Security,
                        onClick = onOpenPermissionsScreen,
                        modifier = Modifier.widthIn(min = 88.dp)
                    )
                }
            }

            // SECTION 6: About
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_section_about),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Git Commit: ${BuildConfig.GIT_SHA}",
                        style = JarvisMonoStyle.copy(fontSize = 11.sp, color = AuroraColors.Lavender)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Build Time: ${BuildConfig.BUILD_TIME}",
                        style = JarvisMonoStyle.copy(fontSize = 11.sp, color = AuroraColors.TextSecondary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_about_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AuroraTextButton(
                        text = stringResource(R.string.settings_privacy_policy_link),
                        onClick = { showPrivacyPolicyDialog = true },
                        testTag = "settings_privacy_policy_button"
                    )
                }
            }

            // SECTION 7: Advanced (Troubleshooting) - COLLAPSED BY DEFAULT
            item {
                AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { advancedExpanded = !advancedExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BugReport, null, tint = AuroraColors.Lavender)
                            Text(
                                text = stringResource(R.string.settings_section_advanced),
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Icon(
                            imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = AuroraColors.Lavender
                        )
                    }

                    AnimatedVisibility(
                        visible = advancedExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_adv_model_id, settings.selectedModelId),
                                style = JarvisMonoStyle.copy(color = AuroraColors.Lavender)
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_adv_usage,
                                    settings.requestsToday,
                                    settings.tokensToday
                                ),
                                style = JarvisMonoStyle.copy(color = AuroraColors.TextSecondary)
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_adv_latency,
                                    diagnostics.sttLatencyMs,
                                    diagnostics.agentLatencyMs,
                                    diagnostics.ttsLatencyMs
                                ),
                                style = JarvisMonoStyle.copy(color = AuroraColors.TextSecondary)
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_adv_stt_err,
                                    diagnostics.lastSttErrorMessage ?: "None"
                                ),
                                style = JarvisMonoStyle.copy(color = AuroraColors.TextSecondary)
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_adv_voice_id,
                                    diagnostics.activeVoiceId
                                ),
                                style = JarvisMonoStyle.copy(color = AuroraColors.TextSecondary)
                            )

                            Text(
                                text = stringResource(
                                    R.string.diagnostics_timings,
                                    settings.timeToFirstTokenMs,
                                    settings.timeToFirstWordMs
                                ),
                                style = JarvisMonoStyle.copy(color = AuroraColors.Lavender)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            val recentEvents = DiagnosticsLogger.getRecentVoiceEvents()
                            Text(
                                text = stringResource(R.string.diagnostics_events_count, recentEvents.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (recentEvents.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.diagnostics_voice_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    recentEvents.takeLast(5).reversed().forEach { event ->
                                        Text(
                                            text = "[${event.type}] ${event.message}",
                                            style = JarvisMonoStyle.copy(fontSize = 11.sp, color = AuroraColors.TextSecondary)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AuroraSecondaryButton(
                                    text = stringResource(R.string.diagnostics_voice_clear),
                                    onClick = { viewModel.clearVoiceLogs() },
                                    modifier = Modifier.weight(1f)
                                )
                                AuroraSecondaryButton(
                                    text = stringResource(R.string.voice_test_button),
                                    onClick = { viewModel.previewVoice("Voice diagnostics test.") },
                                    modifier = Modifier.weight(1f)
                                )
                                AuroraSecondaryButton(
                                    text = stringResource(R.string.settings_share_log),
                                    icon = Icons.Default.Share,
                                    onClick = { DiagnosticsLogger.shareLog(context) },
                                    modifier = Modifier.weight(1f).testTag("settings_share_log_button")
                                )
                            }
                        }
                    }
                }
            }
        }

        // Change API Key Dialog
        if (showChangeKeyDialog) {
            AlertDialog(
                onDismissRequest = { showChangeKeyDialog = false },
                title = {
                    Text(
                        stringResource(R.string.settings_ai_change_key),
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Enter your new Gemini API key below:",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        OutlinedTextField(
                            value = newKeyInput,
                            onValueChange = { newKeyInput = it },
                            placeholder = { Text("AIza...") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("settings_new_key_input")
                        )
                        if (isVerifyingKey) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Text("Verifying...", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (keyStatusMessage != null) {
                            Text(
                                text = keyStatusMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Lavender
                            )
                        }
                    }
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.settings_ai_key_save),
                        enabled = newKeyInput.isNotBlank() && !isVerifyingKey,
                        onClick = {
                            viewModel.verifyAndSaveNewKey(newKeyInput) {
                                showChangeKeyDialog = false
                            }
                        },
                        modifier = Modifier.testTag("settings_save_key_button")
                    )
                },
                dismissButton = {
                    AuroraSecondaryButton(
                        text = stringResource(R.string.settings_ai_key_cancel),
                        onClick = { showChangeKeyDialog = false }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }

        // Remove Key Confirmation Dialog
        if (showRemoveKeyDialog) {
            AlertDialog(
                onDismissRequest = { showRemoveKeyDialog = false },
                title = {
                    Text("Remove API Key?", color = AuroraColors.Danger, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "This will lock JARVIS and return to the Setup Gate. Your local memories will remain safe.",
                        color = AuroraColors.TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    AuroraDangerButton(
                        text = "Remove Key",
                        onClick = {
                            showRemoveKeyDialog = false
                            viewModel.removeApiKey {
                                onKeyRemoved()
                            }
                        }
                    )
                },
                dismissButton = {
                    AuroraSecondaryButton(
                        text = "Cancel",
                        onClick = { showRemoveKeyDialog = false }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }

        // Privacy Policy Dialog
        if (showPrivacyPolicyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyPolicyDialog = false },
                title = {
                    Text(
                        stringResource(R.string.settings_privacy_policy_title),
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.settings_privacy_policy_body),
                        color = AuroraColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = "OK",
                        onClick = { showPrivacyPolicyDialog = false }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }

        // Wake Consent Dialog
        if (showWakeConsentDialog) {
            AlertDialog(
                onDismissRequest = { showWakeConsentDialog = false },
                title = {
                    Text(
                        stringResource(R.string.wake_phrase_dialog_title),
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.wake_phrase_dialog_body),
                        color = AuroraColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = "Agree",
                        onClick = {
                            showWakeConsentDialog = false
                            viewModel.setWakePhraseEnabled(true)
                        }
                    )
                },
                dismissButton = {
                    AuroraSecondaryButton(
                        text = "Cancel",
                        onClick = { showWakeConsentDialog = false }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AuroraColors.TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AuroraColors.TextPrimary,
                checkedTrackColor = AuroraColors.Violet,
                uncheckedThumbColor = AuroraColors.TextMuted,
                uncheckedTrackColor = AuroraColors.GlassFillLight
            )
        )
    }
}
