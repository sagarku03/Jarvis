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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
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
import com.example.ui.components.AuroraSectionHeader
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
    val canUnlockJarvis by setupViewModel.canUnlockJarvis.collectAsStateWithLifecycle()
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

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionsViewModel.refreshStatuses()
        setupViewModel.refreshPermissionAndKeyState()
    }

    AuroraBackground(modifier = Modifier.imePadding()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("setup_gate_list"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                AuroraSectionHeader(
                    title = stringResource(R.string.setup_gate_title),
                    subtitle = stringResource(R.string.setup_gate_subtitle)
                )
            }

            // STEP 1: Connect your Gemini API key
            item {
                AuroraGlassCard(elevated = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = stringResource(R.string.setup_step1_title),
                            tint = AuroraColors.Aqua
                        )
                        Text(
                            text = stringResource(R.string.setup_step1_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.setup_step1_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = setupViewModel::updateApiKeyInput,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("setup_api_key_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.setup_key_hint),
                                    style = JarvisMonoStyle,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            textStyle = JarvisMonoStyle.copy(color = AuroraColors.TextPrimary),
                            singleLine = true,
                            visualTransformation = if (isKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                AuroraIconButton(
                                    icon = if (isKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isKeyVisible) {
                                        stringResource(R.string.setup_hide_key)
                                    } else {
                                        stringResource(R.string.setup_show_key)
                                    },
                                    testTag = "setup_key_toggle_visibility",
                                    onClick = setupViewModel::toggleKeyVisibility
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedContainerColor = AuroraColors.SurfacePrimary,
                                unfocusedContainerColor = AuroraColors.SurfacePrimary,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )

                        AuroraSecondaryButton(
                            text = stringResource(R.string.setup_paste_key),
                            leadingIcon = Icons.Default.ContentPaste,
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                if (!pasted.isNullOrEmpty()) {
                                    setupViewModel.updateApiKeyInput(pasted)
                                }
                            },
                            testTag = "setup_key_paste_button"
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AuroraSecondaryButton(
                            text = stringResource(R.string.setup_get_free_key),
                            leadingIcon = Icons.Default.OpenInNew,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://aistudio.google.com/apikey")
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            testTag = "setup_get_free_key_button"
                        )

                        AuroraPrimaryButton(
                            text = if (isVerifying) {
                                stringResource(R.string.setup_verifying)
                            } else {
                                stringResource(R.string.setup_verify_key)
                            },
                            loading = isVerifying,
                            enabled = apiKeyInput.isNotBlank(),
                            onClick = setupViewModel::verifyApiKey,
                            modifier = Modifier.weight(1f),
                            testTag = "setup_verify_key_button"
                        )
                    }

                    if (isVerifying || !verificationMessage.isNullOrBlank()) {
                        val isQuota = verificationMessage?.contains("Quota", ignoreCase = true) == true
                        val statusColor = when {
                            isVerifying -> AuroraColors.Aqua
                            verificationSuccess -> AuroraColors.EmeraldSuccess
                            isQuota -> AuroraColors.AmberWarning
                            else -> AuroraColors.CoralDanger
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(statusColor.copy(alpha = 0.14f))
                                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("setup_verification_status"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = statusColor,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (verificationSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = verificationMessage ?: stringResource(R.string.setup_verifying),
                                style = MaterialTheme.typography.bodySmall,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (isKeyVerified && availableModels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.setup_model_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = AuroraColors.Lavender
                        )
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AuroraColors.SurfacePrimary)
                                    .border(1.dp, AuroraColors.GlassBorderStrong, RoundedCornerShape(14.dp))
                                    .clickable { modelDropdownExpanded = true }
                                    .padding(horizontal = 14.dp)
                                    .testTag("setup_model_selector"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedModelId,
                                    style = JarvisMonoStyle,
                                    color = AuroraColors.TextPrimary
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.setup_model_label),
                                    tint = AuroraColors.TextSecondary
                                )
                            }

                            DropdownMenu(
                                expanded = modelDropdownExpanded,
                                onDismissRequest = { modelDropdownExpanded = false },
                                modifier = Modifier.background(AuroraColors.SurfaceElevated)
                            ) {
                                availableModels.forEach { modelId ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = modelId,
                                                style = JarvisMonoStyle,
                                                color = AuroraColors.TextPrimary
                                            )
                                        },
                                        onClick = {
                                            setupViewModel.selectModel(modelId)
                                            modelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // STEP 2: Tell Jarvis about you
            item {
                AuroraGlassCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = stringResource(R.string.setup_step2_title),
                            tint = AuroraColors.Lavender
                        )
                        Text(
                            text = stringResource(R.string.setup_step2_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.setup_step2_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = userNameInput,
                            onValueChange = setupViewModel::updateUserName,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("setup_name_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.setup_name_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )

                        OutlinedTextField(
                            value = userCityInput,
                            onValueChange = setupViewModel::updateUserCity,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("setup_city_input"),
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.setup_city_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextMuted
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AuroraColors.Violet500,
                                unfocusedBorderColor = AuroraColors.GlassBorder,
                                focusedTextColor = AuroraColors.TextPrimary,
                                unfocusedTextColor = AuroraColors.TextPrimary
                            )
                        )
                    }

                    Text(
                        text = stringResource(R.string.setup_contacts_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = AuroraColors.TextSecondary
                    )

                    contactNicknames.forEachIndexed { idx, draft ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.nickname,
                                onValueChange = { nick ->
                                    setupViewModel.updateContactNickname(idx, nick, draft.fullName)
                                },
                                modifier = Modifier
                                    .weight(0.45f)
                                    .testTag("setup_contact_nick_$idx"),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.setup_contact_nick_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AuroraColors.TextMuted
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AuroraColors.Violet500,
                                    unfocusedBorderColor = AuroraColors.GlassBorder,
                                    focusedTextColor = AuroraColors.TextPrimary,
                                    unfocusedTextColor = AuroraColors.TextPrimary
                                )
                            )

                            OutlinedTextField(
                                value = draft.fullName,
                                onValueChange = { full ->
                                    setupViewModel.updateContactNickname(idx, draft.nickname, full)
                                },
                                modifier = Modifier
                                    .weight(0.55f)
                                    .testTag("setup_contact_full_$idx"),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.setup_contact_name_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AuroraColors.TextMuted
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AuroraColors.Violet500,
                                    unfocusedBorderColor = AuroraColors.GlassBorder,
                                    focusedTextColor = AuroraColors.TextPrimary,
                                    unfocusedTextColor = AuroraColors.TextPrimary
                                )
                            )
                        }
                    }
                }
            }

            // STEP 3: Permissions & Privacy
            item {
                AuroraGlassCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = stringResource(R.string.setup_step3_title),
                            tint = AuroraColors.EmeraldSuccess
                        )
                        Text(
                            text = stringResource(R.string.setup_step3_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )
                    }
                    Text(
                        text = stringResource(R.string.setup_step3_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.TextSecondary
                    )

                    permissionsList.filter {
                        it.id in setOf(
                            "RECORD_AUDIO",
                            "READ_CONTACTS",
                            "CALL_PHONE",
                            "POST_NOTIFICATIONS",
                            "SYSTEM_ALERT_WINDOW",
                            "ACCESSIBILITY_SERVICE"
                        )
                    }.forEach { perm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AuroraColors.SurfacePrimary)
                                .border(1.dp, AuroraColors.GlassBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = perm.titleEn,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AuroraColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = perm.rationaleEn,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AuroraColors.TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (perm.isGranted) {
                                AuroraStatusPill(
                                    text = stringResource(R.string.permissions_status_granted),
                                    color = AuroraColors.EmeraldSuccess
                                )
                            } else {
                                AuroraSecondaryButton(
                                    text = stringResource(R.string.setup_allow_button),
                                    onClick = {
                                        when (perm.id) {
                                            "RECORD_AUDIO" -> runtimePermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            "READ_CONTACTS" -> runtimePermLauncher.launch(Manifest.permission.READ_CONTACTS)
                                            "CALL_PHONE" -> runtimePermLauncher.launch(Manifest.permission.CALL_PHONE)
                                            "POST_NOTIFICATIONS" -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    runtimePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                }
                                            }
                                            "SYSTEM_ALERT_WINDOW" -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                            Uri.parse("package:${context.packageName}")
                                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                                    )
                                                }
                                            }
                                            "ACCESSIBILITY_SERVICE" -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    testTag = "setup_allow_perm_${perm.id}"
                                )
                            }
                        }
                    }

                    // Privacy disclosure card + required "I understand" checkbox
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AuroraColors.Violet500.copy(alpha = 0.12f))
                            .border(1.dp, AuroraColors.Violet500.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setup_privacy_card_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AuroraColors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.setup_privacy_card_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = AuroraColors.TextSecondary
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { setupViewModel.setPrivacyAccepted(!privacyAccepted) }
                                .testTag("setup_privacy_checkbox_row"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = privacyAccepted,
                                onCheckedChange = setupViewModel::setPrivacyAccepted,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AuroraColors.EmeraldSuccess,
                                    uncheckedColor = AuroraColors.TextSecondary
                                ),
                                modifier = Modifier.testTag("setup_privacy_checkbox")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.setup_privacy_checkbox),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AuroraColors.TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    AuroraPrimaryButton(
                        text = stringResource(R.string.setup_unlock_button),
                        leadingIcon = Icons.Default.LockOpen,
                        enabled = canUnlockJarvis,
                        onClick = setupViewModel::completeSetup,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "setup_unlock_button"
                    )
                }
            }
        }
    }
}
