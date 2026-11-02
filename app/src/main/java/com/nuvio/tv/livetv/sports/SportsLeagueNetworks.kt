package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.sports.SportsCatalog.hasWord
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A league's (or a sport's) own channel: NFL Network, NBA TV, Golf Channel… [words] are what its
 * name says (lowercase, as [SportsCatalog.norm] writes them); a name with any of [not] is another
 * channel (RedZone isn't NFL Network).
 */
internal class LeagueNetwork(
    val id: String,
    val sport: String,
    val name: String,
    val mark: String,
    val color: Long,
    val words: List<String>,
    val not: List<String> = emptyList()
)

/** A network on the page: the channel it plays on now, and the other copies of it you have. */
internal class NetworkTile(
    val network: LeagueNetwork,
    val lead: LiveChannel?,
    val others: List<LiveChannel>,
    val picked: Boolean
)

internal object LeagueNetworks {
    /** In page order: sports in this order, a sport's networks in this order. */
    val all: List<LeagueNetwork> = listOf(
        LeagueNetwork("nfl", "FOOTBALL", "NFL Network", "NFL", 0xFF1C4F9C, listOf("nfl network"), listOf("redzone", "red zone")),
        LeagueNetwork("redzone", "FOOTBALL", "NFL RedZone", "RZ", 0xFFA8322D, listOf("redzone", "red zone")),
        LeagueNetwork("nbatv", "BASKETBALL", "NBA TV", "NBA", 0xFF8A3B1F, listOf("nba tv"), listOf("canada")),
        LeagueNetwork("nbatvca", "BASKETBALL", "NBA TV Canada", "NBA", 0xFF5A3A8F, listOf("nba tv canada")),
        LeagueNetwork("mlb", "BASEBALL", "MLB Network", "MLB", 0xFF1D6B5A, listOf("mlb network"), listOf("strike zone")),
        LeagueNetwork("strikezone", "BASEBALL", "MLB Strike Zone", "SZ", 0xFF4B5870, listOf("strike zone")),
        LeagueNetwork("nhl", "HOCKEY", "NHL Network", "NHL", 0xFF2F5D8A, listOf("nhl network")),
        LeagueNetwork("golf", "GOLF", "Golf Channel", "GC", 0xFF2E7D4F, listOf("golf channel")),
        LeagueNetwork("tennis", "TENNIS", "Tennis Channel", "TC", 0xFF7A8A22, listOf("tennis channel")),
        LeagueNetwork("laliga", "SOCCER", "LaLiga TV", "LL", 0xFFB5372E, listOf("laliga tv", "la liga tv")),
        LeagueNetwork("ligue1", "SOCCER", "Ligue 1+", "L1", 0xFF2A3F8F, listOf("ligue 1 plus"))
    )

    /** Names that are a replay or an archive of the channel, not the channel. */
    private val NOT_THE_CHANNEL = listOf("replay", "replays", "24 7", "classic", "vault", "encore", "highlights")

    /**
     * Which of [channels] are each network: by the channel's name only (never the guide), so this
     * is cheap and only needs doing again when the channel list changes.
     */
    fun find(channels: List<LiveChannel>): Map<String, List<LiveChannel>> {
        val out = LinkedHashMap<String, MutableList<LiveChannel>>()
        for (ch in channels) {
            if (SportsMatcher.isBanner(ch.name)) continue
            val n = SportsCatalog.norm(ch.name.replace("+", " plus "))
            for (net in all) {
                if (net.words.none { hasWord(n, it) }) continue
                if (net.not.any { hasWord(n, it) } || NOT_THE_CHANNEL.any { hasWord(n, it) }) continue
                out.getOrPut(net.id) { ArrayList() } += ch
            }
        }
        return out
    }

    /**
     * Every network as a tile. The channel you picked leads; otherwise the best of what was found
     * (your region and groups first, then the better picture). "Channels from" narrows the copies
     * when that leaves something.
     */
    fun tiles(found: Map<String, List<LiveChannel>>, settings: SportsSettings, byKey: Map<String, LiveChannel>): List<NetworkTile> {
        val rank = SportsMatcher.preferenceRank(settings)
        return all.map { net ->
            var list = found[net.id].orEmpty()
            if (settings.channelRegion.isNotBlank()) {
                val inRegion = list.filter { ChannelRegions.includes(it, settings.channelRegion) }
                if (inRegion.isNotEmpty()) list = inRegion
            }
            val sorted = list.sortedWith(compareByDescending<LiveChannel> { rank(it) }.thenByDescending { quality(it) })
            val pick = settings.leagueNetworkChannels[net.id]?.let { byKey[it] }
            val lead = pick ?: sorted.firstOrNull()
            NetworkTile(net, lead, sorted.filter { it.key != lead?.key }, pick != null)
        }
    }

    private fun quality(ch: LiveChannel): Int {
        val n = SportsCatalog.norm(ch.name)
        return when {
            hasWord(n, "4k") || hasWord(n, "uhd") -> 4
            hasWord(n, "fhd") || hasWord(n, "1080") || hasWord(n, "1080p") -> 3
            hasWord(n, "sd") || hasWord(n, "480") || hasWord(n, "576") -> 1
            else -> 2
        }
    }
}

/** What a tile says under the name: the show on now (live = true), what's next, or that there's no guide. */
private fun tileLine(p: Pair<EpgProgram?, EpgProgram?>?, now: Long, use24h: Boolean): Pair<String, Boolean> {
    val cur = p?.first
    val next = p?.second
    return when {
        cur != null -> cur.title to true
        next != null -> {
            val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
            val today = dayKey.format(Date(now)) == dayKey.format(Date(next.startMs))
            val day = if (today) "" else SimpleDateFormat("EEE", Locale.getDefault()).format(Date(next.startMs)) + " "
            "Next: $day${clock(next.startMs, use24h)}" to false
        }
        else -> "No guide info" to false
    }
}

/**
 * League Networks: each league's own channel in one place. OK plays it in the video window (OK
 * again: full screen); hold OK for the other copies, a different channel, or back to automatic.
 * A network that isn't found still has its tile: OK picks the channel by hand.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LeagueNetworksPage(
    viewModel: SportsViewModel,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    videoOn: Boolean,
    videoSlot: @Composable (Modifier) -> Unit,
    playingKey: String?,
    header: @Composable () -> Unit,
    onWatchFull: (LiveChannel) -> Unit
) {
    val found by viewModel.repo.leagueNetworks.collectAsStateWithLifecycle()
    val byKey = remember(found, settings.leagueNetworkChannels) {
        val wanted = settings.leagueNetworkChannels.values.toSet()
        if (wanted.isEmpty()) emptyMap<String, LiveChannel>() else viewModel.repo.channelsForPicker().filter { it.key in wanted }.associateBy { it.key }
    }
    val tiles = remember(found, settings, byKey) { LeagueNetworks.tiles(found, settings, byKey) }

    // What's on now / next, for the channel each tile plays (a few times an hour is plenty).
    val leadKeys = remember(tiles) { tiles.mapNotNull { it.lead?.key }.toSet() }
    var guide by remember { mutableStateOf<Map<String, Pair<EpgProgram?, EpgProgram?>>>(emptyMap()) }
    LaunchedEffect(leadKeys, now / 300_000L) { guide = viewModel.repo.nowAndNext(leadKeys) }

    // A tile's menu, the pick-by-hand list for a sport with nothing found, and the channel picker.
    var menuFor by remember { mutableStateOf<NetworkTile?>(null) }
    var chooseFrom by remember { mutableStateOf<List<LeagueNetwork>?>(null) }
    var pickFor by remember { mutableStateOf<LeagueNetwork?>(null) }

    menuFor?.let { t -> NetworkMenu(t, viewModel, onWatchFull = onWatchFull, onDismiss = { menuFor = null }) }
    chooseFrom?.let { list ->
        val first = remember { FocusRequester() }
        SportsDialog(onDismiss = { chooseFrom = null }, width = 460.dp) {
            LiveText("Choose a channel for…", size = 18.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            list.forEachIndexed { i, net ->
                SportsMenuRow(net.name, if (i == 0) Modifier.focusRequester(first) else Modifier) { chooseFrom = null; pickFor = net }
            }
        }
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    }
    pickFor?.let { net ->
        ChannelPickerDialog(
            title = "Pick the channel for ${net.name}",
            initial = net.name,
            viewModel = viewModel,
            onPick = { ch ->
                viewModel.repo.pickLeagueNetwork(net.id, ch.key)
                LiveTvNotice.show("Saved: ${net.name} uses ${ch.name}")
                pickFor = null
            },
            onDismiss = { pickFor = null }
        )
    }

    val columns = if (videoOn) 2 else 3
    val playingTile = tiles.firstOrNull { t -> playingKey != null && (t.lead?.key == playingKey || t.others.any { it.key == playingKey }) }

    fun playTile(t: NetworkTile) {
        val lead = t.lead
        when {
            lead == null -> pickFor = t.network
            !videoOn || playingKey == lead.key -> onWatchFull(lead)
            else -> viewModel.playChannelHere(lead)
        }
    }

    val content: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            header()
            LiveText(
                "Each league's own channel, found in your playlists by name.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp, start = 4.dp, bottom = 2.dp)
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).keepFocusWhileHeld(horizontal = false),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val bySport = tiles.groupBy { it.network.sport }
                bySport.forEach { (sport, all) ->
                    item(key = "h|$sport") { SectionHeader(sport) }
                    val sorted = all.sortedBy { it.lead == null }
                    if (sorted.size > 1 && sorted.all { it.lead == null }) {
                        // Nothing found for this sport: one tile, then pick which network to set by hand.
                        item(key = "c|$sport") {
                            NetworkCard(
                                name = sorted.joinToString(" · ") { it.network.name },
                                mark = sorted.first().network.mark, color = sorted.first().network.color,
                                logo = null, others = 0, line = "Not found. OK to choose a channel", live = false,
                                found = false, playing = false, modifier = Modifier.fillMaxWidth(),
                                onClick = { chooseFrom = sorted.map { it.network } }, onLongClick = { chooseFrom = sorted.map { it.network } }
                            )
                        }
                    } else {
                        sorted.chunked(columns).forEach { row ->
                            item(key = "r|" + row.first().network.id) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { t ->
                                        val lead = t.lead
                                        val info: Pair<String, Boolean> =
                                            if (lead != null) tileLine(guide[lead.key], now, use24h)
                                            else Pair("Not found. OK to choose a channel", false)
                                        NetworkCard(
                                            name = t.network.name, mark = t.network.mark, color = t.network.color,
                                            logo = lead?.logo, others = t.others.size, line = info.first, live = info.second,
                                            found = lead != null,
                                            playing = videoOn && playingTile === t,
                                            modifier = Modifier.weight(1f),
                                            onClick = { playTile(t) },
                                            onLongClick = { if (t.lead == null) { pickFor = t.network } else { menuFor = t } }
                                        )
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(30.dp)) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (videoOn) {
            Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp, bottom = 26.dp)) {
                content(Modifier.width(500.dp).fillMaxHeight())
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                    videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    Spacer(Modifier.height(10.dp))
                    playingTile?.let { t ->
                        LiveText(t.network.name, color = Color.White, size = 16.sp, weight = FontWeight.ExtraBold, maxLines = 1)
                        val key = playingKey
                        val p = key?.let { guide[it]?.first }
                        if (p != null) LiveText(p.title, color = SportsLook.Dim, size = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp, bottom = 26.dp)) {
                content(Modifier.weight(1f).fillMaxHeight())
            }
        }
        LiveText(
            if (videoOn) "OK: play here, OK again: full screen  ·  Hold OK: other copies, pick a channel" else "OK: watch  ·  Hold OK: other copies, pick a channel",
            color = NuvioTheme.colors.TextTertiary, size = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 8.dp)
        )
    }
}

/** One network: its channel's logo (or its mark when not found), name, and what's on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkCard(
    name: String,
    mark: String,
    color: Long,
    logo: String?,
    others: Int,
    line: String,
    live: Boolean,
    found: Boolean,
    playing: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Box(
        modifier
            .height(78.dp)
            .sportsFrame(focused, playing, accent)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp).alpha(if (found) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
            if (found) {
                ChannelLogo(logo, 40.dp)
            } else {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(Color(color)), contentAlignment = Alignment.Center) {
                    LiveText(mark, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveText(name, color = Color.White, size = 14.sp, weight = FontWeight.ExtraBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (others > 0) {
                        Spacer(Modifier.width(8.dp))
                        LiveText("+$others more", color = SportsLook.Dim, size = 11.sp, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(SportsLook.Live))
                        Spacer(Modifier.width(6.dp))
                    }
                    LiveText(line, color = if (live) Color.White.copy(alpha = 0.85f) else SportsLook.Dim, size = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Hold OK on a network: watch it, its other copies, a channel of your own, or back to automatic. */
@Composable
private fun NetworkMenu(
    tile: NetworkTile,
    viewModel: SportsViewModel,
    onWatchFull: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    val net = tile.network
    val lead = tile.lead ?: return
    var mode by remember { mutableStateOf("menu") }
    val first = remember { FocusRequester() }
    when (mode) {
        "copies" -> {
            SportsDialog(onDismiss = { mode = "menu" }, width = 560.dp) {
                LiveText("Other copies of ${net.name}", size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
                LiveText("OK uses that copy.", color = SportsLook.Dim, size = 12.sp)
                Spacer(Modifier.height(8.dp))
                val list = listOf(lead) + tile.others
                LazyColumn(Modifier.heightIn(max = 330.dp).keepFocusWhileHeld(horizontal = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(list, key = { _, ch -> ch.key }) { i, ch ->
                        ChannelLine(
                            channel = ch,
                            detail = (if (i == 0) "In use" else listOf(ch.sourceName, ch.group).filter { it.isNotBlank() }.joinToString(" · ")),
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            onClick = {
                                viewModel.repo.pickLeagueNetwork(net.id, ch.key)
                                LiveTvNotice.show("Saved: ${net.name} uses ${ch.name}")
                                onDismiss()
                            }
                        )
                    }
                }
            }
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        }
        "pick" -> ChannelPickerDialog(
            title = "Pick the channel for ${net.name}",
            initial = net.name,
            viewModel = viewModel,
            onPick = { ch ->
                viewModel.repo.pickLeagueNetwork(net.id, ch.key)
                LiveTvNotice.show("Saved: ${net.name} uses ${ch.name}")
                onDismiss()
            },
            onDismiss = { mode = "menu" }
        )
        else -> {
            SportsDialog(onDismiss = onDismiss, width = 460.dp) {
                LiveText(net.name, size = 18.sp, weight = FontWeight.Bold, maxLines = 1)
                LiveText(listOf(lead.name, lead.group).filter { it.isNotBlank() }.joinToString(" · "), color = SportsLook.Dim, size = 12.sp, maxLines = 1)
                Spacer(Modifier.height(8.dp))
                SportsMenuRow("Watch full screen", Modifier.focusRequester(first)) { onDismiss(); onWatchFull(lead) }
                if (tile.others.isNotEmpty()) SportsMenuRow("Other copies…", detail = "${tile.others.size}") { mode = "copies" }
                SportsMenuRow("Pick a different channel…") { mode = "pick" }
                if (tile.picked) SportsMenuRow("Reset to automatic") {
                    viewModel.repo.resetLeagueNetwork(net.id)
                    LiveTvNotice.show("${net.name} is automatic again")
                    onDismiss()
                }
            }
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        }
    }
}
