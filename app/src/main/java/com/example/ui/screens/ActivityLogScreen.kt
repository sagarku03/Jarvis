package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.RiskLevel
import com.example.domain.repository.ActivityLogRecord
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.theme.Danger
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Warning
import com.example.ui.viewmodel.ActivityLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityLogScreen(
    viewModel: ActivityLogViewModel,
    onBackToHome: () -> Unit
) {
    BackHandler(onBack = onBackToHome)

    val logs by viewModel.logs.collectAsStateWithLifecycle()

    AuroraBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 14.dp, bottom = 128.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${logs.size} recorded events",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.activity_log_title),
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                    }

                    if (logs.isNotEmpty()) {
                        GhostButton(
                            text = "Clear",
                            leadingIcon = Icons.Default.DeleteSweep,
                            onClick = { viewModel.clearLogs() },
                            testTag = "clear_activity_logs_button"
                        )
                    }
                }
            }

            if (logs.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No tool actions logged yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Run any voice or text command on Home to inspect its SafetyGate audit trail here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            items(logs, key = { it.id }) { log ->
                ActivityLogTimelineItem(log = log)
            }
        }
    }
}

@Composable
private fun ActivityLogTimelineItem(log: ActivityLogRecord) {
    val riskColor = when (log.riskLevel) {
        RiskLevel.LOW -> Success
        RiskLevel.MEDIUM -> Warning
        RiskLevel.HIGH -> Danger
    }

    val timeFormat = SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault())
    val timeLabel = timeFormat.format(Date(log.timestamp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Timeline indicator dot
        Box(
            modifier = Modifier
                .padding(top = 18.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(riskColor)
        )

        GlassCard(modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = log.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Surface(
                        color = riskColor.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.border(1.dp, riskColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                    ) {
                        Text(
                            text = "${log.riskLevel.name} · ${log.status}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = riskColor,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }

                // JetBrains Mono ONLY for args and timestamp
                Text(
                    text = log.argsJson,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp,
                    color = TextSecondary
                )

                Text(
                    text = log.resultSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )

                Text(
                    text = timeLabel,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
    }
}
