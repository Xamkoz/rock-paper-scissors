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
import androidx.compose.ui.text.style.TextOverflow
import com.rpsonline.app.R

@Composable
fun HomeSignOutMiniWidget(
    onSignOutConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val signOutLabel = stringResource(R.string.sign_out)

    HomeHeaderChip(
        onClick = { showConfirmDialog = true },
        onLongClick = null,
        containerColor = scheme.surfaceContainerLow.copy(alpha = 0.88f),
        borderColor = scheme.outline.copy(alpha = 0.55f),
        contentDescription = signOutLabel,
        modifier = modifier,
    ) {
        Text(
            text = signOutLabel,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.error,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }

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
