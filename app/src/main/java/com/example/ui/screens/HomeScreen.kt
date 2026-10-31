package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationRequest
import com.example.domain.model.MessageRole
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.JarvisOrb
import com.example.ui.components.RiskLevelBadge
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val lastResponse by viewModel.lastResponseText.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val isKilled by viewModel.isKilled.collectAsStateWithLifecycle()
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    val rmsEnergy by viewModel.rmsEnergyLevel.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val isWakeMonitoring by viewModel.isWakeWordMonitoring.collectAsStateWithLifecycle()
    val isWakePaused by viewModel.isWakeWordPaused.collectAsStateWithLifecycle()
    val isOfflineVoiceActive by viewModel.isOfflineVoiceActive.collectAsStateWithLifecycle()
    val sttStatusBanner by viewModel.sttStatusBanner.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.togglePushToTalk()
        }
    }

    val stateStatusText = when (orbState) {
        AssistantOrbState.IDLE -> stringResource(R.string.home_tap_to_talk)
        AssistantOrbState.LISTENING -> stringResource(R.string.home_listening)
        AssistantOrbState.THINKING -> stringResource(R.string.home_thinking)
        AssistantOrbState.SPEAKING -> stringResource(R.string.home_speaking)
        AssistantOrbState.PAUSED_KILLSWITCH -> stringResource(R.string.home_paused_killswitch)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AuroraColors.BackgroundDeep,
                        AuroraColors.SurfacePrimary,
                        AuroraColors.BackgroundDeep
                    )
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = AuroraColors.TextPrimary
                        )
                        AuroraStatusPill(
                            text = settings.language.code,
                            color = AuroraColors.CyanGlow
                        )
                        if (isOfflineVoiceActive) {
                            AuroraStatusPill(
                                text = stringResource(R.string.home_offline_voice_chip),
                                color = AuroraColors.AmberWarning,
                                icon = Icons.Default.VolumeUp,
                                modifier = Modifier.testTag("home_offline_voice_chip")
                            )
                        } else {
                            AuroraStatusPill(
                                text = stringResource(R.string.home_neural_voice_chip),
                                color = AuroraColors.ElectricViolet,
                                icon = Icons.Default.GraphicEq,
                                modifier = Modifier.testTag("home_neural_voice_chip")
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                }

                // KillSwitch Emergency Stop / Resume Button
                Button(
                    onClick = {
                        if (isKilled) viewModel.resetKillSwitch()
                        else viewModel.triggerKillSwitch()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isKilled) AuroraColors.EmeraldSuccess else AuroraColors.CoralDanger,
                        contentColor = AuroraColors.TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("home_kill_switch_button")
                ) {
                    Icon(
                        imageVector = if (isKilled) Icons.Default.PowerSettingsNew else Icons.Default.StopCircle,
                        contentDescription = if (isKilled) {
                            stringResource(R.string.home_kill_switch_resume)
                        } else {
                            stringResource(R.string.home_kill_switch_trigger)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isKilled) {
                            stringResource(R.string.home_kill_switch_resume)
                        } else {
                            stringResource(R.string.home_kill_switch_trigger)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Active Mic / Wake Word Status Row
            if (orbState == AssistantOrbState.LISTENING || settings.wakeWordEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (orbState == AssistantOrbState.LISTENING) {
                        AuroraStatusPill(
                            text = stringResource(R.string.home_mic_active_badge),
                            color = AuroraColors.CyanGlow,
                            icon = Icons.Default.GraphicEq,
                            modifier = Modifier.testTag("home_mic_active_badge")
                        )
                    } else if (isWakePaused) {
                        AuroraStatusPill(
                            text = stringResource(R.string.home_wake_paused_badge),
                            color = AuroraColors.AmberWarning,
                            icon = Icons.Default.MicOff,
                            modifier = Modifier.testTag("home_wake_paused_badge")
                        )
                    } else if (isWakeMonitoring) {
                        AuroraStatusPill(
                            text = stringResource(R.string.home_wake_listening_badge),
                            color = AuroraColors.EmeraldSuccess,
                            icon = Icons.Default.Hearing,
                            modifier = Modifier.testTag("home_wake_listening_badge")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Hero Breathing Orb + State Caption + Latest Reply Card
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                JarvisOrb(
                    state = orbState,
                    rmsEnergy = rmsEnergy,
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.togglePushToTalk()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                Text(
                    text = stateStatusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (orbState) {
                        AssistantOrbState.LISTENING -> AuroraColors.CyanGlow
                        AssistantOrbState.THINKING -> AuroraColors.ElectricViolet
                        AssistantOrbState.SPEAKING -> AuroraColors.EmeraldSuccess
                        AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.CoralDanger
                        AssistantOrbState.IDLE -> AuroraColors.TextSecondary
                    },
                    modifier = Modifier.testTag("home_state_caption")
                )

                // Live Partial Transcript Banner while listening
                AnimatedVisibility(visible = orbState == AssistantOrbState.LISTENING && partialTranscript.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.home_Partial_prefix, partialTranscript),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuroraColors.CyanGlow,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(top = 4.dp, start = 16.dp, end = 16.dp)
                            .testTag("home_partial_transcript")
                    )
                }

                // Inline non-spoken STT status banner
                AnimatedVisibility(visible = !sttStatusBanner.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.AmberWarning.copy(alpha = 0.14f))
                            .border(1.dp, AuroraColors.AmberWarning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("home_stt_status_banner"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = AuroraColors.AmberWarning,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = sttStatusBanner.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextPrimary,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        IconButton(
                            onClick = { viewModel.dismissSttBanner() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.home_cancel_turn),
                                tint = AuroraColors.TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                AuroraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_latest_reply_card")
                ) {
                    Text(
                        text = lastResponse,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuroraColors.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Conversation Transcript History
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("home_chat_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.home_Empty_transcript),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AuroraColors.TextMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubbleItem(msg)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Command Input Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputDraft,
                    onValueChange = viewModel::updateDraft,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("home_text_input"),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.home_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextMuted
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuroraColors.ElectricViolet,
                        unfocusedBorderColor = AuroraColors.GlassBorder,
                        focusedContainerColor = AuroraColors.SurfacePrimary,
                        unfocusedContainerColor = AuroraColors.SurfacePrimary,
                        focusedTextColor = AuroraColors.TextPrimary,
                        unfocusedTextColor = AuroraColors.TextPrimary
                    )
                )

                IconButton(
                    onClick = { viewModel.sendTextCommand() },
                    enabled = inputDraft.isNotBlank() && !isKilled,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (inputDraft.isNotBlank() && !isKilled) {
                                AuroraColors.ElectricViolet
                            } else {
                                AuroraColors.SurfaceElevated
                            }
                        )
                        .testTag("home_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.home_send),
                        tint = AuroraColors.TextPrimary
                    )
                }
            }
        }

        // SafetyGate High/Medium Risk Confirmation Modal with Contact Disambiguation Picker & Countdown
        pendingConfirmation?.let { request ->
            SafetyConfirmationModal(
                request = request,
                onApprove = { selectedPhone ->
                    viewModel.resolveConfirmation(approved = true, selectedPhoneOverride = selectedPhone)
                },
                onDeny = {
                    viewModel.resolveConfirmation(approved = false)
                }
            )
        }
    }
}

@Composable
private fun ChatBubbleItem(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isTool = message.role == MessageRole.TOOL

    val bubbleColor = when {
        isUser -> AuroraColors.ElectricViolet.copy(alpha = 0.22f)
        isTool -> AuroraColors.CyanGlow.copy(alpha = 0.12f)
        else -> AuroraColors.SurfaceElevated
    }
    val borderColor = when {
        isUser -> AuroraColors.ElectricViolet.copy(alpha = 0.45f)
        isTool -> AuroraColors.CyanGlow.copy(alpha = 0.35f)
        else -> AuroraColors.GlassBorder
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isTool && message.toolName != null) {
                Text(
                    text = message.toolName.replace('_', ' ').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AuroraColors.CyanGlow
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextPrimary
            )
        }
    }
}

@Composable
private fun SafetyConfirmationModal(
    request: ConfirmationRequest,
    onApprove: (String?) -> Unit,
    onDeny: () -> Unit
) {
    var remainingSeconds by remember(request.id) { mutableIntStateOf(request.countdownSeconds) }
    var selectedPhone by remember(request.id) {
        mutableStateOf(request.candidateContacts.firstOrNull()?.phoneNumber)
    }

    LaunchedEffect(request.id) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        onDeny()
    }

    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = AuroraColors.SurfaceElevated,
        titleContentColor = AuroraColors.TextPrimary,
        textContentColor = AuroraColors.TextSecondary,
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = stringResource(
                    R.string.safety_confirm_title,
                    request.riskLevel.name
                ),
                tint = AuroraColors.AmberWarning,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.safety_confirm_title, request.riskLevel.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary
                )
                RiskLevelBadge(riskLevel = request.riskLevel)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = request.summaryEn,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )

                if (request.candidateContacts.size > 1) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.safety_contact_picker_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = AuroraColors.CyanGlow
                    )
                    request.candidateContacts.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedPhone = candidate.phoneNumber }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPhone == candidate.phoneNumber,
                                onClick = { selectedPhone = candidate.phoneNumber }
                            )
                            Column {
                                Text(
                                    text = candidate.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AuroraColors.TextPrimary
                                )
                                Text(
                                    text = candidate.maskedNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.safety_confirm_Countdown, remainingSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = AuroraColors.CoralDanger
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApprove(selectedPhone) },
                colors = ButtonDefaults.buttonColors(containerColor = AuroraColors.EmeraldSuccess),
                modifier = Modifier.testTag("safety_approve_button")
            ) {
                Text(
                    text = stringResource(R.string.safety_confirm_approve),
                    color = AuroraColors.BackgroundDeep,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.testTag("safety_deny_button")
            ) {
                Text(
                    text = stringResource(R.string.safety_confirm_deny),
                    color = AuroraColors.TextPrimary
                )
            }
        },
        modifier = Modifier.testTag("safety_confirmation_dialog")
    )
}
