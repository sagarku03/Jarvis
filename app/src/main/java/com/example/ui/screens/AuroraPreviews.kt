package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.domain.model.OrbState
import com.example.domain.model.RiskLevel
import com.example.domain.model.SafetyConfirmationRequest
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassListRow
import com.example.ui.components.GradientButton
import com.example.ui.components.JarvisAnimatedOrb
import com.example.ui.components.SafetyGateConfirmationModal
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusChip
import com.example.ui.theme.JarvisTheme
import com.example.ui.theme.Lime
import com.example.ui.theme.Warning

@Preview(name = "1 · Login / Welcome (360dp)", widthDp = 360, heightDp = 780)
@Preview(name = "1 · Login / Welcome (411dp Font 1.3x)", widthDp = 411, heightDp = 860, fontScale = 1.3f)
@Composable
fun LoginScreenPreview() {
    JarvisTheme {
        LoginScreen(
            onLoginWithEmail = { _, _ -> },
            onCreateAccountWithEmail = { _, _ -> },
            onForgotPassword = {},
            onContinueWithGoogle = {},
            onContinueOffline = {}
        )
    }
}

@Preview(name = "2 · Home Orb — IDLE", widthDp = 360, heightDp = 320)
@Composable
fun HomeOrbIdlePreview() {
    JarvisTheme {
        AuroraBackground {
            JarvisAnimatedOrb(
                orbState = OrbState.IDLE,
                rmsLevel = 0.1f,
                onOrbClick = {}
            )
        }
    }
}

@Preview(name = "2 · Home Orb — LISTENING", widthDp = 360, heightDp = 320)
@Composable
fun HomeOrbListeningPreview() {
    JarvisTheme {
        AuroraBackground {
            JarvisAnimatedOrb(
                orbState = OrbState.LISTENING,
                rmsLevel = 0.72f,
                onOrbClick = {}
            )
        }
    }
}

@Preview(name = "2 · Home Orb — THINKING", widthDp = 360, heightDp = 320)
@Composable
fun HomeOrbThinkingPreview() {
    JarvisTheme {
        AuroraBackground {
            JarvisAnimatedOrb(
                orbState = OrbState.THINKING,
                rmsLevel = 0.35f,
                onOrbClick = {}
            )
        }
    }
}

@Preview(name = "2 · Home Orb — SPEAKING", widthDp = 360, heightDp = 320)
@Composable
fun HomeOrbSpeakingPreview() {
    JarvisTheme {
        AuroraBackground {
            JarvisAnimatedOrb(
                orbState = OrbState.SPEAKING,
                rmsLevel = 0.68f,
                onOrbClick = {}
            )
        }
    }
}

@Preview(name = "3 · Safety Sheet — LOW RISK", widthDp = 360, heightDp = 640)
@Composable
fun SafetySheetLowPreview() {
    JarvisTheme {
        SafetyGateConfirmationModal(
            request = SafetyConfirmationRequest(
                requestId = "preview_low",
                toolName = "flashlight",
                riskLevel = RiskLevel.LOW,
                args = mapOf("state" to "on", "mode" to "Strict policy"),
                summaryEn = "Turn on flashlight",
                summaryHi = "टॉर्च चालू करें",
                requiresExplicitHighRiskCheck = false
            ),
            onApprove = {},
            onDeny = {}
        )
    }
}

@Preview(name = "3 · Safety Sheet — MEDIUM RISK", widthDp = 360, heightDp = 640)
@Composable
fun SafetySheetMediumPreview() {
    JarvisTheme {
        SafetyGateConfirmationModal(
            request = SafetyConfirmationRequest(
                requestId = "preview_med",
                toolName = "tap_element",
                riskLevel = RiskLevel.MEDIUM,
                args = mapOf("text" to "Send", "mode" to "Accessibility gesture"),
                summaryEn = "Allow JARVIS to tap 'Send'?",
                summaryHi = "क्या JARVIS को 'Send' पर टैप करने की अनुमति दें?",
                requiresExplicitHighRiskCheck = false
            ),
            onApprove = {},
            onDeny = {}
        )
    }
}

@Preview(name = "3 · Safety Sheet — HIGH RISK", widthDp = 360, heightDp = 700)
@Composable
fun SafetySheetHighPreview() {
    JarvisTheme {
        SafetyGateConfirmationModal(
            request = SafetyConfirmationRequest(
                requestId = "preview_high",
                toolName = "call_contact",
                riskLevel = RiskLevel.HIGH,
                args = mapOf(
                    "resolved_contact" to "Mom (+91 98••• ••210)",
                    "mode" to "Direct phone call"
                ),
                summaryEn = "Place phone call to Mom (+91 98••• ••210)",
                summaryHi = "माँ (+91 98••• ••210) को कॉल लगाएं",
                requiresExplicitHighRiskCheck = true
            ),
            onApprove = {},
            onDeny = {}
        )
    }
}

@Preview(name = "4 · Settings & Permissions Preview", widthDp = 360, heightDp = 500)
@Composable
fun SettingsAndPermissionsPreview() {
    JarvisTheme {
        AuroraBackground {
            Column(modifier = Modifier.padding(18.dp)) {
                SectionHeader(title = "Access & security")
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    GlassListRow(
                        title = "Permissions",
                        subtitle = "Microphone, contacts, overlay & accessibility",
                        icon = Icons.Default.Security,
                        showChevron = true
                    )
                    GlassListRow(
                        title = "Audit log",
                        subtitle = "Every tool execution and SafetyGate decision",
                        icon = Icons.Default.History,
                        showChevron = true
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    StatusChip(label = "Granted", dotColor = Lime)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusChip(label = "Needed", dotColor = Warning)
                    Spacer(modifier = Modifier.height(10.dp))
                    GradientButton(
                        text = "Grant access · अनुमति दें",
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
