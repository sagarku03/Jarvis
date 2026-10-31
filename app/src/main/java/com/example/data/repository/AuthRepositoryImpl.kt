package com.example.data.repository

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AuthResult
import com.example.domain.model.AuthUserSession
import com.example.domain.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val firebaseAuth: FirebaseAuth?
) : AuthRepository {

    override val isFirebaseConfigured: Boolean
        get() = firebaseAuth != null

    override val currentSessionFlow: Flow<AuthUserSession?> = callbackFlow {
        val auth = firebaseAuth
        if (auth == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        val listener = FirebaseAuth.AuthStateListener { fa ->
            val user = fa.currentUser
            if (user == null) {
                trySend(null)
            } else {
                trySend(
                    AuthUserSession(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        isEmailVerified = user.isEmailVerified,
                        idToken = null
                    )
                )
            }
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthResult {
        val auth = firebaseAuth ?: return AuthResult.Error(
            messageEn = "Firebase Auth is not configured on this device."
        )
        return try {
            val res = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = res.user
            if (user != null) {
                val token = runCatching { user.getIdToken(false).await().token }.getOrNull()
                AuthResult.Success(
                    AuthUserSession(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        isEmailVerified = user.isEmailVerified,
                        idToken = token
                    )
                )
            } else {
                AuthResult.Error(
                    messageEn = "Sign-in succeeded but user session was empty."
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AuthResult.Error(
                messageEn = "Sign-in failed: ${e.localizedMessage ?: "Invalid credentials"}"
            )
        }
    }

    override suspend fun registerWithEmail(email: String, password: String): AuthResult {
        val auth = firebaseAuth ?: return AuthResult.Error(
            messageEn = "Firebase Auth is not configured on this device."
        )
        return try {
            val res = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = res.user
            if (user != null) {
                val token = runCatching { user.getIdToken(false).await().token }.getOrNull()
                AuthResult.Success(
                    AuthUserSession(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        isEmailVerified = user.isEmailVerified,
                        idToken = token
                    )
                )
            } else {
                AuthResult.Error(
                    messageEn = "Account created but session could not be loaded."
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AuthResult.Error(
                messageEn = "Account registration failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): AuthResult {
        val auth = firebaseAuth ?: return AuthResult.Error(
            messageEn = "Firebase Auth is not configured on this device."
        )
        return try {
            auth.sendPasswordResetEmail(email.trim()).await()
            AuthResult.Success(
                AuthUserSession(
                    uid = "password_reset_sent",
                    email = email.trim(),
                    displayName = "Password Reset Sent",
                    isEmailVerified = false,
                    idToken = null
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AuthResult.Error(
                messageEn = "Password reset failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): AuthResult {
        val auth = firebaseAuth ?: return AuthResult.Error(
            messageEn = "Firebase Auth is not configured on this device."
        )
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val res = auth.signInWithCredential(credential).await()
            val user = res.user
            if (user != null) {
                val firebaseToken = runCatching { user.getIdToken(false).await().token }.getOrNull()
                AuthResult.Success(
                    AuthUserSession(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName,
                        isEmailVerified = user.isEmailVerified,
                        idToken = firebaseToken
                    )
                )
            } else {
                AuthResult.Error(
                    messageEn = "Google Sign-In succeeded but user profile was empty."
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AuthResult.Error(
                messageEn = "Google Sign-In failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        }
    }

    override suspend fun getValidIdToken(forceRefresh: Boolean): String? {
        val user = firebaseAuth?.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun signOut() {
        runCatching { firebaseAuth?.signOut() }
    }
}
