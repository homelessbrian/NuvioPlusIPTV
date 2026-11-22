package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.record.NetworkFolderConfig
import com.nuvio.tv.livetv.record.NetworkFolderStore
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.livetv.record.SmbFolder
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sets up the network folder for recordings: address, username, password. "Test and Save"
 * connects, makes the folder and writes a small test file before saving; if that fails it says
 * why in plain words, and "Save Anyway" keeps it (a computer that's asleep right now).
 */
@Composable
internal fun NetworkFolderDialog(
    onDismiss: () -> Unit,
    /** Saved (and tested, unless saved anyway). */
    onSaved: () -> Unit,
    /** The set-up was removed. */
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    val existing = remember { NetworkFolderStore.config(context) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var password by remember { mutableStateOf(existing?.password.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val firstField = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { firstField.requestFocus() }
    }

    fun configOrNull(): NetworkFolderConfig? {
        val (host, share, path) = NetworkFolderConfig.parseAddress(address) ?: return null
        return NetworkFolderConfig(host = host, share = share, path = path, username = username.trim(), password = password)
    }

    fun save(config: NetworkFolderConfig) {
        if (NetworkFolderStore.save(context, config)) onSaved()
        else { failed = true; status = context.getString(R.string.iptv_network_save_failed) }
    }

    // Closed without saving: the VPN goes back to the saved folder's address.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { NetworkFolderStore.restoreVpn(context) } }
    }
    NuvioDialog(
        onDismiss = { if (!checking) onDismiss() },
        title = context.getString(R.string.iptv_network_title),
        subtitle = context.getString(R.string.iptv_network_subtitle),
        width = 620.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            Field(context.getString(R.string.iptv_network_address)) {
                NuvioTextField(
                    value = address,
                    onValueChange = { address = it; failed = false; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = context.getString(R.string.iptv_network_address_hint),
                    focusRequester = firstField
                )
            }
            Field(context.getString(R.string.iptv_network_username)) {
                NuvioTextField(
                    value = username,
                    onValueChange = { username = it; failed = false; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = context.getString(R.string.iptv_network_username_hint)
                )
            }
            Field(context.getString(R.string.iptv_network_password)) {
                NuvioTextField(
                    value = password,
                    onValueChange = { password = it; failed = false; status = null },
                    modifier = Modifier.fillMaxWidth(),
                    secret = true
                )
            }
            Text(
                text = context.getString(R.string.iptv_network_tips),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        status?.let {
            Text(it, color = if (failed) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = context.getString(R.string.action_cancel), onClick = onDismiss, enabled = !checking)
            if (existing != null) SettingsDialogActionButton(
                text = context.getString(R.string.iptv_network_remove),
                onClick = { if (NetworkFolderStore.save(context, null)) onRemoved() },
                enabled = !checking
            )
            if (failed) SettingsDialogActionButton(
                text = context.getString(R.string.iptv_network_save_anyway),
                onClick = { configOrNull()?.let { save(it) } },
                enabled = !checking
            )
            SettingsDialogActionButton(
                text = context.getString(R.string.iptv_network_test_save),
                primary = true,
                enabled = !checking,
                onClick = {
                    val config = configOrNull()
                    if (config == null) {
                        failed = false
                        status = context.getString(R.string.iptv_network_bad_address)
                        return@SettingsDialogActionButton
                    }
                    checking = true
                    failed = false
                    status = context.getString(R.string.iptv_network_checking)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            // VPN in Full app mode: let this address past it first (it reconnects).
                            runCatching {
                                if (com.nuvio.vpn.android.VpnHomeBypass.fullAppOn(context)) {
                                    withContext(Dispatchers.Main) { status = context.getString(R.string.iptv_network_checking_vpn) }
                                    NetworkFolderStore.tellVpn(context, config)
                                    com.nuvio.vpn.android.VpnHomeBypass.awaitConnected(context, 20_000)
                                }
                            }
                            runCatching { SmbFolder(config).use { it.test() } }
                        }
                        // The VPN in Full app mode can be why: say so (and what to do).
                        val vpnTip = if (result.isFailure) withContext(Dispatchers.IO) {
                            runCatching { com.nuvio.vpn.android.VpnHomeBypass.hint(context, config.host) }.getOrNull()
                        } else null
                        checking = false
                        result.onSuccess { space ->
                            NetworkFolderStore.rememberSpace(context, space)
                            status = if (space != null) context.getString(R.string.iptv_network_works, RecordingStorage.sizeLabel(space.first))
                                else context.getString(R.string.iptv_network_works_no_size)
                            save(config)
                        }.onFailure { e ->
                            failed = true
                            status = SmbFolder.describe(e, config) + (vpnTip?.let { "\n$it" } ?: "")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
        content()
    }
}
