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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.domain.repository.MemoryRecord
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassTextField
import com.example.ui.components.GradientButton
import com.example.ui.components.SectionHeader
import com.example.ui.theme.Bg1
import com.example.ui.theme.Danger
import com.example.ui.theme.Lavender
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MemoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    onBackToHome: () -> Unit
) {
    BackHandler(onBack = onBackToHome)

    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val pendingCandidates by viewModel.pendingCandidates.collectAsStateWithLifecycle()
    val exportedJson by viewModel.exportedJson.collectAsStateWithLifecycle()

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableLongStateOf(0L) }
    var editorCategory by remember { mutableStateOf("Contact Nickname") }
    var editorKey by remember { mutableStateOf("") }
    var editorValue by remember { mutableStateOf("") }

    exportedJson?.let { jsonPayload ->
        AlertDialog(
            onDismissRequest = { viewModel.clearExportedJsonPreview() },
            containerColor = Bg1,
            title = {
                Text(
                    text = "Exported memory vault (JSON)",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                GlassTextField(
                    value = jsonPayload,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            },
            confirmButton = {
                GradientButton(
                    text = "Close",
                    onClick = { viewModel.clearExportedJsonPreview() }
                )
            }
        )
    }

    if (showEditorDialog) {
        AlertDialog(
            onDismissRequest = { showEditorDialog = false },
            containerColor = Bg1,
            title = {
                Text(
                    text = if (editingId == 0L) "Add memory" else "Edit memory",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassTextField(
                        value = editorCategory,
                        onValueChange = { editorCategory = it },
                        label = "Category",
                        placeholder = "Contact Nickname, Preference…",
                        testTag = "memory_category_input"
                    )
                    GlassTextField(
                        value = editorKey,
                        onValueChange = { editorKey = it },
                        label = "Title / key",
                        placeholder = "Mom",
                        testTag = "memory_key_input"
                    )
                    GlassTextField(
                        value = editorValue,
                        onValueChange = { editorValue = it },
                        label = "Value (encrypted at rest)",
                        placeholder = "+919876543210",
                        singleLine = false,
                        testTag = "memory_value_input"
                    )
                }
            },
            confirmButton = {
                GradientButton(
                    text = "Save",
                    onClick = {
                        viewModel.saveMemory(
                            id = editingId,
                            category = editorCategory,
                            keyLabel = editorKey,
                            contentValue = editorValue
                        )
                        showEditorDialog = false
                    },
                    testTag = "memory_save_confirm_button"
                )
            },
            dismissButton = {
                GhostButton(
                    text = "Cancel",
                    onClick = { showEditorDialog = false }
                )
            }
        )
    }

    val groupedMemories = remember(memories) {
        memories.groupBy { it.category.ifBlank { "General" } }
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
                            text = "AES-256-GCM encrypted",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.memory_title),
                            style = MaterialTheme.typography.displayLarge,
                            color = TextPrimary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton(
                            text = "Export",
                            leadingIcon = Icons.Default.FileDownload,
                            onClick = { viewModel.exportVaultToJson() },
                            testTag = "export_memories_button"
                        )
                        GradientButton(
                            text = "Add",
                            leadingIcon = Icons.Default.Add,
                            onClick = {
                                editingId = 0L
                                editorCategory = "Contact Nickname"
                                editorKey = ""
                                editorValue = ""
                                showEditorDialog = true
                            },
                            testTag = "add_memory_button"
                        )
                    }
                }
            }

            item {
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = "Search memories, nicknames, or preferences…",
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Lavender
                        )
                    },
                    testTag = "memory_search_field"
                )
            }

            if (pendingCandidates.isNotEmpty()) {
                item {
                    SectionHeader(title = "Pending approval")
                }
                items(pendingCandidates, key = { it.id }) { cand ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cand.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Lavender
                                )
                                Text(
                                    text = "${cand.keyLabel}: ${cand.contentValue}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                            Row {
                                IconButton(onClick = { viewModel.approveCandidate(cand.id) }) {
                                    Icon(Icons.Default.Check, contentDescription = "Approve", tint = Success)
                                }
                                IconButton(onClick = { viewModel.dismissCandidate(cand.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Danger)
                                }
                            }
                        }
                    }
                }
            }

            if (memories.isEmpty()) {
                item {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No memories saved yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap Add or say \"Remember that…\" / \"याद रखो कि…\" to store personal facts or contact nicknames.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                groupedMemories.forEach { (category, categoryItems) ->
                    item(key = "header_$category") {
                        SectionHeader(title = category)
                    }
                    items(categoryItems, key = { it.id }) { item ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    viewModel.deleteMemory(item.id)
                                    true
                                } else {
                                    false
                                }
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {}
                        ) {
                            MemoryItemCard(
                                item = item,
                                onEdit = {
                                    editingId = item.id
                                    editorCategory = item.category
                                    editorKey = item.keyLabel
                                    editorValue = item.contentValue
                                    showEditorDialog = true
                                },
                                onDelete = { viewModel.deleteMemory(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryItemCard(
    item: MemoryRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.keyLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.contentValue,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Row {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_memory_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit memory",
                        tint = Lavender
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_memory_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete memory",
                        tint = Danger
                    )
                }
            }
        }
    }
}
