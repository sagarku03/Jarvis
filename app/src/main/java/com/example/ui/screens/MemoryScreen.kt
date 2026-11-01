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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.example.ui.components.AuroraStatusPill
import com.example.ui.components.AuroraTextButton
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisTimestampMonoStyle
import com.example.ui.viewmodel.MemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    val isAddSheetOpen by viewModel.isAddSheetOpen.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var addSheetPromptText by remember { mutableStateOf("") }

    var editingFact by remember { mutableStateOf<MemoryFact?>(null) }
    var editKeyDraft by remember { mutableStateOf("") }
    var editValueDraft by remember { mutableStateOf("") }

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var deleteConfirmInput by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }

    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header: "What I know about you" + Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.memory_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.memory_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )
                }

                Box {
                    AuroraIconButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        onClick = { showMenu = true },
                        tint = AuroraColors.Lavender
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.memory_export_json)) },
                            leadingIcon = { Icon(Icons.Default.IosShare, null) },
                            onClick = {
                                showMenu = false
                                viewModel.exportMemoriesJson { json ->
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Export Memories"))
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.memory_delete_all_data),
                                    color = AuroraColors.Danger
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = AuroraColors.Danger) },
                            onClick = {
                                showMenu = false
                                showDeleteAllDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("memory_search_input"),
                placeholder = {
                    Text(
                        stringResource(R.string.memory_search_hint),
                        color = AuroraColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AuroraColors.Lavender
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AuroraColors.Violet,
                    unfocusedBorderColor = AuroraColors.GlassBorder,
                    focusedContainerColor = AuroraColors.GlassFillLight,
                    unfocusedContainerColor = AuroraColors.GlassFillDark,
                    focusedTextColor = AuroraColors.TextPrimary,
                    unfocusedTextColor = AuroraColors.TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Big "+ Add" button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tab selectors
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    TabPill(
                        label = stringResource(R.string.memory_tab_facts),
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 }
                    )
                    TabPill(
                        label = stringResource(R.string.memory_tab_learned_today),
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 }
                    )
                    TabPill(
                        label = stringResource(R.string.memory_tab_conversations),
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 }
                    )
                }

                AuroraPrimaryButton(
                    text = stringResource(R.string.memory_add_button),
                    icon = Icons.Default.Add,
                    onClick = {
                        addSheetPromptText = ""
                        viewModel.openAddSheet()
                    },
                    modifier = Modifier.testTag("memory_open_add_sheet")
                )
            }

            if (memoryStatusBanner != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(AuroraColors.AmberWarning.copy(alpha = 0.15f))
                        .border(1.dp, AuroraColors.AmberWarning.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = memoryStatusBanner.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    AuroraIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        onClick = { viewModel.dismissBanner() },
                        size = 28.dp,
                        tint = AuroraColors.TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Content based on tab
            when (selectedTabIndex) {
                0 -> {
                    // FACTS TAB: Grouped by friendly categories
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .testTag("memory_facts_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (memories.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.memory_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AuroraColors.TextMuted
                                    )
                                }
                            }
                        } else {
                            val pinnedFacts = memories.filter { it.pinned }
                            if (pinnedFacts.isNotEmpty()) {
                                item {
                                    CategoryHeader(title = "Pinned")
                                }
                                items(pinnedFacts, key = { "pinned_${it.id}" }) { fact ->
                                    FactCard(
                                        fact = fact,
                                        onPinToggle = { viewModel.togglePinMemory(fact) },
                                        onEdit = {
                                            editingFact = fact
                                            editKeyDraft = fact.key
                                            editValueDraft = fact.value
                                        },
                                        onDelete = { viewModel.deleteMemory(fact.id) }
                                    )
                                }
                            }

                            val unpinned = memories.filter { !it.pinned }
                            val peopleFacts = unpinned.filter { it.category == MemoryCategory.CONTACT.name }
                            val aboutYouFacts = unpinned.filter { it.category == MemoryCategory.PROFILE.name || it.category == MemoryCategory.TASK.name }
                            val preferenceFacts = unpinned.filter { it.category == MemoryCategory.PREFERENCE.name || it.category == MemoryCategory.ROUTINE_HINT.name }
                            val reminderFacts = unpinned.filter { it.category == MemoryCategory.NOTE.name || it.category == MemoryCategory.EPISODE.name }

                            if (aboutYouFacts.isNotEmpty()) {
                                item { CategoryHeader(title = stringResource(R.string.memory_cat_about_you)) }
                                items(aboutYouFacts, key = { it.id }) { fact ->
                                    FactCard(
                                        fact = fact,
                                        onPinToggle = { viewModel.togglePinMemory(fact) },
                                        onEdit = {
                                            editingFact = fact
                                            editKeyDraft = fact.key
                                            editValueDraft = fact.value
                                        },
                                        onDelete = { viewModel.deleteMemory(fact.id) }
                                    )
                                }
                            }

                            if (peopleFacts.isNotEmpty()) {
                                item { CategoryHeader(title = stringResource(R.string.memory_cat_people)) }
                                items(peopleFacts, key = { it.id }) { fact ->
                                    FactCard(
                                        fact = fact,
                                        onPinToggle = { viewModel.togglePinMemory(fact) },
                                        onEdit = {
                                            editingFact = fact
                                            editKeyDraft = fact.key
                                            editValueDraft = fact.value
                                        },
                                        onDelete = { viewModel.deleteMemory(fact.id) }
                                    )
                                }
                            }

                            if (preferenceFacts.isNotEmpty()) {
                                item { CategoryHeader(title = stringResource(R.string.memory_cat_preferences)) }
                                items(preferenceFacts, key = { it.id }) { fact ->
                                    FactCard(
                                        fact = fact,
                                        onPinToggle = { viewModel.togglePinMemory(fact) },
                                        onEdit = {
                                            editingFact = fact
                                            editKeyDraft = fact.key
                                            editValueDraft = fact.value
                                        },
                                        onDelete = { viewModel.deleteMemory(fact.id) }
                                    )
                                }
                            }

                            if (reminderFacts.isNotEmpty()) {
                                item { CategoryHeader(title = stringResource(R.string.memory_cat_reminders)) }
                                items(reminderFacts, key = { it.id }) { fact ->
                                    FactCard(
                                        fact = fact,
                                        onPinToggle = { viewModel.togglePinMemory(fact) },
                                        onEdit = {
                                            editingFact = fact
                                            editKeyDraft = fact.key
                                            editValueDraft = fact.value
                                        },
                                        onDelete = { viewModel.deleteMemory(fact.id) }
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // LEARNED TODAY TAB
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .testTag("memory_learned_today_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (learnedToday.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.memory_learned_today_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AuroraColors.TextMuted
                                    )
                                }
                            }
                        } else {
                            items(learnedToday, key = { it.id }) { fact ->
                                FactCard(
                                    fact = fact,
                                    onPinToggle = { viewModel.togglePinMemory(fact) },
                                    onEdit = {
                                        editingFact = fact
                                        editKeyDraft = fact.key
                                        editValueDraft = fact.value
                                    },
                                    onDelete = { viewModel.undoLearnedFact(fact) },
                                    isUndo = true
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // CONVERSATIONS TAB
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .testTag("memory_conversations_list"),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        if (conversations.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.memory_conversations_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AuroraColors.TextMuted
                                    )
                                }
                            }
                        } else {
                            items(conversations, key = { it.id }) { conv ->
                                ConversationCard(
                                    conversation = conv,
                                    onClick = { viewModel.openConversationTranscript(conv) },
                                    onDelete = { viewModel.deleteConversation(conv.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Sheet: "+ Add Memory"
        if (isAddSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.closeAddSheet() },
                sheetState = bottomSheetState,
                containerColor = AuroraColors.BackgroundStart,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.memory_add_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = addSheetPromptText,
                        onValueChange = { addSheetPromptText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("memory_add_sheet_input"),
                        placeholder = {
                            Text(
                                stringResource(R.string.memory_add_sheet_hint),
                                color = AuroraColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        minLines = 3,
                        maxLines = 5,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AuroraColors.Violet,
                            unfocusedBorderColor = AuroraColors.GlassBorder,
                            focusedContainerColor = AuroraColors.GlassFillLight,
                            unfocusedContainerColor = AuroraColors.GlassFillDark,
                            focusedTextColor = AuroraColors.TextPrimary,
                            unfocusedTextColor = AuroraColors.TextPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AuroraSecondaryButton(
                            text = stringResource(R.string.memory_cancel_edit),
                            onClick = { viewModel.closeAddSheet() }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        AuroraPrimaryButton(
                            text = stringResource(R.string.memory_save_button),
                            enabled = addSheetPromptText.isNotBlank(),
                            onClick = { viewModel.addFactFromPrompt(addSheetPromptText) },
                            modifier = Modifier.testTag("memory_add_sheet_save")
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Edit Fact Dialog
        editingFact?.let { fact ->
            AlertDialog(
                onDismissRequest = { editingFact = null },
                title = {
                    Text(
                        stringResource(R.string.memory_edit_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editKeyDraft,
                            onValueChange = { editKeyDraft = it },
                            label = { Text("Topic") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editValueDraft,
                            onValueChange = { editValueDraft = it },
                            label = { Text("Value") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    AuroraPrimaryButton(
                        text = stringResource(R.string.memory_update_button),
                        onClick = {
                            viewModel.updateMemory(
                                fact = fact,
                                newCategory = fact.category,
                                newKey = editKeyDraft,
                                newValue = editValueDraft,
                                pinned = fact.pinned
                            )
                            editingFact = null
                        }
                    )
                },
                dismissButton = {
                    AuroraSecondaryButton(
                        text = stringResource(R.string.memory_cancel_edit),
                        onClick = { editingFact = null }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }

        // Delete All Data Dialog
        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                title = {
                    Text(
                        stringResource(R.string.memory_delete_all_dialog_title),
                        color = AuroraColors.Danger,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.memory_delete_all_dialog_body),
                            color = AuroraColors.TextPrimary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = deleteConfirmInput,
                            onValueChange = { deleteConfirmInput = it },
                            placeholder = { Text(stringResource(R.string.memory_delete_all_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    AuroraDangerButton(
                        text = stringResource(R.string.memory_delete_all_confirm),
                        enabled = deleteConfirmInput.trim() == "DELETE",
                        onClick = {
                            viewModel.deleteAllAssistantData("DELETE") {
                                showDeleteAllDialog = false
                            }
                        }
                    )
                },
                dismissButton = {
                    AuroraSecondaryButton(
                        text = stringResource(R.string.memory_cancel_edit),
                        onClick = { showDeleteAllDialog = false }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }

        // Transcript Dialog
        selectedConversation?.let { conv ->
            AlertDialog(
                onDismissRequest = { viewModel.closeConversationTranscript() },
                title = {
                    Text(
                        stringResource(R.string.memory_conversation_messages_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = AuroraColors.TextPrimary
                    )
                },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(selectedConversationMessages, key = { it.id }) { msg ->
                            val isUser = msg.role.name == "USER"
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isUser) AuroraColors.Violet.copy(alpha = 0.2f)
                                        else AuroraColors.GlassFillLight
                                    )
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (isUser) "You" else "Jarvis",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isUser) AuroraColors.Lavender else AuroraColors.Aqua
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
                        onClick = { viewModel.closeConversationTranscript() }
                    )
                },
                containerColor = AuroraColors.BackgroundStart
            )
        }
    }
}

@Composable
private fun CategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = AuroraColors.Lavender,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) AuroraColors.Violet else AuroraColors.GlassFillLight)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) AuroraColors.TextPrimary else AuroraColors.TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun FactCard(
    fact: MemoryFact,
    onPinToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isUndo: Boolean = false
) {
    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .testTag("memory_fact_${fact.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                // Human-readable sentence
                Text(
                    text = fact.readableSentence(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuroraColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sourceText = if (fact.source == MemorySource.USER_EXPLICIT.name || fact.source == MemorySource.SETUP_WIZARD.name) {
                        stringResource(R.string.memory_source_added)
                    } else {
                        stringResource(R.string.memory_source_learned)
                    }
                    AuroraStatusPill(
                        text = sourceText,
                        color = AuroraColors.Lavender
                    )
                    if (fact.pinned) {
                        AuroraStatusPill(
                            text = stringResource(R.string.memory_pinned_badge),
                            color = AuroraColors.Aqua
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isUndo) {
                    AuroraIconButton(
                        icon = Icons.Default.PushPin,
                        contentDescription = "Pin",
                        tint = if (fact.pinned) AuroraColors.Aqua else AuroraColors.TextMuted,
                        onClick = onPinToggle,
                        size = 36.dp
                    )
                }
                AuroraIconButton(
                    icon = if (isUndo) Icons.Default.Undo else Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = if (isUndo) AuroraColors.AmberWarning else AuroraColors.Danger,
                    onClick = onDelete,
                    size = 36.dp
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        .format(Date(conversation.startedAtMs))

    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("memory_conversation_${conversation.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = AuroraColors.Lavender,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuroraColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = dateStr,
                        style = JarvisTimestampMonoStyle.copy(color = AuroraColors.TextMuted)
                    )
                }
            }

            AuroraIconButton(
                icon = Icons.Default.Delete,
                contentDescription = stringResource(R.string.memory_conversation_delete),
                testTag = "delete_conversation_${conversation.id}",
                tint = AuroraColors.Danger,
                onClick = onDelete,
                size = 36.dp
            )
        }
    }
}
