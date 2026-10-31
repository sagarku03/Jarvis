package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.ui.components.AuroraGlassCard
import com.example.ui.components.AuroraStatusPill
import com.example.ui.theme.AuroraColors
import com.example.ui.viewmodel.AuthViewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onContinueToAssistant: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by viewModel.currentSession.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var googleError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        AuroraColors.BackgroundDeep,
                        AuroraColors.SurfacePrimary,
                        AuroraColors.BackgroundDeep
                    )
                )
            )
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.VerifiedUser,
                contentDescription = stringResource(R.string.login_title),
                tint = AuroraColors.ElectricViolet,
                modifier = Modifier.size(52.dp)
            )

            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
                color = AuroraColors.TextPrimary,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = AuroraColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            if (!viewModel.isFirebaseConfigured) {
                AuroraStatusPill(
                    text = stringResource(R.string.login_firebase_not_configured),
                    color = AuroraColors.AmberWarning
                )
            }

            AuroraGlassCard(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.login_email_label)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = stringResource(R.string.login_email_label),
                            tint = AuroraColors.CyanGlow
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuroraColors.ElectricViolet,
                        unfocusedBorderColor = AuroraColors.GlassBorder,
                        focusedTextColor = AuroraColors.TextPrimary,
                        unfocusedTextColor = AuroraColors.TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_email_input")
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password_label)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.login_password_label),
                            tint = AuroraColors.CyanGlow
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuroraColors.ElectricViolet,
                        unfocusedBorderColor = AuroraColors.GlassBorder,
                        focusedTextColor = AuroraColors.TextPrimary,
                        unfocusedTextColor = AuroraColors.TextPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_password_input")
                )

                if (statusMessage != null || googleError != null) {
                    Text(
                        text = googleError ?: statusMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuroraColors.CyanGlow,
                        modifier = Modifier.testTag("login_status_text")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.signInWithEmail(email, password) },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = AuroraColors.ElectricViolet),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("login_signin_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = AuroraColors.TextPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.login_sign_in_button),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.registerWithEmail(email, password) },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("login_register_button")
                    ) {
                        Text(
                            text = stringResource(R.string.login_create_account_button),
                            color = AuroraColors.TextPrimary
                        )
                    }
                }

                TextButton(
                    onClick = { viewModel.sendPasswordReset(email) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .testTag("login_reset_password_button")
                ) {
                    Text(
                        text = stringResource(R.string.login_forgot_password),
                        color = AuroraColors.CyanGlow
                    )
                }

                HorizontalDivider(color = AuroraColors.GlassBorder)

                OutlinedButton(
                    onClick = {
                        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
                        if (webClientId.isEmpty() || webClientId.startsWith("YOUR_")) {
                            googleError = "GOOGLE_WEB_CLIENT_ID is not configured in Secrets."
                            return@OutlinedButton
                        }
                        googleError = null
                        scope.launch {
                            try {
                                val credentialManager = CredentialManager.create(context)
                                val googleIdOption = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(webClientId)
                                    .build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleIdOption)
                                    .build()
                                val result = credentialManager.getCredential(context, request)
                                val cred = result.credential
                                if (cred is CustomCredential &&
                                    cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                ) {
                                    val googleIdTokenCred = GoogleIdTokenCredential.createFrom(cred.data)
                                    viewModel.signInWithGoogleIdToken(googleIdTokenCred.idToken)
                                } else {
                                    googleError = "Unsupported credential returned."
                                }
                            } catch (e: Exception) {
                                googleError = "Google Sign-In cancelled or unavailable: ${e.localizedMessage ?: "error"}"
                            }
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("login_google_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = stringResource(R.string.login_google_button),
                        tint = AuroraColors.CyanGlow
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.login_google_button),
                        color = AuroraColors.TextPrimary
                    )
                }
            }

            if (session != null) {
                Button(
                    onClick = onContinueToAssistant,
                    colors = ButtonDefaults.buttonColors(containerColor = AuroraColors.EmeraldSuccess),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("login_continue_authenticated_button")
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = AuroraColors.BackgroundDeep,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Guest / Offline bypass button is strictly hidden in release builds
            if (BuildConfig.IS_DEV_FLAVOR) {
                TextButton(
                    onClick = onContinueToAssistant,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("login_continue_offline_button")
                ) {
                    Text(
                        text = stringResource(R.string.login_dev_offline_mode),
                        color = AuroraColors.TextSecondary
                    )
                }
            }
        }
    }
}
