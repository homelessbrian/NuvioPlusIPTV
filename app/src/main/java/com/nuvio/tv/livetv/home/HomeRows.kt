@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.nuvio.tv.livetv.home

import androidx.compose.runtime.Immutable
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.ChannelGroup
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Live TV or Sports row on Nuvio's home screen, added from Reorder Home Catalogs (Add a row).
 * Each row sits in Nuvio's own row order under the key [key], so it moves, turns off and on like
 * an addon catalog or a collection. Kept per Nuvio profile (like favorites).
 */
@Immutable
data class HomeLiveRow(
    val id: String,
    /** [KIND_CHANNELS] or [KIND_SPORTS]. */
    val kind: String,
    /**
     * Channels: the group (Favorites, Recently watched, a playlist group or one of your own).
     * Sports: "" for every sport, "sport:<SPORT>" or "league:<league id>".
     */
    val source: String,
    /** Your name for the row; blank = the group's or league's own name. */
    val title: String = "",
    /** Channels: "yours" (the group's order), "number" or "ending" (ending soonest first). */
    val sort: String = "yours",
    /** Most cards in the row. */
    val limit: Int = 20,
    /** Channels: what's on next under what's on now. */
    val showNext: Boolean = false,
    /** Channels: leave out channels with no real listing ("No information"). */
    val hidePlaceholders: Boolean = false,
    /** Channels: OK opens the guide on the channel (instead of full screen). */
    val opensGuide: Boolean = false,
    /** Sports: OK plays the game's best channel (instead of opening the game page). */
    val playsGame: Boolean = false,
    /** Sports: fill the row with games starting soon (always after the live ones). */
    val startingSoon: Boolean = true,
    /** [SHAPE_AUTO] (as the Home layout's cards), [SHAPE_PORTRAIT] or [SHAPE_LANDSCAPE]. */
    val shape: String = SHAPE_AUTO,
    /**
     * The row it sits under on Home ("" = the top), so another TV the rows sync to puts it in
     * the same place. Home's own row order isn't part of Live TV's sync.
     */
    val after: String = ""
) {
    val key: String get() = KEY_PREFIX + id

    companion object {
        const val KEY_PREFIX = "livetv_"
        const val KIND_CHANNELS = "channels"
        const val KIND_SPORTS = "sports"
        const val SHAPE_AUTO = "auto"
        const val SHAPE_PORTRAIT = "portrait"
        const val SHAPE_LANDSCAPE = "landscape"
        const val MAX_LIMIT = 50

        fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)
        fun idOf(key: String): String? = key.takeIf(::isKey)?.removePrefix(KEY_PREFIX)

        fun channels(group: String) = HomeLiveRow(newId(), KIND_CHANNELS, group)
        fun sports(source: String = "") = HomeLiveRow(newId(), KIND_SPORTS, source)

        private fun newId(): String =
            java.lang.Long.toString(System.currentTimeMillis(), 36) +
                Integer.toString((Math.random() * 1296).toInt(), 36)

        fun decode(json: String?): List<HomeLiveRow> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val kind = o.optString("kind").takeIf { it == KIND_CHANNELS || it == KIND_SPORTS }
                        ?: return@mapNotNull null
                    HomeLiveRow(
                        id = id,
                        kind = kind,
                        source = o.optString("source"),
                        title = o.optString("title"),
                        sort = o.optString("sort", "yours"),
                        limit = o.optInt("limit", 20).coerceIn(1, MAX_LIMIT),
                        showNext = o.optBoolean("showNext", false),
                        hidePlaceholders = o.optBoolean("hidePlaceholders", false),
                        opensGuide = o.optBoolean("opensGuide", false),
                        playsGame = o.optBoolean("playsGame", false),
                        startingSoon = o.optBoolean("startingSoon", true),
                        shape = o.optString("shape", SHAPE_AUTO),
                        after = o.optString("after")
                    )
                }.distinctBy { it.id }
            }.getOrDefault(emptyList())
        }

        fun encode(rows: List<HomeLiveRow>): String = JSONArray().apply {
            rows.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("kind", r.kind)
                    put("source", r.source)
                    put("title", r.title)
                    put("sort", r.sort)
                    put("limit", r.limit)
                    put("showNext", r.showNext)
                    put("hidePlaceholders", r.hidePlaceholders)
                    put("opensGuide", r.opensGuide)
                    put("playsGame", r.playsGame)
                    put("startingSoon", r.startingSoon)
                    put("shape", r.shape)
                    put("after", r.after)
                })
            }
        }.toString()
    }
}

/** True for a home row key that belongs to a Live TV or Sports row. */
fun isLiveHomeRowKey(key: String): Boolean = HomeLiveRow.isKey(key)

