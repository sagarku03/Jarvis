package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.RoutineItem
import com.example.domain.model.RoutineTriggerType
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraSwitch
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassTextField
import com.example.ui.components.GradientButton
import com.example.ui.components.StatusChip
import com.example.ui.theme.Bg1
import com.example.ui.theme.Danger
import com.example.ui.theme.GlassFillElevated
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.Lavender
import com.example.ui.theme.Lime
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Violet500
import com.example.ui.theme.Warning
import com.example.ui.viewmodel.RoutinesViewModel

@Composable
fun RoutinesScreen(
    viewModel: RoutinesViewModel,
    onBackToHome: () -> Unit
) {
    BackHandler(onBack = onBackToHome)

    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var draftTitle by remember { mutableStateOf("") }
    var draftTriggerType by remember { mutableStateOf(RoutineTriggerType.TIME_DAILY) }
    var draftTriggerValue by remember { mutableStateOf("07:30") }
    var draftActionsPrompt by remember { mutableStateOf("Good morning briefing") }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            containerColor = Bg1,
            title = {
                Text(
                    text = "New routine · नया रूटीन",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassTextField(
                        value = draftTitle,
                        onValueChange = { draftTitle = it },
                        label = "Routine name",
                        placeholder = "Morning briefing",
                        testTag = "routine_title_input"
                    )

                    Text(
                        text = "Trigger",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    RoutineTriggerType.entries
                        .filter { it != RoutineTriggerType.GEOFENCE_ARRIVE }
                        .forEach { trigger ->
                            FilterChip(
                                selected = draftTriggerType == trigger,
                                onClick = {
                                    draftTriggerType = trigger
                                    draftTriggerValue = when (trigger) {
                                        RoutineTriggerType.TIME_DAILY -> "07:30"
                                        RoutineTriggerType.CHARGING_CONNECTED -> "Charger connected"
                                        RoutineTriggerType.HEADSET_CONNECTED -> "Headset connected"
                                        RoutineTriggerType.GEOFENCE_ARRIVE -> "Location"
                                    }
                                },
                                label = { Text("${trigger.labelEn} · ${trigger.labelHi}") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Violet500.copy(alpha = 0.25f),
                                    selectedLabelColor = TextPrimary,
                                    containerColor = GlassFillElevated,
                                    labelColor = TextSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = draftTriggerType == trigger,
                                    borderColor = GlassStroke,
                                    selectedBorderColor = Lavender
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                    GlassTextField(
                        value = draftTriggerValue,
                        onValueChange = { draftTriggerValue = it },
                        label = "Schedule / condition",
                        placeholder = "07:30"
                    )

                    GlassTextField(
                        value = draftActionsPrompt,
                        onValueChange = { draftActionsPrompt = it },
                        label = "Command to run",
                        placeholder = "Good morning briefing",
                        singleLine = false,
                        testTag = "routine_prompt_input"
                    )
                }
            },
            confirmButton = {
                GradientButton(
                    text = "Save routine",
                    onClick = {
                        viewModel.saveRoutine(
                            title = draftTitle,
                            triggerType = draftTriggerType,
                            triggerValue = draftTriggerValue,
                            actionsPrompt = draftActionsPrompt
                        )
                        showCreateDialog = false
                    },
                    testTag = "save_routine_confirm_button"
                )
            },
            dismissButton = {
                GhostButton(
                    text = "Cancel",
                    onClick = { showCreateDialog = false }
                )
            }
        )
    }

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
                            text = "Automation",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.routines_title),
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                    }

                    GradientButton(
                        text = "New",
                        leadingIcon = Icons.Default.Add,
                        onClick = {
                            draftTitle = ""
                            draftTriggerType = RoutineTriggerType.TIME_DAILY
                            draftTriggerValue = "07:30"
                            draftActionsPrompt = "Good morning briefing"
                            showCreateDialog = true
                        },
                        testTag = "add_routine_button"
                    )
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Proactive suggestions",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Quiet hours: ${settings.quietHoursStart} – ${settings.quietHoursEnd}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        AuroraSwitch(
                            checked = settings.proactiveSuggestionsEnabled,
                            onCheckedChange = { viewModel.setProactiveSuggestionsEnabled(it) },
                            modifier = Modifier.testTag("proactive_suggestions_switch")
                        )
                    }
                }
            }

            if (routines.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No routines scheduled yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap New or say \"Create routine…\" to schedule daily alarms, charger, or headset automations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            items(routines, key = { it.id }) { routine ->
                RoutineItemCard(
                    routine = routine,
                    onToggle = { enabled -> viewModel.toggleRoutineEnabled(routine, enabled) },
                    onRunNow = {
                        viewModel.runRoutineNow(routine)
                        onBackToHome()
                    },
                    onDelete = { viewModel.deleteRoutine(routine.id) }
                )
            }
        }
    }
}

@Composable
private fun RoutineItemCard(
    routine: RoutineItem,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    val nextRunLabel = when (routine.triggerType) {
        RoutineTriggerType.TIME_DAILY -> "Daily · ${routine.triggerValue}"
        else -> "${routine.triggerType.labelEn} · ${routine.triggerValue}"
    }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusChip(
                    label = nextRunLabel,
                    dotColor = if (routine.enabled) Lime else Warning,
                    leadingIcon = Icons.Default.Schedule
                )
                AuroraSwitch(
                    checked = routine.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("routine_switch_${routine.id}")
                )
            }

            Text(
                text = routine.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = routine.actionsPrompt,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostButton(
                    text = "Run now",
                    leadingIcon = Icons.Default.PlayArrow,
                    onClick = onRunNow,
                    testTag = "run_routine_${routine.id}"
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_routine_${routine.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete routine",
                        tint = Danger
                    )
                }
            }
        }
    }
}
