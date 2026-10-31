package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.domain.model.OrbState
import com.example.domain.model.RiskLevel
import com.example.domain.model.SafetyConfirmationRequest
import com.example.ui.theme.Aqua
import com.example.ui.theme.Danger
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.GlassStrokeStrong
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.Lavender
import com.example.ui.theme.Lime
import com.example.ui.theme.SafetySheetFill
import com.example.ui.theme.ScrimBackdrop
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.ThinkingMagenta
import com.example.ui.theme.Violet500
import com.example.ui.theme.Violet600
import com.example.ui.theme.Warning
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * 2.4 Orb Hero:
 * Height ~270dp: soft radial glow behind (360dp, violet 50% -> blue 20% -> transparent),
 * two thin rings (250dp lavender 30%, 210dp blue 28%), 156dp gradient sphere
 * (radial: #EADFFF at 34%/28% -> #A57BFF -> #6D3AF0 -> #2A1B8F), violet glow shadow, inner shade.
 * Aqua 1.5dp ring (+14dp) with aqua glow when LISTENING.
 * Centered live waveform bars (7 bars, white gradient, heights driven by rmsDbLevel) while listening/speaking.
 * Respects reduce-motion (no infinite animations when enabled).
 */
@Composable
fun JarvisAnimatedOrb(
    orbState: OrbState,
    rmsLevel: Float,
    onOrbClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHalted: Boolean = false
) {
    val reduceMotion = rememberReduceMotionEnabled()

    val pulseScale: Float
    val rotationDeg: Float
    val wavePhase: Float

    if (reduceMotion) {
        pulseScale = 1.0f
        rotationDeg = 0f
        wavePhase = 0f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "aurora_orb_transition")
        val animatedPulse by infiniteTransition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when (orbState) {
                        OrbState.IDLE -> 2600
                        OrbState.LISTENING -> 850
                        OrbState.THINKING -> 1100
                        OrbState.SPEAKING -> 700
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orb_pulse"
        )
        val animatedRotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (orbState == OrbState.THINKING) 3200 else 9000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "orb_rotation"
        )
        val animatedWave by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 6.28318f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orb_wave"
        )
        pulseScale = animatedPulse
        rotationDeg = animatedRotation
        wavePhase = animatedWave
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(270.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    onClick = onOrbClick
                )
                .semantics {
                    contentDescription = when (orbState) {
                        OrbState.IDLE -> "JARVIS orb idle, tap to talk"
                        OrbState.LISTENING -> "JARVIS orb listening, tap to stop"
                        OrbState.THINKING -> "JARVIS orb thinking"
                        OrbState.SPEAKING -> "JARVIS orb speaking, tap to interrupt"
                    }
                }
                .testTag("jarvis_orb"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val rmsBoost = 1f + (rmsLevel.coerceIn(0f, 1f) * 0.14f)

                // 1. Soft radial glow behind (violet 50% -> blue 20% -> transparent)
                val outerGlowRadius = (size.minDimension * 0.50f) * pulseScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Violet500.copy(alpha = 0.50f),
                            ElectricBlue.copy(alpha = 0.20f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = outerGlowRadius.coerceAtLeast(10f)
                    ),
                    radius = outerGlowRadius,
                    center = center
                )

                // 2. Outer thin ring (250dp lavender 30%)
                val ring250Radius = (250.dp.toPx() / 2f).coerceAtMost(size.minDimension * 0.48f)
                drawCircle(
                    color = Lavender.copy(alpha = 0.30f),
                    radius = ring250Radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                // 3. Inner thin ring (210dp blue 28%)
                val ring210Radius = (210.dp.toPx() / 2f).coerceAtMost(size.minDimension * 0.40f)
                drawCircle(
                    color = ElectricBlue.copy(alpha = 0.28f),
                    radius = ring210Radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )

                val sphereRadius = (156.dp.toPx() / 2f) * pulseScale * rmsBoost

                // 4. State-specific outer ring (Halted = Danger, Listening = Aqua +14dp, Thinking = Magenta arc)
                when {
                    isHalted -> {
                        drawCircle(
                            color = Danger,
                            radius = sphereRadius + 12.dp.toPx(),
                            center = center,
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                    }
                    orbState == OrbState.LISTENING -> {
                        val aquaRingRadius = sphereRadius + 14.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Aqua.copy(alpha = 0.32f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = aquaRingRadius + 12.dp.toPx()
                            ),
                            radius = aquaRingRadius + 10.dp.toPx(),
                            center = center
                        )
                        drawCircle(
                            color = Aqua,
                            radius = aquaRingRadius,
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                    orbState == OrbState.THINKING -> {
                        rotate(degrees = rotationDeg, pivot = center) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Violet500,
                                        ThinkingMagenta,
                                        ElectricBlue,
                                        Violet500
                                    ),
                                    center = center
                                ),
                                radius = sphereRadius + 10.dp.toPx(),
                                center = center,
                                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                    orbState == OrbState.SPEAKING -> {
                        drawCircle(
                            color = Lavender.copy(alpha = 0.55f),
                            radius = sphereRadius + 10.dp.toPx(),
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // 5. Core 156dp gradient sphere (radial: #EADFFF at 34%/28% -> #A57BFF -> #6D3AF0 -> #2A1B8F)
                val highlightCenter = Offset(
                    x = center.x - sphereRadius * 0.32f,
                    y = center.y - sphereRadius * 0.44f
                )
                val sphereColors = if (orbState == OrbState.THINKING) {
                    listOf(
                        Color(0xFFEADFFF),
                        ThinkingMagenta,
                        Violet600,
                        Color(0xFF2A1B8F)
                    )
                } else {
                    listOf(
                        Color(0xFFEADFFF),
                        Color(0xFFA57BFF),
                        Violet600,
                        Color(0xFF2A1B8F)
                    )
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = sphereColors,
                        center = highlightCenter,
                        radius = (sphereRadius * 1.45f).coerceAtLeast(10f)
                    ),
                    radius = sphereRadius,
                    center = center
                )

                // Inner rim highlight
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            Color.White.copy(alpha = 0.08f)
                        ),
                        start = Offset(center.x - sphereRadius, center.y - sphereRadius),
                        end = Offset(center.x + sphereRadius, center.y + sphereRadius)
                    ),
                    radius = sphereRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // 6. Centered live waveform bars (7 bars, white gradient) when LISTENING or SPEAKING
                if (orbState == OrbState.LISTENING || orbState == OrbState.SPEAKING) {
                    val barCount = 7
                    val barWidth = 4.dp.toPx()
                    val barSpacing = 6.dp.toPx()
                    val totalWidth = barCount * barWidth + (barCount - 1) * barSpacing
                    val startX = center.x - totalWidth / 2f
                    val maxBarHeight = 46.dp.toPx()
                    val minBarHeight = 10.dp.toPx()
                    val effectiveLevel = rmsLevel.coerceIn(0.15f, 1f)

                    for (i in 0 until barCount) {
                        val centerWeight = 1f - (kotlin.math.abs(i - 3) * 0.18f)
                        val waveFactor = if (reduceMotion) {
                            0.65f
                        } else {
                            0.5f + 0.5f * sin(wavePhase + i * 0.85f)
                        }
                        val barHeight = (minBarHeight + (maxBarHeight - minBarHeight) *
                            centerWeight * (0.4f + 0.6f * effectiveLevel * waveFactor))
                            .coerceIn(minBarHeight, maxBarHeight)

                        val left = startX + i * (barWidth + barSpacing)
                        val top = center.y - barHeight / 2f
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White,
                                    Color.White.copy(alpha = 0.70f)
                                ),
                                startY = top,
                                endY = top + barHeight
                            ),
                            topLeft = Offset(left, top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                        )
                    }
                }
            }

            // Center icon when IDLE or THINKING, plus Speaking Lime dot
            if (orbState == OrbState.IDLE || orbState == OrbState.THINKING) {
                Icon(
                    imageVector = if (orbState == OrbState.THINKING) {
                        Icons.Default.Psychology
                    } else {
                        Icons.Default.Mic
                    },
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(34.dp)
                )
            } else if (orbState == OrbState.SPEAKING) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(38.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(Lime)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }
    }
}

