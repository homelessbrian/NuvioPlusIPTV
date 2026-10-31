package com.nuvio.tv.livetv.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.navigation.NavController
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.sports.GameState
import com.nuvio.tv.livetv.sports.SportsCatalog
import com.nuvio.tv.livetv.sports.SportsFeeds
import com.nuvio.tv.livetv.sports.SportsEntryPoint
import com.nuvio.tv.livetv.sports.SportsLaunch
import com.nuvio.tv.livetv.sports.openLiveTvFullscreen
import com.nuvio.tv.livetv.startup.LiveTvStartupEntryPoint
import com.nuvio.tv.ui.navigation.Screen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.sports.SportsEvent

/**
 * Rows on the launcher's home screen (Google TV, Projectivy, Monet…): Live now in Sports,
 * Favorites and Recently watched channels. Each is switched on in Settings → Live TV; pressing
 * an item opens that channel (or game) in Live TV.
 */
object LiveTvLauncherRows {
    const val ROW_SPORTS = "sports"
    const val ROW_FAVORITES = "favorites"
    const val ROW_RECENT = "recent"

    private const val PREFS = "live_tv_launcher_rows"
    private const val MAX_ITEMS = 24
    private const val ACTION_REFRESH = "com.nuvio.tv.livetv.LAUNCHER_ROWS"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    fun parse(raw: String): Set<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun join(set: Set<String>): String = set.sorted().joinToString(",")

    private fun title(row: String) = when (row) {
        ROW_SPORTS -> "Live Now in Sports"
        ROW_FAVORITES -> "Favorite Channels"
        else -> "Recently Watched Channels"
    }

    /** Brings the rows up to date, in the background. Safe to call often. */
    fun refresh(context: Context, afterMs: Long = 0L) {
        val app = context.applicationContext
        scope.launch {
            if (afterMs > 0) kotlinx.coroutines.delay(afterMs)
            refreshNow(app)
        }
    }

