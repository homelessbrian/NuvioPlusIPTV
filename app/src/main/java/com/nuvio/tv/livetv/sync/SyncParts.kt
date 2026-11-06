package com.nuvio.tv.livetv.sync

import android.content.SharedPreferences
import org.json.JSONObject

/** The parts of the Live TV setup that Setup Sync can leave out on one device. */
enum class SyncPart(val label: String, val description: String, val defaultOn: Boolean) {
    LOGINS("Playlists and logins", "Your IPTV links, usernames and passwords. Stored encrypted.", true),
    VPN("VPN profiles and protection", "VPN configurations, selected profile and automatic protection scope.", true),
    FAVORITES("Favorites and groups", "Favorite channels, hidden channels and group order.", true),
    SPORTS("Sports", "Followed teams and leagues, and Choose Leagues.", true),
    CALENDAR("Calendar", "Followed and hidden shows.", true),
    LOOK("Look and layout", "Themes, menu, card styles and other options, kept the same on your TVs. Turn off to keep each TV its own.", true)
}

/**
 * Which parts of the setup this device syncs. The choice is kept on this device only (it is not part
 * of the synced setup). A part that is off is left out when this device saves, and keeps this
 * device's own values when a saved setup is restored; the saved copy keeps what other devices put
 * in it. With every part on, nothing here changes how sync works.
 */
internal class SyncParts(private val store: SharedPreferences) {

    fun isOn(part: SyncPart): Boolean = store.getBoolean(KEY + part.name, part.defaultOn)

    fun isOff(part: SyncPart): Boolean = !isOn(part)

    fun enabled(): Set<SyncPart> = SyncPart.values().filter { isOn(it) }.toSet()

    /**
     * Turns a part on or off on this device. A part just turned on takes the saved copy's values
     * for what the copy has (see [freshen]), so this device doesn't overwrite what other devices
     * changed meanwhile.
     */
    fun set(part: SyncPart, on: Boolean) {
        if (isOn(part) == on) return
        store.edit().putBoolean(KEY + part.name, on).putBoolean(FRESH + part.name, on).apply()
    }

    private fun allOn() = SyncPart.values().all { isOn(it) }

    private fun keyIsOn(key: String): Boolean = partOf(key)?.let { isOn(it) } ?: true

    /** [data] without the parts that are off. */
    fun strip(data: JSONObject): JSONObject {
        if (allOn()) return data
        val out = JSONObject()
        data.keys().forEach { k -> if (keyIsOn(k)) out.put(k, data.get(k)) }
        return out
    }

    /** [view] plus the entries of the parts that are off, taken from [from]. */
    fun keep(view: JSONObject, from: JSONObject): JSONObject {
        if (allOn()) return view
        val out = JSONObject()
        view.keys().forEach { k -> out.put(k, view.get(k)) }
        from.keys().forEach { k -> if (!keyIsOn(k)) out.put(k, from.get(k)) }
        return out
    }

    /**
     * [local] where the saved copy's value wins for parts switched on since the last sync (the
     * lists of playlists and guides are left alone: they combine source by source anyway).
     */
    fun freshen(local: JSONObject, remote: JSONObject): JSONObject {
        val fresh = SyncPart.values().filter { isOn(it) && store.getBoolean(FRESH + it.name, false) }
        if (fresh.isEmpty()) return local
        val out = JSONObject()
        local.keys().forEach { k ->
            val base = k.replace(PROFILE_SUFFIX, "")
            val takeSaved = base != "playlists" && base != "epg_sources" &&
                partOf(k)?.let { it in fresh } == true && remote.has(k)
            out.put(k, if (takeSaved) remote.get(k) else local.get(k))
        }
        return out
    }

    /** Called after a sync round finished: the parts switched on are now part of the agreed copy. */
    fun clearFresh() {
        val edit = store.edit()
        SyncPart.values().forEach { if (store.getBoolean(FRESH + it.name, false)) edit.putBoolean(FRESH + it.name, false) }
        edit.apply()
    }

    companion object {
        private const val KEY = "sync_part_"
        private const val FRESH = "sync_part_fresh_"
        private val PROFILE_SUFFIX = Regex("""_p\d+$""")

        private val LOGIN_KEYS = setOf("playlists", "epg_sources", "playlist_accounts", "epg_overrides")
        private val FAVORITE_KEYS = setOf(
            "favorites", "hidden_channels", "hidden_groups", "channel_names", "channel_logos", "channel_numbers",
            "group_names", "group_order", "custom_groups", "channel_order", "channel_copies", "channel_audio",
            "picture_shapes", "locked_groups", "name_removals", "reminders",
            "vod_hidden_categories", "vod_group_order", "vod_group_names"
        )
        private val SPORTS_KEYS = setOf("sports", "sports_mine")
        private val CALENDAR_KEYS = setOf(
            "calendar_follows", "calendar_hidden_shows", "calendar_channel_picks", "calendar_plans", "calendar_country"
        )

        /** Always synced, whatever is switched off: the parental lock and the profile set-up marker. */
        private val ALWAYS_KEYS = setOf("parental_pin", "lock_adult_content", "profile_seeded")

        /** Which part a stored key belongs to, or null when it is always synced. Everything not named above is "Look and layout". */
        fun partOf(key: String): SyncPart? {
            val base = key.replace(PROFILE_SUFFIX, "")
            return when {
                base == "vpn_profiles_v1" -> SyncPart.VPN
                base in ALWAYS_KEYS -> null
                base in LOGIN_KEYS -> SyncPart.LOGINS
                base in FAVORITE_KEYS -> SyncPart.FAVORITES
                base in SPORTS_KEYS -> SyncPart.SPORTS
                base in CALENDAR_KEYS -> SyncPart.CALENDAR
                else -> SyncPart.LOOK
            }
        }
    }
}
