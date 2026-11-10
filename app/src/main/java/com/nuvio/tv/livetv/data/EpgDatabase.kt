package com.nuvio.tv.livetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The TV guide on the device, TiviMate style: every listing is stored here after a guide
 * update, and the app reads only the hours it needs (what's on screen, plus a margin), so Live
 * TV opens straight away however big the guide is, and uses far less memory.
 *
 * Each listing is stored once, under the guide channel it came from ([GUIDE_CH]: one row per
 * guide file + channel id in it). Playlist channels only point at a guide channel ([CHAN]), with
 * their own time shift. So:
 *  - a guide file that hasn't changed isn't read again (its listings are already here);
 *  - channels sharing a guide channel (HD / SD / backups, the same channel in two playlists)
 *    share one copy of its listings instead of each having their own;
 *  - changing which guide a channel uses is a one-row change, nothing is copied.
 * Before, every guide update wrote the whole guide to a working table, copied it per playlist
 * channel, deleted the working copy and then rewrote the database file to give the space back.
 */
@Singleton
class EpgDatabase @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, "livetv_guide2.db", null, 1) {

    /**
     * Goes up whenever listings are written or removed: things worked out from the guide (Sports'
     * channel search) can tell they still hold without reading it again.
     */
    @Volatile var changes: Long = 1L
        private set

