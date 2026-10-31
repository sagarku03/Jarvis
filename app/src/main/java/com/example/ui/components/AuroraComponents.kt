package com.example.ui.components

import android.provider.Settings
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.Aqua
import com.example.ui.theme.Bg0
import com.example.ui.theme.Bg1
import com.example.ui.theme.BgTopGradient
import com.example.ui.theme.Danger
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassFillElevated
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.GlassStrokeStrong
import com.example.ui.theme.Indigo600
import com.example.ui.theme.Lavender
import com.example.ui.theme.Lime
import com.example.ui.theme.NavBarFill
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Violet500
import com.example.ui.theme.Violet600

/**
 * Checks if system animations are disabled or reduced by the user in Accessibility settings.
 */
@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
        }.getOrDefault(1.0f)
        scale == 0f
    }
}

/**
 * 2.2 AuroraBackground:
 * Vertical gradient #0B0718 -> Bg0 plus two radial glows drawn with drawBehind:
 * - Violet (#7C4DFF @ 60%) centered top-right (85% width, y=0, radius 0.9*width)
 * - Blue (#4C8DFF @ 38%) bottom-left (0, height, radius 0.9*width)
 * Used behind every screen.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(BgTopGradient, Bg0)
                    )
                )
                val violetRadius = (size.width * 0.9f).coerceAtLeast(100f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF7C4DFF).copy(alpha = 0.60f),
                            Color(0xFF7C4DFF).copy(alpha = 0.18f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, 0f),
                        radius = violetRadius
                    ),
                    radius = violetRadius,
                    center = Offset(size.width * 0.85f, 0f)
                )
                val blueRadius = (size.width * 0.9f).coerceAtLeast(100f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ElectricBlue.copy(alpha = 0.38f),
                            ElectricBlue.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(0f, size.height),
                        radius = blueRadius
                    ),
                    radius = blueRadius,
                    center = Offset(0f, size.height)
                )
            },
        content = content
    )
}

/**
 * 2.2 GlassCard:
 * Radius 24dp, fill GlassFill, 1dp gradient border (white 30% -> white 6%), padding 16dp.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    fillColor: Color = GlassFill,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.30f),
            Color.White.copy(alpha = 0.06f)
        )
    )
    Column(
        modifier = modifier
            .clip(shape)
            .background(fillColor, shape)
            .border(1.dp, borderBrush, shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 2.2 GradientButton:
 * Height 52dp, radius 18dp, linear gradient Violet500 -> Violet600 -> Indigo600,
 * 1dp GlassStrokeStrong border, soft violet shadow, white bold label.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    testTag: String? = null
) {
    val shape = RoundedCornerShape(18.dp)
    val gradient = if (enabled) {
        Brush.horizontalGradient(listOf(Violet500, Violet600, Indigo600))
    } else {
        Brush.horizontalGradient(
            listOf(
                Violet500.copy(alpha = 0.35f),
                Violet600.copy(alpha = 0.35f),
                Indigo600.copy(alpha = 0.35f)
            )
        )
    }

    val appliedModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    Box(
        modifier = appliedModifier
            .height(52.dp)
            .shadow(
                elevation = if (enabled) 10.dp else 0.dp,
                shape = shape,
                ambientColor = Violet500.copy(alpha = 0.45f),
                spotColor = Violet500.copy(alpha = 0.45f)
            )
            .clip(shape)
            .background(gradient, shape)
            .border(1.dp, GlassStrokeStrong, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 2.2 GhostButton:
 * Height 52dp, radius 18dp, GlassFill + GlassStroke.
 */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    testTag: String? = null
) {
    val shape = RoundedCornerShape(18.dp)
    val appliedModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    Box(
        modifier = appliedModifier
            .height(52.dp)
            .clip(shape)
            .background(GlassFill, shape)
            .border(1.dp, GlassStroke, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (enabled) TextPrimary else TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) TextPrimary else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 2.2 GlassTextField:
 * Label above field, height >= 52dp, radius 16dp, dark translucent fill, 1dp white-30% border.
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    testTag: String? = null
) {
    Column(modifier = modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        val fieldMod = Modifier
            .fillMaxWidth()
            .let { if (testTag != null) it.testTag(testTag) else it }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = GlassFillElevated,
                unfocusedContainerColor = GlassFill,
                focusedBorderColor = Violet500,
                unfocusedBorderColor = GlassStrokeStrong,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Aqua
            ),
            modifier = fieldMod
        )
    }
}

/**
 * 2.2 StatusChip:
 * Pill, GlassFill + GlassStroke, 7dp status dot (Lime = on, Warning, Danger), 12sp label, no wrapping.
 */
