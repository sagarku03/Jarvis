package com.example.domain.repository

import com.example.domain.model.AuthResult
import com.example.domain.model.AuthUserSession
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val isFirebaseConfigured: Boolean
    val currentSessionFlow: Flow<AuthUserSession?>

    suspend fun signInWithEmail(email: String, password: String): AuthResult
    suspend fun registerWithEmail(email: String, password: String): AuthResult
    suspend fun sendPasswordResetEmail(email: String): AuthResult
    suspend fun signInWithGoogleIdToken(idToken: String): AuthResult
    suspend fun getValidIdToken(forceRefresh: Boolean = false): String?
    suspend fun signOut()
}
