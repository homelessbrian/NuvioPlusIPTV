package com.nuvio.tv.livetv.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSyncEntryPoint {
    fun cloudSync(): LiveTvCloudSync
}

/**
 * Starts Setup Sync when the app opens (so it reacts to a sign-in right away, from any
 * screen) and shows its two one-time questions on top of whatever screen you're on.
 * Lives next to the app's navigation.
 */
@Composable
fun CloudSyncHost() {
    val context = LocalContext.current
    val sync = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, CloudSyncEntryPoint::class.java).cloudSync() }.getOrNull()
    } ?: return
    LaunchedEffect(Unit) { runCatching { sync.start() } }

    val state by sync.state.collectAsState()
    val prompt = state.prompt ?: return
    val first = remember(prompt) { FocusRequester() }
    LaunchedEffect(prompt) { delay(80); runCatching { first.requestFocus() } }

    when (prompt) {
        CloudPrompt.BackUp -> LiveDialog(onDismiss = { sync.answerBackUp(false) }, width = 520.dp) {
            LiveText("Setup Sync", color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
            LiveText("Keep your Live TV setup on all your devices?", size = 22.sp, weight = FontWeight.Bold, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            LiveText(
                "Your playlists (including their logins), guides, favorites, Sports and Calendar choices and Live TV settings are saved, so every TV you sign in on has the same setup. A phone keeps its own look and layout. You can change what's included, or turn it off, any time in Settings → Live TV → Setup Sync.",
                color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 6
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem("Save My Setup", Modifier.focusRequester(first)) { sync.answerBackUp(true) }
                MenuItem("Not Now") { sync.answerBackUp(false) }
            }
        }
        CloudPrompt.Choose -> LiveDialog(onDismiss = { sync.answerChoose(CloudChoice.Later) }, width = 520.dp) {
            LiveText("Setup Sync", color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
            LiveText("You have a saved Live TV setup", size = 22.sp, weight = FontWeight.Bold, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            LiveText(
                "This TV has its own setup too. Combine keeps the playlists and guides from both. Use the saved setup replaces what is on this TV.",
                color = NuvioTheme.colors.TextSecondary, size = 14.sp, maxLines = 6
            )
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuItem("Combine Both", Modifier.focusRequester(first)) { sync.answerChoose(CloudChoice.Combine) }
                MenuItem("Use the Saved Setup") { sync.answerChoose(CloudChoice.UseSaved) }
                MenuItem("Not Now") { sync.answerChoose(CloudChoice.Later) }
            }
        }
    }
}