/**
 * 2.5 Safety Confirmation Sheet (match mock "3 · Safety confirmation sheet"):
 * Bottom glass sheet: radius 32dp, fill rgba(24,17,50,86%), 1dp strong border, violet top glow,
 * grab handle (40x4dp).
 * Header: 52dp rounded shield icon tile (Danger tint for HIGH, Warning for MEDIUM) +
 * title "Confirm action" + risk chip (LOW green / MEDIUM amber / HIGH red, ALL-CAPS 11sp).
 * Body: key-value card (Action, Contact/target with masked number, Mode).
 * Row: warning dot + "Auto-cancels in 30 s if you don't respond" with live countdown; timeout = deny.
 * Buttons: Cancel (GhostButton, weight 1) + "Approve & <action>" (GradientButton, weight 1.4).
 * HIGH risk requires an explicit checkbox before Approve is enabled. Background dims (#A004020C).
 */
@Composable
fun SafetyGateConfirmationModal(
    request: SafetyConfirmationRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    var highRiskAcknowledged by remember(request.requestId) { mutableStateOf(false) }
    var remainingSeconds by remember(request.requestId) { mutableIntStateOf(30) }

    LaunchedEffect(request.requestId) {
        remainingSeconds = 30
        while (remainingSeconds > 0) {
            delay(1_000L)
            remainingSeconds--
        }
        onDeny()
    }

    val isHighRisk = request.riskLevel == RiskLevel.HIGH
    val riskColor = when (request.riskLevel) {
        RiskLevel.LOW -> Success
        RiskLevel.MEDIUM -> Warning
        RiskLevel.HIGH -> Danger
    }
    val riskChipLabel = when (request.riskLevel) {
        RiskLevel.LOW -> "LOW RISK"
        RiskLevel.MEDIUM -> "MEDIUM RISK"
        RiskLevel.HIGH -> "HIGH RISK"
    }

    val actionLabel = request.toolName.replace('_', ' ')
    val targetDisplay = request.args["resolved_contact"]
        ?: request.args["contact"]
        ?: request.args["recipient"]
        ?: request.args["text"]
        ?: request.args["app_name"]
        ?: request.args.values.firstOrNull()
        ?: "System"
    val modeDisplay = request.args["mode"]
        ?: request.args["app"]
        ?: if (request.requiresExplicitHighRiskCheck) "Direct execution" else "Standard"

    Dialog(
        onDismissRequest = onDeny,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ScrimBackdrop)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDeny
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(sheetShape)
                    .background(SafetySheetFill, sheetShape)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(GlassStrokeStrong, GlassStroke)
                        ),
                        shape = sheetShape
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Grab handle (40x4dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(GlassStrokeStrong)
                )

                // Header: 52dp rounded shield icon tile + title "Confirm action" + risk chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(riskColor.copy(alpha = 0.16f))
                                .border(1.dp, riskColor.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isHighRisk) Icons.Default.Warning else Icons.Default.Security,
                                contentDescription = "Safety shield",
                                tint = riskColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Confirm action",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary
                            )
                            Text(
                                text = "सुरक्षा पुष्टि आवश्यक है",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }

                    Surface(
                        color = riskColor.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.border(1.dp, riskColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                    ) {
                        Text(
                            text = riskChipLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = riskColor,
                            letterSpacing = 0.6.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Body: Key-Value Card (Action, Contact/target with masked number, Mode)
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    contentPadding = 14.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SafetyKeyValueRow(label = "Action", value = actionLabel)
                        SafetyKeyValueRow(label = "Contact / target", value = targetDisplay)
                        SafetyKeyValueRow(label = "Mode", value = modeDisplay)
                        request.args["message"]?.takeIf { it.isNotBlank() }?.let { msg ->
                            SafetyKeyValueRow(label = "Message", value = msg)
                        }
                    }
                }

                // Countdown row: warning dot + "Auto-cancels in 30 s if you don't respond"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Warning)
                    )
                    Text(
                        text = "Auto-cancels in ${remainingSeconds} s if you don't respond",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                // HIGH risk explicit verification checkbox
                if (isHighRisk) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlassFill)
                            .border(1.dp, GlassStroke, RoundedCornerShape(12.dp))
                            .clickable { highRiskAcknowledged = !highRiskAcknowledged }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = highRiskAcknowledged,
                            onCheckedChange = { highRiskAcknowledged = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Violet500,
                                uncheckedColor = TextSecondary,
                                checkmarkColor = Color.White
                            ),
                            modifier = Modifier.testTag("high_risk_checkbox")
                        )
                        Text(
                            text = "I verify the contact and parameters are safe (मैंने जांच कर ली है)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                // Action Buttons: Cancel (GhostButton, weight 1) + "Approve & <action>" (GradientButton, weight 1.4)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GhostButton(
                        text = "Cancel",
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        testTag = "safety_gate_deny_button"
                    )
                    GradientButton(
                        text = "Approve & $actionLabel",
                        onClick = onApprove,
                        enabled = !isHighRisk || highRiskAcknowledged,
                        modifier = Modifier.weight(1.4f),
                        testTag = "safety_gate_approve_button"
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyKeyValueRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(0.62f)
        )
    }
}
