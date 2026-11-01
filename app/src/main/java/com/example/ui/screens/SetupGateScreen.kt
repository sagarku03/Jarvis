package com.example.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.AuroraBackground
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraIconButton
import com.example.ui.components.AuroraPrimaryButton
import com.example.ui.components.AuroraSecondaryButton
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.theme.JarvisMonoStyle
import com.example.ui.viewmodel.PermissionsViewModel
import com.example.ui.viewmodel.SetupViewModel

@Composable
fun SetupGateScreen(
    setupViewModel: SetupViewModel,
    permissionsViewModel: PermissionsViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val currentStep by setupViewModel.currentStep.collectAsStateWithLifecycle()
    val stepError by setupViewModel.stepError.collectAsStateWithLifecycle()

    val apiKeyInput by setupViewModel.apiKeyInput.collectAsStateWithLifecycle()
    val isKeyVisible by setupViewModel.isKeyVisible.collectAsStateWithLifecycle()
    val isVerifying by setupViewModel.isVerifying.collectAsStateWithLifecycle()
    val isKeyVerified by setupViewModel.isKeyVerified.collectAsStateWithLifecycle()
    val verificationMessage by setupViewModel.verificationMessage.collectAsStateWithLifecycle()
    val verificationSuccess by setupViewModel.verificationSuccess.collectAsStateWithLifecycle()
    val availableModels by setupViewModel.availableModels.collectAsStateWithLifecycle()
    val selectedModelId by setupViewModel.selectedModelId.collectAsStateWithLifecycle()

    val userNameInput by setupViewModel.userNameInput.collectAsStateWithLifecycle()
    val userCityInput by setupViewModel.userCityInput.collectAsStateWithLifecycle()
    val contactNicknames by setupViewModel.contactNicknames.collectAsStateWithLifecycle()

    val privacyAccepted by setupViewModel.privacyAccepted.collectAsStateWithLifecycle()
    val permissionsList by permissionsViewModel.permissionsList.collectAsStateWithLifecycle()

    var modelDropdownExpanded by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsViewModel.refreshStatuses()
                setupViewModel.refreshPermissionAndKeyState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionsViewModel.refreshStatuses()
        setupViewModel.refreshPermissionAndKeyState()
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionsViewModel.refreshStatuses()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionsViewModel.refreshStatuses()
    }

    AuroraBackground(modifier = Modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("setup_gate_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Header with Wizard Step Indicator
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.setup_gate_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        AuroraStatusPill(
                            text = stringResource(R.string.setup_step_indicator, currentStep),
                            color = AuroraColors.Violet
                        )
                    }
                    Text(
                        text = stringResource(R.string.setup_gate_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuroraColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    // Step progress indicators
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (i in 1..3) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (i <= currentStep) AuroraColors.Violet
                                        else AuroraColors.GlassFillLight
                                    )
                            )
                        }
                    }
                }
            }

            // STEP 1: Connect your AI
            if (currentStep == 1) {
                item {
                    AuroraGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_step1_card")
                    ) {
                        Text(
                            text = stringResource(R.string.setup_step1_header),
                            style = MaterialTheme.typography.titleLarge,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.setup_step1_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // API key text field
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { setupViewModel.updateApiKeyInput(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_key_input"),
                            placeholder = {
                                Text(
                                    stringResource(R.string.setup_key_input_hint),
                                    color = AuroraColors.TextMuted
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            textStyle = JarvisMonoStyle.copy(color = AuroraColors.TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedContainerColor = AuroraColors.GlassFillLight,
                                unfocusedContainerColor = AuroraColors.GlassFillDark,
                                cursorColor = AuroraColors.Violet
                            ),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AuroraIconButton(
                                        icon = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (isKeyVisible) {
                                            stringResource(R.string.setup_hide_key)
                                        } else {
                                            stringResource(R.string.setup_show_key)
                                        },
                                        onClick = { setupViewModel.toggleKeyVisibility() },
                                        size = 36.dp
                                    )
                                    AuroraIconButton(
                                        icon = Icons.Default.ContentPaste,
                                        contentDescription = stringResource(R.string.setup_paste_key),
                                        onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()
                                            if (!clip.isNullOrBlank()) {
                                                setupViewModel.onPasteKey(clip)
                                            }
                                        },
                                        size = 36.dp,
                                        modifier = Modifier.testTag("setup_paste_button")
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.setup_get_free_key),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Lavender,
                                modifier = Modifier
                                    .clickable {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://aistudio.google.com/app/apikey")
                                        )
                                        context.startActivity(intent)
                                    }
                                    .testTag("setup_get_key_link")
                            )

                            AuroraPrimaryButton(
                                text = if (isVerifying) {
                                    stringResource(R.string.setup_verifying)
                                } else {
                                    stringResource(R.string.setup_verify_key)
                                },
                                onClick = { setupViewModel.verifyApiKey() },
                                enabled = apiKeyInput.isNotBlank() && !isVerifying,
                                icon = if (isVerifying) null else Icons.Default.Key,
                                modifier = Modifier.testTag("setup_verify_button")
                            )
                        }

                        if (isVerifying) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = AuroraColors.Violet
                                )
                                Text(
                                    text = stringResource(R.string.setup_verifying),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.Lavender
                                )
                            }
                        }

                        if (verificationMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (verificationSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (verificationSuccess) AuroraColors.Success else AuroraColors.Danger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = verificationMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (verificationSuccess) AuroraColors.Success else AuroraColors.Danger
                                )
                            }
                        }

                        if (isKeyVerified && availableModels.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.setup_model_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(AuroraColors.GlassFillLight)
                                        .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
                                        .clickable { modelDropdownExpanded = true }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("setup_model_dropdown"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = selectedModelId,
                                        style = JarvisMonoStyle.copy(color = AuroraColors.TextPrimary)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        tint = AuroraColors.Lavender
                                    )
                                }
                                DropdownMenu(
                                    expanded = modelDropdownExpanded,
                                    onDismissRequest = { modelDropdownExpanded = false }
                                ) {
                                    availableModels.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                setupViewModel.selectModel(model)
                                                modelDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Validation error notice
                        if (stepError != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stepError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("setup_step1_error")
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Next button
                        AuroraPrimaryButton(
                            text = stringResource(R.string.setup_next_step),
                            onClick = { setupViewModel.onNextClicked() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_next_button")
                        )
                    }
                }
            }

            // STEP 2: About you
            if (currentStep == 2) {
                item {
                    AuroraGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_step2_card")
                    ) {
                        Text(
                            text = stringResource(R.string.setup_step2_header),
                            style = MaterialTheme.typography.titleLarge,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.setup_step2_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextSecondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Name (Required)
                        Text(
                            text = stringResource(R.string.setup_user_name_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.setup_step2_name_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = { setupViewModel.updateUserName(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_name_input"),
                            placeholder = { Text(stringResource(R.string.setup_user_name_hint), color = AuroraColors.TextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedContainerColor = AuroraColors.GlassFillLight,
                                unfocusedContainerColor = AuroraColors.GlassFillDark,
                                cursorColor = AuroraColors.Violet,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // City (Optional)
                        Text(
                            text = stringResource(R.string.setup_user_city_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = userCityInput,
                            onValueChange = { setupViewModel.updateUserCity(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_city_input"),
                            placeholder = { Text(stringResource(R.string.setup_user_city_hint), color = AuroraColors.TextMuted) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedContainerColor = AuroraColors.GlassFillLight,
                                unfocusedContainerColor = AuroraColors.GlassFillDark,
                                cursorColor = AuroraColors.Violet,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Contacts (Optional, up to 3)
                        Text(
                            text = stringResource(R.string.setup_contacts_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        contactNicknames.forEachIndexed { index, draft ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = draft.nickname,
                                    onValueChange = { setupViewModel.updateContactNickname(index, it, draft.fullName) },
                                    modifier = Modifier
                                        .weight(0.4f)
                                        .testTag("setup_contact_nick_$index"),
                                    placeholder = { Text(stringResource(R.string.setup_contact_nick_hint), color = AuroraColors.TextMuted) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AuroraColors.Violet,
                                        unfocusedBorderColor = AuroraColors.GlassBorder,
                                        focusedContainerColor = AuroraColors.GlassFillLight,
                                        unfocusedContainerColor = AuroraColors.GlassFillDark,
                                        cursorColor = AuroraColors.Violet,
                                        focusedTextColor = AuroraColors.TextPrimary,
                                        unfocusedTextColor = AuroraColors.TextPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = draft.fullName,
                                    onValueChange = { setupViewModel.updateContactNickname(index, draft.nickname, it) },
                                    modifier = Modifier
                                        .weight(0.6f)
                                        .testTag("setup_contact_name_$index"),
                                    placeholder = { Text(stringResource(R.string.setup_contact_name_hint), color = AuroraColors.TextMuted) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AuroraColors.Violet,
                                        unfocusedBorderColor = AuroraColors.GlassBorder,
                                        focusedContainerColor = AuroraColors.GlassFillLight,
                                        unfocusedContainerColor = AuroraColors.GlassFillDark,
                                        cursorColor = AuroraColors.Violet,
                                        focusedTextColor = AuroraColors.TextPrimary,
                                        unfocusedTextColor = AuroraColors.TextPrimary
                                    ),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }

                        if (stepError != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stepError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("setup_step2_error")
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AuroraSecondaryButton(
                                text = stringResource(R.string.setup_prev_step),
                                onClick = { setupViewModel.onBackClicked() },
                                modifier = Modifier.weight(1f)
                            )
                            AuroraPrimaryButton(
                                text = stringResource(R.string.setup_next_step),
                                onClick = { setupViewModel.onNextClicked() },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("setup_step2_next_button")
                            )
                        }
                    }
                }
            }

            // STEP 3: Permissions & Privacy
            if (currentStep == 3) {
                item {
                    AuroraGlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("setup_step3_card")
                    ) {
                        Text(
                            text = stringResource(R.string.setup_step3_header),
                            style = MaterialTheme.typography.titleLarge,
                            color = AuroraColors.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.setup_step3_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AuroraColors.TextSecondary
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Microphone (REQUIRED)
                        val micStatus = permissionsList.firstOrNull { it.id == "RECORD_AUDIO" }
                        val isMicGranted = micStatus?.isGranted == true
                        PermissionRow(
                            title = "1. Microphone",
                            subtitle = "REQUIRED to hear spoken commands",
                            isGranted = isMicGranted,
                            onAllow = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Contacts (RECOMMENDED)
                        val contactsStatus = permissionsList.firstOrNull { it.id == "READ_CONTACTS" }
                        PermissionRow(
                            title = "2. Contacts",
                            subtitle = "RECOMMENDED to call/message by name",
                            isGranted = contactsStatus?.isGranted == true,
                            onAllow = { contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS) },
                            allowLater = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Notifications (RECOMMENDED)
                        val notifStatus = permissionsList.firstOrNull { it.id == "POST_NOTIFICATIONS" }
                        PermissionRow(
                            title = "3. Notifications",
                            subtitle = "RECOMMENDED for persistent STOP indicator",
                            isGranted = notifStatus?.isGranted == true,
                            onAllow = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            allowLater = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Accessibility (OPTIONAL)
                        val accessStatus = permissionsList.firstOrNull { it.id == "ACCESSIBILITY_SERVICE" }
                        PermissionRow(
                            title = "4. Accessibility",
                            subtitle = "OPTIONAL for screen reading and automation",
                            isGranted = accessStatus?.isGranted == true,
                            onAllow = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            allowLater = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Draw over other apps (OPTIONAL)
                        val overlayStatus = permissionsList.firstOrNull { it.id == "SYSTEM_ALERT_WINDOW" }
                        PermissionRow(
                            title = "5. Draw Over Other Apps",
                            subtitle = "OPTIONAL for floating shortcut orb",
                            isGranted = overlayStatus?.isGranted == true,
                            onAllow = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            },
                            allowLater = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Privacy statement checkbox
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AuroraColors.GlassFillLight)
                                .clickable { setupViewModel.setPrivacyAccepted(!privacyAccepted) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = privacyAccepted,
                                onCheckedChange = { setupViewModel.setPrivacyAccepted(it) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AuroraColors.Violet,
                                    uncheckedColor = AuroraColors.TextMuted,
                                    checkmarkColor = AuroraColors.TextPrimary
                                ),
                                modifier = Modifier.testTag("setup_privacy_checkbox")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.setup_privacy_checkbox),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary
                            )
                        }

                        if (stepError != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stepError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("setup_step3_error")
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AuroraSecondaryButton(
                                text = stringResource(R.string.setup_prev_step),
                                onClick = { setupViewModel.onBackClicked() },
                                modifier = Modifier.weight(1f)
                            )
                            AuroraPrimaryButton(
                                text = stringResource(R.string.setup_unlock_button),
                                onClick = { setupViewModel.onNextClicked() },
                                icon = Icons.Default.LockOpen,
                                modifier = Modifier
                                    .weight(1.2f)
                                    .testTag("setup_finish_button")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onAllow: () -> Unit,
    allowLater: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AuroraColors.GlassFillLight)
            .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AuroraColors.TextSecondary
            )
        }

        if (isGranted) {
            AuroraStatusPill(
                text = stringResource(R.string.permissions_status_granted),
                color = AuroraColors.Success,
                icon = Icons.Default.Check
            )
        } else {
            AuroraSecondaryButton(
                text = stringResource(R.string.setup_allow_button),
                onClick = onAllow
            )
        }
    }
}
