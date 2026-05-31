package com.rpsonline.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.rpsonline.app.R
import com.rpsonline.app.data.preferences.OnlineFilterPreferences

@Composable
fun rememberPersistedOnlineOnlyFilter(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val preferences = remember { OnlineFilterPreferences(context) }
    var onlineOnlyFilter by remember { mutableStateOf(preferences.isOnlineOnlyEnabled()) }
    val setOnlineOnlyFilter: (Boolean) -> Unit = { enabled ->
        onlineOnlyFilter = enabled
        preferences.setOnlineOnlyEnabled(enabled)
    }
    return onlineOnlyFilter to setOnlineOnlyFilter
}

@Composable
fun OnlineOnlyFilterControl(    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.leaderboard_online_only),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = MaterialTheme.colorScheme.outline,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
            ),
        )
    }
}
