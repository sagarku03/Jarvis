package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AssistantOrbState
import com.example.ui.theme.AuroraColors

@Composable
fun JarvisOrb(
    state: AssistantOrbState,
    rmsEnergy: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_orb_transition")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val primaryColor = when (state) {
        AssistantOrbState.IDLE -> AuroraColors.Violet500
        AssistantOrbState.LISTENING -> AuroraColors.Aqua
        AssistantOrbState.THINKING -> AuroraColors.Lavender
        AssistantOrbState.SPEAKING -> AuroraColors.EmeraldSuccess
        AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.CoralDanger
    }

    val secondaryColor = when (state) {
        AssistantOrbState.IDLE -> AuroraColors.Violet700
        AssistantOrbState.LISTENING -> AuroraColors.ElectricBlue
        AssistantOrbState.THINKING -> AuroraColors.Violet600
        AssistantOrbState.SPEAKING -> AuroraColors.Aqua
        AssistantOrbState.PAUSED_KILLSWITCH -> AuroraColors.CoralDanger.copy(alpha = 0.65f)
    }

    val icon = when (state) {
        AssistantOrbState.IDLE -> Icons.Default.Mic
        AssistantOrbState.LISTENING -> Icons.Default.GraphicEq
        AssistantOrbState.THINKING -> Icons.Default.GraphicEq
        AssistantOrbState.SPEAKING -> Icons.Default.VolumeUp
        AssistantOrbState.PAUSED_KILLSWITCH -> Icons.Default.MicOff
    }

    val orbDescription = stringResource(R.string.cd_voice_orb)
    val energyBoost = 1f + (rmsEnergy.coerceIn(0f, 1f) * 0.28f)

    Box(
        modifier = modifier
            .size(164.dp)
            .clip(CircleShape)
            .semantics { contentDescription = orbDescription }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = primaryColor),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(164.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f
            val outerHaloRadius = baseRadius * pulsePhase * energyBoost * 1.45f
            val midHaloRadius = baseRadius * pulsePhase * energyBoost * 1.18f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.32f),
                        secondaryColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = outerHaloRadius.coerceAtLeast(1f)
                ),
                radius = outerHaloRadius,
                center = center
            )

            drawCircle(
                color = AuroraColors.GlassBorderStrong,
                radius = midHaloRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(primaryColor, secondaryColor)
                ),
                radius = baseRadius * (1f + rmsEnergy.coerceIn(0f, 1f) * 0.12f),
                center = center
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AuroraColors.TextPrimary,
            modifier = Modifier.size(34.dp)
        )
    }
}
