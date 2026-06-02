package com.rpsonline.app.viewmodel

import android.content.Context
import com.rpsonline.app.ui.util.findActivity
import com.rpsonline.app.ui.util.offerSavePassword
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import com.rpsonline.app.BuildConfig
import com.rpsonline.app.R
import com.rpsonline.app.data.auth.toAuthMessage
import com.rpsonline.app.data.auth.toGoogleSignInMessage
import com.rpsonline.app.data.model.UserProfile
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.UserProfileSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

enum class EmailAuthMode {
    SIGN_IN,
    REGISTER,
}

data class SignInUiState(
    val isLoading: Boolean = false,
    val isRestoringSession: Boolean = false,
    val isCheckingFirebase: Boolean = true,
    val isFirebaseAvailable: Boolean = false,
    val profile: UserProfile? = null,
    val error: String? = null,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
)

class SignInViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val authRepository = AuthRepository()
    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()
    private var latestAuthUser: FirebaseUser? = null
    private var restoreJob: Job? = null
    private var guestSignInJob: Job? = null
    private val firebaseAvailabilityMutex = Mutex()
    /** Blocks [maybeStartSessionRestore] while email auth + save-password UI is in flight. */
    private var blockSessionRestore = false

    private companion object {
        private const val GUEST_SIGN_IN_WATCHDOG_MS = 32_000L
        private const val GOOGLE_SIGN_IN_WATCHDOG_MS = 28_000L
        private const val GOOGLE_SIGN_IN_TIMEOUT_MS = 22_000L
        private const val KEY_AUTH_ERROR = "auth_error"
    }

    init {
        savedStateHandle.get<String>(KEY_AUTH_ERROR)?.let { message ->
            _uiState.update { it.copy(error = message) }
        }
        viewModelScope.launch {
            runFirebaseAvailabilityCheck(showProgress = true)
            monitorFirebaseAvailability()
        }
        viewModelScope.launch {
            authRepository.authStateFlow().collect { user ->
                latestAuthUser = user
                if (user != null) {
                    maybeStartSessionRestore()
                } else {
                    restoreJob?.cancel()
                    restoreJob = null
                    // Guest sign-in may sign out a stale session first; don't reset UI mid-flow.
                    if (blockSessionRestore || _uiState.value.isLoading) {
                        return@collect
                    }
                    _uiState.update {
                        it.copy(
                            profile = null,
                            isLoading = false,
                            isRestoringSession = false,
                        )
                    }
                }
            }
        }
    }

    fun updateEmail(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun updatePassword(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun updateDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value, error = null) }
    }

    fun retryFirebaseAvailabilityCheck() {
        viewModelScope.launch {
            runFirebaseAvailabilityCheck(showProgress = !_uiState.value.isFirebaseAvailable)
            maybeStartSessionRestore()
        }
    }

    fun refreshFirebaseAvailabilityOnResume() {
        viewModelScope.launch {
            runFirebaseAvailabilityCheck(showProgress = false)
            maybeStartSessionRestore()
        }
    }

    fun signInWithGoogle(context: Context) {
        if (!_uiState.value.isFirebaseAvailable) {
            setAuthError("Firebase is unavailable. Check your connection and try again.")
            return
        }
        viewModelScope.launch {
            beginExplicitSignIn()
            val watchdog = launch {
                delay(GOOGLE_SIGN_IN_WATCHDOG_MS)
                if (_uiState.value.isLoading && _uiState.value.profile == null) {
                    val user = authRepository.currentUser
                    if (user != null) {
                        endExplicitSignIn()
                        val fallback = authRepository.fallbackProfile(user)
                        UserProfileSync.rememberQueueReady(fallback.uid, fallback)
                        clearAuthError()
                        _uiState.update {
                            it.copy(isLoading = false, profile = fallback, error = null)
                        }
                    }
                }
            }
            try {
                val webClientId = context.getString(R.string.default_web_client_id)
                require(!webClientId.startsWith("REPLACE")) {
                    "Set default_web_client_id in strings.xml from Firebase console"
                }

                val signInContext = context.findActivity() ?: context
                val idToken = requestGoogleIdToken(signInContext, webClientId)
                val profile = withTimeout(GOOGLE_SIGN_IN_TIMEOUT_MS) {
                    authRepository.signInWithGoogle(idToken)
                }
                UserProfileSync.rememberQueueReady(profile.uid, profile)
                clearAuthError()
                _uiState.update {
                    it.copy(isLoading = false, profile = profile, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                val user = authRepository.currentUser
                if (user != null) {
                    val fallback = authRepository.fallbackProfile(user)
                    UserProfileSync.rememberQueueReady(fallback.uid, fallback)
                    clearAuthError()
                    _uiState.update {
                        it.copy(isLoading = false, profile = fallback, error = null)
                    }
                    viewModelScope.launch {
                        runCatching {
                            authRepository.ensureUserProfile(
                                uid = user.uid,
                                displayName = user.displayName,
                                photoUrl = user.photoUrl?.toString(),
                            )
                        }.onSuccess { synced ->
                            if (_uiState.value.profile?.uid == synced.uid) {
                                _uiState.update { it.copy(profile = synced) }
                            }
                        }
                    }
                } else {
                    setAuthError("Sign-in timed out. Check your connection and try again.")
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: GetCredentialException) {
                setAuthError(e.toGoogleSignInMessage(BuildConfig.DEBUG))
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                setAuthError(e.toAuthMessage())
                _uiState.update { it.copy(isLoading = false) }
            } finally {
                watchdog.cancel()
                endExplicitSignIn()
            }
        }
    }

    fun signInAnonymously() {
        if (!_uiState.value.isFirebaseAvailable) {
            setAuthError("Firebase is unavailable. Check your connection and try again.")
            return
        }
        if (guestSignInJob?.isActive == true) return
        guestSignInJob = viewModelScope.launch {
            beginExplicitSignIn()
            val watchdog = launch {
                delay(GUEST_SIGN_IN_WATCHDOG_MS)
                if (_uiState.value.isLoading && _uiState.value.profile == null) {
                    endExplicitSignIn()
                    val user = authRepository.currentUser
                    if (user != null) {
                        clearAuthError()
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                profile = authRepository.fallbackProfile(user),
                            )
                        }
                    } else {
                        setAuthError("Sign-in timed out. Try again in a moment.")
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            }
            try {
                val (user, profile) = withContext(Dispatchers.IO) {
                    val signedIn = authRepository.beginAnonymousSignIn()
                    signedIn to authRepository.fallbackProfile(signedIn)
                }
                clearAuthError()
                _uiState.update {
                    it.copy(isLoading = false, profile = profile, error = null)
                }
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        authRepository.ensureUserProfile(
                            uid = user.uid,
                            displayName = profile.displayName,
                            photoUrl = user.photoUrl?.toString(),
                        )
                    }.onSuccess { synced ->
                        if (_uiState.value.profile?.uid == synced.uid) {
                            _uiState.update { it.copy(profile = synced) }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val user = authRepository.currentUser
                if (user != null) {
                    clearAuthError()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = authRepository.fallbackProfile(user),
                        )
                    }
                } else {
                    setAuthError("Sign-in timed out. Try again in a moment.")
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: CancellationException) {
                if (_uiState.value.profile == null) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                throw e
            } catch (e: Exception) {
                val user = authRepository.currentUser
                if (user != null) {
                    clearAuthError()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            profile = authRepository.fallbackProfile(user),
                        )
                    }
                } else {
                    setAuthError(e.toAuthMessage())
                    _uiState.update { it.copy(isLoading = false) }
                }
            } finally {
                watchdog.cancel()
                endExplicitSignIn()
                if (_uiState.value.isLoading && _uiState.value.profile == null) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun submitEmailAuth(context: Context, mode: EmailAuthMode) {
        if (!_uiState.value.isFirebaseAvailable) {
            setAuthError("Firebase is unavailable. Check your connection and try again.")
            return
        }
        val state = _uiState.value
        val email = state.email.trim()
        val password = state.password

        if (email.isBlank()) {
            setAuthError("Enter your email")
            return
        }
        if (password.length < 6) {
            setAuthError("Password must be at least 6 characters")
            return
        }

        viewModelScope.launch {
            beginExplicitSignIn()
            try {
                val profile = when (mode) {
                    EmailAuthMode.SIGN_IN -> authRepository.signInWithEmail(email, password)
                    EmailAuthMode.REGISTER -> authRepository.registerWithEmail(
                        email = email,
                        password = password,
                        displayName = state.displayName,
                    )
                }
                runCatching {
                    offerSavePassword(
                        context = context,
                        email = email,
                        password = password,
                    )
                }
                clearAuthError()
                _uiState.update {
                    it.copy(isLoading = false, profile = profile, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setAuthError(e.toAuthMessage())
                _uiState.update { it.copy(isLoading = false) }
            } finally {
                endExplicitSignIn()
            }
        }
    }

    private fun beginExplicitSignIn() {
        blockSessionRestore = true
        restoreJob?.cancel()
        restoreJob = null
        clearAuthError()
        _uiState.update {
            it.copy(
                isLoading = true,
                isRestoringSession = false,
                error = null,
            )
        }
    }

    private fun endExplicitSignIn() {
        blockSessionRestore = false
    }

    private fun setAuthError(message: String) {
        savedStateHandle[KEY_AUTH_ERROR] = message
        _uiState.update { it.copy(error = message) }
    }

    private fun clearAuthError() {
        if (savedStateHandle.contains(KEY_AUTH_ERROR)) {
            savedStateHandle.remove<String>(KEY_AUTH_ERROR)
        }
    }

    /**
     * Debug builds try [GetGoogleIdOption] first (works better on emulators). Release tries
     * [GetSignInWithGoogleOption] first, then falls back to the other option.
     */
    private suspend fun requestGoogleIdToken(context: Context, webClientId: String): String {
        var lastError: GetCredentialException? = null
        repeat(2) { attempt ->
            try {
                return requestGoogleIdTokenOnce(context, webClientId)
            } catch (e: GetCredentialException) {
                if (e is GetCredentialCancellationException) throw e
                lastError = e
                if (attempt == 0) delay(400)
            }
        }
        throw lastError ?: error("Google sign-in failed")
    }

    private suspend fun requestGoogleIdTokenOnce(context: Context, webClientId: String): String {
        val credentialManager = CredentialManager.create(context)
        return if (BuildConfig.DEBUG) {
            try {
                requestGoogleIdTokenViaIdOption(credentialManager, context, webClientId)
            } catch (first: GetCredentialException) {
                if (first is GetCredentialCancellationException) throw first
                try {
                    requestGoogleIdTokenViaSignInWithGoogle(credentialManager, context, webClientId)
                } catch (second: GetCredentialException) {
                    throw first
                }
            }
        } else {
            try {
                requestGoogleIdTokenViaSignInWithGoogle(credentialManager, context, webClientId)
            } catch (first: GetCredentialException) {
                if (first is GetCredentialCancellationException) throw first
                requestGoogleIdTokenViaIdOption(credentialManager, context, webClientId)
            }
        }
    }

    private suspend fun requestGoogleIdTokenViaSignInWithGoogle(
        credentialManager: CredentialManager,
        context: Context,
        webClientId: String,
    ): String {
        val signInWithGoogle = GetSignInWithGoogleOption.Builder(webClientId).build()
        val signInRequest = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogle)
            .build()
        return extractGoogleIdToken(credentialManager.getCredential(context, signInRequest).credential)
    }

    private suspend fun requestGoogleIdTokenViaIdOption(
        credentialManager: CredentialManager,
        context: Context,
        webClientId: String,
    ): String {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
        val googleIdRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        return extractGoogleIdToken(credentialManager.getCredential(context, googleIdRequest).credential)
    }

    private fun extractGoogleIdToken(credential: androidx.credentials.Credential): String {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        error("Unexpected credential type")
    }

    private suspend fun runFirebaseAvailabilityCheck(showProgress: Boolean = false) {
        firebaseAvailabilityMutex.withLock {
            val shouldShowProgress = showProgress && !_uiState.value.isFirebaseAvailable
            if (shouldShowProgress) {
                _uiState.update {
                    it.copy(
                        isCheckingFirebase = true,
                        isFirebaseAvailable = false,
                        error = null,
                    )
                }
            }
            val available = withContext(Dispatchers.IO) {
                authRepository.isFirebaseAvailable()
            }
            _uiState.update { state ->
                state.copy(
                    isCheckingFirebase = false,
                    isFirebaseAvailable = available,
                    error = if (shouldShowProgress && available) null else state.error,
                )
            }
        }
    }

    private suspend fun monitorFirebaseAvailability() {
        while (true) {
            runFirebaseAvailabilityCheck(showProgress = false)
            if (_uiState.value.isFirebaseAvailable) {
                maybeStartSessionRestore()
                delay(60_000)
            } else {
                delay(5_000)
            }
        }
    }

    private fun maybeStartSessionRestore() {
        val user = latestAuthUser ?: return
        if (blockSessionRestore) return
        val state = _uiState.value
        if (state.isLoading && !state.isRestoringSession) return
        if (user.isAnonymous && state.profile != null) return
        if (!_uiState.value.isFirebaseAvailable) return
        if (restoreJob?.isActive == true) return
        if (_uiState.value.profile?.uid == user.uid) return
        restoreJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, isRestoringSession = true, error = null)
            }
            try {
                val profile = withTimeout(10_000) {
                    authRepository.loadCurrentUserProfile() ?: authRepository.ensureUserProfile(
                        uid = user.uid,
                        displayName = user.displayName,
                        photoUrl = user.photoUrl?.toString(),
                    )
                }
                _uiState.update {
                    it.copy(
                        profile = profile,
                        isLoading = false,
                        isRestoringSession = false,
                        error = null,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                val fallback = authRepository.fallbackProfile(user)
                _uiState.update {
                    it.copy(
                        profile = fallback,
                        isLoading = false,
                        isRestoringSession = false,
                        error = null,
                    )
                }
                viewModelScope.launch {
                    runCatching {
                        authRepository.ensureUserProfile(
                            uid = user.uid,
                            displayName = fallback.displayName,
                            photoUrl = user.photoUrl?.toString(),
                        )
                    }.onSuccess { synced ->
                        if (_uiState.value.profile?.uid == synced.uid) {
                            _uiState.update { it.copy(profile = synced) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (!blockSessionRestore) {
                    _uiState.update {
                        it.copy(isLoading = false, isRestoringSession = false)
                    }
                }
            } catch (e: Exception) {
                if (e.message.orEmpty().contains("cancel", ignoreCase = true)) return@launch
                val fallback = authRepository.fallbackProfile(user)
                setAuthError(e.toAuthMessage())
                _uiState.update {
                    it.copy(
                        profile = fallback,
                        isLoading = false,
                        isRestoringSession = false,
                    )
                }
            }
        }
    }
}
