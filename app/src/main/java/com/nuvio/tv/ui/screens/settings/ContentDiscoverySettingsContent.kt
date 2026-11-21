@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ContentDiscoverySettingsContent(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToHomeRows: () -> Unit,
    showPlugins: Boolean,
    showHomeRows: Boolean,
    initialFocusRequester: FocusRequester?
) {
    var serverPage by rememberSaveable { mutableStateOf<String?>(null) }
    var returnFocus by rememberSaveable { mutableStateOf(FOCUS_TOP) }
    val hubServersRequester = remember { FocusRequester() }
    val serversRequester = remember { FocusRequester() }
    val serverRequester = remember { FocusRequester() }
    var returningToHub by remember { mutableStateOf(false) }

    BackHandler(enabled = serverPage != null) {
        serverPage = if (serverPage == SERVERS_PAGE) null else SERVERS_PAGE
        if (serverPage == null) returningToHub = true
    }

    LaunchedEffect(serverPage) {
        val requester = when (serverPage) {
            null -> hubServersRequester.takeIf { returningToHub }
            SERVERS_PAGE -> serversRequester
            else -> serverRequester
        } ?: return@LaunchedEffect
        returningToHub = false
        runCatching { requester.requestFocus() }
    }

    when (val page = serverPage) {
        null -> ContentDiscoveryHub(
            onNavigateToAddons = {
                returnFocus = FOCUS_ADDONS
                onNavigateToAddons()
            },
            onNavigateToPlugins = {
                returnFocus = FOCUS_PLUGINS
                onNavigateToPlugins()
            },
            onNavigateToHomeRows = {
                returnFocus = FOCUS_TOP
                onNavigateToHomeRows()
            },
            onNavigateToServers = { serverPage = SERVERS_PAGE },
            showPlugins = showPlugins,
            showHomeRows = showHomeRows,
            initialFocusRequester = initialFocusRequester,
            returnFocus = when {
                returnFocus == FOCUS_PLUGINS && !showPlugins -> FOCUS_TOP
                else -> returnFocus
            },
            serversFocusRequester = hubServersRequester
        )
        SERVERS_PAGE -> MediaServersSettingsContent(
            initialFocusRequester = serversRequester,
            onOpenServer = { serverPage = it }
        )
        else -> MediaServerSettingsContent(
            connectionId = page,
            initialFocusRequester = serverRequester,
            onOpenServer = { serverPage = it },
            onBack = { serverPage = SERVERS_PAGE }
        )
    }
}

@Composable
private fun ContentDiscoveryHub(
    onNavigateToAddons: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToHomeRows: () -> Unit,
    onNavigateToServers: () -> Unit,
    showPlugins: Boolean,
    showHomeRows: Boolean,
    initialFocusRequester: FocusRequester?,
    returnFocus: String,
    serversFocusRequester: FocusRequester
) {
    // The first row takes focus on entry unless we're coming back from another row's screen.
    val focusAddons = returnFocus == FOCUS_ADDONS || (returnFocus == FOCUS_TOP && !showHomeRows)
    fun focusModifier(target: Boolean): Modifier =
        if (initialFocusRequester != null && target) Modifier.focusRequester(initialFocusRequester) else Modifier

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_content_discovery),
            subtitle = stringResource(R.string.settings_content_discovery_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
            if (showHomeRows) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_home_screen_rows),
                    subtitle = stringResource(R.string.settings_home_screen_rows_subtitle),
                    onClick = onNavigateToHomeRows,
                    leadingIcon = Icons.Default.Reorder,
                    modifier = focusModifier(returnFocus == FOCUS_TOP)
                )
            }
            SettingsActionRow(
                title = stringResource(R.string.addon_title),
                subtitle = stringResource(R.string.settings_content_discovery_addons_subtitle),
                onClick = onNavigateToAddons,
                leadingIcon = Icons.Default.Extension,
                modifier = focusModifier(focusAddons)
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_media_servers),
                subtitle = stringResource(R.string.settings_media_servers_subtitle),
                onClick = onNavigateToServers,
                leadingIcon = Icons.Default.Dns,
                modifier = Modifier.focusRequester(serversFocusRequester)
            )
            if (showPlugins) {
                SettingsActionRow(
                    title = stringResource(R.string.plugin_title),
                    subtitle = stringResource(R.string.settings_content_discovery_plugins_subtitle),
                    onClick = onNavigateToPlugins,
                    leadingIcon = Icons.Default.Power,
                    modifier = focusModifier(returnFocus == FOCUS_PLUGINS)
                )
            }
        }
    }
}

private const val SERVERS_PAGE = "servers"
private const val FOCUS_TOP = "top"
private const val FOCUS_ADDONS = "addons"
private const val FOCUS_PLUGINS = "plugins"
