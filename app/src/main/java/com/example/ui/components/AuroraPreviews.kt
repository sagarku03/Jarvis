package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.domain.model.AssistantOrbState
import com.example.domain.model.RiskLevel
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisTheme

@Preview(
    name = "Jarvis Orb & Status Pills — Aurora Dark",
    showBackground = true,
    backgroundColor = 0xFF070913,
    widthDp = 412,
    heightDp = 520
)
@Composable
fun JarvisOrbAndComponentsPreview() {
    JarvisTheme(darkTheme = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AuroraColors.BackgroundDeep)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuroraStatusPill(
                    text = "en-IN",
                    color = AuroraColors.CyanGlow,
                    icon = Icons.Default.GraphicEq
                )
                RiskLevelBadge(riskLevel = RiskLevel.HIGH)
            }

            JarvisOrb(
                state = AssistantOrbState.LISTENING,
                rmsEnergy = 0.65f,
                onClick = {}
            )

            AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuroraStatusPill(
                        text = "Confirm Action",
                        color = AuroraColors.AmberWarning,
                        icon = Icons.Default.Security
                    )
                }
                Text(
                    text = "Call Mom (+91 •••• ••3210)?",
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary
                )
                Text(
                    text = "Hi, I'm Jarvis. How can I help?",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuroraColors.TextSecondary
                )
            }
        }
    }
}
