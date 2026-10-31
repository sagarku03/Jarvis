package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraGlassSafetySheet
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.AuroraStopPill
import com.example.ui.components.AuroraTextButton
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenPermissions: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val lastResponse by viewModel.lastResponseText.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val isKilled by viewModel.isKilled.collectAsStateWithLifecycle()
    val keyRejectedBanner by viewModel.keyRejectedBanner.collectAsStateWithLifecycle()
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    val rmsEnergy by viewModel.rmsEnergyLevel.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
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

    AuroraBackground(modifier = Modifier.imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top Status Header — responsive FlowRow prevents clipping at 360dp / 1.3x font scale
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = AuroraColors.TextPrimary
                        )
                        AuroraStatusPill(
                            text = settings.language.code,
                            color = AuroraColors.Aqua
                        )
                        AuroraStatusPill(
                            text = stringResource(R.string.home_offline_voice_chip),
                            color = AuroraColors.Lavender,
                            icon = Icons.Default.VolumeUp,
                            modifier = Modifier.testTag("home_offline_voice_chip")
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuroraIconButton(
                        icon = Icons.Default.AddComment,
                        contentDescription = stringResource(R.string.home_new_chat),
                        testTag = "home_new_chat_button",
                        onClick = viewModel::startNewChat,
                        tint = AuroraColors.Lavender
                    )

                    AuroraStopPill(
                        isKilled = isKilled,
                        onToggle = {
                            if (isKilled) viewModel.resetKillSwitch()
                            else viewModel.triggerKillSwitch()
                        },
                        modifier = Modifier.testTag("home_kill_switch_button")
                    )
                }
            }

            // Key rejected banner if 401/403 encountered at runtime
            AnimatedVisibility(visible = keyRejectedBanner) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AuroraColors.CoralDanger.copy(alpha = 0.16f))
                        .border(1.dp, AuroraColors.CoralDanger.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("home_key_rejected_banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = AuroraColors.CoralDanger,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_key_rejected_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextPrimary
                        )
                    }
                    AuroraTextButton(
                        text = stringResource(R.string.home_update_key_action),
                        onClick = onOpenSettings,
                        testTag = "home_update_key_button"
                    )
                }
            }

            // Active Mic Status Row (push-to-talk only)
            if (orbState == AssistantOrbState.LISTENING) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AuroraStatusPill(
                        text = stringResource(R.string.home_mic_active_badge),
                        color = AuroraColors.Aqua,
                        icon = Icons.Default.GraphicEq,
                        modifier = Modifier.testTag("home_mic_active_badge")
                    )
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
                        AssistantOrbState.LISTENING -> AuroraColors.Aqua
                        AssistantOrbState.THINKING -> AuroraColors.Lavender
                        AssistantOrbState.SPEAKING -> AuroraColors.EmeraldSuccess
                        AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.CoralDanger
                        AssistantOrbState.IDLE -> AuroraColors.TextSecondary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("home_state_caption")
                )

                // Live Partial Transcript Banner while listening
                AnimatedVisibility(visible = orbState == AssistantOrbState.LISTENING && partialTranscript.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.home_Partial_prefix, partialTranscript),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuroraColors.Aqua,
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
                            .clip(RoundedCornerShape(14.dp))
                            .background(AuroraColors.AmberWarning.copy(alpha = 0.14f))
                            .border(1.dp, AuroraColors.AmberWarning.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
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
                        AuroraIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = stringResource(R.string.home_cancel_turn),
                            testTag = "home_dismiss_stt_banner",
                            onClick = { viewModel.dismissSttBanner() },
                            tint = AuroraColors.TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                AuroraGlassCard(
                    elevated = true,
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
                        focusedBorderColor = AuroraColors.Violet500,
                        unfocusedBorderColor = AuroraColors.GlassBorder,
                        focusedContainerColor = AuroraColors.SurfacePrimary,
                        unfocusedContainerColor = AuroraColors.SurfacePrimary,
                        focusedTextColor = AuroraColors.TextPrimary,
                        unfocusedTextColor = AuroraColors.TextPrimary
                    )
                )

                AuroraIconButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.home_send),
                    testTag = "home_send_button",
                    enabled = inputDraft.isNotBlank() && !isKilled,
                    activeFillColor = if (inputDraft.isNotBlank() && !isKilled) {
                        AuroraColors.Violet500
                    } else {
                        null
                    },
                    onClick = { viewModel.sendTextCommand() }
                )
            }
        }

        // Glass Safety Bottom Sheet with Contact Disambiguation Picker & Countdown
        pendingConfirmation?.let { request ->
            AuroraGlassSafetySheet(
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
        isUser -> AuroraColors.Violet500.copy(alpha = 0.22f)
        isTool -> AuroraColors.Aqua.copy(alpha = 0.12f)
        else -> AuroraColors.GlassFillStrong
    }
    val borderColor = when {
        isUser -> AuroraColors.Violet500.copy(alpha = 0.45f)
        isTool -> AuroraColors.Aqua.copy(alpha = 0.35f)
        else -> AuroraColors.GlassBorder
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AuroraColors.SurfacePrimary.copy(alpha = 0.85f))
                .background(bubbleColor)
                .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (isTool && message.toolName != null) {
                val formattedToolName = message.toolName
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() }
                Text(
                    text = formattedToolName,
                    style = MaterialTheme.typography.labelSmall,
                    color = AuroraColors.Aqua
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
