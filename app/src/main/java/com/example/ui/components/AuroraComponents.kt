package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ConfirmationRequest
import com.example.domain.model.RiskLevel
import com.example.ui.navigation.JarvisDestination
import com.example.ui.theme.AuroraColors
import kotlinx.coroutines.delay

/**
 * Deep Aurora Violet ambient background (#07050F -> #0E0A1F) with radial violet & electric blue glows.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
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
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val topGlowCenter = Offset(size.width * 0.2f, size.height * 0.12f)
            val bottomGlowCenter = Offset(size.width * 0.85f, size.height * 0.78f)
            val glowRadius = size.minDimension * 0.72f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AuroraColors.Violet500.copy(alpha = 0.16f),
                        AuroraColors.Violet700.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = topGlowCenter,
                    radius = glowRadius.coerceAtLeast(1f)
                ),
                radius = glowRadius,
                center = topGlowCenter
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        AuroraColors.ElectricBlue.copy(alpha = 0.12f),
                        AuroraColors.Lavender.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = bottomGlowCenter,
                    radius = glowRadius.coerceAtLeast(1f)
                ),
                radius = glowRadius,
                center = bottomGlowCenter
            )
        }
        content()
    }
}

/**
 * Glassmorphic card using Aurora glass fill (white 8% / 12%) and glass stroke (white 14% / 30%).
 */
@Composable
fun AuroraGlassCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    borderColor: Color = if (elevated) AuroraColors.GlassBorderStrong else AuroraColors.GlassBorder,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    val fillTop = if (elevated) AuroraColors.GlassFillStrong else AuroraColors.GlassFill
    val fillBottom = AuroraColors.GlassFill
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AuroraColors.SurfacePrimary.copy(alpha = 0.82f))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(fillTop, fillBottom)
                )
            )
            .border(1.dp, borderColor, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun AuroraSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AuroraColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuroraColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun AuroraStatusPill(
    text: String,
    color: Color = AuroraColors.EmeraldSuccess,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier.border(1.dp, color.copy(alpha = 0.35f), shape),
        shape = shape,
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = AuroraColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Red (#FF6B87) STOP pill / Emerald (#3DDC97) Resume pill for emergency KillSwitch control.
 */
@Composable
fun AuroraStopPill(
    isKilled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillColor = if (isKilled) AuroraColors.EmeraldSuccess else AuroraColors.CoralDanger
    val contentColor = if (isKilled) AuroraColors.BackgroundDeep else AuroraColors.TextPrimary
    val labelText = if (isKilled) {
        stringResource(R.string.home_kill_switch_resume)
    } else {
        stringResource(R.string.home_kill_switch_trigger)
    }

    Button(
        onClick = onToggle,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = pillColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .border(1.dp, AuroraColors.GlassBorderStrong, RoundedCornerShape(50))
    ) {
        Icon(
            imageVector = if (isKilled) Icons.Default.PowerSettingsNew else Icons.Default.StopCircle,
            contentDescription = labelText,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Aurora Floating Pill Navigation bar.
 * Adapts cleanly at 360dp width and 1.3x font scale without clipping labels.
 */
@Composable
fun AuroraFloatingPillNav(
    currentDestination: JarvisDestination,
    onSelectDestination: (JarvisDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(32.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(pillShape)
                .background(AuroraColors.SurfacePrimary.copy(alpha = 0.92f))
                .background(AuroraColors.GlassFillStrong)
                .border(1.dp, AuroraColors.GlassBorderStrong, pillShape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            JarvisDestination.entries.forEach { destination ->
                val selected = currentDestination == destination
                val itemShape = RoundedCornerShape(24.dp)
                val itemModifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(itemShape)
                    .then(
                        if (selected) {
                            Modifier
                                .background(AuroraColors.VioletGradient)
                                .border(1.dp, AuroraColors.GlassBorderStrong, itemShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelectDestination(destination) }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .testTag(destination.testTag)

                Column(
                    modifier = itemModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = stringResource(destination.labelRes),
                        tint = if (selected) AuroraColors.TextPrimary else AuroraColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(destination.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) AuroraColors.TextPrimary else AuroraColors.TextSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Glass Safety Bottom Sheet for Medium/High Risk actions with Contact Disambiguation and auto-cancel timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraGlassSafetySheet(
    request: ConfirmationRequest,
    onApprove: (String?) -> Unit,
    onDeny: () -> Unit
) {
    var remainingSeconds by remember(request.id) { mutableIntStateOf(request.countdownSeconds) }
    var selectedPhone by remember(request.id) {
        mutableStateOf(request.candidateContacts.firstOrNull()?.phoneNumber)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(request.id) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
        onDeny()
    }

    ModalBottomSheet(
        onDismissRequest = onDeny,
        sheetState = sheetState,
        containerColor = AuroraColors.SurfacePrimary,
        contentColor = AuroraColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("safety_confirmation_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = AuroraColors.AmberWarning,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.safety_confirm_title, request.riskLevel.name.lowercase().replaceFirstChar { it.uppercase() }),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )
                    }
                    RiskLevelBadge(riskLevel = request.riskLevel)
                }

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
                        color = AuroraColors.Lavender
                    )
                    request.candidateContacts.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AuroraColors.GlassFill)
                                .clickable { selectedPhone = candidate.phoneNumber }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPhone == candidate.phoneNumber,
                                onClick = { selectedPhone = candidate.phoneNumber },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AuroraColors.Violet500,
                                    unselectedColor = AuroraColors.TextSecondary
                                )
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

                Text(
                    text = stringResource(R.string.safety_confirm_Countdown, remainingSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = AuroraColors.CoralDanger
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("safety_deny_button")
                    ) {
                        Text(
                            text = stringResource(R.string.safety_confirm_deny),
                            color = AuroraColors.TextPrimary
                        )
                    }

                    Button(
                        onClick = { onApprove(selectedPhone) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AuroraColors.EmeraldSuccess,
                            contentColor = AuroraColors.BackgroundDeep
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("safety_approve_button")
                    ) {
                        Text(
                            text = stringResource(R.string.safety_confirm_approve),
                            color = AuroraColors.BackgroundDeep,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun RiskLevelBadge(
    riskLevel: RiskLevel,
    modifier: Modifier = Modifier
) {
    val badgeColor: Color = when (riskLevel) {
        RiskLevel.LOW -> AuroraColors.EmeraldSuccess
        RiskLevel.MEDIUM -> AuroraColors.AmberWarning
        RiskLevel.HIGH -> AuroraColors.CoralDanger
    }
    val labelRes: Int = when (riskLevel) {
        RiskLevel.LOW -> R.string.risk_badge_low
        RiskLevel.MEDIUM -> R.string.risk_badge_medium
        RiskLevel.HIGH -> R.string.risk_badge_high
    }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier.border(1.dp, badgeColor.copy(alpha = 0.4f), shape),
        shape = shape,
        color = badgeColor.copy(alpha = 0.16f)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = badgeColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
