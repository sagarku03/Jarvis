package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.domain.model.AgentMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.OrbState
import com.example.domain.model.PendingMemoryCandidate
import com.example.domain.model.VoiceLatencyMetrics
import com.example.services.OverlayService
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassTextField
import com.example.ui.components.GradientButton
import com.example.ui.components.JarvisAnimatedOrb
import com.example.ui.components.SafetyGateConfirmationModal
import com.example.ui.components.StatusChip
import com.example.ui.theme.Aqua
import com.example.ui.theme.Danger
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.GlassStrokeStrong
import com.example.ui.theme.Indigo600
import com.example.ui.theme.Lavender
import com.example.ui.theme.Lime
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Violet500
import com.example.ui.theme.Violet600
import com.example.ui.theme.Warning
import com.example.ui.viewmodel.HomeViewModel

private data class QuickActionChipItem(
    val label: String,
    val command: String,
    val icon: ImageVector
)

private val SAFE_QUICK_CHIPS = listOf(
    QuickActionChipItem("Flashlight", "Turn on flashlight", Icons.Default.FlashlightOn),
    QuickActionChipItem("Alarm", "Set alarm for 7:00 AM", Icons.Default.AccessAlarm),
    QuickActionChipItem("Read screen", "Read screen", Icons.Default.ScreenshotMonitor),
    QuickActionChipItem("Notifications", "Summarize notifications", Icons.Default.NotificationsNone),
    QuickActionChipItem("Morning briefing", "Good morning briefing", Icons.Default.WbSunny)
)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToPermissions: () -> Unit
) {
    val context = LocalContext.current
    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val rmsLevel by viewModel.rmsDbLevel.collectAsStateWithLifecycle()
    val noiseFloorDb by viewModel.noiseFloorDb.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val draftTranscript by viewModel.liveInputTranscript.collectAsStateWithLifecycle()
    val speechError by viewModel.speechError.collectAsStateWithLifecycle()
    val lastResponse by viewModel.lastResponseText.collectAsStateWithLifecycle()
    val currentStepInfo by viewModel.currentStepInfo.collectAsStateWithLifecycle()
    val latencyMetrics by viewModel.latencyMetrics.collectAsStateWithLifecycle()
    val settings by viewModel.userSettings.collectAsStateWithLifecycle()
    val chatHistory by viewModel.chatHistory.collectAsStateWithLifecycle()
    val activeConfirmation by viewModel.activeConfirmation.collectAsStateWithLifecycle()
    val pendingCandidates by viewModel.pendingMemoryCandidates.collectAsStateWithLifecycle()
    val isHalted by viewModel.isKillSwitchHalted.collectAsStateWithLifecycle()
    val isOverlayShowing by viewModel.isOverlayShowing.collectAsStateWithLifecycle()
    val isMicActive by viewModel.isMicActiveIndicator.collectAsStateWithLifecycle()

    var commandInput by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startPushToTalk()
        } else {
            onNavigateToPermissions()
        }
    }

    activeConfirmation?.let { req ->
        SafetyGateConfirmationModal(
            request = req,
            onApprove = { viewModel.resolveSafetyGate(req.requestId, true) },
            onDeny = { viewModel.resolveSafetyGate(req.requestId, false) }
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
            // 1. Header Row: Left = "Namaste" caption + "Ready when you are" Display title;
            // Right = compact STOP pill (height 42dp, red glass, testTag("kill_switch_button"))
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Namaste",
                            fontSize = 13.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Ready when you are",
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    val stopShape = RoundedCornerShape(50)
                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(stopShape)
                            .background(Danger.copy(alpha = 0.16f), stopShape)
                            .border(1.dp, Danger.copy(alpha = 0.55f), stopShape)
                            .clickable(
                                role = Role.Button,
                                onClick = { viewModel.triggerEmergencyKillSwitch() }
                            )
                            .padding(horizontal = 14.dp)
                            .testTag("kill_switch_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Emergency Stop",
                            tint = Danger,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "STOP",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = TextPrimary
                        )
                    }
                }
            }

            // 2. Orb Hero (~270dp) + Sentence-case state label under orb
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    JarvisAnimatedOrb(
                        orbState = orbState,
                        rmsLevel = rmsLevel,
                        isHalted = isHalted,
                        onOrbClick = {
                            when (orbState) {
                                OrbState.SPEAKING -> viewModel.bargeIn()
                                OrbState.LISTENING -> viewModel.stopPushToTalk()
                                else -> requestMicOrStart(context, micPermissionLauncher) {
                                    viewModel.startPushToTalk()
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val stateLabel = when {
                        isHalted -> "Halted – tap the mic to resume"
                        orbState == OrbState.LISTENING -> "Listening…"
                        orbState == OrbState.THINKING -> "Thinking…"
                        orbState == OrbState.SPEAKING -> "Speaking – tap to interrupt"
                        else -> "Tap the mic to talk"
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stateLabel,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.4.sp,
                            color = when {
                                isHalted -> Danger
                                orbState == OrbState.LISTENING -> Aqua
                                orbState == OrbState.SPEAKING -> Lavender
                                else -> TextSecondary
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(role = Role.Button) {
                                    if (orbState == OrbState.LISTENING) {
                                        viewModel.stopPushToTalk()
                                    } else {
                                        requestMicOrStart(context, micPermissionLauncher) {
                                            viewModel.startPushToTalk()
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("push_to_talk_button")
                        )

                        if (orbState == OrbState.SPEAKING) {
                            StatusChip(
                                label = "Interrupt",
                                dotColor = Warning,
                                leadingIcon = Icons.AutoMirrored.Filled.VolumeOff,
                                onClick = { viewModel.bargeIn() },
                                modifier = Modifier.testTag("barge_in_button")
                            )
                        }

                        StatusChip(
                            label = if (isOverlayShowing) "Overlay on" else "Floating orb",
                            dotColor = if (isOverlayShowing) Lime else Lavender,
                            leadingIcon = Icons.Default.BubbleChart,
                            onClick = {
                                toggleFloatingOverlay(context, isOverlayShowing, onNavigateToPermissions)
                            },
                            modifier = Modifier.testTag("overlay_orb_button")
                        )
                    }
                }
            }

            // 3. Horizontal LazyRow of vector-icon chips (no wrap)
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isMicActive) {
                        item(key = "mic_on_chip") {
                            StatusChip(
                                label = "Mic on",
                                dotColor = Lime,
                                modifier = Modifier.testTag("mic_active_indicator")
                            )
                        }
                    }

                    itemsIndexed(SAFE_QUICK_CHIPS) { index, chip ->
                        StatusChip(
                            label = chip.label,
                            leadingIcon = chip.icon,
                            onClick = { viewModel.submitCommand(chip.command) },
                            modifier = Modifier.testTag("quick_prompt_chip_$index")
                        )
                    }
                }
            }

            // 4. GlassCard "You said": caption "YOU SAID" (11sp, TextMuted, spaced) + transcript 17sp semibold
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "YOU SAID",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.0.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val activeTranscript = partialTranscript.ifEmpty { draftTranscript }
                        .ifEmpty { "Tap the mic or type a command in Hindi, Hinglish, or English…" }
                    Text(
                        text = activeTranscript,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.testTag("live_transcript_text")
                    )

                    speechError?.let { err ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Warning
                        )
                    }
                }
            }

            // 5. GlassCard "steps": rows with 24dp rounded icon (✓ green tint = done, lavender = running/waiting)
            val recentToolMessages = chatHistory.filter { it.role == MessageRole.TOOL }.takeLast(3)
            if (currentStepInfo != null || recentToolMessages.isNotEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            currentStepInfo?.let { activeStep ->
                                StepRowItem(
                                    text = activeStep,
                                    isDone = false
                                )
                            }
                            recentToolMessages.forEach { toolMsg ->
                                StepRowItem(
                                    text = "${toolMsg.toolName ?: "tool"}: ${toolMsg.content}",
                                    isDone = true
                                )
                            }
                        }
                    }
                }
            }

            // 6. Assistant reply GlassCard (lastResponseText)
            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Assistant reply",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lastResponse,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        modifier = Modifier.testTag("last_response_text")
                    )
                }
            }

            // 7. Pending Memory-candidate approval as a GlassCard with "Save" (gradient) / "Dismiss" (ghost)
            if (pendingCandidates.isNotEmpty()) {
                items(pendingCandidates, key = { it.id }) { candidate ->
                    MemoryCandidateApprovalCard(
                        candidate = candidate,
                        onApprove = { viewModel.approveMemoryCandidate(candidate.id) },
                        onDismiss = { viewModel.dismissMemoryCandidate(candidate.id) }
                    )
                }
            }

            // 8. Text input as GlassTextField with send icon button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassTextField(
                        value = commandInput,
                        onValueChange = {
                            commandInput = it
                            viewModel.updateDraftTranscript(it)
                        },
                        placeholder = "Ask anything in Hindi, Hinglish, or English…",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (commandInput.isNotBlank()) {
                                    viewModel.submitCommand(commandInput)
                                    commandInput = ""
                                }
                            }
                        ),
                        modifier = Modifier.weight(1f),
                        testTag = "command_input_field"
                    )

                    val sendShape = RoundedCornerShape(16.dp)
                    IconButton(
                        onClick = {
                            if (commandInput.isNotBlank()) {
                                viewModel.submitCommand(commandInput)
                                commandInput = ""
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(sendShape)
                            .background(
                                Brush.linearGradient(listOf(Violet500, Violet600, Indigo600)),
                                sendShape
                            )
                            .border(1.dp, GlassStrokeStrong, sendShape)
                            .testTag("send_command_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send command",
                            tint = Color.White
                        )
                    }
                }
            }

            // Dev-only latency panel (never shown in prod)
            if (BuildConfig.IS_DEV_FLAVOR && settings.showLatencyDebugPanel) {
                item {
                    VoiceLatencyDebugCard(
                        metrics = latencyMetrics,
                        noiseFloorDb = noiseFloorDb
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRowItem(
    text: String,
    isDone: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isDone) Success.copy(alpha = 0.18f) else Lavender.copy(alpha = 0.18f)
                )
                .border(
                    1.dp,
                    if (isDone) Success.copy(alpha = 0.45f) else Lavender.copy(alpha = 0.45f),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDone) Icons.Default.Check else Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = if (isDone) Success else Lavender,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            color = if (isDone) TextSecondary else TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MemoryCandidateApprovalCard(
    candidate: PendingMemoryCandidate,
    onApprove: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Save to memory?",
            style = MaterialTheme.typography.labelLarge,
            color = Lavender
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${candidate.keyLabel}: ${candidate.contentValue}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GhostButton(
                text = "Dismiss",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                testTag = "dismiss_memory_candidate_${candidate.id}"
            )
            GradientButton(
                text = "Save",
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                testTag = "approve_memory_candidate_${candidate.id}"
            )
        }
    }
}

