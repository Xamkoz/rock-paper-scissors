package com.rpsonline.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rpsonline.app.data.repository.PresenceRepository
import kotlinx.coroutines.delay

val LocalOnlineUids = staticCompositionLocalOf { emptySet<String>() }

data class OnlinePresenceRowStyle(
    val isOnline: Boolean,
    val containerColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val nameColor: Color,
    val accentStripeColor: Color?,
)

@Composable
fun rememberOnlineUids(uids: Collection<String>): Set<String> {
    val presenceRepository = remember { PresenceRepository() }
    val tracked = remember(uids) { uids.filter { it.isNotBlank() }.toSet() }
    val onlineUids by presenceRepository.observeOnlineUids(tracked)
        .collectAsStateWithLifecycle(initialValue = emptySet())
    return onlineUids
}

@Composable
fun ProvideOnlinePresence(
    uids: Collection<String>,
    content: @Composable () -> Unit,
) {
    val onlineUids = rememberOnlineUids(uids)
    CompositionLocalProvider(LocalOnlineUids provides onlineUids, content = content)
}

/** Live presence from Firestore listeners. */
@Composable
fun isPlayerUidOnlineLive(uid: String?): Boolean {
    val uidValue = uid?.takeIf { it.isNotBlank() } ?: return false
    return LocalOnlineUids.current.contains(uidValue)
}

/**
 * Latches online for display so row styling does not flicker at heartbeat boundaries.
 * Clears only after [PresenceRepository.ONLINE_DISPLAY_GRACE_MS] of sustained offline.
 */
@Composable
fun rememberStablePlayerOnline(uid: String?): Boolean {
    val uidValue = uid?.takeIf { it.isNotBlank() }
    val liveOnline = uidValue != null && LocalOnlineUids.current.contains(uidValue)
    var displayedOnline by remember(uidValue) { mutableStateOf(false) }

    LaunchedEffect(uidValue, liveOnline) {
        if (liveOnline) {
            displayedOnline = true
            return@LaunchedEffect
        }
        if (!displayedOnline) return@LaunchedEffect
        delay(PresenceRepository.ONLINE_DISPLAY_GRACE_MS)
        displayedOnline = false
    }

    return displayedOnline
}

@Composable
fun isPlayerUidOnline(uid: String?): Boolean = rememberStablePlayerOnline(uid)

@Composable
fun onlinePresenceRowStyle(
    uid: String?,
    emphasized: Boolean = false,
    defaultContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
    defaultBorderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
    defaultBorderWidth: Dp = 1.dp,
    defaultNameColor: Color = MaterialTheme.colorScheme.onSurface,
): OnlinePresenceRowStyle {
    val scheme = MaterialTheme.colorScheme
    if (emphasized) {
        return OnlinePresenceRowStyle(
            isOnline = false,
            containerColor = defaultContainerColor,
            borderColor = scheme.primary.copy(alpha = 0.82f),
            borderWidth = 2.dp,
            nameColor = scheme.primary,
            accentStripeColor = null,
        )
    }
    if (!isPlayerUidOnline(uid)) {
        return OnlinePresenceRowStyle(
            isOnline = false,
            containerColor = defaultContainerColor,
            borderColor = defaultBorderColor,
            borderWidth = defaultBorderWidth,
            nameColor = defaultNameColor,
            accentStripeColor = null,
        )
    }
    return OnlinePresenceRowStyle(
        isOnline = true,
        containerColor = scheme.primaryContainer.copy(alpha = 0.88f),
        borderColor = scheme.primary.copy(alpha = 0.78f),
        borderWidth = 2.dp,
        nameColor = scheme.primary,
        accentStripeColor = scheme.primary.copy(alpha = 0.92f),
    )
}

@Composable
fun playerNameColor(
    uid: String?,
    emphasized: Boolean = false,
    defaultColor: Color = MaterialTheme.colorScheme.onSurface,
): Color = onlinePresenceRowStyle(
    uid = uid,
    emphasized = emphasized,
    defaultNameColor = defaultColor,
).nameColor

@Composable
fun PlayerDisplayNameText(
    name: String,
    uid: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    emphasized: Boolean = false,
    defaultColor: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val color = playerNameColor(uid = uid, emphasized = emphasized, defaultColor = defaultColor)
    val clickableModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }
    Text(
        text = name,
        modifier = clickableModifier,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
