package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.livetv.ui.LiveText
import kotlinx.coroutines.delay

/**
 * "Add-ons" on a game page: which of your installed add-ons are searched for each game's live
 * streams, replays and highlights. Nothing is asked until at least one is ticked.
 */
@Composable
internal fun AddonSourcesDialog(
    addons: List<Addon>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    SportsDialog(onDismiss = onDismiss, width = 520.dp) {
        LiveText("Add-ons for games", size = 18.sp, weight = FontWeight.Bold)
        LiveText(
            "Tick the add-ons to search for each game's live streams, replays and highlights. " +
                "Only ticked add-ons are asked, and only when you open a game.",
            color = SportsLook.Dim, size = 12.sp, maxLines = 4
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(addons) { i, a ->
                val on = SportsAddonSources.key(a) in picked
                SportsMenuRow(
                    (if (on) "✓  " else "      ") + a.displayName,
                    if (i == 0) Modifier.focusRequester(first) else Modifier
                ) { onToggle(SportsAddonSources.key(a)) }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}
