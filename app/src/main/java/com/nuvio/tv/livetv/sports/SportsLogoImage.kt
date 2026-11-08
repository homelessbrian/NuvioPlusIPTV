package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import java.net.URI

/**
 * Team and league logos at the size they are shown. ESPN sends 500 px logos and TheSportsDB
 * full-size badges for marks that are 20 to 60 dp on screen; this asks both for a smaller copy
 * (ESPN's own resizer, TheSportsDB's "small" version), so a first look at a page downloads and
 * decodes far less. Anything else is loaded as it is.
 */
internal object SportsImages {
    /** Pixel sizes asked for, so the image cache keeps a few copies per logo, not one per slot. */
    private val SIZES = intArrayOf(64, 128, 256)
    private val ESPN_HOST = Regex("""a\d?\.espncdn\.com""")

    /** A smaller copy of [url] for a slot [px] pixels wide, or null to load [url] itself. */
    fun sized(url: String, px: Int): String? {
        if (px <= 0) return null
        val want = SIZES.firstOrNull { it >= px } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return when {
            ESPN_HOST.matches(host) &&
                (path.startsWith("/i/teamlogos/") || path.startsWith("/i/leaguelogos/")) ->
                "https://a.espncdn.com/combiner/i?img=$path&w=$want&h=$want"
            (host == "thesportsdb.com" || host.endsWith(".thesportsdb.com")) &&
                path.contains("/images/media/") &&
                (path.endsWith(".png", ignoreCase = true) || path.endsWith(".jpg", ignoreCase = true)) ->
                "$url/small"
            else -> null
        }
    }
}

/**
 * A logo for a [size] slot, loaded at about that size (see [SportsImages]). If the smaller
 * copy can't be had, the full image is loaded instead, so a logo never goes missing because of this.
 */
@Composable
internal fun SportsLogoImage(
    url: String,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier.size(size),
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val small = remember(url, px) { SportsImages.sized(url, px) }
    var smallFailed by remember(url) { mutableStateOf(false) }
    AsyncImage(
        model = if (small != null && !smallFailed) small else url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
        onError = { if (small != null) smallFailed = true },
    )
}