@Composable
fun StatusChip(
    label: String,
    dotColor: Color = Lime,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null
) {
    val shape = RoundedCornerShape(50)
    val baseMod = modifier
        .clip(shape)
        .background(GlassFill, shape)
        .border(1.dp, GlassStroke, shape)
        .let {
            if (onClick != null) {
                it.clickable(role = Role.Button, onClick = onClick)
            } else {
                it
            }
        }
        .padding(horizontal = 12.dp, vertical = 8.dp)

    Row(
        modifier = baseMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = Lavender,
                modifier = Modifier.size(15.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 2.2 SectionHeader
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        if (trailingText != null && onTrailingClick != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = Lavender,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onTrailingClick)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 2.2 GlassListRow:
 * Apple-Library-style grouped rows with leading icon, title/subtitle, and trailing content or chevron.
 */
@Composable
fun GlassListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Lavender,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val rowMod = modifier
        .fillMaxWidth()
        .let {
            if (onClick != null) {
                it
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onClick)
            } else {
                it
            }
        }
        .padding(vertical = 8.dp, horizontal = 4.dp)

    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassFillElevated)
                        .border(1.dp, GlassStroke, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        when {
            trailingContent != null -> trailingContent()
            showChevron -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun AuroraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Violet500,
            checkedBorderColor = GlassStrokeStrong,
            uncheckedThumbColor = TextSecondary,
            uncheckedTrackColor = GlassFillElevated,
            uncheckedBorderColor = GlassStroke
        )
    )
}

data class FloatingNavTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

/**
 * 2.2 FloatingGlassNavBar:
 * Pill, height 70dp, horizontal margin 18dp, bottom margin 18dp, fill #B8140E2A,
 * 1dp GlassStrokeStrong border, glow shadow (violet 25%).
 * 4 tabs (Home, Routines, Memory, Settings) with icon above 10.5sp label
 * (selected = Lavender icon + white label; unselected = TextMuted) and a
 * CENTER gradient mic FAB (60dp circle, gradient Lavender->Violet600->ElectricBlue,
 * white 45% border, raised 26dp above the bar, 6dp translucent halo).
 * FAB tap = push-to-talk (same as orb).
 */
@Composable
fun FloatingGlassNavBar(
    currentRoute: String,
    isListening: Boolean,
    onSelectRoute: (String) -> Unit,
    onCenterMicTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val leftTabs = listOf(
        FloatingNavTabItem("home", "Home", Icons.Default.GraphicEq, "nav_tab_home"),
        FloatingNavTabItem("routines", "Routines", Icons.Default.AutoAwesome, "nav_tab_routines")
    )
    val rightTabs = listOf(
        FloatingNavTabItem("memory", "Memory", Icons.Default.Memory, "nav_tab_memory"),
        FloatingNavTabItem("settings", "Settings", Icons.Default.Settings, "nav_tab_settings")
    )

    val pillShape = RoundedCornerShape(36.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 18.dp, top = 26.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Floating glass pill container
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(70.dp)
                .shadow(
                    elevation = 20.dp,
                    shape = pillShape,
                    ambientColor = Violet500.copy(alpha = 0.25f),
                    spotColor = Violet500.copy(alpha = 0.25f)
                )
                .clip(pillShape)
                .background(NavBarFill, pillShape)
                .border(1.dp, GlassStrokeStrong, pillShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            leftTabs.forEach { tab ->
                FloatingNavTabButton(
                    item = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onSelectRoute(tab.route) },
                    modifier = Modifier.weight(1f)
                )
            }

            // Center spacer for the raised Mic FAB
            Spacer(modifier = Modifier.width(68.dp))

            rightTabs.forEach { tab ->
                FloatingNavTabButton(
                    item = tab,
                    selected = currentRoute == tab.route,
                    onClick = { onSelectRoute(tab.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Raised 60dp Center Gradient Mic FAB (+ 6dp translucent halo = 72dp outer box)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-26).dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (isListening) Aqua.copy(alpha = 0.22f) else Violet500.copy(alpha = 0.18f),
                    CircleShape
                )
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            val fabGradient = Brush.linearGradient(
                colors = if (isListening) {
                    listOf(Aqua, ElectricBlue, Violet600)
                } else {
                    listOf(Lavender, Violet600, ElectricBlue)
                }
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .shadow(
                        elevation = 14.dp,
                        shape = CircleShape,
                        ambientColor = Violet500.copy(alpha = 0.55f),
                        spotColor = ElectricBlue.copy(alpha = 0.55f)
                    )
                    .clip(CircleShape)
                    .background(fabGradient, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = onCenterMicTap
                    )
                    .semantics {
                        contentDescription = if (isListening) "Stop listening" else "Push to talk"
                    }
                    .testTag("floating_mic_fab"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun FloatingNavTabButton(
    item: FloatingNavTabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag(item.testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = if (selected) Lavender else TextMuted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = item.label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TextPrimary else TextMuted,
            maxLines = 1
        )
    }
}