    /** Brings the rows up to date now (one at a time), and sets when to do it next. */
    suspend fun refreshNow(context: Context) {
        lock.withLock {
            runCatching { doRefresh(context.applicationContext) }
                .onFailure { LiveTvLoadReport.add("Launcher rows: failed: ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    @Volatile private var noTvProviderReported = false

    /** A channel as a row shows it, kept so the rows can be updated without loading every channel. */
    private class Src(val key: String, val name: String, val logo: String?)

    private class Item(
        val title: String,
        val text: String?,
        val spec: CardSpec,
        val link: String,
        val id: String,
        val startMs: Long? = null,
        val stopMs: Long? = null
    )

    private suspend fun doRefresh(context: Context) {
        // Phones and tablets (and some boxes) have no home-screen channel service: nothing to do
        // there, said once instead of an error per row every time.
        val hasTvProvider = runCatching {
            context.packageManager.resolveContentProvider("android.media.tv", 0) != null
        }.getOrDefault(false)
        if (!hasTvProvider) {
            if (!noTvProviderReported) {
                noTvProviderReported = true
                LiveTvLoadReport.add("Launcher rows: this device has no home-screen channels (not Android TV / Google TV); skipped")
            }
            return
        }
        val entry = EntryPointAccessors.fromApplication(context, LiveTvStartupEntryPoint::class.java)
        val prefs = entry.liveTvPreferences()
        val settings = prefs.settings.first()
        val shape = CardShape.of(settings.launcherCardShape)
        // The three rows are always on offer: the launcher's own settings decide which to show.
        val wanted = listOf(ROW_SPORTS, ROW_FAVORITES, ROW_RECENT)
        val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val helper = PreviewChannelHelper(context)
        LauncherImages.tidy(context)

        // The channels: the app's own list when it's loaded (with your renames), otherwise the
        // copy kept from last time (an update while the app isn't open).
        val channels = entry.channelStore().displayChannels.value
        val user = prefs.userState.first()
        fun visible(ch: LiveChannel) = ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups
        val kept = readKept(store)
        val sources: Map<String, Src> = if (channels.isNotEmpty()) {
            val byKey = channels.associateBy { it.key }
            (user.favorites + user.recent).distinct().mapNotNull { byKey[it] }.filter(::visible)
                .associate { it.key to Src(it.key, it.name, it.logo) }
                .also { writeKept(store, it.values) }
        } else kept
        if (sources.isEmpty() && channels.isEmpty() && kept.isEmpty()) {
            // Nothing known yet (first start): make sure the rows exist, but leave them alone.
            LiveTvLoadReport.add("Launcher rows: no channels loaded yet, rows made without refreshing")
            for (row in wanted) publish(context, helper, store, row, emptyList(), shape, onlyMake = true)
            return
        }

        // What's on: straight from the stored guide, for just these channels.
        val now = System.currentTimeMillis()
        val shift = settings.epgOffsetMinutes * 60_000L
        val listings: Map<String, List<EpgProgram>> = runCatching {
            entry.epgDatabase().range(now - shift, now - shift + 1, channels = sources.keys)
        }.getOrDefault(emptyMap())
        fun nowOn(key: String): EpgProgram? = listings[key]
            ?.firstOrNull { it.isLive(now - shift) }
            ?.let { p -> if (shift == 0L) p else p.copy(startMs = p.startMs + shift, stopMs = p.stopMs + shift) }
        val recording = runCatching { entry.liveTvRecorder().recordings.value.filter { it.isRecording }.mapTo(HashSet()) { it.channelKey } }
            .getOrDefault(emptySet<String>())
        val timeFmt = java.text.SimpleDateFormat(if (settings.use24HourClock) "HH:mm" else "h:mm a", java.util.Locale.getDefault())

        // Sports (when its scores are loaded): live games, and which channels show them.
        val sportsEntry = EntryPointAccessors.fromApplication(context, SportsEntryPoint::class.java)
        val sports = sportsEntry.sportsRepository()
        val known = sports.known.value
        val matches = sports.matches.value
        val liveGames = known.filter { it.state == GameState.LIVE && matches[it.id]?.isNotEmpty() == true }
        val gameOnChannel = HashMap<String, SportsEvent>()
        liveGames.forEach { ev -> matches[ev.id].orEmpty().filter { it.sure }.forEach { m -> gameOnChannel.putIfAbsent(m.channel.key, ev) } }

        fun team(side: com.nuvio.tv.livetv.sports.Side) = CardSpec.Team(
            name = side.team.name.ifBlank { side.team.fullName },
            abbr = side.team.abbr,
            color = side.team.color?.let { (it or 0xFF000000L).toInt() },
            logo = side.team.logo
        )
        // No score, clock or lap: launchers refresh too rarely for those to stay right. Only what
        // stays true for the whole game: the league and when it started.
        fun started(ev: SportsEvent): String? =
            if (ev.hasTime && ev.startMs <= now) "Started " + timeFmt.format(java.util.Date(ev.startMs)) else null
        fun statusLine(ev: SportsEvent): String? =
            listOfNotNull(SportsCatalog.league(ev.leagueId)?.name, started(ev)).joinToString("  ·  ").ifBlank { null }
        fun gameSpec(ev: SportsEvent, channelLogo: String?, channelName: String?): CardSpec {
            val away = ev.away
            val home = ev.home
            if (away == null || home == null) {
                // A race, a tournament: the event's name on the channel's card.
                return CardSpec.Show(channelLogo, channelName ?: ev.title, ev.title,
                    listOfNotNull(SportsCatalog.league(ev.leagueId)?.name), started(ev), listOf(Tags.LIVE))
            }
            return CardSpec.Game(channelName, team(away), team(home), statusLine(ev), listOf(Tags.LIVE))
        }

        fun channelItem(src: Src): Item {
            val link = "nuvio://livetv/channel/" + Uri.encode(src.key)
            val rec = src.key in recording
            // A game the Sports hub knows is on here: the matchup card.
            gameOnChannel[src.key]?.let { ev ->
                return Item(ev.title, src.name, gameSpec(ev, src.logo, src.name), link, "c:" + src.key)
            }
            val p = nowOn(src.key)
            val text = p?.let { LauncherText.forShow(it, src.name) }
            if (p == null || text == null) {
                val tags = if (rec) listOf(Tags.REC) else emptyList()
                return Item(src.name, null, CardSpec.Channel(src.logo, src.name, tags), link, "c:" + src.key)
            }
            val tags = buildList {
                add(Tags.LIVE)
                if (text.isNew) add(Tags.NEW)
                if (rec) add(Tags.REC)
            }
            // "Until 10:05 PM": right for the whole show (unlike "minutes left").
            val time = "Until " + timeFmt.format(java.util.Date(p.stopMs))
            return Item(
                title = text.title,
                text = listOf(src.name, time).joinToString("  ·  "),
                spec = CardSpec.Show(src.logo, src.name, text.title, text.detail, time, tags),
                link = link,
                id = "c:" + src.key,
                startMs = p.startMs,
                stopMs = p.stopMs
            )
        }

        var nextChange = Long.MAX_VALUE
        var sportsLive = false
        for (row in wanted) {
            val items: List<Item>? = when (row) {
                ROW_FAVORITES -> user.favorites.mapNotNull { sources[it] }.take(MAX_ITEMS).map(::channelItem)
                ROW_RECENT -> user.recent.mapNotNull { sources[it] }.take(MAX_ITEMS).map(::channelItem)
                ROW_SPORTS -> when {
                    known.isNotEmpty() -> {
                        store.edit().putLong("sports_at", now).apply()
                        liveGames.take(MAX_ITEMS).map { ev ->
                            val best = matches[ev.id]?.firstOrNull()?.channel
                            Item(ev.title, listOfNotNull(best?.name, SportsCatalog.league(ev.leagueId)?.name).joinToString("  ·  ").ifBlank { null },
                                gameSpec(ev, best?.logo, best?.name), "nuvio://livetv/game/" + Uri.encode(ev.id), "g:" + ev.id)
                        }
                    }
                    // Scores not loaded (an update while the app isn't open): leave the row as it
                    // is, unless it's hours old, when its games will have finished.
                    now - store.getLong("sports_at", 0L) > 3 * 3600_000L -> emptyList()
                    else -> null
                }
                else -> emptyList()
            }
            if (items == null) continue
            if (row == ROW_SPORTS && items.isNotEmpty()) sportsLive = true
            items.forEach { it.stopMs?.let { s -> if (s > now) nextChange = minOf(nextChange, s) } }
            if (row != ROW_SPORTS && items.any { it.spec is CardSpec.Game }) sportsLive = true
            publish(context, helper, store, row, items, shape)
        }

        // Next update: when a show in the rows changes (or in an hour), every few minutes while
        // games are on (games starting and finishing), never more than every two minutes.
        var next = minOf(now + 60 * 60_000L, if (nextChange == Long.MAX_VALUE) Long.MAX_VALUE else nextChange + 20_000L)
        if (sportsLive) next = minOf(next, now + 5 * 60_000L)
        schedule(context, next.coerceAtLeast(now + 2 * 60_000L))
    }

    /** Wakes the app for the next update. Not the TV from standby: it runs once the TV is on. */
    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val pi = android.app.PendingIntent.getBroadcast(
            context, 7301,
            android.content.Intent(context, LauncherRowsReceiver::class.java).setAction(ACTION_REFRESH),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.set(android.app.AlarmManager.RTC, at, pi) }
    }

    private fun readKept(store: android.content.SharedPreferences): Map<String, Src> = runCatching {
        val arr = org.json.JSONArray(store.getString("kept", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { o -> Src(o.getString("k"), o.optString("n"), o.optString("l").ifBlank { null }) }
            .associateBy { it.key }
    }.getOrDefault(emptyMap())

    private fun writeKept(store: android.content.SharedPreferences, list: Collection<Src>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("k", it.key).put("n", it.name).put("l", it.logo ?: "")) }
        val raw = arr.toString()
        if (store.getString("kept", null) != raw) store.edit().putString("kept", raw).apply()
    }

    private fun publish(
        context: Context,
        helper: PreviewChannelHelper,
        store: android.content.SharedPreferences,
        row: String,
        items: List<Item>,
        shape: CardShape,
        onlyMake: Boolean = false
    ) {
        // This row's channel, found by its name (never trust a saved number alone: asking the
        // system by number failed and made a new copy of the row every time). Extra copies go.
        val named = channelIdsNamed(context, title(row)).sorted()
        var channelId = store.getLong("channel_$row", -1L).takeIf { it in named } ?: named.firstOrNull() ?: -1L
        named.filter { it != channelId }.forEach { runCatching { helper.deletePreviewChannel(it) } }
        if (channelId >= 0) store.edit().putLong("channel_$row", channelId).apply()
        if (named.size > 1) LiveTvLoadReport.add("Launcher rows: removed ${named.size - 1} extra copies of the $row row")
        if (channelId < 0) {
            val channel = PreviewChannel.Builder()
                .setDisplayName(title(row))
                .setAppLinkIntentUri(Uri.parse("nuvio://livetv"))
                .setLogo(appLogo(context))
                .build()
            channelId = runCatching { helper.publishChannel(channel) }
                .onFailure { LiveTvLoadReport.add("Launcher rows: couldn't make the $row row: ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(-1L)
            if (channelId < 0) return
            LiveTvLoadReport.add("Launcher rows: made the $row row (id $channelId)")
            store.edit().putLong("channel_$row", channelId).apply()
            // Some launchers show a new row at once; others ask for it in their own row settings.
            runCatching { TvContractCompat.requestChannelBrowsable(context, channelId) }
        }
        if (onlyMake) return

        val built = items.mapIndexedNotNull { index, item ->
            val image = LauncherImages.cardUri(context, item.spec, shape) ?: return@mapIndexedNotNull null
            val b = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
                .setTitle(item.title)
                .setPosterArtUri(image)
                .setPosterArtAspectRatio(shape.aspect)
                .setIntentUri(Uri.parse(item.link))
                .setInternalProviderId(item.id)
                .setLive(true)
                .setWeight(items.size - index)
            item.text?.let { b.setDescription(it) }
            item.startMs?.let { b.setStartTimeUtcMillis(it) }
            item.stopMs?.let { b.setEndTimeUtcMillis(it) }
            Triple(item.id, image.toString(), b.build())
        }
        // Nothing changed since last time: leave the row exactly as it is.
        val signature = built.joinToString("|") { it.first + "=" + it.second } + "|" + items.joinToString("|") { it.title + "/" + (it.text ?: "") }
        val sigKey = "sig_$row"
        if (store.getString(sigKey, null) == signature + "@" + channelId) return

        // Update cards in place (the row doesn't jump or lose its place), add new ones, remove the rest.
        val existing = HashMap<String, Long>()
        runCatching {
            context.contentResolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                arrayOf(TvContractCompat.PreviewPrograms._ID, TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID),
                null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { existing[it] = c.getLong(0) } }
        }
        var added = 0
        var updated = 0
        var firstError: String? = null
        val keep = HashSet<Long>()
        for ((id, _, program) in built) {
            runCatching {
                val old = existing[id]
                if (old != null) {
                    helper.updatePreviewProgram(old, program)
                    keep += old
                    updated++
                } else {
                    keep += helper.publishPreviewProgram(program)
                    added++
                }
            }.onFailure { if (firstError == null) firstError = "${it.javaClass.simpleName} ${it.message}" }
        }
        existing.values.filter { it !in keep }.forEach { runCatching { helper.deletePreviewProgram(it) } }
        store.edit().putString(sigKey, signature + "@" + channelId).apply()
        LiveTvLoadReport.add("Launcher rows: $row: ${built.size} cards ($added new, $updated updated)" + (firstError?.let { " (first problem: $it)" } ?: ""))
    }

    /** Ids of this app's launcher channels with this name. */
    private fun channelIdsNamed(context: Context, name: String): List<Long> = runCatching {
        val out = ArrayList<Long>()
        context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) out += c.getLong(0)
        }
        out
    }.getOrDefault(emptyList())

    private fun appLogo(context: Context): Bitmap {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, 160, 160)
        d.draw(c)
        return bmp
    }

    /**
     * Opens what a launcher item points at: nuvio://livetv (the guide), nuvio://livetv/channel/<key>
     * (plays that channel full screen) or nuvio://livetv/game/<event id> (the best channel for
     * that game, or its page in Sports if no channel is known yet).
     */
    suspend fun open(context: Context, navController: NavController, url: String) {
        val app = context.applicationContext
        val uri = Uri.parse(url)
        val kind = uri.pathSegments.getOrNull(0)
        val arg = uri.pathSegments.getOrNull(1)
        val entry = EntryPointAccessors.fromApplication(app, LiveTvStartupEntryPoint::class.java)
        val sportsEntry = EntryPointAccessors.fromApplication(app, SportsEntryPoint::class.java)
        if (kind == null || arg == null) {
            navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
            return
        }
        val repo = entry.liveTvRepository()
        repo.ensureLoaded(updateCheckDelayMs = 20_000)
        // Cold start from the launcher: give the channel list a moment to load.
        withTimeoutOrNull(15_000) { repo.channels.first { it.isNotEmpty() } }
        when (kind) {
            "channel" -> {
                val ch = repo.channels.value.firstOrNull { it.key == arg }
                if (ch == null) {
                    navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
                } else {
                    sportsEntry.sportsPlayback().play(ch)
                    runCatching { entry.liveTvPreferences().recordWatched(ch.key) }
                    openLiveTvFullscreen(navController)
                }
            }
            "game" -> {
                val sports = sportsEntry.sportsRepository()
                sports.start()
                val m = withTimeoutOrNull(15_000) { sports.matches.first { it[arg]?.isNotEmpty() == true } }?.get(arg)?.firstOrNull()
                if (m != null) {
                    sportsEntry.sportsPlayback().play(m.channel)
                    runCatching { entry.liveTvPreferences().recordWatched(m.channel.key) }
                    openLiveTvFullscreen(navController)
                } else {
                    SportsLaunch.openEvent(arg)
                    navController.navigate(Screen.Sports.route) { launchSingleTop = true }
                }
            }
            else -> navController.navigate(Screen.LiveTv.route) { launchSingleTop = true }
        }
    }
}

/** Wakes the app to bring the launcher rows up to date (a new show, a new score). */
class LauncherRowsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(25_000) { LiveTvLauncherRows.refreshNow(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
