package com.rpsonline.app.ui.auth

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.rpsonline.app.BuildConfig
import com.rpsonline.app.R
import com.rpsonline.app.ui.components.AppUpdateDialogs
import com.rpsonline.app.ui.components.AutofillTextField
import com.rpsonline.app.ui.components.RpsLoadingColumn
import com.rpsonline.app.ui.components.RpsOutlinedActionButton
import com.rpsonline.app.ui.components.RpsPrimaryActionButton
import com.rpsonline.app.ui.components.excludeFromAutofill
import com.rpsonline.app.ui.components.rpsScreenPadding
import com.rpsonline.app.ui.home.HomeAppInfoFooter
import com.rpsonline.app.ui.util.NetworkUtils
import com.rpsonline.app.ui.util.findActivity
import com.rpsonline.app.viewmodel.AppUpdateViewModel
import com.rpsonline.app.viewmodel.EmailAuthMode
import com.rpsonline.app.viewmodel.SignInViewModel

@Composable
fun SignInScreen(
    onChangelog: () -> Unit = {},
    onSignedIn: () -> Unit = {},
    viewModel: SignInViewModel = viewModel(),
    updateViewModel: AppUpdateViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()

    LaunchedEffect(Unit) {
        updateViewModel.onScreenVisible(context)
    }

    var signedInNavigationDone by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.profile, uiState.isLoading) {
        if (!signedInNavigationDone && uiState.profile != null && !uiState.isLoading) {
            signedInNavigationDone = true
            onSignedIn()
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshFirebaseAvailabilityOnResume()
        onPauseOrDispose { }
    }

    AppUpdateDialogs(
        updateState = updateState,
        activity = activity,
        viewModel = updateViewModel,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rpsScreenPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))

        val isDeviceOnline = rememberDeviceOnline(context)

        if (uiState.isLoading) {
            SignInLoadingState(isRestoringSession = uiState.isRestoringSession)
        } else {
            val showFirebaseCheck = uiState.isCheckingFirebase && !uiState.isFirebaseAvailable
            if (showFirebaseCheck) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.checking_firebase),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
            } else if (!uiState.isFirebaseAvailable) {
                Text(
                    text = if (isDeviceOnline) {
                        stringResource(R.string.waiting_for_firebase)
                    } else {
                        stringResource(R.string.no_internet_try_again)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                RpsOutlinedActionButton(
                    onClick = { viewModel.retryFirebaseAvailabilityCheck() },
                    text = stringResource(R.string.retry),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            AuthButtons(
                onGoogle = {
                    val signInContext = activity ?: context
                    viewModel.signInWithGoogle(signInContext)
                },
                onGuest = viewModel::signInAnonymously,
                enabled = uiState.isFirebaseAvailable && !uiState.isCheckingFirebase,
            )
            uiState.error?.let { message ->
                SignInAuthError(message = message)
            }
            CollapsibleEmailAuthSection(
                email = uiState.email,
                password = uiState.password,
                displayName = uiState.displayName,
                onEmailChange = viewModel::updateEmail,
                onPasswordChange = viewModel::updatePassword,
                onDisplayNameChange = viewModel::updateDisplayName,
                onSignIn = {
                    viewModel.submitEmailAuth(context, EmailAuthMode.SIGN_IN)
                },
                onRegister = {
                    viewModel.submitEmailAuth(context, EmailAuthMode.REGISTER)
                },
                enabled = uiState.isFirebaseAvailable && !uiState.isCheckingFirebase,
            )
        }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HomeAppInfoFooter(
            versionName = updateState.versionName,
            updatesEnabled = BuildConfig.GITHUB_UPDATES_ENABLED,
            availableUpdate = updateState.availableUpdate,
            isCheckingForUpdate = updateState.isCheckingForUpdate,
            isDownloadingUpdate = updateState.isDownloadingUpdate,
            updateMessage = updateState.updateMessage,
            onCheckForUpdate = { updateViewModel.checkForUpdate(context) },
            onInstallUpdate = {
                activity?.let { updateViewModel.downloadAndInstallUpdate(it) }
                    ?: updateViewModel.showUpdatePrompt()
            },
            onVersionClick = onChangelog,
        )
    }
}

@Composable
private fun rememberDeviceOnline(context: android.content.Context): Boolean {
    var isOnline by remember { mutableStateOf(NetworkUtils.isOnline(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            isOnline = NetworkUtils.isOnline(context)
            delay(2_000)
        }
    }
    return isOnline
}

@Composable
private fun SignInLoadingState(isRestoringSession: Boolean) {
    val context = LocalContext.current
    var isOnline by remember { mutableStateOf(NetworkUtils.isOnline(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            isOnline = NetworkUtils.isOnline(context)
            delay(2_000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RpsLoadingColumn(
            message = if (isRestoringSession) stringResource(R.string.restoring_session) else stringResource(R.string.signing_in),
        )
        if (!isOnline) {
            Text(
                text = stringResource(R.string.no_internet_try_again),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SignInAuthError(message: String) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuthButtons(
    onGoogle: () -> Unit,
    onGuest: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RpsPrimaryActionButton(
            onClick = onGoogle,
            text = stringResource(R.string.sign_in_with_google),
            enabled = enabled,
        )
        RpsOutlinedActionButton(
            onClick = onGuest,
            text = stringResource(R.string.continue_as_guest),
            enabled = enabled,
        )
    }
}

@Composable
private fun CollapsibleEmailAuthSection(
    email: String,
    password: String,
    displayName: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onRegister: () -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(16.dp))
    RpsOutlinedActionButton(
        onClick = { expanded = !expanded },
        text = if (expanded) {
            stringResource(R.string.hide_email_sign_in)
        } else {
            stringResource(R.string.sign_in_with_email)
        },
        enabled = enabled,
    )
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            EmailAuthSection(
                email = email,
                password = password,
                displayName = displayName,
                onEmailChange = onEmailChange,
                onPasswordChange = onPasswordChange,
                onDisplayNameChange = onDisplayNameChange,
                onSignIn = onSignIn,
                onRegister = onRegister,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun EmailAuthSection(
    email: String,
    password: String,
    displayName: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onRegister: () -> Unit,
    enabled: Boolean,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val hideKeyboard: () -> Unit = { keyboardController?.hide() }

    AutofillTextField(
        value = email,
        onValueChange = onEmailChange,
        label = stringResource(R.string.email),
        autofillHints = arrayOf(View.AUTOFILL_HINT_EMAIL_ADDRESS),
        imeAction = EditorInfo.IME_ACTION_NEXT,
        enabled = enabled,
    )
    Spacer(modifier = Modifier.height(12.dp))
    AutofillTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.password),
        autofillHints = arrayOf(View.AUTOFILL_HINT_PASSWORD),
        isPassword = true,
        imeAction = EditorInfo.IME_ACTION_NEXT,
        enabled = enabled,
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = displayName,
        onValueChange = onDisplayNameChange,
        label = { Text(stringResource(R.string.display_name_optional)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                hideKeyboard()
                onRegister()
            },
        ),
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .excludeFromAutofill(),
    )

    Spacer(modifier = Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RpsPrimaryActionButton(
            onClick = {
                hideKeyboard()
                onSignIn()
            },
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.sign_in),
            enabled = enabled,
        )
        RpsOutlinedActionButton(
            onClick = {
                hideKeyboard()
                onRegister()
            },
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.create_account),
            enabled = enabled,
        )
    }
}
