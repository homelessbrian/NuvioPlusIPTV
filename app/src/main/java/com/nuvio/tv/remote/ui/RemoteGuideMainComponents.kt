@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.remote.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.remote.RemoteButton
import com.nuvio.tv.remote.RemoteConfiguration
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun RemoteSelectedButtonHeading(name: String, changed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        Text(name, Modifier.weight(1f), color = NuvioTheme.colors.TextPrimary,
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (changed) R.string.remote_guide_changed else R.string.remote_guide_original),
            Modifier.background(NuvioTheme.colors.Secondary.copy(alpha = .10f), NuvioTheme.shapes.badge)
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
            color = NuvioTheme.colors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun RemoteListeningPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(NuvioTheme.colors.BackgroundCard, NuvioTheme.shapes.dialog)
        .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Secondary.copy(alpha = .4f), NuvioTheme.shapes.dialog)
        .padding(NuvioTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        content = content)
}

internal fun remotePhysicalButtonName(context: Context, button: RemoteButton, configuration: RemoteConfiguration): String =
    remoteButtonName(context, button, configuration.copy(extras = configuration.extras.map {
        if (it.button.canonical() == button.canonical()) it.copy(label = "") else it
    }))
