package com.nuvio.tv.remote.host

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.remote.RemoteAction
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteCatalog
import com.nuvio.tv.remote.RemoteDefaultBinding
import com.nuvio.tv.remote.RemoteInputOwner
import com.nuvio.tv.remote.RemoteScreen

/** Screen-local commands. Leaving a screen never leaves its callbacks in the activity. */
@Composable
fun RemoteActionHost(
    screen: RemoteScreen,
    enabled: Boolean,
    defaults: () -> List<RemoteDefaultBinding> = { RemoteCatalog.defaults(screen) },
    onNativeCancel: () -> Unit = {},
    canHandleAction: (RemoteAction) -> Boolean = { true },
    canRemapButton: (RemoteButton) -> Boolean = { true },
    onAction: (RemoteAction) -> Boolean
) {
    val owner = LocalContext.current.remoteInputOwner()
    val lifecycleOwner = LocalLifecycleOwner.current
    val sidebarExpanded = com.nuvio.tv.LocalSidebarExpanded.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentEnabled = rememberUpdatedState(enabled && !sidebarExpanded && windowFocused)
    val currentAction = rememberUpdatedState(onAction)
    val currentDefaults = rememberUpdatedState(defaults)
    val currentNativeCancel = rememberUpdatedState(onNativeCancel)
    val currentCanHandle = rememberUpdatedState(canHandleAction)
    val currentCanRemap = rememberUpdatedState(canRemapButton)
    DisposableEffect(owner, lifecycleOwner, screen) {
        val router = owner?.remoteInputRouter
        val registration = router?.register(
            screen = screen,
            active = {
                currentEnabled.value &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            },
            onAction = { currentAction.value(it) },
            defaults = { currentDefaults.value() },
            onNativeCancel = { currentNativeCancel.value() },
            canHandleAction = { currentCanHandle.value(it) },
            canRemapButton = { currentCanRemap.value(it) }
        )
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) router?.cancelPending()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            registration?.close()
        }
    }
}

private fun Context.remoteInputOwner(): RemoteInputOwner? {
    var current: Context = this
    while (true) {
        if (current is RemoteInputOwner) return current
        val next = (current as? ContextWrapper)?.baseContext ?: return null
        if (next === current) return null
        current = next
    }
}