    init {
        // Readers don't wait for writers: the guide stays readable (scrolling, catch-up) while a
        // guide update is being stored, instead of stalling until it finishes.
        setWriteAheadLoggingEnabled(true)
        // The guide as earlier versions stored it (a copy of the listings for every playlist
        // channel). It's only a copy of what the guide files say, so it's simply removed, which
        // gives its space back at once; the guide is read from the saved guide files once more.
        runCatching { context.deleteDatabase("livetv_guide.db") }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listings (
                gid INTEGER NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        // The only index: a guide channel's listings in time order. Listings arrive a guide
        // channel at a time, so it grows in step with the table (cheap to keep up to date).
        db.execSQL("CREATE INDEX listings_gid_start ON listings(gid, start)")
        db.execSQL(
            """CREATE TABLE guide_ch (
                gid INTEGER PRIMARY KEY AUTOINCREMENT, src TEXT NOT NULL, xid TEXT NOT NULL,
                cnt INTEGER NOT NULL DEFAULT 0, last_stop INTEGER NOT NULL DEFAULT 0,
                placeholders INTEGER NOT NULL DEFAULT 0, state INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX guide_ch_src_xid ON guide_ch(src, xid)")
        db.execSQL("CREATE INDEX guide_ch_state ON guide_ch(state)")
        db.execSQL("CREATE TABLE guide_src (src TEXT PRIMARY KEY, stamp TEXT NOT NULL, made INTEGER NOT NULL, listed INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE guide_src_channels (src TEXT NOT NULL, xid TEXT NOT NULL, names TEXT, icon TEXT, extra INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX guide_src_channels_src ON guide_src_channels(src)")
        db.execSQL("CREATE TABLE chan (ch TEXT PRIMARY KEY, gid INTEGER NOT NULL, sh INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX chan_gid ON chan(gid)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing here is the only copy of anything: start again and read the guide files.
        listOf("listings", "guide_ch", "guide_src", "guide_src_channels", "chan", "auto", "meta", "sports_rows", "sports_words")
            .forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    /** A playlist channel's guide: a guide file ([sourceId]), a channel in it, and how far its times are moved. */
    class GuideLink(val sourceId: String, val xmltvId: String, val shiftMs: Long = 0L)

    // ------------------------------------------------------------ fingerprint, revision

    /** Stores the fingerprint, and its parts (or clears stale parts), inside the caller's transaction. */
    private fun putFingerprint(db: SQLiteDatabase, fingerprint: String, parts: String?) {
        putRevision(db)
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "fingerprint"); put("v", fingerprint) },
            SQLiteDatabase.CONFLICT_REPLACE)
        if (parts != null) {
            db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "parts"); put("v", parts) },
                SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("meta", "k = ?", arrayOf("parts"))
        }
    }

    /** What the stored guide was built from, in parts, or null. */
    fun parts(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'parts'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * A stamp for the stored listings that changes with every write and, unlike [changes], is kept
     * in the database: what Sports works out from the guide can be saved and used again after a
     * restart, as long as the guide is the same.
     */
    fun revision(): Long {
        val at = changes
        revCache?.let { (c, r) -> if (c == at) return r }
        fun read(): Long? = readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'rev'", null).use { c ->
            if (c.moveToFirst()) c.getString(0)?.toLongOrNull() else null
        }
        val r = read() ?: run {
            writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
                put("k", "rev"); put("v", newRevision().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            read() ?: 0L
        }
        if (changes == at) revCache = at to r
        return r
    }

    @Volatile private var revCache: Pair<Long, Long>? = null

    /** A new [revision], inside the caller's transaction. */
    private fun putRevision(db: SQLiteDatabase) {
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "rev"); put("v", newRevision().toString()) },
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun newRevision(): Long = kotlin.random.Random.nextLong().let { if (it == 0L) 1L else it }

    fun fingerprint(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'fingerprint'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    // ------------------------------------------------------------ reading

    /** A playlist channel and the guide channel it points at. */
    private class Link(val ch: String, val gid: Long, val sh: Long)

    /** Which guide channel each of these playlist channels points at ([keys] null: every channel). */
    private fun links(keys: Collection<String>?): List<Link> {
        val out = ArrayList<Link>()
        fun read(where: String, args: Array<String>?) {
            readableDatabase.rawQuery("SELECT ch, gid, sh FROM chan$where", args).use { c ->
                while (c.moveToNext()) out += Link(c.getString(0), c.getLong(1), c.getLong(2))
            }
        }
        if (keys == null) read("", null)
        else keys.distinct().chunked(400).forEach { part ->
            read(" WHERE ch IN (" + part.joinToString(",") { "?" } + ")", part.toTypedArray())
        }
        return out
    }

    /**
     * Channels that share a time shift, by guide channel: each guide channel is read once and its
     * listings handed to every channel pointing at it.
     */
    private class Group(val shiftMs: Long, val byGid: Map<Long, List<String>>)

    private fun groups(links: List<Link>): List<Group> =
        links.groupBy { it.sh }.map { (sh, list) ->
            val byGid = HashMap<Long, MutableList<String>>()
            list.forEach { byGid.getOrPut(it.gid) { ArrayList(1) } += it.ch }
            Group(sh, byGid)
        }

    /**
     * Every channel's guide channel, grouped. Searches across the whole guide (Sports, Calendar)
     * come in bursts of many small ones, so the last answer is kept for as long as nothing has
     * been written, and only as long as memory allows (it's let go when memory runs short).
     */
    private fun allGroups(): List<Group> {
        val at = changes
        allGroupsCache?.let { (stamp, ref) -> if (stamp == at) ref.get()?.let { return it } }
        val built = groups(links(null))
        if (changes == at) allGroupsCache = at to java.lang.ref.SoftReference(built)
        return built
    }

    @Volatile private var allGroupsCache: Pair<Long, java.lang.ref.SoftReference<List<Group>>>? = null

    /**
     * Listings in [fromMs, toMs), by channel, in time order.
     *  - [light]: just the parts the guide grid needs (title, times, episode, image), without
     *    descriptions and cast. Several times quicker to read and much smaller in memory;
     *    details are fetched for the show you're looking at ([program]).
     *  - [channels]: only these channels (the group on screen first, the rest afterwards).
     * Channels that share a guide channel (and time shift) get the very same list.
     */
    fun range(
        fromMs: Long,
        toMs: Long,
        onlyChannel: String? = null,
        light: Boolean = false,
        channels: Collection<String>? = null
    ): Map<String, List<EpgProgram>> {
        // A guide file swapped in while this was reading (channels moved to new guide channel
        // rows, the old listings being deleted): read again, so nothing comes back half there.
        repeat(2) {
            val at = changes
            val got = rangeOnce(fromMs, toMs, onlyChannel, light, channels)
            if (changes == at) return got
        }
        return rangeOnce(fromMs, toMs, onlyChannel, light, channels)
    }

    private fun rangeOnce(
        fromMs: Long,
        toMs: Long,
        onlyChannel: String?,
        light: Boolean,
        channels: Collection<String>?
    ): Map<String, List<EpgProgram>> {
        val out = HashMap<String, List<EpgProgram>>()
        val columns = if (light) "gid, start, stop, title, ep, icon" else "gid, start, stop, title, descr, cat, ep, icon, year, people"
        val wanted = when {
            onlyChannel != null -> groups(links(listOf(onlyChannel)))
            channels != null -> groups(links(channels))
            else -> allGroups()
        }
        for (group in wanted) {
            val sh = group.shiftMs
            val lists = HashMap<Long, ArrayList<EpgProgram>>()
            // Read by start time: shows starting in the window, plus ones that started up to
            // [MAX_SHOW_MS] earlier and are still on.
            val timeArgs = arrayOf((fromMs - sh - MAX_SHOW_MS).toString(), (toMs - sh).toString(), (fromMs - sh).toString())
            group.byGid.keys.chunked(GID_CHUNK).forEach { part ->
                val sql = "SELECT $columns FROM listings WHERE gid IN (${part.joinToString(",")}) AND start >= ? AND start < ? AND stop > ?"
                readableDatabase.rawQuery(sql, timeArgs).use { c ->
                    while (c.moveToNext()) {
                        val p = if (light) EpgProgram(
                            startMs = c.getLong(1) + sh,
                            stopMs = c.getLong(2) + sh,
                            title = c.getString(3),
                            episode = if (c.isNull(4)) null else XmltvParser.cleanEpisode(c.getString(4)),
                            icon = if (c.isNull(5)) null else c.getString(5)
                        ) else EpgProgram(
                            startMs = c.getLong(1) + sh,
                            stopMs = c.getLong(2) + sh,
                            title = c.getString(3),
                            description = if (c.isNull(4)) null else c.getString(4),
                            category = if (c.isNull(5)) null else c.getString(5),
                            episode = if (c.isNull(6)) null else XmltvParser.cleanEpisode(c.getString(6)),
                            icon = if (c.isNull(7)) null else c.getString(7),
                            year = if (c.isNull(8)) null else c.getInt(8),
                            people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                        )
                        lists.getOrPut(c.getLong(0)) { ArrayList() } += p
                    }
                }
            }
            lists.forEach { (gid, list) ->
                list.sortBy { it.startMs }
                list.trimToSize()
                group.byGid[gid]?.forEach { ch -> out[ch] = list }
            }
        }
        return out
    }

    /**
     * Sports: every listing starting in [fromMs, toMs) and still on after [minStopMs], one at a
     * time, read in a single pass (the caller picks out what it wants as the rows go by). A show
     * on a guide channel several playlist channels use is handed over once for each of them.
     */
    fun scanListings(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit
    ) = scanListingsGrouped(fromMs, toMs, minStopMs) { channels, start, stop, title, descr, cat ->
        for (ch in channels) onRow(ch, start, stop, title, descr, cat)
    }

    /** A guide show is read once even when many playlist channels share it. */
    fun scanListingsGrouped(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        onRow: (channels: List<String>, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit
    ) {
        for (group in allGroups()) {
            val sh = group.shiftMs
            val args = arrayOf((fromMs - sh).toString(), (toMs - sh).toString(), (minStopMs - sh).toString())
            group.byGid.keys.chunked(GID_CHUNK).forEach { part ->
                val sql = "SELECT gid, start, stop, title, descr, cat FROM listings WHERE gid IN (${part.joinToString(",")}) AND start >= ? AND start < ? AND stop > ?"
                readableDatabase.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) {
                        val chans = group.byGid[c.getLong(0)] ?: continue
                        val start = c.getLong(1) + sh
                        val stop = c.getLong(2) + sh
                        val title = c.getString(3)
                        val descr = if (c.isNull(4)) null else c.getString(4)
                        val cat = if (c.isNull(5)) null else c.getString(5)
                        onRow(chans, start, stop, title, descr, cat)
                    }
                }
            }
        }
    }

    /**
     * Sports: listings starting in [fromMs, toMs) and still on at [minStopMs] whose title (or, with
     * [inDescription], description) contains any of [words], ignoring case. Channel key -> show.
     * Asked in parts so a busy day's team names never go over SQLite's limit on placeholders.
     */
    fun findByWords(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        words: Collection<String>,
        inDescription: Boolean = true,
        limit: Int = 4_000
    ): List<Pair<String, EpgProgram>> {
        val clean = words.map { it.trim() }.filter { it.length >= 3 }.distinct()
        if (clean.isEmpty()) return emptyList()
        // Read again if a guide file was swapped in meanwhile (see [range]).
        repeat(2) {
            val at = changes
            val got = findByWordsOnce(fromMs, toMs, minStopMs, clean, inDescription, limit)
            if (changes == at) return got
        }
        return findByWordsOnce(fromMs, toMs, minStopMs, clean, inDescription, limit)
    }

    private fun findByWordsOnce(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        clean: List<String>,
        inDescription: Boolean,
        limit: Int
    ): List<Pair<String, EpgProgram>> {
        val out = ArrayList<Pair<String, EpgProgram>>()
        val seen = HashSet<String>()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val all = allGroups()
        clean.chunked(if (inDescription) 180 else 360).forEach { part ->
            val conds = part.flatMap { w ->
                if (inDescription) listOf("title LIKE ? ESCAPE '\\'", "descr LIKE ? ESCAPE '\\'") else listOf("title LIKE ? ESCAPE '\\'")
            }.joinToString(" OR ")
            val wordArgs = part.flatMap { w -> if (inDescription) listOf(like(w), like(w)) else listOf(like(w)) }
            for (group in all) {
                val sh = group.shiftMs
                val args = (listOf((fromMs - sh).toString(), (toMs - sh).toString(), (minStopMs - sh).toString()) + wordArgs).toTypedArray()
                for (gids in group.byGid.keys.chunked(GID_CHUNK)) {
                    if (out.size >= limit) return out
                    val sql = "SELECT gid, start, stop, title, descr, cat FROM listings WHERE gid IN (${gids.joinToString(",")}) " +
                        "AND start >= ? AND start < ? AND stop > ? AND ($conds)"
                    readableDatabase.rawQuery(sql, args).use { c ->
                        while (c.moveToNext() && out.size < limit) {
                            val chans = group.byGid[c.getLong(0)] ?: continue
                            val start = c.getLong(1) + sh
                            var program: EpgProgram? = null
                            for (ch in chans) {
                                if (out.size >= limit) break
                                if (!seen.add("$ch|$start")) continue
                                val p = program ?: EpgProgram(
                                    startMs = start,
                                    stopMs = c.getLong(2) + sh,
                                    title = c.getString(3),
                                    description = if (c.isNull(4)) null else c.getString(4),
                                    category = if (c.isNull(5)) null else c.getString(5)
                                ).also { program = it }
                                out += ch to p
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /** One show's full details (description, cast…), for the show you're looking at. */
    fun program(ch: String, startMs: Long): EpgProgram? =
        range(startMs, startMs + 1, onlyChannel = ch)[ch]?.firstOrNull { it.startMs == startMs }

    /** One channel's listings across [fromMs, toMs) (overlay mode's full schedule, catch-up). */
    fun channelRange(ch: String, fromMs: Long, toMs: Long): List<EpgProgram> =
        range(fromMs, toMs, ch)[ch].orEmpty()

    fun autoMatches(): Map<String, EpgAssignment> {
        val out = HashMap<String, EpgAssignment>()
        readableDatabase.rawQuery("SELECT ch, source, xmltv FROM auto", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = EpgAssignment(c.getString(1), c.getString(2))
        }
        return out
    }

    // ------------------------------------------------------------ guide files as they're read
    //
    // A guide file's listings (for the guide channels a playlist channel could use) are written
    // here while the file is read, a guide channel at a time, instead of being held in memory.
    // They're written under new guide channel rows nobody points at yet, so the guide on screen
    // stays as it was until the whole file is in; then the playlist channels are moved over to
    // the new rows in one short step and the old listings are deleted a batch at a time.

    /**
     * What's known about one guide channel: how many listings it had, how far ahead they go and
     * how many are placeholders ("Programming"…). [hasRows] false: the listings themselves aren't
     * kept (no playlist channel uses this guide channel), only these numbers.
     */
    class StagedMeta(val count: Int, val lastStop: Long, val placeholders: Int, val hasRows: Boolean = true)

    /**
     * A guide file as it was last read: its stamp, when, the guide channels kept from it, and
     * how many channels it [listed] before its first show (those were matched by their names and
     * ids; 0: none, so its shows were matched by their channel ids alone).
     */
    class SourceState(val stamp: String, val made: Long, val meta: Map<String, StagedMeta>, val listed: Int)

    /** One guide file being read in: [add] each guide channel's shows, then [finish] (or [abort]). */
    inner class SourceWriter internal constructor(private val src: String) {
        private val db = writableDatabase
        private val stmt: android.database.sqlite.SQLiteStatement
        private var rows = 0
        private val gids = HashMap<String, Long>()
        private val meta = HashMap<String, LongArray>() // count, lastStop, placeholders
        private val countedOnly = HashSet<String>()
        private var open = true

        init {
            // A read that never finished (the app was closed part-way): what it left goes first.
            markUnfinishedDead(db, src)
            purgeDead()
            db.beginTransaction()
            stmt = db.compileStatement(
                "INSERT INTO listings (gid, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
            )
        }

        private fun gidFor(xid: String): Long = gids.getOrPut(xid) {
            db.insertOrThrow("guide_ch", null, ContentValues().apply { put("src", src); put("xid", xid); put("state", PENDING) })
        }

        /**
         * [keepRows] false: the shows are only counted (how many, how far ahead, how many
         * placeholders), not stored. For a guide channel no playlist channel used last time: it
         * can still be compared with other guides, without its listings being written and then
         * deleted again at every update.
         */
        fun add(xid: String, programs: List<EpgProgram>, isPlaceholder: (String) -> Boolean, keepRows: Boolean = true) {
            if (programs.isEmpty()) return
            val gid = gidFor(xid)
            val m = meta.getOrPut(xid) { LongArray(3) }
            if (!keepRows) countedOnly += xid
            programs.forEach { p ->
                if (keepRows) {
                    bindListing(stmt, gid, p, 0L)
                    stmt.executeInsert()
                }
                m[0]++
                if (p.stopMs > m[1]) m[1] = p.stopMs
                if (isPlaceholder(p.title)) m[2]++
            }
            if (!keepRows) return
            rows += programs.size
            // Written in parts, so the database's pending changes stay small on big guides.
            if (rows >= 50_000) {
                rows = 0
                db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction()
            }
        }

        /**
         * The file is in: the playlist channels using this guide move over to the new listings,
         * and the file's stamp and channel list are stored. [kept]: every guide channel this
         * read was meant to keep, with or without listings in the hours kept (so next time it's
         * clear they were looked for). [listed]: see [SourceState]. [extraIds]: channel ids its
         * shows mention that aren't in its channel list. Returns what was read for each guide
         * channel.
         */
        fun finish(
            stamp: String,
            channels: List<Triple<String, List<String>, String?>>,
            kept: Collection<String>,
            listed: Int = channels.size,
            extraIds: Collection<String> = emptyList()
        ): Map<String, StagedMeta> {
            kept.forEach { gidFor(it) }
            val upd = db.compileStatement("UPDATE guide_ch SET cnt = ?, last_stop = ?, placeholders = ? WHERE gid = ?")
            meta.forEach { (xid, m) ->
                upd.clearBindings()
                upd.bindLong(1, m[0]); upd.bindLong(2, m[1]); upd.bindLong(3, m[2]); upd.bindLong(4, gids.getValue(xid))
                upd.executeUpdateDelete()
            }
            upd.close()
            changes++
            try {
                // The guide channels this file had before: channels pointing at them now point at
                // the new ones (or at nothing, when the file no longer has that guide channel).
                val old = ArrayList<Pair<Long, String>>()
                db.rawQuery("SELECT gid, xid FROM guide_ch WHERE src = ? AND state IN ($LIVE, $EMPTIED)", arrayOf(src)).use { c ->
                    while (c.moveToNext()) old += c.getLong(0) to c.getString(1)
                }
                val move = db.compileStatement("UPDATE chan SET gid = ? WHERE gid = ?")
                val drop = db.compileStatement("DELETE FROM chan WHERE gid = ?")
                old.forEach { (oldGid, xid) ->
                    val now = gids[xid]
                    if (now != null) { move.clearBindings(); move.bindLong(1, now); move.bindLong(2, oldGid); move.executeUpdateDelete() }
                    else { drop.clearBindings(); drop.bindLong(1, oldGid); drop.executeUpdateDelete() }
                }
                move.close(); drop.close()
                db.execSQL("UPDATE guide_ch SET state = $DEAD WHERE src = ? AND state IN ($LIVE, $EMPTIED)", arrayOf<Any>(src))
                db.execSQL("UPDATE guide_ch SET state = $LIVE WHERE src = ? AND state = $PENDING", arrayOf<Any>(src))
                countedOnly.forEach { xid ->
                    db.execSQL("UPDATE guide_ch SET state = $EMPTIED WHERE gid = ?", arrayOf<Any>(gids.getValue(xid)))
                }
                db.delete("guide_src_channels", "src = ?", arrayOf(src))
                val ch = db.compileStatement("INSERT INTO guide_src_channels (src, xid, names, icon) VALUES (?,?,?,?)")
                channels.forEach { (id, names, icon) ->
                    ch.clearBindings()
                    ch.bindString(1, src); ch.bindString(2, id)
                    if (names.isEmpty()) ch.bindNull(3) else ch.bindString(3, names.joinToString("\u001F"))
                    icon?.let { ch.bindString(4, it) } ?: ch.bindNull(4)
                    ch.executeInsert()
                }
                ch.close()
                if (extraIds.isNotEmpty()) {
                    val ex = db.compileStatement("INSERT INTO guide_src_channels (src, xid, extra) VALUES (?,?,1)")
                    extraIds.forEach { id -> ex.clearBindings(); ex.bindString(1, src); ex.bindString(2, id); ex.executeInsert() }
                    ex.close()
                }
                db.insertWithOnConflict("guide_src", null, ContentValues().apply {
                    put("src", src); put("stamp", stamp); put("made", System.currentTimeMillis()); put("listed", listed)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                putRevision(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
                open = false
                changes++
            }
            runCatching { stmt.close() }
            // The listings this file had before, a batch at a time.
            runCatching { purgeDead() }
            return gids.keys.associateWith { xid ->
                val m = meta[xid]
                StagedMeta(m?.get(0)?.toInt() ?: 0, m?.get(1) ?: 0L, m?.get(2)?.toInt() ?: 0, hasRows = xid !in countedOnly)
            }
        }

        /** Something went wrong: what this read wrote is dropped, and the guide stays as it was. */
        fun abort() {
            if (!open) return
            open = false
            runCatching { db.endTransaction() }
            runCatching { stmt.close() }
            runCatching { markUnfinishedDead(db, src); purgeDead() }
        }
    }

    fun startSource(src: String): SourceWriter = SourceWriter(src)

    /** Guide channel rows a read of [src] wrote but never finished: marked for deleting. */
    private fun markUnfinishedDead(db: SQLiteDatabase, src: String) {
        db.execSQL("UPDATE guide_ch SET state = $DEAD WHERE src = ? AND state = $PENDING", arrayOf<Any>(src))
    }

    /** A guide file as it was last read in, or null when it never was (or its read never finished). */
    fun sourceState(src: String): SourceState? {
        val info = readableDatabase.rawQuery("SELECT stamp, made, listed FROM guide_src WHERE src = ?", arrayOf(src)).use { c ->
            if (c.moveToFirst()) Triple(c.getString(0), c.getLong(1), c.getInt(2)) else null
        } ?: return null
        val meta = HashMap<String, StagedMeta>()
        readableDatabase.rawQuery(
            "SELECT xid, cnt, last_stop, placeholders, state FROM guide_ch WHERE src = ? AND state IN ($LIVE, $EMPTIED)", arrayOf(src)
        ).use { c ->
            while (c.moveToNext()) meta[c.getString(0)] = StagedMeta(c.getInt(1), c.getLong(2), c.getInt(3), hasRows = c.getInt(4) == LIVE)
        }
        return SourceState(info.first, info.second, meta, info.third)
    }

    /** Channel ids a read-in guide's shows mention that aren't in its channel list. */
    fun sourceExtraIds(src: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT xid FROM guide_src_channels WHERE src = ? AND extra = 1", arrayOf(src)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** A read-in guide's own channel list, in the file's order: id, display names, logo. */
    fun sourceChannels(src: String): List<Triple<String, List<String>, String?>> {
        val out = ArrayList<Triple<String, List<String>, String?>>()
        readableDatabase.rawQuery("SELECT xid, names, icon FROM guide_src_channels WHERE src = ? AND extra = 0 ORDER BY rowid", arrayOf(src)).use { c ->
            while (c.moveToNext()) out += Triple(
                c.getString(0),
                if (c.isNull(1)) emptyList() else c.getString(1).split('\u001F'),
                if (c.isNull(2)) null else c.getString(2)
            )
        }
        return out
    }

    // ------------------------------------------------------------ which guide each channel uses

    /** Every guide channel that can be pointed at, as "guide file + NUL + channel id" -> its row and whether its listings are kept. */
    private fun guideChannels(db: SQLiteDatabase): HashMap<String, Pair<Long, Boolean>> {
        val out = HashMap<String, Pair<Long, Boolean>>()
        db.rawQuery("SELECT gid, src, xid, state FROM guide_ch WHERE state IN ($LIVE, $EMPTIED)", null).use { c ->
            while (c.moveToNext()) out[c.getString(1) + "\u0000" + c.getString(2)] = c.getLong(0) to (c.getInt(3) == LIVE)
        }
        return out
    }

    /**
     * Guide channels nobody points at any more: their listings aren't kept (only what's known
     * about them, so they can still be compared with other guides). Inside the caller's
     * transaction; [emptyOut] deletes the listings afterwards.
     */
    private fun releaseUnused(db: SQLiteDatabase) {
        db.execSQL("UPDATE guide_ch SET state = $DEAD WHERE state = $LIVE AND src = ? AND gid NOT IN (SELECT gid FROM chan)", arrayOf<Any>(OWN_SOURCE))
        db.execSQL("UPDATE guide_ch SET state = $EMPTIED WHERE state = $LIVE AND gid NOT IN (SELECT gid FROM chan)")
    }

    /**
     * The guide after an update: every playlist channel in [links] points at its guide channel
     * (the listings themselves are already here, see [SourceWriter]); channels not in it have no
     * guide. Guide files not in [sources] are forgotten. Nothing is copied.
     */
    fun applyGuide(
        fingerprint: String,
        links: Map<String, GuideLink>,
        auto: Map<String, EpgAssignment>,
        parts: String?,
        sources: Collection<String>
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val gone = HashSet<String>()
                db.rawQuery("SELECT src FROM guide_src", null).use { c -> while (c.moveToNext()) gone += c.getString(0) }
                db.rawQuery("SELECT DISTINCT src FROM guide_ch WHERE state IN ($LIVE, $EMPTIED)", null).use { c -> while (c.moveToNext()) gone += c.getString(0) }
                gone -= sources.toSet()
                gone -= OWN_SOURCE
                gone.forEach { src -> forgetSource(db, src) }
                val known = guideChannels(db)
                db.delete("chan", null, null)
                db.delete("auto", null, null)
                val ins = db.compileStatement("INSERT OR REPLACE INTO chan (ch, gid, sh) VALUES (?,?,?)")
                links.forEach { (ch, link) ->
                    val gid = known[link.sourceId + "\u0000" + link.xmltvId]?.first ?: return@forEach
                    ins.clearBindings(); ins.bindString(1, ch); ins.bindLong(2, gid); ins.bindLong(3, link.shiftMs)
                    ins.executeInsert()
                }
                ins.close()
                putAuto(db, auto)
                releaseUnused(db)
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
        runCatching { purgeDead(); emptyOut() }
    }

    private fun putAuto(db: SQLiteDatabase, auto: Map<String, EpgAssignment>) {
        if (auto.isEmpty()) return
        val stmt = db.compileStatement("INSERT OR REPLACE INTO auto (ch, source, xmltv) VALUES (?,?,?)")
        auto.forEach { (ch, a) ->
            stmt.clearBindings(); stmt.bindString(1, ch); stmt.bindString(2, a.sourceId); stmt.bindString(3, a.xmltvId)
            stmt.executeInsert()
        }
        stmt.close()
    }

    /** A guide file that's no longer used: everything read from it goes (inside the caller's transaction). */
    private fun forgetSource(db: SQLiteDatabase, src: String) {
        db.execSQL("DELETE FROM chan WHERE gid IN (SELECT gid FROM guide_ch WHERE src = ?)", arrayOf<Any>(src))
        db.execSQL("UPDATE guide_ch SET state = $DEAD WHERE src = ?", arrayOf<Any>(src))
        db.delete("guide_src", "src = ?", arrayOf(src))
        db.delete("guide_src_channels", "src = ?", arrayOf(src))
    }

    /**
     * Changes just these channels' guides (Assign EPG: unassigned, given another guide channel,
     * or back to automatic), drops their automatic matches, and stores the new fingerprint, in
     * one go. Channels in [channels] but not in [links] end up with no guide. [listings]: the
     * shows of guide channels that may not be stored yet ("guide file + NUL + channel id" ->
     * shows, in the guide's own times); ones already stored are left as they are.
     */
    fun setChannels(
        fingerprint: String,
        channels: Set<String>,
        links: Map<String, GuideLink>,
        listings: Map<String, List<EpgProgram>>,
        isPlaceholder: (String) -> Boolean,
        /** Automatic matches found for these channels (kept for next time). */
        auto: Map<String, EpgAssignment> = emptyMap(),
        parts: String? = null
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                channels.forEach { ch ->
                    db.delete("chan", "ch = ?", arrayOf(ch))
                    db.delete("auto", "ch = ?", arrayOf(ch))
                }
                val ins = db.compileStatement("INSERT OR REPLACE INTO chan (ch, gid, sh) VALUES (?,?,?)")
                links.forEach { (ch, link) ->
                    val gid = ensureGuideChannel(db, link.sourceId, link.xmltvId, listings[link.sourceId + "\u0000" + link.xmltvId], isPlaceholder)
                        ?: return@forEach
                    ins.clearBindings(); ins.bindString(1, ch); ins.bindLong(2, gid); ins.bindLong(3, link.shiftMs)
                    ins.executeInsert()
                }
                ins.close()
                putAuto(db, auto)
                releaseUnused(db)
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
        runCatching { purgeDead(); emptyOut() }
    }

    /**
     * The row of guide channel [xid] of guide file [src] with its listings stored: as it is when
     * they already are, otherwise filled from [programs] (null and not stored: no row, null).
     * Inside the caller's transaction.
     */
    private fun ensureGuideChannel(
        db: SQLiteDatabase,
        src: String,
        xid: String,
        programs: List<EpgProgram>?,
        isPlaceholder: (String) -> Boolean
    ): Long? {
        var gid: Long? = null
        var state = -1
        db.rawQuery("SELECT gid, state FROM guide_ch WHERE src = ? AND xid = ? AND state IN ($LIVE, $EMPTIED) LIMIT 1", arrayOf(src, xid)).use { c ->
            if (c.moveToFirst()) { gid = c.getLong(0); state = c.getInt(1) }
        }
        if (gid != null && state == LIVE) return gid
        if (programs == null) return null
        val id = gid ?: db.insertOrThrow("guide_ch", null, ContentValues().apply { put("src", src); put("xid", xid); put("state", LIVE) })
        // Emptied before: whatever a cleanup hadn't got to yet goes, so nothing is stored twice.
        db.delete("listings", "gid = ?", arrayOf(id.toString()))
        val stmt = db.compileStatement(
            "INSERT INTO listings (gid, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
        )
        var lastStop = 0L
        var placeholders = 0L
        programs.forEach { p ->
            bindListing(stmt, id, p, 0L)
            stmt.executeInsert()
            if (p.stopMs > lastStop) lastStop = p.stopMs
            if (isPlaceholder(p.title)) placeholders++
        }
        stmt.close()
        db.execSQL(
            "UPDATE guide_ch SET cnt = ?, last_stop = ?, placeholders = ?, state = $LIVE WHERE gid = ?",
            arrayOf<Any>(programs.size.toLong(), lastStop, placeholders, id)
        )
        return id
    }

    /**
     * A playlist turned off or removed: its channels no longer point at a guide (every channel
     * key starts with its playlist's id and "|"), nor do [channels]; guide files in [sources]
     * went with it. Stores the new fingerprint, in one go. Nothing else in the guide changes, so
     * no guide has to be read again.
     */
    fun dropPlaylists(
        playlistIds: Collection<String>,
        channels: Collection<String>,
        fingerprint: String,
        parts: String,
        sources: Collection<String> = emptyList()
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                playlistIds.forEach { id ->
                    val prefix = "$id|"
                    val args = arrayOf(prefix.length.toString(), prefix)
                    db.delete("chan", "substr(ch, 1, ?) = ?", args)
                    db.delete("auto", "substr(ch, 1, ?) = ?", args)
                }
                channels.forEach { ch ->
                    db.delete("chan", "ch = ?", arrayOf(ch))
                    db.delete("auto", "ch = ?", arrayOf(ch))
                }
                sources.forEach { src -> forgetSource(db, src) }
                releaseUnused(db)
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
        runCatching { purgeDead(); emptyOut() }
    }

    /**
     * Adds a channel's listings for a time span (Xtream catch-up archive), replacing anything
     * stored for it in that span. They go to the guide channel it points at (so channels sharing
     * that guide channel get them too); a channel with no guide gets a guide channel of its own.
     */
    fun putChannelRange(ch: String, fromMs: Long, toMs: Long, programs: List<EpgProgram>) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                var gid: Long? = null
                var sh = 0L
                db.rawQuery("SELECT gid, sh FROM chan WHERE ch = ?", arrayOf(ch)).use { c ->
                    if (c.moveToFirst()) { gid = c.getLong(0); sh = c.getLong(1) }
                }
                val id = gid ?: run {
                    var own: Long? = null
                    db.rawQuery("SELECT gid FROM guide_ch WHERE src = ? AND xid = ? AND state = $LIVE LIMIT 1", arrayOf(OWN_SOURCE, ch)).use { c ->
                        if (c.moveToFirst()) own = c.getLong(0)
                    }
                    val made = own ?: db.insertOrThrow("guide_ch", null, ContentValues().apply {
                        put("src", OWN_SOURCE); put("xid", ch); put("state", LIVE)
                    })
                    db.insertWithOnConflict("chan", null, ContentValues().apply { put("ch", ch); put("gid", made); put("sh", 0L) },
                        SQLiteDatabase.CONFLICT_REPLACE)
                    made
                }
                db.delete("listings", "gid = ? AND start >= ? AND start < ?", arrayOf(id.toString(), (fromMs - sh).toString(), (toMs - sh).toString()))
                val stmt = db.compileStatement(
                    "INSERT INTO listings (gid, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
                )
                programs.forEach { p ->
                    stmt.clearBindings()
                    stmt.bindLong(1, id); stmt.bindLong(2, p.startMs - sh); stmt.bindLong(3, p.stopMs - sh); stmt.bindString(4, p.title)
                    p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    stmt.bindNull(6); stmt.bindNull(7); stmt.bindNull(8); stmt.bindNull(9); stmt.bindNull(10)
                    stmt.executeInsert()
                }
                stmt.close()
                putRevision(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    /** These channels no longer have a guide (channels hidden in every profile). */
    fun deleteChannels(channels: Collection<String>) {
        changes++
        try {
            if (channels.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                channels.forEach { ch -> db.delete("chan", "ch = ?", arrayOf(ch)) }
                releaseUnused(db)
                putRevision(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
        runCatching { purgeDead(); emptyOut() }
    }

    // ------------------------------------------------------------ clearing up

    /**
     * Deletes guide channel rows marked for deleting and their listings, a batch at a time (each
     * its own short step, so nothing else waits long and the pending changes stay small).
     */
    private fun purgeDead() {
        val db = writableDatabase
        while (true) {
            val gids = ArrayList<Long>()
            db.rawQuery("SELECT gid FROM guide_ch WHERE state = $DEAD LIMIT $PURGE_BATCH", null).use { c -> while (c.moveToNext()) gids += c.getLong(0) }
            if (gids.isEmpty()) return
            val list = gids.joinToString(",")
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM listings WHERE gid IN ($list)")
                db.execSQL("DELETE FROM guide_ch WHERE gid IN ($list)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Deletes the listings of guide channels nobody points at any more ([releaseUnused]), a batch at a time. */
    private fun emptyOut() {
        val db = writableDatabase
        val gids = ArrayList<Long>()
        db.rawQuery(
            "SELECT gid FROM guide_ch WHERE state = $EMPTIED AND EXISTS (SELECT 1 FROM listings WHERE listings.gid = guide_ch.gid)", null
        ).use { c -> while (c.moveToNext()) gids += c.getLong(0) }
        gids.chunked(PURGE_BATCH).forEach { part ->
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM listings WHERE gid IN (${part.joinToString(",")})")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Finishes any clearing up an earlier run was interrupted in (cheap when there's nothing to do). */
    fun tidy() {
        runCatching {
            // Only called when no guide file is being read in: anything still unfinished was abandoned.
            writableDatabase.execSQL("UPDATE guide_ch SET state = $DEAD WHERE state = $PENDING")
            purgeDead(); emptyOut()
        }
    }

    private fun bindListing(stmt: android.database.sqlite.SQLiteStatement, gid: Long, p: EpgProgram, shiftMs: Long) {
        stmt.clearBindings()
        stmt.bindLong(1, gid)
        stmt.bindLong(2, p.startMs - shiftMs); stmt.bindLong(3, p.stopMs - shiftMs); stmt.bindString(4, p.title)
        p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
        p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
        p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
        p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
        p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
        if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
    }

    // ------------------------------------------------------------ sports shows
    //
    // Test builds: the guide's shows that name a sport, a competition or a team (with their text
    // already cleaned up for matching), so Sports' channel search reads a few thousand rows instead
    // of the whole guide. Built by SportsMatcher from one read of the guide, and only good for the
    // guide [revision] it was built from. Its tables are made the first time they're used.

    /** What the sports-shows table holds: the guide it came from, the time span read, how many shows. */
    class SportsRowsInfo(val stamp: String, val rev: Long, val fromMs: Long, val toMs: Long, val rows: Int)

    private fun ensureSportsTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS sports_rows (
                ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, nt TEXT NOT NULL, nd TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS sports_rows_start ON sports_rows(start)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sports_words (w TEXT PRIMARY KEY)")
    }

    /** The sports-shows table's details, or null when there's none (never built, or being rebuilt). */
    fun sportsRowsInfo(): SportsRowsInfo? = runCatching {
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'sports_rows'", null).use { c ->
            if (!c.moveToFirst()) return@use null
            val stamp = c.getString(0) ?: return@use null
            val f = stamp.split('|')
            if (f.size < 4) null else SportsRowsInfo(stamp, f[0].toLong(), f[1].toLong(), f[2].toLong(), f[3].toInt())
        }
    }.getOrNull()

    /** The words the sports-shows table was built for (every show naming one of them is in it). */
    fun sportsRowsWords(): Set<String> = runCatching {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT w FROM sports_words", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }.getOrDefault(emptySet())

    /**
     * Rebuilds the sports-shows table: [add] each show, then [finish] (or [abort]). Shows are written
     * a couple of thousand at a time in short steps, so a guide update never waits long behind it.
     */
    inner class SportsRowsWriter internal constructor() {
        private val db = writableDatabase
        private val startedAt = changes
        private val buffer = ArrayList<Array<Any?>>(BATCH)
        private var open = true
        var rows = 0
            private set

        init {
            db.beginTransaction()
            try {
                ensureSportsTables(db)
                // Cleared first: a table that's half rebuilt is never taken for a finished one.
                db.delete("meta", "k = ?", arrayOf("sports_rows"))
                db.delete("sports_rows", null, null)
                db.delete("sports_words", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        fun add(ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?, nt: String, nd: String) {
            buffer += arrayOf(ch, start, stop, title, descr, cat, nt, nd)
            rows++
            if (buffer.size >= BATCH) { db.beginTransaction(); try { flush(); db.setTransactionSuccessful() } finally { db.endTransaction() } }
        }

        /** Writes what's waiting, inside the caller's transaction. */
        private fun flush() {
            if (buffer.isEmpty()) return
            val stmt = db.compileStatement("INSERT INTO sports_rows (ch, start, stop, title, descr, cat, nt, nd) VALUES (?,?,?,?,?,?,?,?)")
            for (r in buffer) {
                stmt.clearBindings()
                stmt.bindString(1, r[0] as String); stmt.bindLong(2, r[1] as Long); stmt.bindLong(3, r[2] as Long)
                stmt.bindString(4, r[3] as String)
                (r[4] as String?)?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                (r[5] as String?)?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                stmt.bindString(7, r[6] as String); stmt.bindString(8, r[7] as String)
                stmt.executeInsert()
            }
            stmt.close()
            buffer.clear()
        }

        /** Marks the table finished for guide [rev] — unless the guide changed while it was read (then it's left unfinished, never used). */
        fun finish(rev: Long, fromMs: Long, toMs: Long, words: Collection<String>): Boolean {
            if (!open) return false
            open = false
            db.beginTransaction()
            try {
                flush()
                val ok = changes == startedAt
                if (ok) {
                    val w = db.compileStatement("INSERT OR IGNORE INTO sports_words (w) VALUES (?)")
                    words.forEach { w.clearBindings(); w.bindString(1, it); w.executeInsert() }
                    w.close()
                    db.insertWithOnConflict("meta", null, ContentValues().apply {
                        put("k", "sports_rows"); put("v", "$rev|$fromMs|$toMs|$rows|${System.currentTimeMillis()}")
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
                return ok
            } finally {
                db.endTransaction()
            }
        }

        /** Something went wrong: the table stays unfinished (never used) until the next rebuild. */
        fun abort() {
            open = false
            buffer.clear()
        }
    }

    fun startSportsRows(): SportsRowsWriter = SportsRowsWriter()

    /** [scanListings] over the sports-shows table, with each show's cleaned-up title and description. */
    fun scanSportsRows(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?, nt: String, nd: String) -> Unit
    ) {
        val sql = "SELECT ch, start, stop, title, descr, cat, nt, nd FROM sports_rows WHERE start >= ? AND start < ? AND stop > ?"
        readableDatabase.rawQuery(sql, arrayOf(fromMs.toString(), toMs.toString(), minStopMs.toString())).use { c ->
            while (c.moveToNext()) {
                onRow(
                    c.getString(0), c.getLong(1), c.getLong(2), c.getString(3),
                    if (c.isNull(4)) null else c.getString(4),
                    if (c.isNull(5)) null else c.getString(5),
                    c.getString(6), c.getString(7)
                )
            }
        }
    }

    /**
     * Gives back space left empty by deleted listings (see [SqliteCompact]), but only when most
     * of the file is empty (a guide removed, far fewer days kept). The space an updated guide's
     * old listings leave is used again by its next update, so the file isn't rewritten for that.
     */
    fun compact(): Long = SqliteCompact.compactIfWasteful(writableDatabase, "guide", emptyShare = 0.6)

    fun clear() {
        changes++
        try {
            val db = writableDatabase
            db.delete("listings", null, null)
            db.delete("guide_ch", null, null)
            db.delete("guide_src", null, null)
            db.delete("guide_src_channels", null, null)
            db.delete("chan", null, null)
            db.delete("auto", null, null)
            db.delete("meta", null, null)
            runCatching { db.delete("sports_rows", null, null); db.delete("sports_words", null, null) }
        } finally {
            changes++
        }
    }

    companion object {
        /** A guide channel made for one playlist channel's own listings (Xtream archive, no guide file). */
        const val OWN_SOURCE = "__own__"

        // A guide channel row's state.
        /** Being written by a read that hasn't finished: nothing points at it yet. */
        private const val PENDING = 0
        /** In use, with its listings stored. */
        private const val LIVE = 1
        /** Read, but no playlist channel uses it: its listings aren't kept. */
        private const val EMPTIED = 2
        /** To be deleted with its listings (replaced by a newer read, or its guide file is gone). */
        private const val DEAD = 3
    }
}

/** Longest single listing assumed when reading a time window (longer ones are very rare). */
private const val MAX_SHOW_MS = 6L * 60 * 60 * 1000

/** Sports shows written to their table at a time (see [EpgDatabase.SportsRowsWriter]). */
private const val BATCH = 2_000

/** Guide channels asked for in one query. */
private const val GID_CHUNK = 400

/** Guide channels whose listings are deleted in one step. */
private const val PURGE_BATCH = 200
