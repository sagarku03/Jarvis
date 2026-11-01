package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.RoutinesViewModel

@Composable
fun RoutinesScreen(viewModel: RoutinesViewModel) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()

    var titleDraft by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf(RoutineTriggerType.VOICE_PHRASE) }
    var triggerValueDraft by remember { mutableStateOf("") }
    var actionsDraft by remember { mutableStateOf("") }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AuroraSectionHeader(
                title = stringResource(R.string.routines_title),
                subtitle = stringResource(R.string.routines_subtitle)
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("routines_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                // Create Routine Card
                item {
                    AuroraGlassCard(elevated = true) {
                        Text(
                            text = stringResource(R.string.routines_create_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )

                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_title_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.routines_name_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RoutineTriggerType.entries.forEach { trigger ->
                                FilterChip(
                                    selected = selectedTrigger == trigger,
                                    onClick = { selectedTrigger = trigger },
                                    label = { Text(trigger.labelEn) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AuroraColors.Violet500.copy(alpha = 0.25f),
                                        selectedLabelColor = AuroraColors.TextPrimary
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("routine_trigger_${trigger.code}")
                                )
                            }
                        }

                        OutlinedTextField(
                            value = triggerValueDraft,
                            onValueChange = { triggerValueDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_trigger_value_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.routines_trigger_value_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )

                        OutlinedTextField(
                            value = actionsDraft,
                            onValueChange = { actionsDraft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("routine_actions_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.routines_actions_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )

                        AuroraPrimaryButton(
                            text = stringResource(R.string.routines_save_button),
                            leadingIcon = Icons.Default.Add,
                            enabled = titleDraft.isNotBlank() &&
                                triggerValueDraft.isNotBlank() &&
                                actionsDraft.isNotBlank(),
                            onClick = {
                                viewModel.saveRoutine(
                                    title = titleDraft,
                                    triggerType = selectedTrigger,
                                    triggerValue = triggerValueDraft,
                                    actionsCommaSeparated = actionsDraft
                                )
                                titleDraft = ""
                                triggerValueDraft = ""
                                actionsDraft = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "save_routine_button"
                        )
                    }
                }

                // Active Routines List
                items(routines, key = { it.id }) { item ->
                    RoutineItemCard(
                        item = item,
                        onToggle = { enabled -> viewModel.toggleRoutine(item, enabled) },
                        onRunNow = { viewModel.runRoutineNow(item) },
                        onDelete = { viewModel.deleteRoutine(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineItemCard(
    item: RoutineItem,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("routine_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                AuroraStatusPill(
                    text = "${item.triggerType.labelEn}: ${item.triggerValue}",
                    color = AuroraColors.Lavender
                )
            }

            Switch(
                checked = item.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AuroraColors.BackgroundDeep,
                    checkedTrackColor = AuroraColors.EmeraldSuccess
                ),
                modifier = Modifier.testTag("routine_switch_${item.id}")
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.actionsJson.forEachIndexed { idx, action ->
                Text(
                    text = "${idx + 1}. $action",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuroraColors.TextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuroraSecondaryButton(
                text = stringResource(R.string.routines_run_now),
                leadingIcon = Icons.Default.PlayArrow,
                iconTint = AuroraColors.Aqua,
                onClick = onRunNow,
                testTag = "run_routine_${item.id}"
            )

            AuroraIconButton(
                icon = Icons.Default.Delete,
                contentDescription = stringResource(R.string.routines_delete),
                testTag = "delete_routine_${item.id}",
                tint = AuroraColors.CoralDanger,
                onClick = onDelete
            )
        }
    }
}
