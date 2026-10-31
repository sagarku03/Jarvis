package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.example.domain.model.MemoryFact
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.MemoryViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    var keyDraft by remember { mutableStateOf("") }
    var valueDraft by remember { mutableStateOf("") }
    var categoryDraft by remember { mutableStateOf("CONTACT") }

    val categories = listOf(
        "CONTACT" to R.string.memory_cat_contact,
        "PREFERENCE" to R.string.memory_cat_preference,
        "NOTE" to R.string.memory_cat_note
    )

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AuroraSectionHeader(
                title = stringResource(R.string.memory_title),
                subtitle = stringResource(R.string.memory_subtitle),
                trailing = {
                    if (memories.isNotEmpty()) {
                        OutlinedButton(
                            onClick = viewModel::clearAllMemories,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("clear_memories_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.memory_clear_all),
                                tint = AuroraColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.memory_clear_all),
                                style = MaterialTheme.typography.labelLarge,
                                color = AuroraColors.TextPrimary
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("memory_search_input"),
                placeholder = {
                    Text(
                        text = stringResource(R.string.memory_search_hint),
                        color = AuroraColors.TextMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.memory_search_hint),
                        tint = AuroraColors.Aqua
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AuroraColors.Violet500,
                    unfocusedBorderColor = AuroraColors.GlassBorder,
                    focusedContainerColor = AuroraColors.SurfacePrimary,
                    unfocusedContainerColor = AuroraColors.SurfacePrimary,
                    focusedTextColor = AuroraColors.TextPrimary,
                    unfocusedTextColor = AuroraColors.TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Add New Fact Card
            AuroraGlassCard(elevated = true) {
                Text(
                    text = stringResource(R.string.memory_add_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("memory_key_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.memory_key_hint),
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
                        value = valueDraft,
                        onValueChange = { valueDraft = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("memory_value_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.memory_value_hint),
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
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { (catKey, labelRes) ->
                            AuroraStatusPill(
                                text = stringResource(labelRes),
                                color = if (categoryDraft == catKey) AuroraColors.Violet500 else AuroraColors.TextMuted,
                                modifier = Modifier
                                    .clickable { categoryDraft = catKey }
                                    .padding(vertical = 4.dp)
                                    .testTag("memory_cat_$catKey")
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveMemory(categoryDraft, keyDraft, valueDraft)
                            keyDraft = ""
                            valueDraft = ""
                        },
                        enabled = keyDraft.isNotBlank() && valueDraft.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AuroraColors.Violet500),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("memory_save_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.memory_save_button)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.memory_save_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = stringResource(R.string.memory_empty),
                            tint = AuroraColors.TextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.memory_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("memory_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(memories, key = { it.id }) { fact ->
                        MemoryFactCard(
                            fact = fact,
                            onDelete = { viewModel.deleteMemory(fact.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryFactCard(
    fact: MemoryFact,
    onDelete: () -> Unit
) {
    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_card_${fact.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AuroraStatusPill(
                    text = fact.category.lowercase().replaceFirstChar { it.uppercase() },
                    color = AuroraColors.Lavender
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = fact.key,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = fact.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuroraColors.TextSecondary
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_memory_${fact.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.routines_delete),
                    tint = AuroraColors.CoralDanger
                )
            }
        }
    }
}