/** The rows this profile has added, their names, and changes to them. */
@Singleton
class HomeLiveRows @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val prefs: LiveTvPreferences,
    private val channelStore: com.nuvio.tv.livetv.data.LiveTvChannelStore,
    private val layoutPrefs: com.nuvio.tv.data.local.LayoutPreferenceDataStore
) {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    val rows: Flow<List<HomeLiveRow>> = prefs.homeRowsJson
        .map { HomeLiveRow.decode(it) }
        .distinctUntilChanged()

    /**
     * The rows as Home needs them: none while Live TV is hidden from the side menu (the same
     * as the tucked-away Live TV row).
     */
    val shownRows: Flow<List<HomeLiveRow>> = combine(rows, prefs.settings) { r, s ->
        if (s.showInSidebar) r else emptyList()
    }.distinctUntilChanged()

    /** Each row's name as shown on Home and in Reorder Home Catalogs, by row id. */
    val names: StateFlow<Map<String, String>> = combine(
        rows,
        prefs.userState.map { it.customGroups to it.groupNames }.distinctUntilChanged(),
        channelStore.displayChannels
    ) { list, (custom, renamed), channels ->
        if (list.isEmpty()) return@combine emptyMap()
        val wanted = list.filter { it.kind == HomeLiveRow.KIND_CHANNELS }.mapTo(HashSet()) { it.source }
        val found = HashMap<String, String>()
        if (wanted.isNotEmpty()) for (c in channels) {
            if (c.groupId in wanted && c.groupId !in found) found[c.groupId] = c.group
        }
        val ctx = context.withAppLocale()
        list.associate { row ->
            row.id to row.title.ifBlank {
                when (row.kind) {
                    HomeLiveRow.KIND_SPORTS -> sportsTitle(ctx, row.source)
                    else -> when (row.source) {
                        ChannelGroup.FAVORITES -> ctx.getString(R.string.home_rows_favorites_title)
                        ChannelGroup.RECENT -> ctx.getString(R.string.home_rows_recent_title)
                        else -> custom.firstOrNull { it.id == row.source }?.name
                            ?: renamed[row.source] ?: found[row.source]
                            ?: row.source.substringAfter("::").ifBlank { ctx.getString(R.string.home_rows_channels_title) }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    @Volatile private var placesStarted = false

    /**
     * Keeps each row's place in step with Home's row order, both ways (started by Home, once):
     * a row you move remembers the row above it; a row synced from another TV that this TV's
     * order doesn't have yet goes under that same row.
     */
    fun keepPlaces() {
        if (placesStarted) return
        placesStarted = true
        scope.launch {
            combine(layoutPrefs.homeCatalogOrderKeys, rows) { order, list -> order to list }
                .debounce(1_500)
                .collectLatest { (order, list) ->
                    if (list.isEmpty()) return@collectLatest
                    val missing = list.filter { it.key !in order }
                    if (missing.isNotEmpty() && order.isNotEmpty()) {
                        val next = order.toMutableList()
                        missing.forEach { r ->
                            val at = if (r.after.isBlank()) 0 else next.indexOf(r.after).let { if (it < 0) -1 else it + 1 }
                            if (at >= 0) next.add(at, r.key)
                        }
                        if (next.size != order.size) {
                            layoutPrefs.setHomeCatalogOrderKeys(next)
                            return@collectLatest
                        }
                    }
                    // Remember where each row is now (the nearest row above it that isn't another live row).
                    val anchors = list.associate { r ->
                        val i = order.indexOf(r.key)
                        r.id to if (i < 0) null else order.subList(0, i).lastOrNull { !HomeLiveRow.isKey(it) }.orEmpty()
                    }
                    if (list.any { r -> anchors[r.id]?.let { it != r.after } == true }) {
                        edit { rows -> rows.map { r -> anchors[r.id]?.let { r.copy(after = it) } ?: r } }
                    }
                }
        }
    }

    suspend fun add(row: HomeLiveRow) = edit { it + row }

    suspend fun update(row: HomeLiveRow) = edit { list -> list.map { if (it.id == row.id) row else it } }

    suspend fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    private suspend fun edit(transform: (List<HomeLiveRow>) -> List<HomeLiveRow>) {
        prefs.editHomeRows { HomeLiveRow.encode(transform(HomeLiveRow.decode(it))) }
    }

    companion object {
        /** A Sports row's own name: every sport, one sport or one league. */
        fun sportsTitle(ctx: android.content.Context, source: String): String = when {
            source.startsWith("sport:") -> com.nuvio.tv.livetv.sports.Sport.entries
                .firstOrNull { it.name == source.removePrefix("sport:") }
                ?.let { ctx.getString(R.string.home_rows_sports_one_title, it.label) }
                ?: ctx.getString(R.string.home_rows_sports_title)
            source.startsWith("league:") -> com.nuvio.tv.livetv.sports.SportsCatalog.league(source.removePrefix("league:"))
                ?.let { ctx.getString(R.string.home_rows_sports_one_title, it.name) }
                ?: ctx.getString(R.string.home_rows_sports_title)
            else -> ctx.getString(R.string.home_rows_sports_title)
        }
    }
}
