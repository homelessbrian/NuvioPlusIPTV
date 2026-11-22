package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.livetv.record.FoundComputer
import com.nuvio.tv.livetv.record.NetworkFolderConfig
import com.nuvio.tv.livetv.record.NetworkFolderFinder
import com.nuvio.tv.livetv.record.NetworkFolderStore
import com.nuvio.tv.livetv.record.NetworkSearchState
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.livetv.record.SmbDiscovery
import com.nuvio.tv.livetv.record.SmbFolder
import com.nuvio.tv.livetv.record.SmbShare
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.collection.NuvioTextField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The parts of the set-up, in order (Find on Network goes through all of them). */
private enum class FolderStep { FORM, SEARCH, LOGIN, SHARES, FOLDERS }

/**
 * Sets up the network folder for recordings: address, username, password. "Test and Save"
 * connects, makes the folder and writes a small test file before saving; if that fails it says
 * why in plain words, and "Save Anyway" keeps it (a computer that's asleep right now).
 *
 * "Find on Network" fills it in instead: pick a computer or NAS from the ones found, sign in,
 * pick one of its shared folders and a folder inside it, and it's tested and saved.
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

    // Find on Network
    var step by remember { mutableStateOf(FolderStep.FORM) }
    var searchRun by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf(NetworkSearchState()) }
    var computer by remember { mutableStateOf<FoundComputer?>(null) }
    var shares by remember { mutableStateOf<List<SmbShare>>(emptyList()) }
    var share by remember { mutableStateOf<String?>(null) }
    var browsePath by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var foldersRun by remember { mutableStateOf(0) }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val firstItem = remember { FocusRequester() }
    LaunchedEffect(step, creatingFolder, foldersRun) {
        delay(80)
        runCatching { firstItem.requestFocus() }
    }

    fun clearStatus() { failed = false; status = null }

    fun configOrNull(): NetworkFolderConfig? {
        val (host, share, path) = NetworkFolderConfig.parseAddress(address) ?: return null
        return NetworkFolderConfig(host = host, share = share, path = path, username = username.trim(), password = password)
    }

    fun save(config: NetworkFolderConfig) {
        if (NetworkFolderStore.save(context, config)) onSaved()
        else { failed = true; status = context.getString(R.string.iptv_network_save_failed) }
    }

    /** In Full app VPN mode, lets [host] past the VPN first (it reconnects). Call off the main thread. */
    suspend fun letPastVpn(host: String) {
        runCatching {
            if (com.nuvio.vpn.android.VpnHomeBypass.fullAppOn(context)) {
                withContext(Dispatchers.Main) { status = context.getString(R.string.iptv_network_checking_vpn) }
                NetworkFolderStore.tellVpn(context, NetworkFolderConfig(host = host, share = ""))
                com.nuvio.vpn.android.VpnHomeBypass.awaitConnected(context, 20_000)
            }
        }
    }

    fun testAndSave() {
        val config = configOrNull()
        if (config == null) {
            failed = false
            status = context.getString(R.string.iptv_network_bad_address)
            return
        }
        checking = true
        failed = false
        status = context.getString(R.string.iptv_network_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                letPastVpn(config.host)
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

    fun browsingConfig(path: String = browsePath): NetworkFolderConfig? {
        val c = computer ?: return null
        val s = share ?: return null
        return NetworkFolderConfig(host = c.host, share = s, path = path, username = username.trim(), password = password)
    }

    fun signIn() {
        val c = computer ?: return
        checking = true
        failed = false
        status = context.getString(R.string.iptv_network_find_signing_in)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                letPastVpn(c.host)
                runCatching { SmbDiscovery.listShares(c.host, username, password) }
            }
            checking = false
            result.onSuccess {
                shares = it
                clearStatus()
                step = FolderStep.SHARES
            }.onFailure { e ->
                failed = true
                status = SmbFolder.describe(e, NetworkFolderConfig(host = c.host, share = ""))
            }
        }
    }

    fun openFolder(path: String) {
        browsePath = path
        folders = null
        creatingFolder = false
        clearStatus()
        foldersRun++
    }

    LaunchedEffect(step, searchRun) {
        if (step != FolderStep.SEARCH) return@LaunchedEffect
        search = NetworkSearchState()
        NetworkFolderFinder.search(context).collect { search = it }
    }

    LaunchedEffect(step, foldersRun) {
        if (step != FolderStep.FOLDERS) return@LaunchedEffect
        val config = browsingConfig() ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching {
                SmbFolder(config).use { f ->
                    f.listFolders("").filterNot { it.startsWith(".") }.sortedBy { it.lowercase() }
                }
            }
        }
        result.onSuccess { folders = it }.onFailure { e ->
            folders = emptyList()
            failed = true
            status = SmbFolder.describe(e, config)
        }
    }

    fun back() {
        if (checking) return
        clearStatus()
        when (step) {
            FolderStep.FORM -> onDismiss()
            FolderStep.SEARCH -> step = FolderStep.FORM
            FolderStep.LOGIN -> step = FolderStep.SEARCH
            FolderStep.SHARES -> step = FolderStep.LOGIN
            FolderStep.FOLDERS -> when {
                creatingFolder -> creatingFolder = false
                browsePath.isEmpty() -> step = FolderStep.SHARES
                else -> openFolder(browsePath.substringBeforeLast('/', ""))
            }
        }
    }

    // Closed without saving: the VPN goes back to the saved folder's address.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { NetworkFolderStore.restoreVpn(context) } }
    }
    val computerLabel = computer?.let { c -> c.name?.let { "$it (${c.host})" } ?: c.host }.orEmpty()
    NuvioDialog(
        onDismiss = { back() },
        title = context.getString(R.string.iptv_network_title),
        subtitle = when (step) {
            FolderStep.FORM -> context.getString(R.string.iptv_network_subtitle)
            FolderStep.SEARCH -> context.getString(R.string.iptv_network_find_subtitle)
            FolderStep.LOGIN -> context.getString(R.string.iptv_network_find_login_subtitle, computerLabel)
            FolderStep.SHARES -> context.getString(R.string.iptv_network_find_shares_subtitle, computerLabel)
            FolderStep.FOLDERS -> context.getString(R.string.iptv_network_find_folders_subtitle)
        },
        width = 620.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            when (step) {
                FolderStep.FORM -> {
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_network_find),
                        subtitle = context.getString(R.string.iptv_network_find_row_subtitle),
                        leadingIcon = Icons.Default.Search,
                        enabled = !checking,
                        onClick = { clearStatus(); searchRun++; step = FolderStep.SEARCH },
                        modifier = Modifier.focusRequester(firstItem)
                    )
                    Field(context.getString(R.string.iptv_network_address)) {
                        NuvioTextField(
                            value = address,
                            onValueChange = { address = it; clearStatus() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = context.getString(R.string.iptv_network_address_hint)
                        )
                    }
                    Field(context.getString(R.string.iptv_network_username)) {
                        NuvioTextField(
                            value = username,
                            onValueChange = { username = it; clearStatus() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = context.getString(R.string.iptv_network_username_hint)
                        )
                    }
                    Field(context.getString(R.string.iptv_network_password)) {
                        NuvioTextField(
                            value = password,
                            onValueChange = { password = it; clearStatus() },
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

                FolderStep.SEARCH -> {
                    SettingsActionRow(
                        title = context.getString(R.string.iptv_network_find_type_instead),
                        subtitle = null,
                        leadingIcon = Icons.Default.Edit,
                        onClick = { step = FolderStep.FORM },
                        modifier = Modifier.focusRequester(firstItem)
                    )
                    Text(
                        text = when {
                            search.noNetwork -> context.getString(R.string.iptv_network_find_no_network)
                            search.searching -> context.getString(R.string.iptv_network_find_searching)
                            search.found.isEmpty() -> context.getString(R.string.iptv_network_find_none)
                            else -> context.getString(R.string.iptv_network_find_pick)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    if (search.vpnInTheWay) Text(
                        text = context.getString(R.string.iptv_network_find_vpn),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    for (found in search.found) {
                        SettingsActionRow(
                            title = found.name ?: found.host,
                            subtitle = if (found.name != null) found.host else null,
                            leadingIcon = Icons.Default.Dns,
                            onClick = {
                                // Signing in to the same computer again: keep the saved login.
                                if (existing != null && existing.host.equals(found.host, ignoreCase = true) && computer?.host != found.host) {
                                    username = existing.username
                                    password = existing.password
                                }
                                computer = found
                                clearStatus()
                                step = FolderStep.LOGIN
                            }
                        )
                    }
                }

                FolderStep.LOGIN -> {
                    Field(context.getString(R.string.iptv_network_username)) {
                        NuvioTextField(
                            value = username,
                            onValueChange = { username = it; clearStatus() },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = context.getString(R.string.iptv_network_username_hint),
                            focusRequester = firstItem
                        )
                    }
                    Field(context.getString(R.string.iptv_network_password)) {
                        NuvioTextField(
                            value = password,
                            onValueChange = { password = it; clearStatus() },
                            modifier = Modifier.fillMaxWidth(),
                            secret = true
                        )
                    }
                    Text(
                        text = context.getString(R.string.iptv_network_find_login_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }

                FolderStep.SHARES -> {
                    if (shares.isEmpty()) {
                        Text(
                            text = context.getString(R.string.iptv_network_find_no_shares),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_network_find_type_instead),
                            subtitle = null,
                            leadingIcon = Icons.Default.Edit,
                            onClick = {
                                computer?.let { address = "\\\\" + it.host + "\\" }
                                step = FolderStep.FORM
                            },
                            modifier = Modifier.focusRequester(firstItem)
                        )
                    }
                    shares.forEachIndexed { i, s ->
                        SettingsActionRow(
                            title = s.name,
                            subtitle = s.remark.ifBlank { null },
                            leadingIcon = Icons.Default.FolderShared,
                            onClick = {
                                share = s.name
                                step = FolderStep.FOLDERS
                                openFolder("")
                            },
                            modifier = if (i == 0) Modifier.focusRequester(firstItem) else Modifier
                        )
                    }
                }

                FolderStep.FOLDERS -> {
                    val here = browsingConfig()?.label.orEmpty()
                    if (creatingFolder) {
                        Field(context.getString(R.string.iptv_network_find_new_folder_name)) {
                            NuvioTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it; clearStatus() },
                                modifier = Modifier.fillMaxWidth(),
                                focusRequester = firstItem
                            )
                        }
                        Text(
                            text = context.getString(R.string.iptv_network_find_new_folder_in, here),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    } else {
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_network_find_use_folder),
                            subtitle = here,
                            leadingIcon = Icons.Default.Check,
                            enabled = !checking,
                            onClick = {
                                val config = browsingConfig() ?: return@SettingsActionRow
                                address = config.address
                                step = FolderStep.FORM
                                testAndSave()
                            },
                            modifier = Modifier.focusRequester(firstItem)
                        )
                        SettingsActionRow(
                            title = context.getString(R.string.iptv_network_find_new_folder),
                            subtitle = null,
                            leadingIcon = Icons.Default.Add,
                            enabled = !checking && folders != null,
                            onClick = { newFolderName = ""; clearStatus(); creatingFolder = true }
                        )
                        val list = folders
                        if (list == null) Text(
                            text = context.getString(R.string.iptv_network_find_loading_folders),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        ) else for (name in list) {
                            SettingsActionRow(
                                title = name,
                                subtitle = null,
                                leadingIcon = Icons.Default.Folder,
                                onClick = { openFolder(if (browsePath.isEmpty()) name else "$browsePath/$name") }
                            )
                        }
                    }
                }
            }
        }
        status?.let {
            Text(it, color = if (failed) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        SettingsDialogActionRow {
            when (step) {
                FolderStep.FORM -> {
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
                        onClick = { testAndSave() }
                    )
                }
                FolderStep.SEARCH -> {
                    SettingsDialogActionButton(text = context.getString(R.string.iptv_network_find_back), onClick = { back() })
                    SettingsDialogActionButton(
                        text = context.getString(R.string.iptv_network_find_again),
                        primary = true,
                        enabled = !search.searching,
                        onClick = { searchRun++ }
                    )
                }
                FolderStep.LOGIN -> {
                    SettingsDialogActionButton(text = context.getString(R.string.iptv_network_find_back), onClick = { back() }, enabled = !checking)
                    SettingsDialogActionButton(
                        text = context.getString(R.string.iptv_network_find_show_shares),
                        primary = true,
                        enabled = !checking,
                        onClick = { signIn() }
                    )
                }
                FolderStep.SHARES -> {
                    SettingsDialogActionButton(text = context.getString(R.string.iptv_network_find_back), onClick = { back() })
                }
                FolderStep.FOLDERS -> {
                    SettingsDialogActionButton(
                        text = context.getString(if (creatingFolder) R.string.action_cancel else R.string.iptv_network_find_back),
                        onClick = { back() },
                        enabled = !checking
                    )
                    if (creatingFolder) SettingsDialogActionButton(
                        text = context.getString(R.string.iptv_network_find_create),
                        primary = true,
                        enabled = !checking,
                        onClick = create@{
                            val name = newFolderName.trim().trim('/', '\\')
                            if (name.isEmpty() || name.any { it in "\\/:*?\"<>|" } || name == "." || name == "..") {
                                failed = true
                                status = context.getString(R.string.iptv_network_find_bad_folder_name)
                                return@create
                            }
                            val config = browsingConfig() ?: return@create
                            checking = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { SmbFolder(config).use { it.mkdirs(name) } }
                                }
                                checking = false
                                result.onSuccess {
                                    openFolder(if (browsePath.isEmpty()) name else "$browsePath/$name")
                                }.onFailure { e ->
                                    failed = true
                                    status = SmbFolder.describe(e, config)
                                }
                            }
                        }
                    )
                }
            }
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
