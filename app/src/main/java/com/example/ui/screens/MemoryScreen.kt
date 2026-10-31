package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ConversationItem
import com.example.domain.model.MemoryCategory
import com.example.domain.model.MemoryFact
import com.example.domain.model.MemorySource
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraDangerButton
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraSectionHeader
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.AuroraTextButton
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisMonoStyle
import com.example.ui.theme.JarvisTimestampMonoStyle
import com.example.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(viewModel: MemoryViewModel) {
    val context = LocalContext.current
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val learnedToday by viewModel.learnedToday.collectAsStateWithLifecycle()
    val selectedConversation by viewModel.selectedConversation.collectAsStateWithLifecycle()
    val selectedConversationMessages by viewModel.selectedConversationMessages.collectAsStateWithLifecycle()
    val memoryStatusBanner by viewModel.memoryStatusBanner.collectAsStateWithLifecycle()
    val storageSizeBytes by viewModel.storageSizeBytes.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var keyDraft by remember { mutableStateOf("") }
    var valueDraft by remember { mutableStateOf("") }
    var categoryDraft by remember { mutableStateOf(MemoryCategory.CONTACT.name) }

    var editingFact by remember { mutableStateOf<MemoryFact?>(null) }
    var editKeyDraft by remember { mutableStateOf("") }
    var editValueDraft by remember { mutableStateOf("") }
    var editCategoryDraft by remember { mutableStateOf(MemoryCategory.NOTE.name) }
    var editPinnedDraft by remember { mutableStateOf(false) }

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteConfirmDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.refreshStorageSize()
    }

    val categories: List<Pair<String, Int>> = listOf(
        MemoryCategory.PROFILE.name to R.string.memory_cat_profile,
        MemoryCategory.CONTACT.name to R.string.memory_cat_contact,
        MemoryCategory.PREFERENCE.name to R.string.memory_cat_preference,
        MemoryCategory.ROUTINE_HINT.name to R.string.memory_cat_routine_hint,
        MemoryCategory.TASK.name to R.string.memory_cat_task,
        MemoryCategory.NOTE.name to R.string.memory_cat_note,
        MemoryCategory.EPISODE.name to R.string.memory_cat_episode
    )

    val tabs: List<Pair<Int, String>> = listOf(
        R.string.memory_tab_facts to "memory_tab_facts",
        R.string.memory_tab_conversations to "memory_tab_conversations",
        R.string.memory_tab_learned_today to "memory_tab_learned_today"
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuroraSecondaryButton(
                            text = stringResource(R.string.memory_export_json),
                            leadingIcon = Icons.Default.IosShare,
                            onClick = {
                                viewModel.exportMemoriesJson { jsonPayload ->
                                    runCatching {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_SUBJECT, "JARVIS Memory Export")
                                            putExtra(Intent.EXTRA_TEXT, jsonPayload)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Export JARVIS Memory").apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            },
                            testTag = "memory_export_json_button"
                        )

                        AuroraDangerButton(
                            text = stringResource(R.string.memory_delete_all_data),
                            leadingIcon = Icons.Default.DeleteForever,
                            onClick = {
                                deleteConfirmDraft = ""
                                showDeleteAllDialog = true
                            },
                            testTag = "memory_delete_all_data_button"
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            val sizeKb = String.format(Locale.US, "%.1f", (storageSizeBytes / 1024f).coerceAtLeast(0.1f))
            Text(
                text = stringResource(R.string.memory_storage_size_kb, sizeKb),
                style = JarvisMonoStyle,
                color = AuroraColors.TextSecondary,
                modifier = Modifier.testTag("memory_storage_size_label")
            )

            if (!memoryStatusBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AuroraColors.AmberWarning.copy(alpha = 0.16f))
                        .border(1.dp, AuroraColors.AmberWarning.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("memory_status_banner"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = memoryStatusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    AuroraTextButton(
                        text = stringResource(R.string.safety_confirm_deny),
                        onClick = viewModel::dismissBanner,
                        testTag = "memory_dismiss_banner_button"
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3-Tab Switcher (Facts | Conversations | Learned today)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AuroraColors.SurfacePrimary)
                    .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                tabs.forEachIndexed { idx, tabPair ->
                    val labelRes = tabPair.first
                    val tag = tabPair.second
                    val selected = selectedTabIndex == idx
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (selected) {
                                    Modifier.background(AuroraColors.VioletGradient)
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { selectedTabIndex = idx }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .testTag(tag),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) AuroraColors.TextPrimary else AuroraColors.TextSecondary,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (selectedTabIndex) {
                0 -> {
                    // FACTS TAB
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

                    Spacer(modifier = Modifier.height(10.dp))

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
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categories.forEach { catPair ->
                                val catKey = catPair.first
                                val labelRes = catPair.second
                                AuroraStatusPill(
                                    text = stringResource(labelRes),
                                    color = if (categoryDraft == catKey) AuroraColors.Violet500 else AuroraColors.TextMuted,
                                    modifier = Modifier
                                        .clickable { categoryDraft = catKey }
                                        .padding(vertical = 2.dp)
                                        .testTag("memory_cat_$catKey")
                                )
                            }
                        }

                        AuroraPrimaryButton(
                            text = stringResource(R.string.memory_save_button),
                            leadingIcon = Icons.Default.Add,
                            enabled = keyDraft.isNotBlank() && valueDraft.isNotBlank(),
                            onClick = {
                                viewModel.saveMemory(categoryDraft, keyDraft, valueDraft)
                                keyDraft = ""
                                valueDraft = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "memory_save_button"
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (memories.isEmpty()) {
                        EmptyMemoryState(stringResource(R.string.memory_empty))
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
                                    onTogglePin = { viewModel.togglePinMemory(fact) },
                                    onEdit = {
                                        editingFact = fact
                                        editKeyDraft = fact.key
                                        editValueDraft = fact.value
                                        editCategoryDraft = fact.category
                                        editPinnedDraft = fact.pinned
                                    },
                                    onDelete = { viewModel.deleteMemory(fact.id) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // CONVERSATIONS TAB
                    if (conversations.isEmpty()) {
                        EmptyMemoryState(stringResource(R.string.memory_conversations_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("conversations_list"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(conversations, key = { it.id }) { conv ->
                                ConversationCard(
                                    conversation = conv,
                                    onClick = { viewModel.openConversationTranscript(conv) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    // LEARNED TODAY TAB
                    if (learnedToday.isEmpty()) {
                        EmptyMemoryState(stringResource(R.string.memory_learned_today_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("learned_today_list"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(learnedToday, key = { it.id }) { fact ->
                                val withinOneHour = (System.currentTimeMillis() - fact.createdAtMs) <= 3600_000L
                                AuroraGlassCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("learned_today_card_${fact.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            AuroraStatusPill(
                                                text = fact.category,
                                                color = AuroraColors.Aqua
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
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
                                        if (withinOneHour) {
                                            AuroraSecondaryButton(
                                                text = stringResource(R.string.memory_undo_button),
                                                leadingIcon = Icons.Default.Undo,
                                                onClick = { viewModel.undoLearnedFact(fact) },
                                                testTag = "undo_learned_${fact.id}"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Edit Fact Dialog
        editingFact?.let { fact ->
            AlertDialog(
                onDismissRequest = { editingFact = null },
                containerColor = AuroraColors.SurfaceElevated,
                title = {
                    Text(
                        text = stringResource(R.string.memory_edit_title),
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editKeyDraft,
                            onValueChange = { editKeyDraft = it },
                            label = { Text(stringResource(R.string.memory_key_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_memory_key_input")
                        )
                        OutlinedTextField(
                            value = editValueDraft,
                            onValueChange = { editValueDraft = it },
                            label = { Text(stringResource(R.string.memory_value_hint)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("edit_memory_value_input")
                        )
                    }
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.memory_save_button),
                        enabled = editKeyDraft.isNotBlank() && editValueDraft.isNotBlank(),
                        onClick = {
                            viewModel.updateMemory(
                                fact = fact,
                                newCategory = editCategoryDraft,
                                newKey = editKeyDraft,
                                newValue = editValueDraft,
                                pinned = editPinnedDraft
                            )
                            editingFact = null
                        },
                        testTag = "edit_memory_confirm_button"
                    )
                },
                dismissButton = {
                    AuroraTextButton(
                        text = stringResource(R.string.safety_confirm_deny),
                        onClick = { editingFact = null },
                        testTag = "edit_memory_cancel_button"
                    )
                }
            )
        }

        // Conversation Transcript Dialog
        selectedConversation?.let { conv ->
            AlertDialog(
                onDismissRequest = viewModel::closeConversationTranscript,
                containerColor = AuroraColors.SurfaceElevated,
                title = {
                    Text(
                        text = conv.title,
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .testTag("conversation_transcript_dialog"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!conv.summary.isNullOrBlank()) {
                            item {
                                Text(
                                    text = conv.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.Lavender
                                )
                            }
                        }
                        items(selectedConversationMessages, key = { it.id }) { msg ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AuroraColors.SurfacePrimary)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = msg.role.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AuroraColors.Aqua
                                )
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    AuroraSecondaryButton(
                        text = stringResource(R.string.memory_close_dialog),
                        onClick = viewModel::closeConversationTranscript,
                        testTag = "close_conversation_transcript_button"
                    )
                }
            )
        }

        // Delete All Assistant Data Dialog (requires typing DELETE)
        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                containerColor = AuroraColors.SurfaceElevated,
                title = {
                    Text(
                        text = stringResource(R.string.memory_delete_all_dialog_title),
                        color = AuroraColors.CoralDanger
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.memory_delete_all_dialog_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        OutlinedTextField(
                            value = deleteConfirmDraft,
                            onValueChange = { deleteConfirmDraft = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.memory_delete_all_hint),
                                    style = JarvisMonoStyle,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            textStyle = JarvisMonoStyle.copy(color = AuroraColors.TextPrimary),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("memory_delete_confirm_input")
                        )
                    }
                },
                confirmButton = {
                    AuroraDangerButton(
                        text = stringResource(R.string.memory_delete_all_confirm),
                        solid = true,
                        enabled = deleteConfirmDraft.trim() == "DELETE",
                        onClick = {
                            viewModel.deleteAllAssistantData(deleteConfirmDraft) {
                                showDeleteAllDialog = false
                            }
                        },
                        testTag = "memory_delete_confirm_button"
                    )
                },
                dismissButton = {
                    AuroraTextButton(
                        text = stringResource(R.string.safety_confirm_deny),
                        onClick = { showDeleteAllDialog = false },
                        testTag = "memory_delete_cancel_button"
                    )
                }
            )
        }
    }
}

@Composable
private fun EmptyMemoryState(message: String) {
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
                contentDescription = message,
                tint = AuroraColors.TextMuted,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextSecondary
            )
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationItem,
    onClick: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()) }
    val dateStr = remember(conversation.startedAtMs) {
        formatter.format(Date(conversation.startedAtMs))
    }

    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("conversation_card_${conversation.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = conversation.title,
                    tint = AuroraColors.Aqua,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AuroraColors.TextPrimary
                )
            }
            AuroraStatusPill(
                text = "#${conversation.id}",
                color = AuroraColors.Lavender
            )
        }
        if (!conversation.summary.isNullOrBlank()) {
            Text(
                text = conversation.summary,
                style = MaterialTheme.typography.bodySmall,
                color = AuroraColors.TextSecondary
            )
        }
        Text(
            text = dateStr,
            style = JarvisTimestampMonoStyle,
            color = AuroraColors.TextMuted
        )
    }
}

@Composable
private fun MemoryFactCard(
    fact: MemoryFact,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val sourceEnum = MemorySource.fromString(fact.source)
    val sourceLabel = when (sourceEnum) {
        MemorySource.USER_EXPLICIT -> stringResource(R.string.memory_source_explicit)
        MemorySource.AUTO_LEARNED -> stringResource(R.string.memory_source_inferred)
        MemorySource.SETUP_WIZARD -> stringResource(R.string.memory_source_setup)
        MemorySource.TOOL_RESULT -> stringResource(R.string.memory_source_tool)
    }

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuroraStatusPill(
                        text = fact.category.lowercase().replaceFirstChar { it.uppercase() },
                        color = AuroraColors.Lavender
                    )
                    if (fact.pinned) {
                        AuroraStatusPill(
                            text = stringResource(R.string.memory_pinned_badge),
                            color = AuroraColors.EmeraldSuccess
                        )
                    }
                    AuroraStatusPill(
                        text = "$sourceLabel · ${(fact.confidence * 100).toInt()}%",
                        color = AuroraColors.Aqua
                    )
                }
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

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AuroraIconButton(
                    icon = Icons.Default.PushPin,
                    contentDescription = stringResource(R.string.memory_pin_toggle),
                    testTag = "pin_memory_${fact.id}",
                    tint = if (fact.pinned) AuroraColors.EmeraldSuccess else AuroraColors.TextSecondary,
                    onClick = onTogglePin
                )
                AuroraIconButton(
                    icon = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.memory_edit_title),
                    testTag = "edit_memory_${fact.id}",
                    tint = AuroraColors.Lavender,
                    onClick = onEdit
                )
                AuroraIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.routines_delete),
                    testTag = "delete_memory_${fact.id}",
                    tint = AuroraColors.CoralDanger,
                    onClick = onDelete
                )
            }
        }
    }
}
