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
 */
@Singleton
class EpgDatabase @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, "livetv_guide.db", null, 3) {

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
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE programs (
                ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        db.execSQL("CREATE INDEX programs_ch_start ON programs(ch, start)")
        db.execSQL("CREATE INDEX programs_stop ON programs(stop)")
        db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
        db.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY, source TEXT NOT NULL, xmltv TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        createStageTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2: an index on start time, so "the hours around now" is read directly instead
        // of going through every future listing (which took minutes on big guides).
        if (oldVersion < 2) {
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)") }
                .onFailure {
                    db.execSQL("DROP TABLE IF EXISTS programs")
                    db.execSQL("DROP TABLE IF EXISTS auto")
                    db.execSQL("DROP TABLE IF EXISTS meta")
                    onCreate(db)
                }
        }
        // Version 3: guides staged while they're read (see StageWriter).
        if (oldVersion < 3) createStageTables(db)
    }

    /** Replaces the whole guide (after a guide update), in one transaction. */
    fun replaceAll(
        fingerprint: String,
        programs: Map<String, List<EpgProgram>>,
        auto: Map<String, EpgAssignment>,
        /** What the guide was built from, in parts (see LiveTvRepository.guideParts). */
        parts: String? = null
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
                db.execSQL("DROP INDEX IF EXISTS programs_stop")
                db.execSQL("DROP INDEX IF EXISTS programs_start")
                db.delete("programs", null, null)
                db.delete("auto", null, null)
                val stmt = db.compileStatement(
                    "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
                )
                programs.forEach { (ch, list) ->
                    list.forEach { p ->
                        stmt.clearBindings()
                        stmt.bindString(1, ch)
                        stmt.bindLong(2, p.startMs)
                        stmt.bindLong(3, p.stopMs)
                        stmt.bindString(4, p.title)
                        p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                        p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                        p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                        p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                        p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                        if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                        stmt.executeInsert()
                    }
                }
                auto.forEach { (ch, a) ->
                    db.insertWithOnConflict("auto", null, ContentValues().apply {
                        put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    /**
     * Replaces just these channels' listings (Assign EPG: unassigned or given another guide
     * channel), drops their automatic matches, and stores the new fingerprint, in one go.
     */
    fun replaceChannels(
        fingerprint: String,
        channels: Set<String>,
        programs: Map<String, List<EpgProgram>>,
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
                    db.delete("programs", "ch = ?", arrayOf(ch))
                    db.delete("auto", "ch = ?", arrayOf(ch))
                }
                val stmt = db.compileStatement(
                    "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
                )
                programs.forEach { (ch, list) ->
                    list.forEach { p ->
                        stmt.clearBindings()
                        stmt.bindString(1, ch)
                        stmt.bindLong(2, p.startMs)
                        stmt.bindLong(3, p.stopMs)
                        stmt.bindString(4, p.title)
                        p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                        p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                        p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                        p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                        p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                        if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                        stmt.executeInsert()
                    }
                }
                auto.forEach { (ch, a) ->
                    db.insertWithOnConflict("auto", null, ContentValues().apply {
                        put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

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

    /** What the stored guide was built from, in parts, or null (stored by an older version). */
    fun parts(): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = 'parts'", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * A playlist turned off or removed: drops its channels' listings and matches (every channel
     * key starts with its playlist's id and "|"), plus [channels], and stores the new fingerprint,
     * in one go. Nothing else in the guide changes, so no guide has to be read again.
     */
    fun dropPlaylists(
        playlistIds: Collection<String>,
        channels: Collection<String>,
        fingerprint: String,
        parts: String
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                playlistIds.forEach { id ->
                    val prefix = "$id|"
                    val args = arrayOf(prefix.length.toString(), prefix)
                    db.delete("programs", "substr(ch, 1, ?) = ?", args)
                    db.delete("auto", "substr(ch, 1, ?) = ?", args)
                }
                channels.forEach { ch ->
                    db.delete("programs", "ch = ?", arrayOf(ch))
                    db.delete("auto", "ch = ?", arrayOf(ch))
                }
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    /**
     * A stamp for the stored listings that changes with every write and, unlike [changes], is kept
     * in the database: what Sports works out from the guide can be saved and used again after a
     * restart, as long as the guide is the same. A guide stored by an older version gets one now.
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

    /** Listings overlapping [fromMs, toMs), by channel, in time order. */
    /**
     * Listings in [fromMs, toMs), by channel, in time order.
     *  - [light]: just the parts the guide grid needs (title, times, episode, image), without
     *    descriptions and cast. Several times quicker to read and much smaller in memory;
     *    details are fetched for the show you're looking at ([program]).
     *  - [channels]: only these channels (the group on screen first, the rest afterwards).
     */
    fun range(
        fromMs: Long,
        toMs: Long,
        onlyChannel: String? = null,
        light: Boolean = false,
        channels: Collection<String>? = null
    ): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        val columns = if (light) "ch, start, stop, title, ep, icon" else "ch, start, stop, title, descr, cat, ep, icon, year, people"
        // Read by start time (indexed): shows starting in the window, plus ones that started up
        // to [MAX_SHOW_MS] earlier and are still on. Looking up "everything that ends after the
        // start of the window" instead meant reading every future listing in the guide.
        val timeArgs = listOf((fromMs - MAX_SHOW_MS).toString(), toMs.toString(), fromMs.toString())
        fun read(chFilter: String, chArgs: List<String>) {
            val sql = "SELECT $columns FROM programs WHERE $chFilter start >= ? AND start < ? AND stop > ?"
            readableDatabase.rawQuery(sql, (chArgs + timeArgs).toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val p = if (light) EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        episode = if (c.isNull(4)) null else XmltvParser.cleanEpisode(c.getString(4)),
                        icon = if (c.isNull(5)) null else c.getString(5)
                    ) else EpgProgram(
                        startMs = c.getLong(1),
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5),
                        episode = if (c.isNull(6)) null else XmltvParser.cleanEpisode(c.getString(6)),
                        icon = if (c.isNull(7)) null else c.getString(7),
                        year = if (c.isNull(8)) null else c.getInt(8),
                        people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                    )
                    out.getOrPut(c.getString(0)) { ArrayList() } += p
                }
            }
        }
        when {
            onlyChannel != null -> read("ch = ? AND", listOf(onlyChannel))
            channels != null -> channels.distinct().chunked(400).forEach { part ->
                read("ch IN (" + part.joinToString(",") { "?" } + ") AND", part)
            }
            else -> read("", emptyList())
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    /**
     * Sports: every listing starting in [fromMs, toMs) and still on after [minStopMs], one at a
     * time, read in a single pass (the caller picks out what it wants as the rows go by).
     */
    fun scanListings(
        fromMs: Long,
        toMs: Long,
        minStopMs: Long,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?) -> Unit
    ) {
        val sql = "SELECT ch, start, stop, title, descr, cat FROM programs WHERE start >= ? AND start < ? AND stop > ?"
        readableDatabase.rawQuery(sql, arrayOf(fromMs.toString(), toMs.toString(), minStopMs.toString())).use { c ->
            while (c.moveToNext()) {
                onRow(
                    c.getString(0), c.getLong(1), c.getLong(2), c.getString(3),
                    if (c.isNull(4)) null else c.getString(4),
                    if (c.isNull(5)) null else c.getString(5)
                )
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
        val out = ArrayList<Pair<String, EpgProgram>>()
        val seen = HashSet<String>()
        fun like(w: String) = "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        clean.chunked(if (inDescription) 180 else 360).forEach { part ->
            if (out.size >= limit) return@forEach
            val conds = part.flatMap { w ->
                if (inDescription) listOf("title LIKE ? ESCAPE '\\'", "descr LIKE ? ESCAPE '\\'") else listOf("title LIKE ? ESCAPE '\\'")
            }.joinToString(" OR ")
            val args = listOf(fromMs.toString(), toMs.toString(), minStopMs.toString()) +
                part.flatMap { w -> if (inDescription) listOf(like(w), like(w)) else listOf(like(w)) }
            val sql = "SELECT ch, start, stop, title, descr, cat FROM programs WHERE start >= ? AND start < ? AND stop > ? AND ($conds) LIMIT ${limit - out.size}"
            readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val ch = c.getString(0)
                    val start = c.getLong(1)
                    if (!seen.add("$ch|$start")) continue
                    out += ch to EpgProgram(
                        startMs = start,
                        stopMs = c.getLong(2),
                        title = c.getString(3),
                        description = if (c.isNull(4)) null else c.getString(4),
                        category = if (c.isNull(5)) null else c.getString(5)
                    )
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

    /**
     * Adds a channel's listings for a time span (Xtream catch-up archive), replacing anything
     * stored for that channel in that span.
     */
    fun putChannelRange(ch: String, fromMs: Long, toMs: Long, programs: List<EpgProgram>) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("programs", "ch = ? AND start >= ? AND start < ?", arrayOf(ch, fromMs.toString(), toMs.toString()))
                val stmt = db.compileStatement(
                    "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
                )
                programs.forEach { p ->
                    stmt.clearBindings()
                    stmt.bindString(1, ch); stmt.bindLong(2, p.startMs); stmt.bindLong(3, p.stopMs); stmt.bindString(4, p.title)
                    p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                    stmt.bindNull(6); stmt.bindNull(7); stmt.bindNull(8); stmt.bindNull(9); stmt.bindNull(10)
                    stmt.executeInsert()
                }
                putRevision(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    /** Drops these channels' listings (channels hidden in every profile). */
    fun deleteChannels(channels: Collection<String>) {
        changes++
        try {
            if (channels.isEmpty()) return
            val db = writableDatabase
            db.beginTransaction()
            try {
                channels.forEach { ch -> db.delete("programs", "ch = ?", arrayOf(ch)) }
                putRevision(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    // ------------------------------------------------------------ staged guides
    //
    // Each guide file's listings (for the channels it could match) are written here while the
    // file is read, a channel at a time, instead of being held in memory: a big guide used to take
    // a few hundred MB while it was matched. The guide shown is then built from here in one step
    // (the best guide for each channel), and a guide file that hasn't changed isn't read again.

    private fun createStageTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS stage (
                src TEXT NOT NULL, xid TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                descr TEXT, cat TEXT, ep TEXT, icon TEXT, year INTEGER, people TEXT)"""
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS stage_meta (src TEXT NOT NULL, xid TEXT NOT NULL, cnt INTEGER NOT NULL, " +
                "last_stop INTEGER NOT NULL, placeholders INTEGER NOT NULL, PRIMARY KEY (src, xid))"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS stage_channels (src TEXT NOT NULL, xid TEXT NOT NULL, names TEXT, icon TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS stage_info (src TEXT PRIMARY KEY, stamp TEXT NOT NULL, made INTEGER NOT NULL)")
        // No index on the working copy: keeping one up to date for every listing made storing a
        // big guide several times slower. The final step looks channels up the other way round.
        db.execSQL("DROP INDEX IF EXISTS stage_src_xid")
    }

    /** What's known about one guide channel's staged listings. */
    class StagedMeta(val count: Int, val lastStop: Long, val placeholders: Int)

    /** One guide file being staged: [add] each channel's shows, then [finish] (or [abort]). */
    inner class StageWriter internal constructor(private val src: String) {
        private val db = writableDatabase
        private val stmt: android.database.sqlite.SQLiteStatement
        private var rows = 0
        private val meta = HashMap<String, LongArray>() // count, lastStop, placeholders
        private var open = true

        init {
            db.execSQL("DROP INDEX IF EXISTS stage_src_xid")
            db.beginTransaction()
            db.delete("stage", "src = ?", arrayOf(src))
            db.delete("stage_meta", "src = ?", arrayOf(src))
            db.delete("stage_channels", "src = ?", arrayOf(src))
            db.delete("stage_info", "src = ?", arrayOf(src))
            stmt = db.compileStatement(
                "INSERT INTO stage (src, xid, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
            )
        }

        fun add(xid: String, programs: List<EpgProgram>, isPlaceholder: (String) -> Boolean) {
            if (programs.isEmpty()) return
            val m = meta.getOrPut(xid) { LongArray(3) }
            programs.forEach { p ->
                stmt.clearBindings()
                stmt.bindString(1, src); stmt.bindString(2, xid)
                stmt.bindLong(3, p.startMs); stmt.bindLong(4, p.stopMs); stmt.bindString(5, p.title)
                p.description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                p.category?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                p.episode?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                p.icon?.let { stmt.bindString(9, it) } ?: stmt.bindNull(9)
                p.year?.let { stmt.bindLong(10, it.toLong()) } ?: stmt.bindNull(10)
                if (p.people.isEmpty()) stmt.bindNull(11) else stmt.bindString(11, p.people.joinToString("\u001F"))
                stmt.executeInsert()
                m[0]++
                if (p.stopMs > m[1]) m[1] = p.stopMs
                if (isPlaceholder(p.title)) m[2]++
            }
            rows += programs.size
            // Written in parts, so the database's pending changes stay small on big guides.
            if (rows >= 50_000) {
                rows = 0
                db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction()
            }
        }

        /** Stores the guide's channel list (for matching it again without reading the file) and the file's stamp. */
        fun finish(stamp: String, channels: List<Triple<String, List<String>, String?>>): Map<String, StagedMeta> {
            meta.forEach { (xid, m) ->
                db.insertWithOnConflict("stage_meta", null, ContentValues().apply {
                    put("src", src); put("xid", xid); put("cnt", m[0]); put("last_stop", m[1]); put("placeholders", m[2])
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val ch = db.compileStatement("INSERT INTO stage_channels (src, xid, names, icon) VALUES (?,?,?,?)")
            channels.forEach { (id, names, icon) ->
                ch.clearBindings()
                ch.bindString(1, src); ch.bindString(2, id)
                if (names.isEmpty()) ch.bindNull(3) else ch.bindString(3, names.joinToString("\u001F"))
                icon?.let { ch.bindString(4, it) } ?: ch.bindNull(4)
                ch.executeInsert()
            }
            db.insertWithOnConflict("stage_info", null, ContentValues().apply {
                put("src", src); put("stamp", stamp); put("made", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            db.endTransaction()
            open = false
            return meta.mapValues { (_, m) -> StagedMeta(m[0].toInt(), m[1], m[2].toInt()) }
        }

        /** Something went wrong: what was written in this round is dropped (earlier parts stay until the next read). */
        fun abort() {
            if (!open) return
            open = false
            runCatching { db.endTransaction() }
            runCatching { db.delete("stage_info", "src = ?", arrayOf(src)) }
        }
    }

    fun startStage(src: String): StageWriter = StageWriter(src)

    /** The stamp a guide was staged with, and when, or null. */
    fun stageInfo(src: String): Pair<String, Long>? =
        readableDatabase.rawQuery("SELECT stamp, made FROM stage_info WHERE src = ?", arrayOf(src)).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }

    fun stagedMeta(src: String): Map<String, StagedMeta> {
        val out = HashMap<String, StagedMeta>()
        readableDatabase.rawQuery("SELECT xid, cnt, last_stop, placeholders FROM stage_meta WHERE src = ?", arrayOf(src)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = StagedMeta(c.getInt(1), c.getLong(2), c.getInt(3))
        }
        return out
    }

    /** A staged guide's channel list: id, display names, logo. */
    fun stagedChannels(src: String): List<Triple<String, List<String>, String?>> {
        val out = ArrayList<Triple<String, List<String>, String?>>()
        readableDatabase.rawQuery("SELECT xid, names, icon FROM stage_channels WHERE src = ?", arrayOf(src)).use { c ->
            while (c.moveToNext()) out += Triple(
                c.getString(0),
                if (c.isNull(1)) emptyList() else c.getString(1).split('\u001F'),
                if (c.isNull(2)) null else c.getString(2)
            )
        }
        return out
    }

    /** Several staged guide channels' listings (full details), in one pass over the guide. */
    fun stagedProgramsFor(src: String, xids: Collection<String>): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        xids.distinct().chunked(400).forEach { part ->
            readableDatabase.rawQuery(
                "SELECT xid, start, stop, title, descr, cat, ep, icon, year, people FROM stage WHERE src = ? AND xid IN (" +
                    part.joinToString(",") { "?" } + ")",
                (listOf(src) + part).toTypedArray()
            ).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() } += EpgProgram(
                    startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                    description = if (c.isNull(4)) null else c.getString(4),
                    category = if (c.isNull(5)) null else c.getString(5),
                    episode = if (c.isNull(6)) null else c.getString(6),
                    icon = if (c.isNull(7)) null else c.getString(7),
                    year = if (c.isNull(8)) null else c.getInt(8),
                    people = if (c.isNull(9)) emptyList() else c.getString(9).split('\u001F')
                )
            }
        }
        out.values.forEach { l -> l.sortBy { it.startMs } }
        return out
    }

    /** One staged guide channel's listings (full details), in time order. */
    fun stagedPrograms(src: String, xid: String): List<EpgProgram> {
        val out = ArrayList<EpgProgram>()
        readableDatabase.rawQuery(
            "SELECT start, stop, title, descr, cat, ep, icon, year, people FROM stage WHERE src = ? AND xid = ? ORDER BY start",
            arrayOf(src, xid)
        ).use { c ->
            while (c.moveToNext()) out += EpgProgram(
                startMs = c.getLong(0), stopMs = c.getLong(1), title = c.getString(2),
                description = if (c.isNull(3)) null else c.getString(3),
                category = if (c.isNull(4)) null else c.getString(4),
                episode = if (c.isNull(5)) null else c.getString(5),
                icon = if (c.isNull(6)) null else c.getString(6),
                year = if (c.isNull(7)) null else c.getInt(7),
                people = if (c.isNull(8)) emptyList() else c.getString(8).split('\u001F')
            )
        }
        return out
    }

    /** A staged guide's listings in [fromMs, toMs), short form (no descriptions), by guide channel. */
    fun stagedWindow(src: String, fromMs: Long, toMs: Long): Map<String, List<EpgProgram>> {
        val out = HashMap<String, ArrayList<EpgProgram>>()
        readableDatabase.rawQuery(
            "SELECT xid, start, stop, title, ep, icon FROM stage WHERE src = ? AND stop > ? AND start < ?",
            arrayOf(src, fromMs.toString(), toMs.toString())
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { ArrayList() } += EpgProgram(
                startMs = c.getLong(1), stopMs = c.getLong(2), title = c.getString(3),
                episode = if (c.isNull(4)) null else c.getString(4),
                icon = if (c.isNull(5)) null else c.getString(5)
            )
        }
        return out
    }

    /** Guides no longer used: their staged listings go. */
    fun dropStagesExcept(keep: Collection<String>) {
        val db = writableDatabase
        if (keep.isEmpty()) {
            db.beginTransaction()
            try {
                db.delete("stage", null, null)
                db.delete("stage_meta", null, null)
                db.delete("stage_channels", null, null)
                db.delete("stage_info", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return
        }
        val stale = ArrayList<String>()
        db.rawQuery("SELECT src FROM stage_info", null).use { c -> while (c.moveToNext()) stale += c.getString(0) }
        db.rawQuery("SELECT DISTINCT src FROM stage_meta", null).use { c -> while (c.moveToNext()) stale += c.getString(0) }
        stale.distinct().filter { it !in keep }.forEach { src ->
            db.delete("stage", "src = ?", arrayOf(src))
            db.delete("stage_meta", "src = ?", arrayOf(src))
            db.delete("stage_channels", "src = ?", arrayOf(src))
            db.delete("stage_info", "src = ?", arrayOf(src))
        }
    }

    /**
     * Replaces the whole guide from the staged guides in one step: each channel in [choice] gets
     * the listings of its chosen guide channel (source, guide id) in [fromMs, toMs); [extra] are
     * listings given directly (hand-picked guides). Like [replaceAll], in one transaction.
     */
    fun replaceAllFromStage(
        fingerprint: String,
        choice: Map<String, EpgAssignment>,
        extra: Map<String, List<EpgProgram>>,
        auto: Map<String, EpgAssignment>,
        parts: String?,
        fromMs: Long,
        toMs: Long
    ) {
        changes++
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DROP INDEX IF EXISTS programs_ch_start")
                db.execSQL("DROP INDEX IF EXISTS programs_stop")
                db.execSQL("DROP INDEX IF EXISTS programs_start")
                db.delete("programs", null, null)
                db.delete("auto", null, null)
                db.execSQL("DROP TABLE IF EXISTS temp.choice")
                db.execSQL("CREATE TEMP TABLE choice (src TEXT NOT NULL, xid TEXT NOT NULL, ch TEXT NOT NULL, PRIMARY KEY (src, xid, ch))")
                val ins = db.compileStatement("INSERT OR IGNORE INTO temp.choice (src, xid, ch) VALUES (?,?,?)")
                choice.forEach { (ch, a) ->
                    ins.clearBindings(); ins.bindString(1, a.sourceId); ins.bindString(2, a.xmltvId); ins.bindString(3, ch)
                    ins.executeInsert()
                }
                db.execSQL(
                    "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) " +
                        "SELECT c.ch, s.start, s.stop, s.title, s.descr, s.cat, s.ep, s.icon, s.year, s.people " +
                        "FROM stage s JOIN temp.choice c ON c.src = s.src AND c.xid = s.xid WHERE s.stop > ? AND s.start < ?",
                    arrayOf<Any>(fromMs, toMs)
                )
                db.execSQL("DROP TABLE IF EXISTS temp.choice")
                val stmt = db.compileStatement(
                    "INSERT INTO programs (ch, start, stop, title, descr, cat, ep, icon, year, people) VALUES (?,?,?,?,?,?,?,?,?,?)"
                )
                extra.forEach { (ch, list) ->
                    list.forEach { p ->
                        stmt.clearBindings()
                        stmt.bindString(1, ch); stmt.bindLong(2, p.startMs); stmt.bindLong(3, p.stopMs); stmt.bindString(4, p.title)
                        p.description?.let { stmt.bindString(5, it) } ?: stmt.bindNull(5)
                        p.category?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                        p.episode?.let { stmt.bindString(7, it) } ?: stmt.bindNull(7)
                        p.icon?.let { stmt.bindString(8, it) } ?: stmt.bindNull(8)
                        p.year?.let { stmt.bindLong(9, it.toLong()) } ?: stmt.bindNull(9)
                        if (p.people.isEmpty()) stmt.bindNull(10) else stmt.bindString(10, p.people.joinToString("\u001F"))
                        stmt.executeInsert()
                    }
                }
                auto.forEach { (ch, a) ->
                    db.insertWithOnConflict("auto", null, ContentValues().apply {
                        put("ch", ch); put("source", a.sourceId); put("xmltv", a.xmltvId)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_ch_start ON programs(ch, start)")
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_stop ON programs(stop)")
                db.execSQL("CREATE INDEX IF NOT EXISTS programs_start ON programs(start)")
                putFingerprint(db, fingerprint, parts)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            changes++
        }
    }

    // ------------------------------------------------------------ sports shows
    //
    // Test builds: the guide's shows that name a sport, a competition or a team (with their text
    // already cleaned up for matching), so Sports' channel search reads a few thousand rows instead
    // of the whole guide. Built by SportsMatcher from one read of the guide, and only good for the
    // guide [revision] it was built from. Its tables are made the first time they're used (no
    // database version change, so an older app version still opens this database).

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

    /** Gives back space left empty by deleted listings (see [SqliteCompact]). */
    fun compact(): Long = SqliteCompact.compactIfWasteful(writableDatabase, "guide")

    fun clear() {
        changes++
        try {
            val db = writableDatabase
            db.delete("programs", null, null)
            db.delete("auto", null, null)
            db.delete("meta", null, null)
            runCatching { db.delete("stage", null, null); db.delete("stage_meta", null, null); db.delete("stage_channels", null, null); db.delete("stage_info", null, null) }
            runCatching { db.delete("meta", "k = ?", arrayOf("sports_rows")); db.delete("sports_rows", null, null); db.delete("sports_words", null, null) }
        } finally {
            changes++
        }
    }
}

/** Longest single listing assumed when reading a time window (longer ones are very rare). */
private const val MAX_SHOW_MS = 6L * 60 * 60 * 1000

/** Sports shows written to their table at a time (see [EpgDatabase.SportsRowsWriter]). */
private const val BATCH = 2_000
