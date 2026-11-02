package com.nuvio.tv.livetv.ondemand

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * "Play" on a details page opened from On Demand plays the provider's copy straight away.
 * The details page remembers which On Demand title it was opened for (in its own saved
 * state), so opening the same movie from anywhere else in Nuvio still works as normal.
 * Long-press Play (Nuvio's "Play manually") always shows the full stream list.
 */
object OnDemandPlay {
    /** Saved-state key on the details page's back stack entry. */
    const val KEY = "livetv_on_demand_uid"

    data class Resolved(val url: String, val title: String, val poster: String?)

    /** The "Play Straight from My Provider" setting. */
    suspend fun playDirect(context: Context): Boolean = runCatching {
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, OnDemandEntryPoint::class.java)
            .liveTvPreferences().settings.first().onDemandPlayDirect
    }.getOrDefault(false)

    /** Every version of the title this details page was opened for (best quality first). */
    suspend fun versions(context: Context, uid: String): List<OnDemandRepository.Version> {
        val repo = onDemandRepository(context)
        val item = repo.itemByUid(uid) ?: return emptyList()
        return repo.versions(item)
    }

    /** The provider link for [uid] (and, for series, the chosen episode), or null. */
    suspend fun resolve(context: Context, uid: String, season: Int?, episode: Int?): Resolved? {
        val repo = onDemandRepository(context)
        val item = repo.itemByUid(uid) ?: return null
        val name = OnDemandDatabase.displayTitle(item.name)
        return if (item.kind == VodKind.MOVIE) {
            repo.movieUrl(item)?.let { Resolved(it, name, item.icon) }
        } else {
            val info = repo.info(item) ?: return null
            val ep = info.episodes.firstOrNull { it.season == season && it.episode == episode }
                ?: if (season == null && episode == null) info.episodes.firstOrNull() else null
            ep?.let { Resolved(it.url, "$name · S${it.season}E${it.episode}", ep.image ?: item.icon) }
        }
    }
}

/**
 * Play on a details page opened from On Demand, when your provider has several copies of the
 * title: pick one (best quality first). Long-press Play still shows every stream.
 */
@Composable
fun OnDemandVersionPicker(
    versions: List<OnDemandRepository.Version>,
    onPick: (OnDemandRepository.Version) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        LiveText("Choose a version", size = 20.sp, weight = FontWeight.Bold)
        LiveText("Your provider has this title more than once.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(versions, key = { it.item.uid }) { v ->
                MenuItem(
                    v.label + if (v.detail.isNotBlank()) "  —  ${v.detail}" else "",
                    if (v == versions.first()) Modifier.focusRequester(first) else Modifier
                ) { onPick(v) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}
