package com.rpsonline.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.rpsonline.app.ui.theme.themedPrimaryLabelColor
import com.rpsonline.app.data.repository.AuthRepository
import com.rpsonline.app.data.repository.PresenceRepository
import kotlinx.coroutines.delay

val LocalOnlineUids = staticCompositionLocalOf { emptySet<String>() }

/** Signed-in user is online while their uid is tracked on this screen. */
internal fun displayOnlineUids(
    tracked: Set<String>,
    liveOnlineUids: Set<String>,
    selfUid: String?,
): Set<String> {
    if (selfUid.isNullOrBlank() || selfUid !in tracked) return liveOnlineUids
    return liveOnlineUids + selfUid
}

@Composable
private fun rememberDisplayOnlineUids(
    tracked: Set<String>,
    liveOnlineUids: Set<String>,
): Set<String> {
    val selfUid = remember { AuthRepository().currentUserId }
    return remember(tracked, liveOnlineUids, selfUid) {
        displayOnlineUids(tracked, liveOnlineUids, selfUid)
    }
}

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
    val onlineFlow = remember(tracked) { presenceRepository.observeOnlineUids(tracked) }
    val onlineUids by onlineFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    return onlineUids
}

@Composable
fun rememberAllOnlineUids(): Set<String> {
    val presenceRepository = remember { PresenceRepository() }
    val onlineFlow = remember { presenceRepository.observeAllOnlineUids() }
    val onlineUids by onlineFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    return onlineUids
}

@Composable
fun rememberOnlineUidsPolling(uids: Collection<String>): Set<String> {
    val presenceRepository = remember { PresenceRepository() }
    val tracked = remember(uids) { uids.filter { it.isNotBlank() }.toSet() }
    val onlineFlow = remember(tracked) { presenceRepository.observeOnlineUidsPolling(tracked) }
    val onlineUids by onlineFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    return onlineUids
}

data class OnlineUidsPollSnapshot(
    val onlineUids: Set<String>,
    val hasPolled: Boolean,
)

/** Batched presence polling with stale-while-revalidate for stable online-filter UI. */
@Composable
fun rememberOnlineUidsPollSnapshot(uids: Collection<String>): OnlineUidsPollSnapshot {
    val tracked = remember(uids) { uids.filter { it.isNotBlank() }.toSet() }
    val selfUid = remember { AuthRepository().currentUserId }
    val cachedSnapshot = remember { mutableStateOf(OnlineUidsPollSnapshot(emptySet(), false)) }

    val seededInitial = remember(tracked, cachedSnapshot.value) {
        cachedSnapshot.value.takeIf { it.hasPolled && tracked.isNotEmpty() }?.let { cached ->
            OnlineUidsPollSnapshot(
                onlineUids = cached.onlineUids.intersect(tracked),
                hasPolled = true,
            )
        } ?: OnlineUidsPollSnapshot(emptySet(), tracked.isEmpty())
    }

    val current = produceState(
        initialValue = seededInitial,
        tracked,
    ) {
        if (tracked.isEmpty()) {
            val empty = OnlineUidsPollSnapshot(emptySet(), true)
            value = empty
            cachedSnapshot.value = empty
            return@produceState
        }
        PresenceRepository().observeOnlineUidsPolling(tracked).collect { liveOnlineUids ->
            val next = OnlineUidsPollSnapshot(
                onlineUids = displayOnlineUids(tracked, liveOnlineUids, selfUid),
                hasPolled = true,
            )
            value = next
            cachedSnapshot.value = next
        }
    }.value

    return if (current.hasPolled) {
        current
    } else {
        cachedSnapshot.value.takeIf { it.hasPolled && tracked.isNotEmpty() }?.let { cached ->
            OnlineUidsPollSnapshot(
                onlineUids = cached.onlineUids.intersect(tracked),
                hasPolled = true,
            )
        } ?: current
    }
}

@Composable
fun ProvideOnlinePresencePolling(
    uids: Collection<String>,
    content: @Composable () -> Unit,
) {
    val tracked = remember(uids) { uids.filter { it.isNotBlank() }.toSet() }
    val liveOnlineUids = rememberOnlineUidsPolling(tracked)
    val displayOnlineUids = rememberDisplayOnlineUids(tracked, liveOnlineUids)
    CompositionLocalProvider(LocalOnlineUids provides displayOnlineUids, content = content)
}

@Composable
fun ProvideOnlinePresence(
    uids: Collection<String>,
    content: @Composable () -> Unit,
) {
    val tracked = remember(uids) { uids.filter { it.isNotBlank() }.toSet() }
    val liveOnlineUids = rememberOnlineUids(tracked)
    val displayOnlineUids = rememberDisplayOnlineUids(tracked, liveOnlineUids)
    CompositionLocalProvider(LocalOnlineUids provides displayOnlineUids, content = content)
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
    defaultNameColor: Color? = null,
): OnlinePresenceRowStyle {
    val scheme = MaterialTheme.colorScheme
    val offlineNameColor = defaultNameColor ?: themedPrimaryLabelColor()
    val online = uid?.isNotBlank() == true && isPlayerUidOnline(uid)
    if (online) {
        return OnlinePresenceRowStyle(
            isOnline = true,
            containerColor = scheme.primaryContainer.copy(alpha = 0.88f),
            borderColor = scheme.primary.copy(alpha = 0.78f),
            borderWidth = 2.dp,
            nameColor = scheme.primary,
            accentStripeColor = scheme.primary.copy(alpha = 0.92f),
        )
    }
    if (emphasized) {
        return OnlinePresenceRowStyle(
            isOnline = false,
            containerColor = defaultContainerColor,
            borderColor = scheme.primary.copy(alpha = 0.82f),
            borderWidth = 2.dp,
            nameColor = scheme.primary,
            accentStripeColor = scheme.primary.copy(alpha = 0.92f),
        )
    }
    if (!isPlayerUidOnline(uid)) {
        return OnlinePresenceRowStyle(
            isOnline = false,
            containerColor = defaultContainerColor,
            borderColor = defaultBorderColor,
            borderWidth = defaultBorderWidth,
            nameColor = offlineNameColor,
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
    defaultColor: Color? = null,
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
    defaultColor: Color? = null,
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
