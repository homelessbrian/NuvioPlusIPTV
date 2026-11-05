@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.DMSansFamily
import com.nuvio.tv.ui.theme.OpenSansFamily
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.vpn.android.VpnAppearance
import com.nuvio.vpn.android.VpnProfilesActivity

@Composable
internal fun vpnProfilesLauncher(): () -> Unit {
    val context = LocalContext.current
    val colors = NuvioTheme.colors
    val type = MaterialTheme.typography
    val font = when (type.bodyLarge.fontFamily) {
        DMSansFamily -> R.font.dm_sans_variable
        OpenSansFamily -> R.font.opensans_variable
        else -> R.font.inter_variable
    }
    val appearance = VpnAppearance(true)
        .colors(
            colors.Background.toArgb(), colors.BackgroundCard.toArgb(), colors.Border.toArgb(),
            colors.TextPrimary.toArgb(), colors.TextSecondary.toArgb(), colors.Secondary.toArgb(),
            colors.FocusBackground.toArgb(), colors.FocusContent.toArgb(), colors.FocusRing.toArgb(),
            colors.Error.toArgb(),
        )
        .typography(type.headlineLarge.fontSize.value, type.bodyLarge.fontSize.value, type.bodySmall.fontSize.value)
        .corners(if (isFlatSettingsStyle()) 16f else SettingsSecondaryCardRadius.value, 12f)
        .fontResource(font)
    return { VpnProfilesActivity.open(context, appearance) }
}
