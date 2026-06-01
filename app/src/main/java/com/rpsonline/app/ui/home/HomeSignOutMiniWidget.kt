package com.rpsonline.app.ui.home

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rpsonline.app.R

@Composable
fun HomeSignOutMiniWidget(
    onSignOutConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    HomeHeaderMiniWidget(
        caption = "",
        value = stringResource(R.string.sign_out),
        onClick = { showConfirmDialog = true },
        modifier = modifier,
        valueColor = MaterialTheme.colorScheme.error,
        contentDescription = stringResource(R.string.sign_out),
    )

    if (showConfirmDialog) {
        SignOutConfirmDialog(
            onConfirm = {
                showConfirmDialog = false
                onSignOutConfirmed()
            },
            onDismiss = { showConfirmDialog = false },
        )
    }
}

@Composable
private fun SignOutConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_out_confirm_title)) },
        text = { Text(stringResource(R.string.sign_out_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.sign_out),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