@Composable
private fun VoiceLatencyDebugCard(
    metrics: VoiceLatencyMetrics,
    noiseFloorDb: Float
) {
    val statusColor = if (metrics.meetsSub1200MsTarget) Success else Warning
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("latency_debug_panel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Voice latency (dev)",
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor
                )
            }
            Text(
                text = "${metrics.endOfSpeechToFirstAudioMs} ms",
                style = MaterialTheme.typography.labelLarge,
                color = statusColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "STT: ${metrics.sttEndpointLatencyMs} ms • Step: ${metrics.backendStepLatencyMs} ms • 1st audio: ${metrics.firstAudioChunkLatencyMs} ms • Noise: ${"%.1f".format(noiseFloorDb)} dB",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

private fun requestMicOrStart(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
    onGranted: () -> Unit
) {
    val hasPerm = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPerm) {
        onGranted()
    } else {
        launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private fun toggleFloatingOverlay(
    context: Context,
    currentlyShowing: Boolean,
    onNavigateToPermissions: () -> Unit
) {
    if (!Settings.canDrawOverlays(context)) {
        onNavigateToPermissions()
        return
    }
    val intent = Intent(context, OverlayService::class.java).apply {
        action = if (currentlyShowing) OverlayService.ACTION_HIDE else OverlayService.ACTION_SHOW
    }
    runCatching {
        context.startService(intent)
    }
}
