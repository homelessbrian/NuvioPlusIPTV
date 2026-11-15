package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.remote.RemoteButtons
import com.nuvio.tv.livetv.remote.RemoteButtonsEditor
import com.nuvio.tv.livetv.remote.RemoteCatalog
import com.nuvio.tv.livetv.remote.RemoteLearnDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Settings → Remote Buttons: give the remote's buttons other jobs, screen by screen. Each
 * screen opens its own page (the list of buttons, a drawing of the remote, and what the
 * highlighted button does). Saved on this TV, for the profile in use; a button nobody changed
 * works exactly as it always has.
 */
@Composable
fun RemoteButtonsSettingsContent(initialFocusRequester: FocusRequester? = null) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RemoteButtons.start(context) }
    val state by RemoteButtons.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val first = initialFocusRequester ?: remember { FocusRequester() }
    val padSeen = state.pad || RemoteCatalog.BUTTONS.any { it.pad && it.codes.any { c -> c in state.seen } }
    val otherProfiles = remember(dialog, editing) { RemoteButtons.profiles().filter { it.first != RemoteButtons.profileId() } }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        SettingsDetailHeader(
            title = "Remote Buttons",
            subtitle = if (state.changedTotal == 0) "Give your remote's buttons other jobs, screen by screen. Saved for this profile, " + (if (state.synced) "and synced to your other TVs" else "on this TV")
            else "${state.changedTotal} changed for this profile" + (if (state.synced) ", synced to your other TVs" else " on this TV") + ". Everything else works the way it always has"
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "screens") {
                SettingsGroupCard(title = "Choose a Screen") {
                    RemoteCatalog.SCREENS.forEachIndexed { i, sc ->
                        val n = state.changed(sc.id)
                        SettingsActionRow(
                            title = sc.title,
                            subtitle = sc.about,
                            value = if (n > 0) "$n changed" else "Standard",
                            onClick = { editing = sc.id },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                        )
                    }
                }
            }
            item(key = "remote") {
                SettingsGroupCard(title = "Your Remote") {
                    SettingsActionRow(
                        title = "Add a Button",
                        subtitle = "For a button that isn't in the lists: press it and it's added. Volume, Home, Power and the voice button belong to the TV and can't be changed",
                        value = if (state.learned.isEmpty()) null else "${state.learned.size} added",
                        onClick = { dialog = "learn" }
                    )
                    state.learned.forEach { l ->
                        SettingsActionRow(
                            title = l.name,
                            subtitle = "A button you added (key ${l.code}). OK removes it, and what you set it to",
                            value = "Remove",
                            onClick = { RemoteButtons.forget(l.code); LiveTvNotice.show("${l.name} removed") }
                        )
                    }
                    if (padSeen) {
                        SettingsActionRow(
                            title = "Use the Suggested Controller Layout",
                            subtitle = "Bumpers change channel, triggers skip, Y opens the options, Start pauses. Only fills buttons you haven't set",
                            onClick = { RemoteButtons.applySuggestedPad(); LiveTvNotice.show("Controller buttons set") }
                        )
                    }
                }
            }
            item(key = "reset") {
                SettingsGroupCard(title = "Sync, Profiles and Reset") {
                    SettingsToggleRow(
                        "Sync Buttons to My Other TVs",
                        "On: this TV uses one set of buttons shared by every TV where this is on (it travels with Setup Sync in Live TV settings). Off: this TV keeps its own",
                        state.synced,
                        { RemoteButtons.setSynced(!state.synced) }
                    )
                    if (otherProfiles.isNotEmpty()) {
                        SettingsActionRow(
                            title = "Copy From Another Profile",
                            subtitle = "Replaces this profile's buttons with another profile's",
                            onClick = { dialog = "copy" }
                        )
                    }
                    SettingsActionRow(
                        title = "Reset Every Screen",
                        subtitle = "All buttons back to how they work out of the box, for this profile",
                        value = if (state.changedTotal > 0) "${state.changedTotal} changed" else null,
                        enabled = state.changedTotal > 0,
                        onClick = { dialog = "reset" }
                    )
                }
            }
        }
    }

    editing?.let { RemoteButtonsEditor(startScreen = it, onClose = { editing = null }) }
    when (dialog) {
        "learn" -> RemoteLearnDialog(onClose = { dialog = null })
        "copy" -> SettingsSingleChoiceDialog(
            title = "Copy From Another Profile",
            subtitle = "This profile's own button changes are replaced.",
            options = listOf(SettingsPickerOption(0, "Cancel")) + otherProfiles.map { SettingsPickerOption(it.first, it.second) },
            selectedValue = 0,
            onOptionSelected = { id ->
                if (id > 0) { RemoteButtons.copyFromProfile(id); LiveTvNotice.show("Buttons copied") }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
        "reset" -> SettingsSingleChoiceDialog(
            title = "Reset Every Screen?",
            subtitle = "All ${state.changedTotal} changes go back to standard, for this profile" + (if (state.synced) " on every TV that syncs its buttons." else ". Other TVs aren't touched."),
            options = listOf(SettingsPickerOption(false, "Keep my buttons"), SettingsPickerOption(true, "Reset everything")),
            selectedValue = false,
            onOptionSelected = { yes ->
                if (yes) { RemoteButtons.resetAll(); LiveTvNotice.show("Remote buttons reset") }
                dialog = null
            },
            onDismiss = { dialog = null }
        )
    }
}
