package com.example.ui.components

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
import com.example.ui.navigation.JarvisDestination
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisTheme

@Preview(
    name = "Jarvis Aurora UI — 360dp & 1.3x Font Scale",
    showBackground = true,
    backgroundColor = 0xFF07050F,
    widthDp = 360,
    heightDp = 640,
    fontScale = 1.3f
)
@Composable
fun JarvisOrbAndComponentsPreview() {
    JarvisTheme(darkTheme = true) {
        AuroraBackground {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
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
                        color = AuroraColors.Aqua,
                        icon = Icons.Default.GraphicEq
                    )
                    AuroraStopPill(
                        isKilled = false,
                        onToggle = {}
                    )
                }

                JarvisOrb(
                    state = AssistantOrbState.LISTENING,
                    rmsEnergy = 0.65f,
                    onClick = {}
                )

                AuroraGlassCard(modifier = Modifier.fillMaxWidth(), elevated = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuroraStatusPill(
                            text = "Confirm action",
                            color = AuroraColors.AmberWarning,
                            icon = Icons.Default.Security
                        )
                        RiskLevelBadge(riskLevel = RiskLevel.HIGH)
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

                AuroraFloatingPillNav(
                    currentDestination = JarvisDestination.HOME,
                    onSelectDestination = {}
                )
            }
        }
    }
}
