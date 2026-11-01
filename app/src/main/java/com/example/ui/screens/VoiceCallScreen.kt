package com.example.ui.screens

import android.app.Activity
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.VoiceSessionState
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.JarvisOrb
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

@Composable
fun VoiceCallScreen(
    viewModel: HomeViewModel,
    onEndCall: () -> Unit
) {
    val context = LocalContext.current
    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.partialTranscript.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val lastResponse by viewModel.lastResponseText.collectAsStateWithLifecycle()
    val userFacingError by viewModel.userFacingError.collectAsStateWithLifecycle()
    val isUsingFallbackChip by viewModel.isUsingFallbackChip.collectAsStateWithLifecycle()

    var isMicMuted by remember { mutableStateOf(false) }
    var silenceSeconds by remember { mutableIntStateOf(0) }

    BackHandler {
        viewModel.cancelCurrentTurn()
        onEndCall()
    }

    // Keep screen on during full-screen voice call
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Listen for incoming calls and end session
        val telephonyManager = context.getSystemService(Activity.TELEPHONY_SERVICE) as? TelephonyManager
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    viewModel.cancelCurrentTurn()
                    onEndCall()
                }
            }
        }
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)

        // Automatically start listening on entry
        viewModel.setInVoiceCall(true)
        viewModel.startPushToTalk()

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
            viewModel.setInVoiceCall(false)
            viewModel.cancelCurrentTurn()
        }
    }

    // 20s silence timer: ends call gracefully if no speech detected
    LaunchedEffect(orbState) {
        if (orbState == AssistantOrbState.LISTENING) {
            silenceSeconds = 0
            while (orbState == AssistantOrbState.LISTENING) {
                delay(1000L)
                silenceSeconds++
                if (silenceSeconds >= 20) {
                    viewModel.cancelCurrentTurn()
                    onEndCall()
                    break
                }
            }
        } else {
            silenceSeconds = 0
        }
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Title & Fallback Chip
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.voice_call_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                val statusText = when (orbState) {
                    AssistantOrbState.LISTENING -> stringResource(R.string.voice_call_listening)
                    AssistantOrbState.THINKING -> stringResource(R.string.voice_call_thinking)
                    AssistantOrbState.SPEAKING -> stringResource(R.string.voice_call_speaking)
                    else -> stringResource(R.string.home_status_ready)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuroraColors.Lavender
                )

                AnimatedVisibility(visible = isUsingFallbackChip) {
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AuroraColors.Warning.copy(alpha = 0.2f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.voice_using_phone_chip),
                            style = MaterialTheme.typography.labelSmall,
                            color = AuroraColors.Warning
                        )
                    }
                }
            }

            // Center: Large Animated Orb with Barge-In Tap
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clickable {
                        // Tapping orb while speaking interrupts immediately
                        if (orbState == AssistantOrbState.SPEAKING) {
                            viewModel.togglePushToTalk()
                        } else if (orbState == AssistantOrbState.IDLE) {
                            viewModel.startPushToTalk()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                JarvisOrb(
                    state = orbState,
                    rmsEnergy = if (orbState == AssistantOrbState.LISTENING) 0.6f else 0f,
                    onClick = {
                        if (orbState == AssistantOrbState.SPEAKING) {
                            viewModel.togglePushToTalk()
                        } else if (orbState == AssistantOrbState.IDLE) {
                            viewModel.startPushToTalk()
                        }
                    },
                    modifier = Modifier.size(220.dp)
                )
            }

            // Live Captions: You said & Jarvis response
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val userSaid = partialTranscript.ifBlank { "" }
                if (userSaid.isNotBlank()) {
                    AuroraGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.voice_call_you_prefix, userSaid),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.Lavender,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                val assistantWords = streamingText ?: lastResponse
                if (assistantWords.isNotBlank() && assistantWords != stringResource(R.string.home_greeting_default)) {
                    AuroraGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.voice_call_jarvis_prefix, assistantWords),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (userFacingError != null) {
                    Text(
                        text = userFacingError!!.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.Danger,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Bottom Controls: Mute Microphone & Red circular END Call button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute mic button
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(if (isMicMuted) AuroraColors.Danger.copy(alpha = 0.25f) else AuroraColors.GlassFillLight)
                        .clickable {
                            isMicMuted = !isMicMuted
                            if (isMicMuted) {
                                viewModel.cancelCurrentTurn()
                            } else {
                                viewModel.startPushToTalk()
                            }
                        }
                        .testTag("voice_call_mute_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMicMuted) stringResource(R.string.voice_call_unmute) else stringResource(R.string.voice_call_mute),
                        tint = if (isMicMuted) AuroraColors.Danger else AuroraColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Red circular END button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(AuroraColors.Danger)
                        .clickable {
                            viewModel.cancelCurrentTurn()
                            onEndCall()
                        }
                        .testTag("voice_call_end_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = stringResource(R.string.voice_call_end),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    }
}
