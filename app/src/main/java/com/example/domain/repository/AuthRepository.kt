package com.example.domain.repository

import com.example.domain.model.AppResult
import kotlinx.coroutines.flow.StateFlow

data class AuthUserSession(
    val uid: String,
    val email: String?,
    val displayName: String?
)

interface AuthRepository {
    val currentUser: StateFlow<AuthUserSession?>
    suspend fun signInWithEmail(email: String, password: String): AppResult<AuthUserSession>
    suspend fun signUpWithEmail(email: String, password: String): AppResult<AuthUserSession>
    suspend fun sendPasswordReset(email: String): AppResult<Unit>
    suspend fun signInWithGoogleIdToken(idToken: String): AppResult<AuthUserSession>
    suspend fun signOut()
}
