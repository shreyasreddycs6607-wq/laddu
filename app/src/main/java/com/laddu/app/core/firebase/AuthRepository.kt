package com.laddu.app.core.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.laddu.app.core.model.UserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data object NotConfigured : AuthState
    data class SignedIn(val user: UserProfile) : AuthState
}

interface AuthRepository {
    val authState: Flow<AuthState>
    val currentUid: String?
    suspend fun signUp(name: String, email: String, password: String): Result<Unit>
    suspend fun signIn(email: String, password: String): Result<Unit>
    suspend fun resetPassword(email: String): Result<Unit>
    suspend fun signOut()
}

/** Turns Firebase exceptions into messages a human can act on. */
fun authErrorMessage(t: Throwable): String = when (t) {
    is FirebaseAuthWeakPasswordException -> "Password is too weak. Use at least 6 characters."
    is FirebaseAuthUserCollisionException -> "An account with this email already exists."
    is FirebaseAuthInvalidUserException -> "No account found for this email."
    is FirebaseAuthInvalidCredentialsException -> "Email or password is incorrect."
    is FirebaseNetworkException -> "No Internet connection. Check your network and try again."
    is IllegalStateException -> t.message ?: "Firebase is not configured."
    else -> t.message ?: "Something went wrong. Please try again."
}

@Singleton
class FirebaseAuthRepository @Inject constructor(
    private val fb: FirebaseProvider,
    private val fcm: FcmTokenManager,
) : AuthRepository {

    override val authState: Flow<AuthState> =
        if (!fb.isConfigured) flowOf(AuthState.NotConfigured) else callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { a ->
                val u = a.currentUser
                trySend(
                    if (u == null) AuthState.SignedOut
                    else AuthState.SignedIn(UserProfile(u.uid, u.email.orEmpty(), u.displayName ?: u.email.orEmpty()))
                )
            }
            fb.auth.addAuthStateListener(listener)
            awaitClose { fb.auth.removeAuthStateListener(listener) }
        }

    override val currentUid: String? get() = fb.currentUid

    override suspend fun signUp(name: String, email: String, password: String): Result<Unit> = runCatching {
        val res = fb.auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val user = res.user ?: error("Sign-up failed")
        user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
        fb.firestore.collection(Paths.USERS).document(user.uid).set(
            mapOf(
                "email" to email.trim(),
                "displayName" to name.trim(),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
            com.google.firebase.firestore.SetOptions.merge(),
        ).awaitOrNull()
        withTimeoutOrNull(4_000) { runCatching { fcm.registerToken() } } // push is optional: never fail or stall the account flow
    }

    override suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        fb.auth.signInWithEmailAndPassword(email.trim(), password).await()
        withTimeoutOrNull(4_000) { runCatching { fcm.registerToken() } }
    }

    override suspend fun resetPassword(email: String): Result<Unit> = runCatching {
        fb.auth.sendPasswordResetEmail(email.trim()).await()
    }

    override suspend fun signOut() {
        if (!fb.isConfigured) return
        withTimeoutOrNull(4_000) { runCatching { fcm.unregisterToken() } } // offline logout must not hang
        fb.auth.signOut()
    }
}
