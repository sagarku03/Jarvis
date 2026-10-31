package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ActivityLogEntry
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.RiskLevelBadge
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisTimestampMonoStyle
import com.example.ui.viewmodel.ActivityLogViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActivityLogScreen(viewModel: ActivityLogViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AuroraSectionHeader(
                title = stringResource(R.string.activity_title),
                subtitle = stringResource(R.string.activity_subtitle),
                trailing = {
                    if (logs.isNotEmpty()) {
                        AuroraSecondaryButton(
                            text = stringResource(R.string.activity_clear_all),
                            leadingIcon = Icons.Default.DeleteSweep,
                            onClick = viewModel::clearLogs,
                            testTag = "clear_activity_logs_button"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = stringResource(R.string.activity_empty),
                            tint = AuroraColors.TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.activity_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("activity_log_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(logs, key = { it.id }) { entry ->
                        ActivityLogCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityLogCard(entry: ActivityLogEntry) {
    val formatter = remember { SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault()) }
    val formattedTime = remember(entry.timestampMs) { formatter.format(Date(entry.timestampMs)) }

    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_log_card_${entry.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.toolName.replace('_', ' ').replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleMedium,
                color = AuroraColors.Aqua,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry.userConfirmed) {
                    AuroraStatusPill(
                        text = stringResource(R.string.activity_user_approved),
                        color = AuroraColors.EmeraldSuccess,
                        icon = Icons.Default.CheckCircle
                    )
                }
                RiskLevelBadge(riskLevel = entry.riskLevel)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${stringResource(R.string.activity_args_label)}: ${entry.argumentsSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = AuroraColors.TextSecondary
            )
            Text(
                text = "${stringResource(R.string.activity_output_label)}: ${entry.resultSummary}",
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextPrimary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formattedTime,
                style = JarvisTimestampMonoStyle,
                color = AuroraColors.TextMuted
            )
            Text(
                text = "${entry.durationMs} ms",
                style = JarvisTimestampMonoStyle,
                color = AuroraColors.Lavender
            )
        }
    }
}
