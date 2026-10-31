package com.example.data.repository

import com.example.di.Inject
import com.example.di.Singleton
import com.example.domain.model.AppResult
import com.example.domain.repository.AuthRepository
import com.example.domain.repository.AuthUserSession
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Singleton
class FirebaseAuthRepositoryImpl @Inject constructor() : AuthRepository {

    private val firebaseAuth: FirebaseAuth? = runCatching {
        FirebaseAuth.getInstance()
    }.getOrNull()

    private val _currentUser = MutableStateFlow(firebaseAuth?.currentUser?.toSession())
    override val currentUser: StateFlow<AuthUserSession?> = _currentUser.asStateFlow()

    init {
        runCatching {
            firebaseAuth?.addAuthStateListener { auth ->
                _currentUser.value = auth.currentUser?.toSession()
            }
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): AppResult<AuthUserSession> =
        withContext(Dispatchers.IO) {
            val auth = firebaseAuth ?: return@withContext AppResult.Error(
                messageEn = "Firebase Auth is not initialized on this device.",
                messageHi = "इस डिवाइस पर Firebase Auth कॉन्फ़िगर नहीं है।"
            )
            try {
                val result = Tasks.await(
                    auth.signInWithEmailAndPassword(email.trim(), password),
                    15,
                    TimeUnit.SECONDS
                )
                val session = result.user?.toSession()
                    ?: return@withContext AppResult.Error(
                        messageEn = "Sign-in succeeded but no user profile was returned.",
                        messageHi = "साइन-इन हुआ लेकिन उपयोगकर्ता प्रोफ़ाइल प्राप्त नहीं हुई।"
                    )
                _currentUser.value = session
                AppResult.Success(session)
            } catch (e: Exception) {
                AppResult.Error(
                    messageEn = e.localizedMessage ?: "Email sign-in failed.",
                    messageHi = "ईमेल साइन-इन विफल: ${e.localizedMessage ?: "अज्ञात त्रुटि"}",
                    throwable = e
                )
            }
        }

    override suspend fun signUpWithEmail(email: String, password: String): AppResult<AuthUserSession> =
        withContext(Dispatchers.IO) {
            val auth = firebaseAuth ?: return@withContext AppResult.Error(
                messageEn = "Firebase Auth is not initialized on this device.",
                messageHi = "इस डिवाइस पर Firebase Auth कॉन्फ़िगर नहीं है।"
            )
            try {
                val result = Tasks.await(
                    auth.createUserWithEmailAndPassword(email.trim(), password),
                    15,
                    TimeUnit.SECONDS
                )
                val session = result.user?.toSession()
                    ?: return@withContext AppResult.Error(
                        messageEn = "Account created but no user session was returned.",
                        messageHi = "खाता बन गया लेकिन सत्र प्राप्त नहीं हुआ।"
                    )
                _currentUser.value = session
                AppResult.Success(session)
            } catch (e: Exception) {
                AppResult.Error(
                    messageEn = e.localizedMessage ?: "Sign-up failed.",
                    messageHi = "खाता बनाने में त्रुटि: ${e.localizedMessage ?: "अज्ञात त्रुटि"}",
                    throwable = e
                )
            }
        }

    override suspend fun sendPasswordReset(email: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            val auth = firebaseAuth ?: return@withContext AppResult.Error(
                messageEn = "Firebase Auth is not initialized on this device.",
                messageHi = "इस डिवाइस पर Firebase Auth कॉन्फ़िगर नहीं है।"
            )
            try {
                Tasks.await(auth.sendPasswordResetEmail(email.trim()), 15, TimeUnit.SECONDS)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(
                    messageEn = e.localizedMessage ?: "Password reset email failed.",
                    messageHi = "पासवर्ड रीसेट ईमेल भेजने में त्रुटि: ${e.localizedMessage ?: "अज्ञात त्रुटि"}",
                    throwable = e
                )
            }
        }

    override suspend fun signInWithGoogleIdToken(idToken: String): AppResult<AuthUserSession> =
        withContext(Dispatchers.IO) {
            val auth = firebaseAuth ?: return@withContext AppResult.Error(
                messageEn = "Firebase Auth is not initialized on this device.",
                messageHi = "इस डिवाइस पर Firebase Auth कॉन्फ़िगर नहीं है।"
            )
            try {
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                val result = Tasks.await(auth.signInWithCredential(credential), 15, TimeUnit.SECONDS)
                val session = result.user?.toSession()
                    ?: return@withContext AppResult.Error(
                        messageEn = "Google sign-in succeeded but no user profile was returned.",
                        messageHi = "Google साइन-इन सफल रहा लेकिन प्रोफ़ाइल प्राप्त नहीं हुई।"
                    )
                _currentUser.value = session
                AppResult.Success(session)
            } catch (e: Exception) {
                AppResult.Error(
                    messageEn = e.localizedMessage ?: "Google sign-in failed.",
                    messageHi = "Google साइन-इन विफल: ${e.localizedMessage ?: "अज्ञात त्रुटि"}",
                    throwable = e
                )
            }
        }

    override suspend fun signOut() {
        runCatching {
            firebaseAuth?.signOut()
            _currentUser.value = null
        }
    }

    private fun FirebaseUser.toSession(): AuthUserSession = AuthUserSession(
        uid = uid,
        email = email,
        displayName = displayName
    )
}
