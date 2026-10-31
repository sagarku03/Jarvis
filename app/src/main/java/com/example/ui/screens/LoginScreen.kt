package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AuroraBackground
import com.example.ui.components.GhostButton
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassTextField
import com.example.ui.components.GradientButton
import com.example.ui.theme.Danger
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GlassStroke
import com.example.ui.theme.GlassStrokeStrong
import com.example.ui.theme.Indigo600
import com.example.ui.theme.Lavender
import com.example.ui.theme.Success
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.Violet500
import com.example.ui.theme.Violet600

/**
 * 2.6 Login / Welcome Screen (match mock "1 · Login / Welcome"):
 * JARVIS mark (64dp gradient rounded square with white ring) + wordmark "JARVIS" letter-spaced +
 * "Welcome back" (30sp, extra-bold) + sub "Sign in to continue · आपका स्वागत है" +
 * GlassCard containing: Email (GlassTextField), Password (with show/hide),
 * "Forgot password?" (Lavender), GradientButton "Login", divider "or",
 * GhostButton "Continue with Google"; footer "New here? Create account".
 * Inline errors in Danger color; disable button + progress while loading.
 */
@Composable
fun LoginScreen(
    isLoading: Boolean = false,
    errorMessage: String? = null,
    statusMessage: String? = null,
    onLoginWithEmail: (email: String, password: String) -> Unit,
    onCreateAccountWithEmail: (email: String, password: String) -> Unit,
    onForgotPassword: (email: String) -> Unit,
    onContinueWithGoogle: () -> Unit,
    onContinueOffline: (() -> Unit)? = null
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isCreateAccountMode by remember { mutableStateOf(false) }
    var localValidationError by remember { mutableStateOf<String?>(null) }

    val displayedError = localValidationError ?: errorMessage

    fun validateInputs(requirePassword: Boolean = true): Boolean {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || !trimmedEmail.contains("@") || !trimmedEmail.contains(".")) {
            localValidationError = "Please enter a valid email address."
            return false
        }
        if (requirePassword && password.length < 6) {
            localValidationError = "Password must be at least 6 characters."
            return false
        }
        localValidationError = null
        return true
    }

    AuroraBackground {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            contentPadding = PaddingValues(top = 36.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // JARVIS mark (64dp gradient rounded square with white ring)
                    val markShape = RoundedCornerShape(20.dp)
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = markShape,
                                ambientColor = Violet500.copy(alpha = 0.55f),
                                spotColor = ElectricBlue.copy(alpha = 0.55f)
                            )
                            .clip(markShape)
                            .background(
                                Brush.linearGradient(listOf(Violet500, Violet600, ElectricBlue)),
                                markShape
                            )
                            .border(1.dp, GlassStrokeStrong, markShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .border(2.5.dp, Color.White, CircleShape)
                        )
                    }

                    Text(
                        text = "JARVIS",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.2.sp,
                        color = Lavender
                    )

                    Text(
                        text = if (isCreateAccountMode) "Create account" else "Welcome back",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TextPrimary
                    )

                    Text(
                        text = "Sign in to continue · आपका स्वागत है",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            }

            item {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 20.dp
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        GlassTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                localValidationError = null
                            },
                            label = "Email",
                            placeholder = "you@example.com",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            testTag = "login_email_field"
                        )

                        GlassTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                localValidationError = null
                            },
                            label = "Password",
                            placeholder = "••••••••",
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = { showPassword = !showPassword },
                                    modifier = Modifier.testTag("login_password_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (showPassword) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            testTag = "login_password_field"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "Forgot password?",
                                style = MaterialTheme.typography.labelLarge,
                                color = Lavender,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !isLoading) {
                                        if (validateInputs(requirePassword = false)) {
                                            onForgotPassword(email.trim())
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTag("forgot_password_button")
                            )
                        }

                        if (!displayedError.isNullOrBlank()) {
                            Text(
                                text = displayedError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Danger,
                                modifier = Modifier.testTag("login_error_text")
                            )
                        }

                        if (!statusMessage.isNullOrBlank()) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Success,
                                modifier = Modifier.testTag("login_status_text")
                            )
                        }

                        if (isLoading) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = Lavender,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .testTag("login_loading_indicator")
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Signing in…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }

                        GradientButton(
                            text = if (isCreateAccountMode) "Create account" else "Login",
                            enabled = !isLoading,
                            onClick = {
                                if (validateInputs(requirePassword = true)) {
                                    if (isCreateAccountMode) {
                                        onCreateAccountWithEmail(email.trim(), password)
                                    } else {
                                        onLoginWithEmail(email.trim(), password)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "login_submit_button"
                        )

                        // Divider "or"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = GlassStroke
                            )
                            Text(
                                text = "or",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = GlassStroke
                            )
                        }

                        GhostButton(
                            text = "Continue with Google",
                            enabled = !isLoading,
                            leadingIcon = Icons.Default.AccountCircle,
                            onClick = {
                                localValidationError = null
                                onContinueWithGoogle()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "google_sign_in_button"
                        )
                    }
                }
            }

            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isCreateAccountMode) "Already have an account? " else "New here? ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = if (isCreateAccountMode) "Login" else "Create account",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Lavender,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isLoading) {
                                    isCreateAccountMode = !isCreateAccountMode
                                    localValidationError = null
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("toggle_auth_mode_button")
                        )
                    }

                    if (onContinueOffline != null) {
                        Text(
                            text = "Continue with offline phone tools",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !isLoading, onClick = onContinueOffline)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("continue_offline_button")
                        )
                    }
                }
            }
        }
    }
}
