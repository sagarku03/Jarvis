package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
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
import com.example.domain.model.MessageRole
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraGlassSafetySheet
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraTextButton
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenVoiceCall: () -> Unit = {},
    onOpenPermissions: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val orbCaption by viewModel.orbCaption.collectAsStateWithLifecycle()
    val userFacingError by viewModel.userFacingError.collectAsStateWithLifecycle()
    val lastResponse by viewModel.lastResponseText.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isUsingFallbackChip by viewModel.isUsingFallbackChip.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val isKilled by viewModel.isKilled.collectAsStateWithLifecycle()
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    val isInputVisible by viewModel.isInputVisible.collectAsStateWithLifecycle()
    val rmsEnergy by viewModel.rmsEnergyLevel.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    val showTutorial by viewModel.showTutorial.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.togglePushToTalk()
        }
    }

    // Centered single-line status text below orb
    val orbLineText = when (orbState) {
        AssistantOrbState.IDLE -> stringResource(R.string.home_tap_to_talk)
        AssistantOrbState.LISTENING -> {
            if (partialTranscript.isNotBlank()) partialTranscript
            else stringResource(R.string.home_listening)
        }
        AssistantOrbState.THINKING -> orbCaption ?: stringResource(R.string.home_thinking)
        AssistantOrbState.SPEAKING -> lastResponse
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
            // Clean Header: Jarvis (left) + Talk button + Small status dot (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Talk button (hands-free full-screen call)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.GlassFillLight)
                            .clickable { onOpenVoiceCall() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("home_talk_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = AuroraColors.Aqua,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(R.string.voice_call_talk),
                                style = MaterialTheme.typography.labelSmall,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Small Status Dot
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.GlassFillLight)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        val dotColor = when (orbState) {
                            AssistantOrbState.IDLE -> AuroraColors.Success
                            AssistantOrbState.LISTENING -> AuroraColors.Warning
                            AssistantOrbState.THINKING -> AuroraColors.ElectricBlue
                            AssistantOrbState.SPEAKING -> AuroraColors.EmeraldSuccess
                            AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.Danger
                        }
                        val statusLabel = when (orbState) {
                            AssistantOrbState.IDLE -> stringResource(R.string.home_status_ready)
                            AssistantOrbState.LISTENING -> stringResource(R.string.home_status_listening)
                            AssistantOrbState.THINKING -> stringResource(R.string.home_status_thinking)
                            AssistantOrbState.SPEAKING -> stringResource(R.string.home_status_speaking)
                            AssistantOrbState.PAUSED_KILLSWITCH -> "Stopped"
                        }

                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Dismissible Error Banner at top
            AnimatedVisibility(visible = userFacingError != null) {
                userFacingError?.let { err ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.CoralDanger.copy(alpha = 0.16f))
                            .border(1.dp, AuroraColors.CoralDanger.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("home_error_banner"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = AuroraColors.CoralDanger,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = err.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuroraTextButton(
                                text = err.actionLabel,
                                onClick = {
                                    if (err.isOpenSettings) {
                                        onOpenSettings()
                                    } else {
                                        viewModel.retryLastCommand()
                                    }
                                    viewModel.clearUserFacingError()
                                },
                                testTag = "home_error_action_button"
                            )
                            AuroraIconButton(
                                icon = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                onClick = { viewModel.clearUserFacingError() },
                                size = 32.dp,
                                tint = AuroraColors.TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Assistant Orb in Center
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

                Spacer(modifier = Modifier.height(6.dp))

                // Below orb: one clear line of text
                Text(
                    text = orbLineText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (orbState) {
                        AssistantOrbState.LISTENING -> AuroraColors.Aqua
                        AssistantOrbState.THINKING -> AuroraColors.Lavender
                        AssistantOrbState.SPEAKING -> AuroraColors.EmeraldSuccess
                        AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.Danger
                        AssistantOrbState.IDLE -> AuroraColors.TextSecondary
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .testTag("home_state_caption")
                )

                AnimatedVisibility(visible = isUsingFallbackChip) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.Warning.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("home_using_phone_voice_chip")
                    ) {
                        Text(
                            text = stringResource(R.string.voice_using_phone_chip),
                            style = MaterialTheme.typography.labelSmall,
                            color = AuroraColors.Warning
                        )
                    }
                }

                // ONE circular red STOP button appearing ONLY while working or speaking
                AnimatedVisibility(
                    visible = orbState == AssistantOrbState.THINKING ||
                        orbState == AssistantOrbState.SPEAKING ||
                        orbState == AssistantOrbState.LISTENING
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(AuroraColors.Danger)
                            .clickable { viewModel.cancelCurrentTurn() }
                            .testTag("home_stop_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.home_cancel_turn),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Conversation List or Empty State with Friendly Greeting
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (userName.isNotBlank()) {
                                    stringResource(R.string.home_greeting_named, userName)
                                } else {
                                    stringResource(R.string.home_greeting_default)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = stringResource(R.string.home_Empty_transcript),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextMuted,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 3 tappable example chips
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ExampleCommandChip(
                                    text = stringResource(R.string.home_sample_timer),
                                    onClick = { viewModel.sendSampleCommand("Set a timer for 10 minutes") }
                                )
                                ExampleCommandChip(
                                    text = stringResource(R.string.home_sample_flashlight),
                                    onClick = { viewModel.sendSampleCommand("Turn on flashlight") }
                                )
                                ExampleCommandChip(
                                    text = stringResource(R.string.home_sample_youtube),
                                    onClick = { viewModel.sendSampleCommand("Play jazz on YouTube") }
                                )
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        ChatBubbleItem(msg)
                    }
                }
                if (!streamingText.isNullOrBlank()) {
                    item {
                        ChatBubbleItem(
                            ChatMessage(
                                role = MessageRole.ASSISTANT,
                                content = streamingText.orEmpty()
                            )
                        )
                    }
                }
            }

            // Keyboard Text Input Drawer
            AnimatedVisibility(
                visible = isInputVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
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
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AuroraColors.Violet,
                            unfocusedBorderColor = AuroraColors.GlassBorder,
                            focusedContainerColor = AuroraColors.GlassFillLight,
                            unfocusedContainerColor = AuroraColors.GlassFillDark,
                            focusedTextColor = AuroraColors.TextPrimary,
                            unfocusedTextColor = AuroraColors.TextPrimary
                        )
                    )

                    AuroraIconButton(
                        icon = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.home_send),
                        testTag = "home_send_button",
                        enabled = inputDraft.isNotBlank() && !isKilled,
                        activeFillColor = AuroraColors.Violet,
                        onClick = { viewModel.sendTextCommand(isVoice = false) }
                    )
                }
            }

            // Bottom Bar: Keyboard toggle (left), Large Mic button (center), + New chat (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keyboard toggle button (left)
                AuroraIconButton(
                    icon = Icons.Default.Keyboard,
                    contentDescription = "Toggle Keyboard Input",
                    testTag = "home_toggle_keyboard",
                    activeFillColor = if (isInputVisible) AuroraColors.Violet else null,
                    onClick = { viewModel.toggleInputVisibility() },
                    size = 44.dp
                )

                // Large Circular Mic Button in Center (56dp)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (orbState == AssistantOrbState.LISTENING) AuroraColors.Aqua
                            else AuroraColors.Violet
                        )
                        .clickable {
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
                        .testTag("home_mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.home_tap_to_talk),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // + New chat button (right)
                AuroraIconButton(
                    icon = Icons.Default.AddComment,
                    contentDescription = stringResource(R.string.home_new_chat),
                    testTag = "home_new_chat_button",
                    onClick = viewModel::startNewChat,
                    size = 44.dp,
                    tint = AuroraColors.Lavender
                )
            }
        }

        // Confirmation Safety Bottom Sheet
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

        // First Arrival Tutorial Dialog (3 cards + 'Got it' button)
        if (showTutorial) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissTutorial() },
                title = {
                    Text(
                        text = stringResource(R.string.setup_tutorial_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TutorialCard(
                            title = stringResource(R.string.setup_tutorial_card1_title),
                            desc = stringResource(R.string.setup_tutorial_card1_desc)
                        )
                        TutorialCard(
                            title = stringResource(R.string.setup_tutorial_card2_title),
                            desc = stringResource(R.string.setup_tutorial_card2_desc)
                        )
                        TutorialCard(
                            title = stringResource(R.string.setup_tutorial_card3_title),
                            desc = stringResource(R.string.setup_tutorial_card3_desc)
                        )
                    }
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.setup_tutorial_got_it),
                        onClick = { viewModel.dismissTutorial() }
                    )
                },
                containerColor = AuroraColors.BackgroundStart,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun TutorialCard(title: String, desc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AuroraColors.GlassFillLight)
            .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = AuroraColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = AuroraColors.TextSecondary
        )
    }
}

@Composable
private fun ExampleCommandChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AuroraColors.GlassFillLight)
            .border(1.dp, AuroraColors.Violet.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AuroraColors.Lavender
        )
    }
}

@Composable
private fun ChatBubbleItem(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    val isTool = message.role == MessageRole.TOOL

    if (isTool) {
        // Tool actions rendered as clean single line with icon — never raw JSON
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = AuroraColors.Success,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.labelSmall,
                color = AuroraColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    val bubbleColor = if (isUser) {
        AuroraColors.Violet.copy(alpha = 0.22f)
    } else {
        AuroraColors.GlassFillStrong
    }
    val borderColor = if (isUser) {
        AuroraColors.Violet.copy(alpha = 0.45f)
    } else {
        AuroraColors.GlassBorder
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextPrimary
            )
        }
    }
}
