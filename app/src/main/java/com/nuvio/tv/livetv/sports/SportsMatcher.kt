package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.CatchupUrlBuilder
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.sports.SportsCatalog.hasWord
import com.nuvio.tv.livetv.sports.SportsCatalog.norm
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finds which of your channels show each game, in four ways (best first):
 *  1. your TV guide: a show around kick-off naming both teams (or the event),
 *  2. the channel's own name ("NFL 03: Bears @ Lions", common for event channels),
 *  3. the network ESPN lists (FOX, ESPN, TNT…) with a sports show on at the time,
 *  4. channels you picked yourself with Fix Match (and never the ones you said were wrong).
 * Works for any country: UK users get Sky Sports / TNT Sports from their own guide.
 */
internal class SportsMatcher(
    private val db: EpgDatabase,
    /** Where what was found in the guide is saved, so a restart with the same guide doesn't search it all again. */
    private val dir: File? = null,
    /**
     * Test builds: read the guide's sports-shows table instead of the whole guide (see
     * [prepareTable]); after each rebuild the whole guide is read once more and the two compared.
     */
    private val sportsTable: Boolean = false
) {

    class Input(
        val events: List<SportsEvent>,
        /** Channels you can see (not hidden, not locked). */
        val channels: List<LiveChannel>,
        /** Channel key -> picture quality seen ("4K", "FHD", "HD", "SD"). */
        val quality: Map<String, String>,
        /** Your guide time shift (Live TV settings), in ms: the database keeps the guide's own times. */
        val shiftMs: Long,
        val settings: SportsSettings,
        val now: Long,
        /** Nobody is on the Sports page: take short breaks while reading the guide so the box stays smooth. */
        val gentle: Boolean = false
    )

    /** One guide find from step 1, kept to be used again while the guide and the half-hour's games stay the same. */
    private class SlotFind(val eventId: String, val key: String, val score: Int, val how: String, val program: EpgProgram)
    @Volatile private var slotCacheKey = 0L
    @Volatile private var slotCache: Map<Long, List<SlotFind>> = emptyMap()
    /** The saved copy has been looked at (once per app start), and which cache it holds. */
    @Volatile private var slotsLoaded = false
    @Volatile private var savedSlotsKey = 0L

    /** What step 1's result for a half-hour depends on: its games (who, when, where) and the words looked for. */
    private fun slotSignature(gs: GuideSlot, discIds: Set<String>): Long {
        var h = gs.fromMs * 31 + gs.toMs
        h = h * 31 + gs.minStopMs
        for (ev in gs.slot.sortedBy { it.id }) {
            h = h * 31 + ev.id.hashCode(); h = h * 31 + ev.startMs; h = h * 31 + ev.title.hashCode()
            h = h * 31 + ev.leagueId.hashCode(); h = h * 31 + (ev.subtitle?.hashCode() ?: 0)
            for (sd in ev.sides) { h = h * 31 + sd.team.id.hashCode(); h = h * 31 + sd.team.name.hashCode(); h = h * 31 + sd.team.fullName.hashCode() }
            if (ev.id in discIds) h = h * 31 + 7
        }
        for (w in gs.words.sorted()) h = h * 31 + w.hashCode()
        return h
    }

    class Result(val matches: Map<String, List<ChannelMatch>>, val summary: String)

    private class Found(val key: String, var score: Int, var how: String, var program: EpgProgram?)

    /** Disc golf is its own sport: its events keep their matches, and no other event takes its shows. */
    private fun isDiscGolf(ev: SportsEvent): Boolean =
        hasWord(norm(ev.title), "disc golf") || ev.leagueId.contains("disc", ignoreCase = true)

    /** Runs [block] over the channels (in list order) whose name has one of [tokens] as a word. */
    private inline fun forNamed(
        index: Map<String, List<Int>>,
        tokens: Set<String>,
        names: List<Pair<LiveChannel, String>>,
        block: (LiveChannel, String) -> Unit
    ) {
        val hits = java.util.BitSet(names.size)
        for (tok in tokens) index[tok]?.forEach { hits.set(it) }
        var i = hits.nextSetBit(0)
        while (i >= 0) {
            val (ch, nn) = names[i]
            block(ch, nn)
            i = hits.nextSetBit(i + 1)
        }
    }

    /** Channel names worked out once per channel list and kept between searches (they rarely change). */
    private class NameData(
        val fingerprint: Long,
        val names: List<Pair<LiveChannel, String>>,
        val tokenIndex: Map<String, List<Int>>,
        val discNames: Set<String>
    )
    @Volatile private var nameData: NameData? = null

    private fun nameData(channels: List<LiveChannel>): NameData {
        // The channel list is rebuilt for every search, so it's recognised by its contents.
        var fp = channels.size.toLong()
        for (ch in channels) fp = fp * 31 + ch.key.hashCode() * 17L + ch.name.hashCode()
        nameData?.let { if (it.fingerprint == fp) return it }
        // Separator "channels" ("##### USA SPORTS #####", "==== UK ====") are never a game's channel.
        // No minimum length any more: short real names ("FS1", "TNT", "TSN1", "ESPN") used to be
        // dropped here and could never be found by channel-name or network matching. isBanner()
        // alone still catches separator rows (it needs a run of symbols, or mostly-symbol text).
        val names = channels.mapNotNull { ch ->
            if (isBanner(ch.name)) null
            else ch to norm(ch.name.replace("+", " plus ").replace("@", " at "))
        }
        // Channel names by word, so a game's channels are found without going through every channel.
        val tokenIndex = HashMap<String, MutableList<Int>>()
        names.forEachIndexed { i, (_, nn) ->
            nn.split(' ').filter { it.isNotEmpty() }.distinct().forEach { tok -> tokenIndex.getOrPut(tok) { ArrayList() }.add(i) }
        }
        val discNames = names.filter { (_, nn) -> hasWord(nn, "disc golf") }.mapTo(HashSet()) { it.first.key }
        return NameData(fp, names, tokenIndex, discNames).also { nameData = it }
    }

    fun match(input: Input): Result {
        val byKey = input.channels.associateBy { it.key }
        val rank = preferenceRank(input.settings)
        val tStart = System.currentTimeMillis()
        val nd0 = nameData(input.channels)
        val names = nd0.names
        val tokenIndex = nd0.tokenIndex
        val discNames = nd0.discNames
        val discIds = input.events.filter { isDiscGolf(it) }.mapTo(HashSet()) { it.id }
        val found = HashMap<String, HashMap<String, Found>>()
        fun add(eventId: String, key: String, score: Int, how: String, program: EpgProgram?) {
            if (key !in byKey || score <= 0) return
            val m = found.getOrPut(eventId) { HashMap() }
            val cur = m[key]
            if (cur == null || score > cur.score) m[key] = Found(key, score, how, program ?: cur?.program)
            else if (cur.program == null && program != null) cur.program = program
        }

        // Events that run for days (a golf tournament, an F1 weekend from practice to the race) have
        // no single start time: while they're on, treat them as starting now.
        fun multiDay(ev: SportsEvent) = !ev.hasTime && ev.endMs != null
        // Race weekends now carry each session's own time, but are still named only by the
        // competition ("Formula 1", "Grand Prix"), so they're matched the same careful way.
        fun raceWeekend(ev: SportsEvent) = ev.source == EventSource.ESPN &&
            SportsCatalog.league(ev.leagueId)?.let { it.kind == LeagueKind.EVENT && it.sport == Sport.RACING } == true
        // Their ids: with no start time of their own, only a show that names the competition ("Formula 1",
        // "Grand Prix"…) counts, not one that merely shares a word or two with the title ("United States").
        val noStart = HashSet<String>()
        val events = input.events.mapNotNull { ev ->
            when {
                ev.hasTime -> ev
                !multiDay(ev) -> null
                // The feed's end date is the start of the last day (Sunday 00:00): the event runs through that day.
                ev.endMs!! + 24 * HOUR < input.now -> null
                ev.startMs >= input.now -> ev
                else -> ev.copy(startMs = input.now)
            }?.also { if (!ev.hasTime || raceWeekend(ev)) noStart.add(ev.id) }
        }
        val wanted = events.filter { ev ->
            ev.state != GameState.OFF &&
                ev.startMs in (input.now - 8 * HOUR)..(input.now + AHEAD_MS)
        }
        // A channel you picked yourself shows straight away, however far off the game is (the
        // search above only looks a couple of days ahead).
        val picked = events.filter { ev ->
            ev.state != GameState.OFF && ev.startMs >= input.now - 8 * HOUR &&
                (ev.id in input.settings.eventChannels || ev.leagueId in input.settings.leagueChannels ||
                    (input.settings.teamChannels.isNotEmpty() && ev.sides.any { teamChannelKey(ev.leagueId, it.team.id) in input.settings.teamChannels }))
        }
        val byId = (wanted + picked).associateBy { it.id }
        var byGuide = 0; var byName = 0; var byNetwork = 0

        // Shows found in the guide already know their channels.
        wanted.filter { it.source == EventSource.GUIDE }.forEach { ev ->
            ev.guideHits.forEach { (key, p) -> add(ev.id, key, 100, "Guide: ${p.title}", p) }
        }

        val teamEvents = wanted.filter { it.source != EventSource.GUIDE }
        // Fights found in the guide ("Boxing : Canelo Alvarez vs. Christian Mbilli") are also looked
        // for by channel name: their event channels ("Canelo v Mbilli") usually have no listings.
        val guideFights = wanted.filter { ev ->
            ev.source == EventSource.GUIDE && SportsCatalog.league(ev.leagueId)?.sport == Sport.FIGHTING &&
                SportsCatalog.fightSides(ev.title) != null
        }
        val terms = (teamEvents + guideFights).associate { ev -> ev.id to termsFor(ev) }

        // Tournaments and race weekends running at the same time (several tennis events, two golf
        // tours): a channel or show that just says "Tennis" or "Golf" can't tell them apart.
        // Each one's own words ("basel", "bermuda") pick it out; the competition's words only
        // count when no other event running now uses them.
        val multiEvents = teamEvents.filter { it.id in noStart }
        val specific: Map<String, Set<String>> = multiEvents.associate { ev ->
            val league = terms[ev.id]?.leagueWords.orEmpty().flatMap { it.split(' ') }.toSet()
            ev.id to norm(ev.title).trim().split(' ')
                .filter { it.length >= 4 && it.any { c -> c.isLetter() } && it !in GENERIC_TOURNAMENT_WORDS && it !in league }
                .toSet()
        }
        val leagueWordUse = HashMap<String, Int>()
        multiEvents.forEach { ev -> terms[ev.id]?.leagueWords.orEmpty().toSet().forEach { leagueWordUse[it] = (leagueWordUse[it] ?: 0) + 1 } }
        fun specificHits(evId: String, text: String) = specific[evId].orEmpty().count { hasWord(text, it) }
        // A word usually too common to tell events apart ("Finals", "Masters") that only one event
        // running now has in its name picks that one out ("WTA Finals" while five other WTA events
        // are on), when the show also names that event's own competition ("WTA", not just "Tennis").
        val ownLeague: Map<String, Set<String>> = multiEvents.associate { ev ->
            ev.id to terms[ev.id]?.leagueWords.orEmpty().filter { w ->
                multiEvents.none { o -> o.leagueId != ev.leagueId && w in terms[o.id]?.leagueWords.orEmpty() }
            }.toSet()
        }
        val titleWordUse = HashMap<String, Int>()
        multiEvents.forEach { ev -> norm(ev.title).trim().split(' ').toSet().forEach { titleWordUse[it] = (titleWordUse[it] ?: 0) + 1 } }
        val rare: Map<String, Set<String>> = multiEvents.associate { ev ->
            ev.id to norm(ev.title).trim().split(' ').filter { it in RARE_TOURNAMENT_WORDS && titleWordUse[it] == 1 }.toSet()
        }
        fun rareHit(evId: String, text: String) =
            rare[evId].orEmpty().any { hasWord(text, it) } && ownLeague[evId].orEmpty().any { hasWord(text, it) }
        /** The competition named by a word only this event uses ("pga tour" while the DP World Tour is on too). */
        fun distinctLeague(evId: String, text: String) = terms[evId]?.leagueWords.orEmpty()
            .any { it != "grand prix" && (leagueWordUse[it] ?: 0) <= 1 && hasWord(text, it) }
        /** The text names another event of the same kind running now ("Vienna" for the Basel tournament). */
        fun namesOther(evId: String, text: String): Boolean {
            val mine = terms[evId]?.leagueWords.orEmpty().toSet()
            return multiEvents.any { o ->
                o.id != evId && terms[o.id]?.leagueWords.orEmpty().any { it in mine } && specificHits(o.id, text) > 0
            }
        }

        // Games whose channel you already linked (this game, its team, its league or one of its
        // networks, on a channel still in your playlists) skip the guide search below: it's the
        // slow part on low-end boxes, and your link decides the channel anyway. Channel names and
        // networks are still checked for them (cheap), so other channels showing it still turn up.
        val linked = teamEvents.filter { ev ->
            val st = input.settings
            fun inList(k: String?) = k != null && k in byKey
            inList(st.eventChannels[ev.id]?.substringBeforeLast('|')) || inList(st.leagueChannels[ev.leagueId]) ||
                (st.teamChannels.isNotEmpty() && ev.sides.any { inList(st.teamChannels[teamChannelKey(ev.leagueId, it.team.id)]) }) ||
                (st.networkChannels.isNotEmpty() && ev.broadcasts.any { inList(st.networkChannels[networkKey(it)]) })
        }.mapTo(HashSet()) { it.id }

        // ---- 1. The guide, a half-hour slot at a time.
        // The guide is read once for all the slots (it used to be searched again for every slot,
        // which on a slower box took minutes); each show is checked against every slot's words as it goes by.
        val guideSlots = teamEvents.filter { it.id !in linked }.groupBy { it.startMs / (30 * MINUTE) }.values.mapNotNull { slot ->
            val slotStart = slot.minOf { it.startMs }
            val slotEnd = slot.maxOf { it.startMs }
            val words = slot.flatMap { ev -> terms[ev.id]?.sqlWords.orEmpty() }.map { it.trim() }.filter { it.length >= 3 }.toSet()
            if (words.isEmpty()) null
            else GuideSlot(
                slot = slot,
                fromMs = slotStart - 100 * MINUTE - input.shiftMs,
                toMs = slotEnd + 70 * MINUTE - input.shiftMs,
                minStopMs = slotStart + 10 * MINUTE - input.shiftMs,
                words = words
            )
        }
        // Half-hours whose games haven't changed since the last search, against the same guide
        // and channels: what the guide gave them then still holds, so only new or changed ones
        // (and race weekends / tournaments, which depend on what else is on) are searched. That
        // also covers games that found nothing: they aren't searched again until something changes.
        val guideAt = db.changes
        // The guide's own stamp, kept across restarts: what was found last time (saved) still holds
        // after a restart while the guide is the same.
        val rev = runCatching { db.revision() }.getOrNull()
        val cacheKey = ((rev ?: guideAt) * 1_000_003L + nd0.fingerprint) * 31 + input.shiftMs
        if (!slotsLoaded) {
            slotsLoaded = true
            if (rev != null && slotCacheKey != cacheKey) loadSlots(cacheKey)?.let {
                slotCache = it; slotCacheKey = cacheKey; savedSlotsKey = cacheKey
            }
        }
        val prevCache = if (cacheKey == slotCacheKey) slotCache else emptyMap()
        val newCache = HashMap<Long, List<SlotFind>>()
        val toScan = ArrayList<GuideSlot>()
        val toScanSig = ArrayList<Long?>()
        var reused = 0
        for (gs in guideSlots) {
            val cacheable = gs.slot.none { it.id in noStart }
            val sig = if (cacheable) slotSignature(gs, discIds) else null
            val kept = sig?.let { prevCache[it] }
            if (kept != null) {
                kept.forEach { f -> add(f.eventId, f.key, f.score, f.how, f.program) }
                newCache[sig!!] = kept
                reused++
            } else {
                toScan += gs
                toScanSig += sig
            }
        }
        var scanned = true
        val slotHits = runCatching { guideHitsBySlot(toScan, input.gentle, rev) }
            .getOrElse { scanned = false; toScan.map { emptyList() } }
        toScan.forEachIndexed { slotIndex, gs ->
            val slot = gs.slot
            val hits = slotHits[slotIndex]
            val finds = ArrayList<SlotFind>()
            // (Not kept when the guide couldn't be read: nothing found then doesn't mean nothing's there.)
            if (scanned) toScanSig[slotIndex]?.let { newCache[it] = finds }
            if (hits.isEmpty()) return@forEachIndexed
            val prepared = hits.mapNotNull { (key, p0) ->
                val ch = byKey[key] ?: return@mapNotNull null
                val p = if (input.shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + input.shiftMs, stopMs = p0.stopMs + input.shiftMs)
                if (fillerOrLoop(ch, p)) return@mapNotNull null
                // The guide's own label for studio shows and replays, when it has one.
                val cat = norm(p.category.orEmpty())
                if (hasWord(cat, "sports non event") || hasWord(cat, "classic sport event")) return@mapNotNull null
                // Many guides put the match-up in the episode title ("MLB Baseball" / "Cubs at
                // Cardinals"); it's the first line of the description here: read it with the title.
                val desc = p.description.orEmpty()
                val sub = if ('\n' in desc) desc.substringBefore('\n').takeIf { it.length <= 90 } else null
                val nt = norm(p.title + (sub?.let { " $it" } ?: ""))
                if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) return@mapNotNull null
                Triple(key, p, nt to norm(desc))
            }
            for (ev in slot) {
                val t = terms[ev.id] ?: continue
                for ((key, p, texts) in prepared) {
                    val (nt, nd) = texts
                    // Disc golf shows never count for regular golf.
                    if (ev.id !in discIds && (hasWord(nt, "disc golf") || hasWord(nd, "disc golf"))) continue
                    if (p.startMs > ev.startMs + 60 * MINUTE || p.stopMs < ev.startMs + 15 * MINUTE) continue
                    var score = t.scoreText(nt, nd)
                    // No start time (a race weekend, a tournament): a show on now that names this
                    // event, or the competition when nothing else of its kind is on.
                    if (ev.id in noStart) {
                        val hits = specificHits(ev.id, nt) + specificHits(ev.id, nd)
                        if (!t.namesLeague(nt, nd) && hits < 2) continue
                        when {
                            score > 0 -> Unit
                            hits >= 1 -> score = 88
                            namesOther(ev.id, nt) || namesOther(ev.id, nd) -> continue
                            rareHit(ev.id, nt) -> score = 75
                            distinctLeague(ev.id, nt) || distinctLeague(ev.id, nd) -> score = 70
                            else -> continue
                        }
                    }
                    if (score <= 0) continue
                    // Pre-game shows often start an hour early on the same channel; much earlier or later is less likely the game.
                    if (p.startMs < ev.startMs - 80 * MINUTE || p.startMs > ev.startMs + 25 * MINUTE) score -= 10
                    add(ev.id, key, score, "Guide: ${p.title}", p)
                    if (score > 0) finds += SlotFind(ev.id, key, score, "Guide: ${p.title}", p)
                }
            }
        }
        // The guide changed while it was being read: keep nothing, search it all next time.
        if (db.changes == guideAt) {
            // Saved when its half-hours changed (or it's for another guide), for the next app start.
            if (rev != null && (savedSlotsKey != cacheKey || newCache.keys != prevCache.keys)) {
                saveSlots(cacheKey, newCache); savedSlotsKey = cacheKey
            }
            slotCache = newCache; slotCacheKey = cacheKey
        }
        else { slotCache = emptyMap(); slotCacheKey = 0L }
        byGuide = found.size
        val tGuide = System.currentTimeMillis()

        // ---- 2. The channel's own name.
        for (ev in teamEvents + guideFights) {
            if (ev.state == GameState.POST && ev.startMs < input.now - 5 * HOUR) continue
            val t = terms[ev.id] ?: continue
            val discEvent = ev.id in discIds
            // Only channels whose name has one of the game's words can match: look at those, not
            // all of them (with tens of thousands of channels, every game against every channel took minutes).
            forNamed(tokenIndex, t.anchorTokens(), names) { ch, nn ->
                if (!discEvent && ch.key in discNames) return@forNamed
                if (t.inName(nn)) { add(ev.id, ch.key, 95, "Channel name", null); byName++ }
                // A channel named for the event ("Tennis: Swiss Indoors Basel") or for the competition
                // ("Sky Sports F1") when nothing else of its kind is on.
                else if (ev.id in noStart) {
                    val hits = specificHits(ev.id, nn)
                    when {
                        (hits >= 1 && t.leagueInName(nn)) || hits >= 2 -> { add(ev.id, ch.key, 90, "Channel name", null); byName++ }
                        !namesOther(ev.id, nn) && rareHit(ev.id, nn) -> { add(ev.id, ch.key, 85, "Channel name", null); byName++ }
                        !namesOther(ev.id, nn) && distinctLeague(ev.id, nn) -> { add(ev.id, ch.key, 78, "Channel name", null); byName++ }
                    }
                }
            }
        }

        val tNames = System.currentTimeMillis()

        // ---- 3. The network ESPN lists, with a sports show on at the time.
        val networkEvents = teamEvents.filter { it.broadcasts.isNotEmpty() && it.startMs > input.now - 5 * HOUR }
        val candidates = HashMap<String, List<LiveChannel>>()
        networkEvents.flatMap { it.broadcasts }.map { networkKey(it) }.distinct().forEach { n ->
            val mapped = NETWORKS[n]
            // A network not on the hand-built list is usually a regional sports network (ESPN's
            // broadcasts say "Bally Sports Detroit", "FanDuel Sports Network - Detroit", "NESN",
            // "MSG Network"...). Requiring the whole phrase, as one unit, almost never matches a
            // real channel name; requiring every distinctive word in it (dropping "sports",
            // "network" and the like) does, and needs no per-network brand list to keep working
            // through a rebrand.
            val words = if (mapped == null) n.split(' ').filter { it.length >= 3 && it !in GENERIC_NETWORK_WORDS } else emptyList()
            val pats = mapped ?: (words.ifEmpty { listOf(n).filter { it.length >= 3 } })
            if (pats.isEmpty()) return@forEach
            val not = NETWORK_NOT[n].orEmpty()
            val list = ArrayList<LiveChannel>()
            val anchors = pats.mapNotNull { it.trim().substringBefore(' ').takeIf { w -> w.isNotEmpty() } }.toSet()
            forNamed(tokenIndex, anchors, names) { ch, nn ->
                val isMatch = if (mapped != null) pats.any { hasWord(nn, it) } else pats.all { hasWord(nn, it) }
                if (isMatch && not.none { hasWord(nn, it) }) list += ch
            }
            candidates[n] = list
        }
        networkEvents.groupBy { it.startMs / (30 * MINUTE) }.forEach { (_, slot) ->
            val at = slot.minOf { it.startMs } + 20 * MINUTE
            // Your own channel for a network counts even when no channel is named like it (Sky
            // Sports F1 for "Apple TV"): nothing to look through, but the pick still applies.
            for (ev in slot) for (b in ev.broadcasts) {
                input.settings.networkChannels[networkKey(b)]?.let { key -> add(ev.id, key, 75, "Your channel for $b", null) }
            }
            val keys = slot.flatMap { ev -> ev.broadcasts.flatMap { candidates[networkKey(it)].orEmpty() } }.map { it.key }.distinct()
            if (keys.isEmpty()) return@forEach
            val onNow: Map<String, EpgProgram?> = runCatching {
                db.range(at - input.shiftMs, at - input.shiftMs + 1, light = true, channels = keys)
                    .mapValues { (_, l) -> l.firstOrNull()?.let { p -> p.copy(startMs = p.startMs + input.shiftMs, stopMs = p.stopMs + input.shiftMs) } }
            }.getOrDefault(emptyMap())
            for (ev in slot) {
                val league = SportsCatalog.league(ev.leagueId) ?: continue
                val sporty = (league.guideWords + SPORT_WORDS).map { norm(it).trim() }
                for (b in ev.broadcasts) {
                    val n = networkKey(b)
                    input.settings.networkChannels[n]?.let { key -> add(ev.id, key, 75, "Your channel for $b", onNow[key]) }
                    // No start time (a race weekend, a tournament): the network's listing says nothing about what's on now.
                    if (ev.id in noStart) continue
                    val national = n in NATIONAL
                    candidates[n].orEmpty()
                        .map { ch ->
                            val p = onNow[ch.key]
                            val showText = p?.let { norm(it.title + " " + (it.episode ?: "")) }
                            val score = when {
                                ev.id !in discIds && showText != null && hasWord(showText, "disc golf") -> 0
                                p == null -> if (keys.size < 400 && national) 45 else 0
                                // The guide says it's a different game: not this one.
                                terms[ev.id]?.otherGame(showText!!) == true -> 0
                                sporty.any { hasWord(norm(p.title), it) } && SportsCatalog.NOT_LIVE_WORDS.none { hasWord(norm(p.title), it) } ->
                                    if (national) 62 else 52
                                else -> 0
                            }
                            Triple(ch, score, p)
                        }
                        .filter { it.second > 0 }
                        .sortedWith(
                            compareByDescending<Triple<LiveChannel, Int, EpgProgram?>> { rank(it.first) }
                                .thenByDescending { qualityRank(input.quality[it.first.key] ?: nameQuality(it.first.name)) }
                        )
                        // More than the top few: their FHD/HD/SD copies (one row on the game page)
                        // and the same network from other providers, as backups if one freezes.
                        .take(if (national) 8 else 12)
                        .forEach { (ch, score, p) -> add(ev.id, ch.key, score, "$b listed by ESPN", p); byNetwork++ }
                }
            }
        }

        // ---- 4. Your own fixes: this game, then every game of a team.
        for (ev in byId.values) {
            input.settings.eventChannels[ev.id]?.substringBeforeLast('|')?.let { add(ev.id, it, 200, "Your pick", null) }
            // Your channel for the whole competition (every Formula 1 round, every PGA Tour event).
            input.settings.leagueChannels[ev.leagueId]?.let { add(ev.id, it, 140, "Your channel for ${SportsCatalog.league(ev.leagueId)?.name ?: "this competition"}", null) }
            if (input.settings.teamChannels.isNotEmpty()) for (side in ev.sides) {
                input.settings.teamChannels[teamChannelKey(ev.leagueId, side.team.id)]?.let { add(ev.id, it, 150, "Your channel for ${side.team.name}", null) }
            }
        }

        val out = HashMap<String, List<ChannelMatch>>()
        for ((eventId, m) in found) {
            val ev = byId[eventId] ?: continue
            val list = m.values
                .filter { "$eventId|${it.key}" !in input.settings.blocked }
                .mapNotNull { f ->
                    val ch = byKey[f.key] ?: return@mapNotNull null
                    ChannelMatch(
                        channel = ch,
                        score = f.score,
                        how = f.how,
                        program = f.program,
                        quality = input.quality[ch.key] ?: nameQuality(ch.name),
                        catchup = ch.catchup != null && CatchupUrlBuilder.isAvailable(ch.catchup, f.program?.startMs ?: ev.startMs, input.now)
                    )
                }
            if (list.isEmpty()) continue
            // Every channel the guide has for it (FHD/HD/SD copies are folded into one row on the
            // game page, other regions under "+ N from other regions"), so nothing the guide shows
            // is left out. 40 is only a guard against a runaway list; it costs no extra searching.
            out[eventId] = sortMatches(list, input.settings.bestChannelFirst, rank).take(40)
        }
        // Events that run for days (golf, race weekends): what was looked for and found, for the report.
        val multi = teamEvents.filter { it.id in noStart }.joinToString(" | ") { ev ->
            val t = terms[ev.id]
            "${ev.title.take(32)}: looked for ${t?.leagueWords?.joinToString("/")}, " +
                "channels named like that ${names.count { (_, nn) -> t?.leagueInName(nn) == true }}, channels found (names + guide) ${out[ev.id]?.size ?: 0}"
        }
        val tEnd = System.currentTimeMillis()
        val summary = "Sports: ${wanted.size} games and events in the next day or so · channels found for ${out.size} " +
            "(guide $byGuide, channel names $byName, networks $byNetwork, ${linked.size} linked games skipped the guide, " +
            "$reused of ${guideSlots.size} half-hours unchanged) · ${input.channels.size} channels searched in " +
            "${(tEnd - tStart) / 1000} s (guide ${(tGuide - tStart) / 1000}, names ${(tNames - tGuide) / 1000}, networks ${(tEnd - tNames) / 1000})" +
            (if (multi.isNotEmpty()) " · multi-day: $multi" else "") + takeTableNotes()
        return Result(out, summary)
    }

    /**
     * Shows in the guide for sports ESPN doesn't cover (darts, snooker, rugby…) and names you
     * follow: live shows on [dayStart, dayEnd) whose title says so.
     */
    fun guideEvents(
        dayStart: Long,
        dayEnd: Long,
        leagues: List<League>,
        followedWords: List<String>,
        channels: Map<String, LiveChannel>,
        shiftMs: Long,
        now: Long
    ): List<SportsEvent> = guideEventsDays(listOf(dayStart to dayEnd), leagues, followedWords, channels, shiftMs, now)[dayStart].orEmpty()

    /** What [guideEventsDays] read from the guide last time, kept while the guide and the words stay the same. */
    private class DayHits(val key: Long, val byDay: Map<Long, List<Pair<String, EpgProgram>>>)
    @Volatile private var dayHits: DayHits? = null
    @Volatile private var daysLoaded = false

    /**
     * [guideEvents] for several days at once, by day start: the guide is read once for all of
     * them (it used to be searched twice a day, for up to 360 words each time), and not at all
     * when it hasn't changed since last time (then only whether each show is on now is worked
     * out again).
     */
    fun guideEventsDays(
        days: List<Pair<Long, Long>>,
        leagues: List<League>,
        followedWords: List<String>,
        channels: Map<String, LiveChannel>,
        shiftMs: Long,
        now: Long
    ): Map<Long, List<SportsEvent>> {
        if (days.isEmpty()) return emptyMap()
        val leagueWords = leagues.flatMap { l -> l.guideWords.map { it to l } }
        val titleWords = leagueWords.map { norm(it.first).trim() }.filter { it.length >= 3 }.toSet()
        val followed = followedWords.map { norm(it).trim() }.filter { it.length >= 3 }.toSet()
        val guideAt = db.changes
        val rev = runCatching { db.revision() }.getOrNull()
        var key = (rev ?: guideAt) * 31 + shiftMs
        for (d in days) key = key * 31 + d.first * 7 + d.second
        for (w in titleWords.sorted()) key = key * 31 + w.hashCode()
        key = key * 17
        for (w in followed.sorted()) key = key * 31 + w.hashCode()
        if (!daysLoaded) {
            daysLoaded = true
            if (rev != null && dayHits?.key != key) loadDays(key)?.let { dayHits = DayHits(key, it) }
        }
        val byDay = dayHits?.takeIf { it.key == key }?.byDay ?: run {
            val out = days.associate { it.first to ArrayList<Pair<String, EpgProgram>>() }
            var ok = true
            if (titleWords.isNotEmpty() || followed.isNotEmpty()) runCatching {
                val firstTitle = HashMap<String, MutableList<String>>()
                titleWords.forEach { w -> firstTitle.getOrPut(w.substringBefore(' ')) { ArrayList() } += w }
                val firstFollowed = HashMap<String, MutableList<String>>()
                followed.forEach { w -> firstFollowed.getOrPut(w.substringBefore(' ')) { ArrayList() } += w }
                fun has(text: String, index: Map<String, List<String>>): Boolean {
                    if (index.isEmpty() || text.length <= 2) return false
                    for (tok in text.split(' ')) {
                        if (tok.isEmpty()) continue
                        val cands = index[tok] ?: continue
                        if (cands.any { hasWord(text, it) }) return true
                    }
                    return false
                }
                val from = days.minOf { it.first } - shiftMs
                val to = days.maxOf { it.second } - shiftMs
                // (A description is only cleaned up when names you follow are looked for in it.)
                val wantDesc = firstFollowed.isNotEmpty()
                fun collect(src: From, into: Map<Long, ArrayList<Pair<String, EpgProgram>>>) =
                    readRows(src, from, to, from, wantDesc, gentle = false) { ch, start, stop, title, descr, cat, nt, nd ->
                        val hit = has(nt, firstTitle) || has(nt, firstFollowed) || (wantDesc && has(nd, firstFollowed))
                        if (!hit) return@readRows
                        for ((ds, de) in days) {
                            if (start >= ds - shiftMs && start < de - shiftMs && stop > ds - shiftMs) {
                                into[ds]?.add(ch to EpgProgram(startMs = start, stopMs = stop, title = title, description = descr, category = cat))
                            }
                        }
                    }
                val src = pickSource(rev, from, to, titleWords + followed)
                val t0 = System.currentTimeMillis()
                collect(src, out)
                if (src == From.TABLE) {
                    val tTable = System.currentTimeMillis() - t0
                    if (checkDays) {
                        checkDays = false
                        val full = days.associate { it.first to ArrayList<Pair<String, EpgProgram>>() }
                        val t1 = System.currentTimeMillis()
                        collect(From.GUIDE, full)
                        val tFull = System.currentTimeMillis() - t1
                        fun ids(m: Map<Long, List<Pair<String, EpgProgram>>>) =
                            m.flatMapTo(HashSet()) { (ds, l) -> l.map { (ch, p) -> "$ds|$ch|${p.startMs}" } }
                        val a = ids(out); val b = ids(full)
                        if (a == b) tableNote("check: guide-only sports and followed names the same (${b.size} shows; table ${tTable} ms, whole guide ${tFull / 1000} s)")
                        else {
                            tableNote("check: guide-only sports DIFFER (table ${a.size} shows, whole guide ${b.size}; " +
                                "missing ${(b - a).take(3).joinToString { it.substringAfter('|') }}); the whole guide's result is used")
                            days.forEach { (ds, _) -> out[ds]?.clear(); out[ds]?.addAll(full[ds].orEmpty()) }
                        }
                    }
                }
            }.onFailure { ok = false }
            val read: Map<Long, List<Pair<String, EpgProgram>>> = out
            // Not kept when the guide couldn't be read, or changed while it was being read.
            if (ok && db.changes == guideAt) {
                dayHits = DayHits(key, read)
                if (rev != null) saveDays(key, read)
            }
            read
        }
        return days.associate { (ds, _) -> ds to guideEventsFrom(byDay[ds].orEmpty(), leagueWords, followedWords, channels, shiftMs, now) }
    }

    private fun guideEventsFrom(
        hits: List<Pair<String, EpgProgram>>,
        leagueWords: List<Pair<String, League>>,
        followedWords: List<String>,
        channels: Map<String, LiveChannel>,
        shiftMs: Long,
        now: Long
    ): List<SportsEvent> {
        val groups = LinkedHashMap<String, MutableList<Pair<String, EpgProgram>>>()
        val meta = HashMap<String, Pair<League?, String?>>()
        val seen = HashSet<String>()
        for ((key, p0) in hits) {
            val ch = channels[key] ?: continue
            if (!seen.add("$key|${p0.startMs}")) continue
            val p = if (shiftMs == 0L) p0 else p0.copy(startMs = p0.startMs + shiftMs, stopMs = p0.stopMs + shiftMs)
            val nt = norm(p.title)
            val nd = norm(p.description.orEmpty())
            if (SportsCatalog.NOT_LIVE_WORDS.any { hasWord(nt, it) }) continue
            // The first listed sport the title names, unless the show is plainly another sport
            // ("Australian Volleyball Super League" isn't rugby league's Super League).
            val league = leagueWords.firstOrNull { (w, l) -> hasWord(nt, norm(w).trim()) && !otherSport(l.sport, nt, nd) }?.second
            // 24/7 and loop channels ("NETFLIX WWE", a movie channel called "… NXT"): not live sport.
            if (fillerOrLoop(ch, p)) continue
            // Wrestling words turn up in other shows' titles ("RuPaul's Drag Race - Lip Sync
            // Smackdown", "The Goldbergs - WrestleMania"): the title must start with the show's
            // name, or name the promotion (WWE, AEW, TNA).
            if (league?.sport == Sport.WRESTLING && !wrestlingTitle(cleanTitle(p.title), league)) continue
            val saysLive = isLive(nt, nd)
            // Wrestling's weekly shows are live without the guide saying so, but not on a channel
            // named after the show or league (those run old shows all day).
            if (!saysLive && (league?.liveByDefault != true || league.guideWords.any { hasWord(nameNorm(ch.name), norm(it).trim()) })) continue
            val word = followedWords.firstOrNull { w -> hasWord(nt, norm(w).trim()) || hasWord(nd, norm(w).trim()) }
            if (league == null && word == null) continue
            val clean = cleanTitle(p.title)
            val g = norm(clean).trim() + "|" + (p.startMs / (15 * MINUTE))
            groups.getOrPut(g) { ArrayList() } += key to p
            val prev = meta[g]
            meta[g] = (prev?.first ?: league) to (prev?.second ?: word)
        }
        return mergeSameSession(groups.map { (g, list) ->
            val p = list.first().second
            val (league, word) = meta[g] ?: (null to null)
            SportsEvent(
                id = "guide:" + g.hashCode().toUInt().toString(16) + ":" + (p.startMs / 60_000),
                leagueId = league?.id ?: OTHER_ID,
                startMs = p.startMs,
                hasTime = true,
                state = when {
                    now >= p.stopMs -> GameState.POST
                    now >= p.startMs -> GameState.LIVE
                    else -> GameState.PRE
                },
                title = cleanTitle(p.title),
                subtitle = p.description?.takeIf { it.isNotBlank() }?.let { d -> d.take(140) + if (d.length > 140) "…" else "" },
                status = null,
                away = null, home = null,
                source = EventSource.GUIDE,
                guideHits = list,
                endMs = p.stopMs,
                word = word
            )
        }, now)
    }

    /**
     * One session listed under different names by different channels ("MotoGP: Malaysia
     * Warm-Up", "MotoGP - Malaysia Practice 3", "(L) MotoGP Malaysia: Warm Up, Round 20"):
     * one entry with every channel. Same sport, on at the same time, and the titles share a
     * word that isn't the sport's own or a session word (here "Malaysia"), so two different
     * games on at once ("Wigan v Leeds", "Hull v Warrington") stay separate. Wrestling has its
     * own merging (SportsRepository) and names you follow are left as they are.
     */
    private fun mergeSameSession(events: List<SportsEvent>, now: Long): List<SportsEvent> {
        if (events.size < 2) return events
        class Run(val league: League, first: SportsEvent) {
            val items = arrayListOf(first)
            val words = HashSet(sessionWords(first.title, league))
            var end = first.endMs ?: first.startMs
        }
        val out = ArrayList<SportsEvent>(events.size)
        val runs = ArrayList<Run>()
        for (ev in events.sortedBy { it.startMs }) {
            val league = SportsCatalog.league(ev.leagueId)
            if (league == null || ev.word != null || league.sport == Sport.WRESTLING) { out += ev; continue }
            val words = sessionWords(ev.title, league)
            val run = if (words.isEmpty()) null else runs.firstOrNull { r ->
                r.league.id == league.id && ev.startMs < r.end && ev.startMs - r.items.first().startMs <= 60 * MINUTE &&
                    words.any { it in r.words }
            }
            if (run == null) {
                runs += Run(league, ev)
            } else {
                run.items += ev
                run.words += words
                run.end = maxOf(run.end, ev.endMs ?: ev.startMs)
            }
        }
        for (r in runs) {
            if (r.items.size == 1) { out += r.items.first(); continue }
            // The first listing (earliest, then by name) names it, so its id stays the same
            // between updates (a channel you picked for it is kept).
            val first = r.items.minWith(compareBy<SportsEvent>({ it.startMs }, { it.title }))
            val start = r.items.minOf { it.startMs }
            val end = r.items.mapNotNull { it.endMs }.maxOrNull()
            out += first.copy(
                startMs = start,
                endMs = end,
                state = when {
                    end != null && now >= end -> GameState.POST
                    now >= start -> GameState.LIVE
                    else -> GameState.PRE
                },
                subtitle = first.subtitle ?: r.items.firstNotNullOfOrNull { it.subtitle },
                guideHits = r.items.flatMap { it.guideHits }.distinctBy { (key, p) -> key + "|" + p.startMs }
            )
        }
        return out.sortedBy { it.startMs }
    }

    /** A title's words that tell one session or game from another (not the sport's own words, session words or numbers). */
    private fun sessionWords(title: String, league: League): Set<String> {
        val own = league.guideWords.flatMapTo(HashSet()) { norm(it).trim().split(' ') }
        return norm(title).trim().split(' ').filterTo(HashSet()) { w ->
            w.length >= 3 && w.any { it.isLetter() } && w !in own && w !in GENERIC_TOURNAMENT_WORDS && w !in SESSION_WORDS
        }
    }

    /** The show is plainly a different sport from [sport]: its title (or, failing that, description) names another sport and never its own. */
    private fun otherSport(sport: Sport, nt: String, nd: String): Boolean {
        fun names(text: String, s: Sport) = SPORT_NAMES[s].orEmpty().any { hasWord(text, it) }
        fun another(text: String) = SPORT_NAMES.keys.any { it != sport && names(text, it) }
        if (names(nt, sport) || names(nd, sport)) return false
        return another(nt) || another(nd)
    }

    // ------------------------------------------------------------------ the guide, read once

    /** One half-hour of games: its guide window and every word its games are looked for by. */
    private class GuideSlot(
        val slot: List<SportsEvent>,
        val fromMs: Long,
        val toMs: Long,
        val minStopMs: Long,
        val words: Set<String>
    )

    /**
     * The guide shows for each slot, in the same order as [slots]: shows starting in the slot's window
     * (and still on at its minimum stop time) whose title or description has one of its words.
     * One pass over the guide for all slots; a show is split into words once and looked up, rather
     * than every show being compared with every word for every slot. With [gentle], a short pause
     * every so often so the screen and video keep priority on slower boxes.
     */
    private fun guideHitsBySlot(slots: List<GuideSlot>, gentle: Boolean, rev: Long? = null): List<List<Pair<String, EpgProgram>>> {
        if (slots.isEmpty()) return emptyList()
        val words = slots.flatMapTo(HashSet<String>()) { it.words }
        val from = slots.minOf { it.fromMs }
        val to = slots.maxOf { it.toMs }
        val minStop = slots.minOf { it.minStopMs }
        val src = pickSource(rev, from, to, words)
        val t0 = System.currentTimeMillis()
        val out = hitsBySlot(src, slots, words, from, to, minStop, gentle)
        if (src != From.TABLE) return out
        val tTable = System.currentTimeMillis() - t0
        if (!checkSlots) {
            tableNote("${slots.size} half-hours read from it in $tTable ms")
            return out
        }
        // First use since it was built: the whole guide too, to check nothing is missed.
        checkSlots = false
        val t1 = System.currentTimeMillis()
        val full = hitsBySlot(From.GUIDE, slots, words, from, to, minStop, gentle)
        val tFull = System.currentTimeMillis() - t1
        val differ = slots.indices.filter { i ->
            out[i].mapTo(HashSet()) { (ch, p) -> "$ch|${p.startMs}" } != full[i].mapTo(HashSet()) { (ch, p) -> "$ch|${p.startMs}" }
        }
        if (differ.isEmpty()) {
            tableNote("check: same shows for all ${slots.size} half-hours (table $tTable ms, whole guide ${tFull / 1000} s)")
            return out
        }
        tableNote("check: ${differ.size} of ${slots.size} half-hours DIFFER (" +
            differ.take(3).joinToString { i -> slots[i].slot.first().title.take(40) } + "); the whole guide's result is used")
        return full
    }

    private fun hitsBySlot(
        src: From,
        slots: List<GuideSlot>,
        words: Set<String>,
        from: Long,
        to: Long,
        minStop: Long,
        gentle: Boolean
    ): List<List<Pair<String, EpgProgram>>> {
        val out = List(slots.size) { ArrayList<Pair<String, EpgProgram>>() }
        // Every word looked for, by its first word, so a show only needs checking against the few that start like one of its own words.
        val byFirst = byFirstWord(words)
        val seen = List(slots.size) { HashSet<String>() }
        readRows(src, from, to, minStop, wantDesc = true, gentle = gentle) { ch, start, stop, title, descr, cat, nt, nd ->
            val m = matchedWords(nt, nd, byFirst) ?: return@readRows
            for (i in slots.indices) {
                val s = slots[i]
                if (start < s.fromMs || start >= s.toMs || stop <= s.minStopMs) continue
                if (s.words.none { it in m }) continue
                if (!seen[i].add("$ch|$start")) continue
                out[i] += ch to EpgProgram(startMs = start, stopMs = stop, title = title, description = descr, category = cat)
            }
        }
        return out
    }

    /** Words by their first word (see [matchedWords]). */
    private fun byFirstWord(words: Collection<String>): Map<String, List<String>> {
        val byFirst = HashMap<String, MutableList<String>>()
        words.forEach { w -> byFirst.getOrPut(w.substringBefore(' ')) { ArrayList() } += w }
        return byFirst
    }

    /** The words in [byFirst] that a show's cleaned-up title [nt] or description [nd] has, or null for none. */
    private fun matchedWords(nt: String, nd: String, byFirst: Map<String, List<String>>): HashSet<String>? {
        var matched: HashSet<String>? = null
        fun look(text: String) {
            if (text.isEmpty()) return
            for (tok in text.split(' ')) {
                if (tok.isEmpty()) continue
                val cands = byFirst[tok] ?: continue
                for (w in cands) {
                    if (hasWord(nt, w) || hasWord(nd, w)) (matched ?: HashSet<String>().also { matched = it }).add(w)
                }
            }
        }
        look(nt)
        look(nd)
        return matched
    }

    // ------------------------------------------------------------------ where the guide is read from

    private enum class From { GUIDE, TABLE }

    /**
     * Guide shows starting in [from, to) and still on after [minStop], with their title and
     * description cleaned up for matching ([wantDesc] false: the description isn't, "" instead).
     * From the whole guide, or from the sports-shows table (already cleaned up there).
     * With [gentle], a short pause every so often so the screen and video keep priority.
     */
    private fun readRows(
        src: From,
        from: Long,
        to: Long,
        minStop: Long,
        wantDesc: Boolean,
        gentle: Boolean,
        onRow: (ch: String, start: Long, stop: Long, title: String, descr: String?, cat: String?, nt: String, nd: String) -> Unit
    ) {
        var rows = 0
        if (src == From.TABLE) {
            db.scanSportsRows(from, to, minStop) { ch, start, stop, title, descr, cat, nt, nd ->
                if (gentle && ++rows % 1000 == 0) Thread.sleep(3)
                onRow(ch, start, stop, title, descr, cat, nt, if (wantDesc) nd else "")
            }
        } else {
            db.scanListings(from, to, minStop) { ch, start, stop, title, descr, cat ->
                if (gentle && ++rows % 1000 == 0) Thread.sleep(3)
                onRow(ch, start, stop, title, descr, cat, norm(title), if (wantDesc && !descr.isNullOrEmpty()) norm(descr) else "")
            }
        }
    }

    /** The sports-shows table as last read: which guide it's for, the time span, and the words it was built for. */
    private class TableState(val stamp: String, val rev: Long, val fromMs: Long, val toMs: Long, val rows: Int, val words: Set<String>)
    @Volatile private var tableState: TableState? = null
    /** The table was just (re)built: the next guide reads also read the whole guide, to compare. */
    @Volatile private var checkSlots = false
    @Volatile private var checkDays = false
    private val tableNotes = ArrayList<String>()

    private fun tableNote(text: String) = synchronized(tableNotes) { tableNotes += text }

    private fun takeTableNotes(): String = synchronized(tableNotes) {
        if (tableNotes.isEmpty()) "" else (" · guide sports table (test build): " + tableNotes.joinToString("; ")).also { tableNotes.clear() }
    }

    private fun refreshTable() {
        val info = db.sportsRowsInfo()
        if (info?.stamp == tableState?.stamp) return
        tableState = info?.let { TableState(it.stamp, it.rev, it.fromMs, it.toMs, it.rows, db.sportsRowsWords()) }
    }

    /**
     * The table, when it's built for this guide, covers [from, to) and was built for every one of
     * [words] (then it holds every show the whole guide would give for them); otherwise the whole guide.
     */
    private fun pickSource(rev: Long?, from: Long, to: Long, words: Collection<String>): From {
        if (!sportsTable || rev == null) return From.GUIDE
        val t = tableState ?: return From.GUIDE
        if (t.rev != rev || from < t.fromMs || to > t.toMs) return From.GUIDE
        if (!t.words.containsAll(words)) {
            tableNote("whole guide read: ${(words - t.words).size} words the table wasn't built for")
            return From.GUIDE
        }
        return From.TABLE
    }

    /**
     * Test builds: makes sure the guide's sports-shows table is there for the guide stored now and
     * holds every show naming a word these games (or the guide-only sports and names you follow)
     * are looked for by; otherwise rebuilds it from one read of the guide. Runs before the
     * searches ([guideEventsDays], [match]).
     */
    fun prepareTable(events: List<SportsEvent>, leagues: List<League>, followedWords: List<String>, now: Long, gentle: Boolean) {
        if (!sportsTable) return
        runCatching {
            val rev = db.revision()
            refreshTable()
            val words = tableWords(events, leagues, followedWords)
            val t = tableState
            if (t != null && t.rev == rev && t.words.containsAll(words) &&
                t.fromMs <= now - 50 * HOUR && t.toMs >= now + AHEAD_MS + 12 * HOUR
            ) return
            // Same guide: the words it was built for stay, so it isn't rebuilt back and forth.
            val all = if (t != null && t.rev == rev) words + t.words else words
            buildTable(rev, now - 60 * HOUR, now + 8 * 24 * HOUR, all, gentle)
            refreshTable()
        }.onFailure { tableNote("couldn't be built (${it.javaClass.simpleName})") }
    }

    /** Every word a search might look for: each game's ([termsFor]), the listed sports' guide words, names you follow. */
    private fun tableWords(events: List<SportsEvent>, leagues: List<League>, followedWords: List<String>): Set<String> {
        val out = HashSet<String>()
        for (ev in events) {
            val fight = ev.source == EventSource.GUIDE && SportsCatalog.league(ev.leagueId)?.sport == Sport.FIGHTING &&
                SportsCatalog.fightSides(ev.title) != null
            if (ev.source == EventSource.GUIDE && !fight) continue
            termsFor(ev).sqlWords.forEach { w -> w.trim().takeIf { it.length >= 3 }?.let(out::add) }
        }
        leagues.forEach { l -> l.guideWords.forEach { w -> norm(w).trim().takeIf { it.length >= 3 }?.let(out::add) } }
        followedWords.forEach { w -> norm(w).trim().takeIf { it.length >= 3 }?.let(out::add) }
        return out
    }

    private fun buildTable(rev: Long, from: Long, to: Long, words: Set<String>, gentle: Boolean) {
        val t0 = System.currentTimeMillis()
        val byFirst = byFirstWord(words)
        val writer = db.startSportsRows()
        var read = 0
        val ok = try {
            readRows(From.GUIDE, from, to, from, wantDesc = true, gentle = gentle) { ch, start, stop, title, descr, cat, nt, nd ->
                read++
                if (matchedWords(nt, nd, byFirst) != null) writer.add(ch, start, stop, title, descr, cat, nt, nd)
            }
            writer.finish(rev, from, to, words)
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
        val secs = (System.currentTimeMillis() - t0) / 1000
        if (ok) {
            checkSlots = true; checkDays = true
            tableNote("built: ${writer.rows} of $read shows kept, for ${words.size} words, in $secs s")
        } else {
            tableNote("guide changed while it was built (${secs} s): whole guide read until the next try")
        }
    }

    // ------------------------------------------------------------------ saved between app starts

    private fun saveJson(name: String, o: JSONObject) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "$name.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(d, name))) { File(d, name).delete(); tmp.renameTo(File(d, name)) }
        }
    }

    private fun loadJson(name: String): JSONObject? {
        val d = dir ?: return null
        return runCatching { File(d, name).takeIf { it.exists() }?.readText()?.let { JSONObject(it) } }.getOrNull()
    }

    private fun JSONArray.str(i: Int): String? = if (isNull(i)) null else getString(i)

    /** A show as [start, stop, title, description, category]. */
    private fun programJson(a: JSONArray, p: EpgProgram): JSONArray = a.put(p.startMs).put(p.stopMs).put(p.title)
        .put(p.description ?: JSONObject.NULL).put(p.category ?: JSONObject.NULL)

    private fun programFrom(a: JSONArray, at: Int): EpgProgram = EpgProgram(
        startMs = a.getLong(at), stopMs = a.getLong(at + 1), title = a.getString(at + 2),
        description = a.str(at + 3), category = a.str(at + 4)
    )

    private fun saveSlots(key: Long, cache: Map<Long, List<SlotFind>>) {
        val s = JSONArray()
        cache.forEach { (sig, finds) ->
            val f = JSONArray()
            finds.forEach { x -> f.put(programJson(JSONArray().put(x.eventId).put(x.key).put(x.score).put(x.how), x.program)) }
            s.put(JSONArray().put(sig).put(f))
        }
        saveJson(SLOTS_FILE, JSONObject().put("v", 1).put("k", key).put("s", s))
    }

    /** The saved half-hour finds, when they were saved for [key] (same guide, channels and time shift). */
    private fun loadSlots(key: Long): Map<Long, List<SlotFind>>? = runCatching {
        val o = loadJson(SLOTS_FILE) ?: return null
        if (o.optInt("v") != 1 || o.optLong("k") != key) return null
        val s = o.getJSONArray("s")
        val out = HashMap<Long, List<SlotFind>>(s.length())
        for (i in 0 until s.length()) {
            val e = s.getJSONArray(i)
            val f = e.getJSONArray(1)
            out[e.getLong(0)] = (0 until f.length()).map { j ->
                val x = f.getJSONArray(j)
                SlotFind(x.getString(0), x.getString(1), x.getInt(2), x.getString(3), programFrom(x, 4))
            }
        }
        out
    }.getOrNull()

    private fun saveDays(key: Long, byDay: Map<Long, List<Pair<String, EpgProgram>>>) {
        val d = JSONArray()
        byDay.forEach { (ds, hits) ->
            val h = JSONArray()
            hits.forEach { (ch, p) -> h.put(programJson(JSONArray().put(ch), p)) }
            d.put(JSONArray().put(ds).put(h))
        }
        saveJson(DAYS_FILE, JSONObject().put("v", 1).put("k", key).put("d", d))
    }

    private fun loadDays(key: Long): Map<Long, List<Pair<String, EpgProgram>>>? = runCatching {
        val o = loadJson(DAYS_FILE) ?: return null
        if (o.optInt("v") != 1 || o.optLong("k") != key) return null
        val d = o.getJSONArray("d")
        val out = HashMap<Long, List<Pair<String, EpgProgram>>>(d.length())
        for (i in 0 until d.length()) {
            val e = d.getJSONArray(i)
            val h = e.getJSONArray(1)
            out[e.getLong(0)] = (0 until h.length()).map { j -> h.getJSONArray(j).let { x -> x.getString(0) to programFrom(x, 1) } }
        }
        out
    }.getOrNull()

    // ------------------------------------------------------------------ how a game is named

    private class Terms(
        val a: List<String>, val aWeak: List<String>,
        val b: List<String>, val bWeak: List<String>,
        val abbrA: String?, val abbrB: String?,
        val leagueWords: List<String>,
        val eventWords: List<String>,
        val sqlWords: List<String>,
        /** A fight's two sides ("canelo", "alvarez" / "mbilli"): a show or channel naming both is it. */
        val fightA: List<String> = emptyList(),
        val fightB: List<String> = emptyList()
    ) {
        private fun any(text: String, list: List<String>) = list.any { hasWord(text, it) }

        fun scoreText(nt: String, nd: String): Int {
            val base = baseScore(nt, nd)
            if (fightA.isEmpty() || fightB.isEmpty()) return base
            val aT = any(nt, fightA); val bT = any(nt, fightB)
            val fight = when {
                aT && bT -> 100
                (aT || any(nd, fightA)) && (bT || any(nd, fightB)) -> 85
                else -> 0
            }
            return maxOf(base, fight)
        }

        private fun baseScore(nt: String, nd: String): Int {
            if (a.isNotEmpty() && b.isNotEmpty()) {
                val aT = any(nt, a); val bT = any(nt, b)
                val aW = any(nt, aWeak); val bW = any(nt, bWeak)
                val aD = any(nd, a); val bD = any(nd, b)
                return when {
                    aT && bT -> 100
                    (aT && bW) || (bT && aW) -> 90
                    (aT || aD) && (bT || bD) -> 82
                    (aT || bT) && any(nt, leagueWords) -> 58
                    else -> 0
                }
            }
            val ew = eventWords.count { hasWord(nt, it) || hasWord(nd, it) }
            val lw = any(nt, leagueWords)
            return when {
                lw && ew > 0 -> 92
                ew >= 2 -> 75
                ew > 0 && any(nd, leagueWords) -> 70
                else -> 0
            }
        }

        /** The show names a game ("Bills at Raiders", "Juventus vs. Lazio") that isn't this one. */
        fun otherGame(text: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            val us = any(text, a) || any(text, aWeak) || any(text, b) || any(text, bWeak) ||
                listOfNotNull(abbrA, abbrB).any { it.length >= 2 && hasWord(text, it) }
            if (us) return false
            return MATCHUP.containsMatchIn(text)
        }

        /** The show's title or description names the competition itself ("Formula 1", "Grand Prix"). */
        fun namesLeague(nt: String, nd: String): Boolean = leagueWords.any { hasWord(nt, it) || hasWord(nd, it) }

        /** The first word of every name this game looks for in a channel's name (to find those channels quickly). */
        fun anchorTokens(): Set<String> {
            val out = HashSet<String>()
            for (l in listOf(a, b, leagueWords, eventWords)) for (term in l) {
                term.trim().substringBefore(' ').takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            abbrA?.let { out.add(it) }
            abbrB?.let { out.add(it) }
            out.addAll(fightA)
            out.addAll(fightB)
            return out
        }

        /** The channel's own name carries the competition's name ("Sky Sports F1", "Formula 1 HD"). */
        fun leagueInName(nn: String): Boolean = leagueWords.any { it.length >= 2 && it != "grand prix" && hasWord(nn, it) }

        fun inName(nn: String): Boolean {
            // An event channel named for the fight ("03 - (HD) Canelo v Mbilli"): both fighters.
            if (fightA.isNotEmpty() && fightB.isNotEmpty() && any(nn, fightA) && any(nn, fightB)) return true
            if (a.isNotEmpty() && b.isNotEmpty()) {
                if (any(nn, a) && any(nn, b)) return true
                val x = abbrA ?: return false
                val y = abbrB ?: return false
                return listOf("$x $y", "$x at $y", "$x vs $y", "$x v $y", "$y $x", "$y vs $x", "$y v $x", "$y at $x").any { hasWord(nn, it) }
            }
            return eventWords.isNotEmpty() && any(nn, leagueWords) && eventWords.count { hasWord(nn, it) } >= 1
        }
    }

    /** A wrestling show's own title: starts with one of the league's names, or names the promotion. */
    private fun wrestlingTitle(title: String, league: League): Boolean {
        val t = norm(title)
        if (listOf("wwe", "aew", "tna", "njpw", "all elite wrestling", "impact wrestling").any { hasWord(t, it) }) return true
        val start = t.trim()
        return league.guideWords.any { w ->
            val n = norm(w).trim()
            start == n || start.startsWith("$n ") || start.startsWith("friday night $n") || start.startsWith("monday night $n")
        }
    }

    /** A channel name without "US:" and quality words, for comparing with show titles. */
    private fun nameNorm(name: String): String =
        norm(name.replace(NAME_PREFIX, "")).replace(NAME_NOISE, " ").replace(Regex(" +"), " ")

    /**
     * Not a real broadcast of a game or show: a 24/7 channel, a block of 6 hours or more, or a
     * listing that's just the channel's own name ("CINE PRO NXT" on CINE PRO NXT).
     */
    private fun fillerOrLoop(ch: LiveChannel, p: EpgProgram): Boolean {
        if (com.nuvio.tv.livetv.data.LiveTvPosterResolver.is247(ch)) return true
        if (p.stopMs - p.startMs >= 6 * HOUR) return true
        val t = nameNorm(cleanTitle(p.title)).trim()
        val c = nameNorm(ch.name).trim()
        if (t.isEmpty() || c.isEmpty()) return t.isEmpty()
        if (t == c) return true
        // A team's own channel ("ATLANTA HAWKS") showing its game ("Atlanta Hawks @ Orlando
        // Magic"): a real game, not the channel's name repeated.
        if (MATCHUP.containsMatchIn(" " + norm(p.title.replace("@", " at ")).trim() + " ")) return false
        // The listing repeats the whole (multi-word) channel name: filler ("CINE PRO NXT").
        return c.contains(' ') && c.length >= 8 && " $t ".contains(" $c ")
    }

    private fun termsFor(ev: SportsEvent): Terms {
        val league = SportsCatalog.league(ev.leagueId)
        val leagueWords = league?.guideWords.orEmpty().map { norm(it).trim() }.filter { it.length >= 2 }
        val away = ev.away
        val home = ev.home
        if (league != null && away != null && home != null) {
            val (a, aw) = SportsCatalog.teamNames(away.team, league)
            val (b, bw) = SportsCatalog.teamNames(home.team, league)
            fun ab(t: TeamInfo) = norm(t.abbr).trim().takeIf { it.length in 2..4 }
            return Terms(a, aw, b, bw, ab(away.team), ab(home.team), leagueWords, emptyList(), (a + b).distinct())
        }
        // A wrestling show: its own name ("RAW #1743" -> "raw", "SmackDown", "NXT"), so a channel
        // called "LIVE EVENT 01 - WWE Monday Night RAW" is found.
        if (league?.sport == Sport.WRESTLING) {
            val show = norm(wrestlingHeadline(ev.title)).trim()
            val words = listOf(show).filter { it.length >= 3 }
            if (words.isNotEmpty()) return Terms(emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, words, (words + leagueWords).distinct())
        }
        // One event (a race, a fight card, a tournament): its own words ("mexico city", "pantoja").
        val core = norm(ev.title).trim()
            .split(" vs ", " v ", " at ")
            .flatMap { part -> listOf(part.trim()) + part.trim().split(' ').filter { it.length >= 5 } }
            .map { it.trim() }
            .filter { it.length >= 4 && it !in GENERIC_EVENT_WORDS && leagueWords.none { lw -> lw == it } }
            .distinct()
            .take(8)
        // A fight ("Canelo Álvarez vs Christian Mbilli"): event channels are named after the two
        // fighters and rarely say "Boxing", so naming both is enough.
        val fight = if (league?.sport == Sport.FIGHTING) SportsCatalog.fightSides(ev.title) else null
        return Terms(
            emptyList(), emptyList(), emptyList(), emptyList(), null, null, leagueWords, core,
            (core + leagueWords + fight?.first.orEmpty() + fight?.second.orEmpty()).filter { it.length >= 3 }.distinct(),
            fightA = fight?.first.orEmpty(),
            fightB = fight?.second.orEmpty()
        )
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        /** How far ahead games are looked for in your channels (most guides cover 2–3 days). */
        const val AHEAD_MS = 56 * HOUR

        /** What the guide gave each half-hour of games, and the guide-only sports by day (see [match], [guideEventsDays]). */
        private const val SLOTS_FILE = "matcher_slots.json"
        private const val DAYS_FILE = "matcher_days.json"

        private val NAME_PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[:|\-]\s*""")
        private val NAME_NOISE = Regex(""" (fhd|uhd|hd|sd|4k|hevc|raw|backup|vip) """)

        /** Shows found in the guide for a followed name that isn't one of the listed sports. */
        const val OTHER_ID = "guide/other"

        /** Session and filler words in event titles ("Warm-Up", "Round 20", "(L)"). */
        private val SESSION_WORDS = setOf(
            "warm", "practise", "session", "day", "night", "coverage", "from", "with", "matches", "match",
            "game", "games", "league", "super", "premier", "full", "main", "event", "stage", "leg", "heat", "heats",
            "pre", "post", "show", "build", "studio", "uncut", "only", "action", "free"
        )

        /**
         * Words that name a sport, for telling when a show is plainly a different sport from the
         * listed one its title happened to match. Already through norm ("Women's" -> "women s").
         */
        private val SPORT_NAMES: Map<Sport, List<String>> = mapOf(
            Sport.RUGBY to listOf("rugby", "nrl"),
            Sport.FOOTBALL to listOf("soccer", "football", "women s super league", "wsl", "indian super league",
                "chinese super league", "super league greece", "swiss super league", "super league kerala"),
            Sport.AMERICAN_FOOTBALL to listOf("nfl", "american football", "ncaaf"),
            Sport.AUSSIE_RULES to listOf("afl", "aflw", "aussie rules"),
            Sport.GAELIC to listOf("gaelic", "hurling", "camogie", "gaa"),
            Sport.VOLLEYBALL to listOf("volleyball", "beach volley"),
            Sport.BASKETBALL to listOf("basketball", "nba", "wnba", "nbl", "euroleague"),
            Sport.NETBALL to listOf("netball"),
            Sport.HOCKEY to listOf("hockey", "nhl"),
            Sport.BASEBALL to listOf("baseball", "mlb"),
            Sport.CRICKET to listOf("cricket"),
            Sport.DARTS to listOf("darts"),
            Sport.SNOOKER to listOf("snooker"),
            Sport.TENNIS to listOf("tennis"),
            Sport.GOLF to listOf("golf"),
            Sport.RACING to listOf("motogp", "formula 1", "formula one", "nascar", "indycar", "superbikes", "wsbk"),
            Sport.LACROSSE to listOf("lacrosse"),
            Sport.OTHER to listOf("handball", "futsal", "badminton", "table tennis", "esports", "cycling", "horse racing")
        )

        /** Words too common to tell one tournament or race from another ("Hall of Fame Open" is hall/fame, never open). */
        private val GENERIC_TOURNAMENT_WORDS = setOf(
            "atp", "wta", "tour", "tennis", "golf", "open", "cup", "championship", "championships", "masters",
            "classic", "international", "invitational", "presented", "the", "for", "and", "ladies", "mens", "womens",
            "grand", "prix", "race", "series", "world", "trophy", "tournament", "challenger", "final", "finals",
            "round", "qualifying", "practice", "sprint", "week", "weekend", "live", "season", "formula", "edition"
        )

        /** Of those, the ones that pick out one event when no other event running now has them in its name (see `rareHit`). */
        private val RARE_TOURNAMENT_WORDS = setOf(
            "final", "finals", "masters", "championship", "championships", "classic", "invitational",
            "cup", "trophy", "challenger", "international", "open"
        )

        private val BANNER_RUN = Regex("([#=*~_▬━─•■◆★|\\-])\\1{2,}")

        /** A separator in the channel list ("##### USA SPORTS #####", "▬▬ UK ▬▬"), not a channel. */
        fun isBanner(name: String): Boolean {
            if (BANNER_RUN.containsMatchIn(name)) return true
            val letters = name.count { it.isLetterOrDigit() }
            return name.length >= 6 && letters * 10 < name.length * 4
        }

        private val GENERIC_EVENT_WORDS = setOf(
            "grand prix", "grand", "prix", "championship", "championships", "open", "classic", "tour", "the", "final",
            "finals", "race", "series", "invitational", "masters", "trophy", "fight night", "night", "main card", "prelims"
        )

        /** Words that make a show on a sports network look like a game (not news or a talk show). */
        private val SPORT_WORDS = listOf(
            "football", "basketball", "hockey", "baseball", "soccer", "game", "live", "ncaa", "college", "sports",
            "nfl", "nba", "nhl", "mlb", "mls", "playoff", "playoffs", "bowl", "series"
        )

        private fun isLive(nt: String, nd: String): Boolean =
            nt.startsWith(" live ") || hasWord(nt, "live") || nd.startsWith(" live ") ||
                hasWord(nd, "live coverage") || hasWord(nd, "live action") || hasWord(nd, "coverage of")

        /** "x at y", "x vs y", "x v y" in a show title (text already through norm). */
        private val MATCHUP = Regex("""\s(?:at|vs|v|versus)\s""")

        private val LIVE_PREFIX = Regex("""^\s*(?:live\s*[:\-–|]?\s*)""", RegexOption.IGNORE_CASE)
        // Guides mark first showings: "New: AEW Collision", "AEW Collision (New)", "… - NEW". Only
        // with the colon, dash or brackets ("New England Patriots" and "New York" stay as they are).
        private val NEW_PREFIX = Regex("""^\s*\(?new\)?\s*[:\-–|]\s*""", RegexOption.IGNORE_CASE)
        private val NEW_SUFFIX = Regex("""\s*(?:\(new\)|\[new]|[\-–|]\s*new)\s*$""", RegexOption.IGNORE_CASE)
        fun cleanTitle(t: String): String {
            var s = SportsCatalog.withoutSmallMarks(t)
            repeat(2) { s = s.replace(LIVE_PREFIX, "").replace(NEW_PREFIX, "").replace(NEW_SUFFIX, "").trim() }
            return s.ifBlank { t }
        }

        /** A network as we key it ("ESPN+" -> "espn plus", "USA Net" -> "usa net"). */
        fun networkKey(name: String): String = norm(name.replace("+", " plus ")).trim()

        /** Networks that show the same thing everywhere (no local stations). */
        private val NATIONAL = setOf(
            "espn", "espn2", "espnu", "espnews", "espn plus", "espn deportes", "fs1", "fs2", "nfl net", "nfl network", "nfln",
            "nba tv", "nhl net", "nhl network", "mlb net", "mlb network", "tnt", "tbs", "trutv", "usa net", "usa network",
            "cbssn", "cbs sports network", "btn", "big ten network", "secn", "sec network", "accn", "acc network",
            "golf", "golf channel", "tennis channel", "fox deportes", "tudn", "univision", "telemundo", "universo", "peacock",
            "prime video", "paramount plus", "apple tv", "netflix", "bein sports", "nbcsn", "mlb tv", "max", "truetv",
            // UK, Canada, Australia: these don't vary by local market the way US RSNs do.
            "sky sports", "sky sports main event", "sky sports football", "sky sports premier league", "sky sports arena",
            "tnt sports", "dazn", "itv", "itv1", "itv4", "bbc", "bbc one", "bbc two", "bbc iplayer",
            "tsn", "sportsnet", "sportsnet one", "ctv", "cbc",
            "fox sports australia", "kayo", "stan sport", "seven", "nine", "optus sport"
        )

        /** Words a network's own name is never worth searching on (too common to mean anything alone). */
        private val GENERIC_NETWORK_WORDS = setOf("sports", "network", "tv", "channel", "the", "regional", "home", "plus")

        /** How each network appears in channel names. */
        private val NETWORKS: Map<String, List<String>> = mapOf(
            "fox" to listOf("fox"),
            "cbs" to listOf("cbs"),
            "nbc" to listOf("nbc"),
            "abc" to listOf("abc"),
            "espn" to listOf("espn"),
            "espn2" to listOf("espn2", "espn 2"),
            "espnu" to listOf("espnu", "espn u"),
            "espnews" to listOf("espnews", "espn news"),
            "espn plus" to listOf("espn plus"),
            "espn deportes" to listOf("espn deportes"),
            "fs1" to listOf("fs1", "fox sports 1"),
            "fs2" to listOf("fs2", "fox sports 2"),
            "nfl net" to listOf("nfl network", "nfl net", "nfln"),
            "nfl network" to listOf("nfl network", "nfl net", "nfln"),
            "nfln" to listOf("nfl network", "nfl net", "nfln"),
            "nba tv" to listOf("nba tv", "nbatv"),
            "nhl net" to listOf("nhl network", "nhl net"),
            "nhl network" to listOf("nhl network", "nhl net"),
            "mlb net" to listOf("mlb network", "mlb net"),
            "mlb network" to listOf("mlb network", "mlb net"),
            "tnt" to listOf("tnt"),
            "tbs" to listOf("tbs"),
            "trutv" to listOf("trutv", "tru tv"),
            "usa net" to listOf("usa network", "usa net"),
            "usa network" to listOf("usa network", "usa net"),
            "cbssn" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "cbs sports network" to listOf("cbs sports network", "cbssn", "cbs sports net"),
            "btn" to listOf("big ten network", "btn", "big ten"),
            "big ten network" to listOf("big ten network", "btn", "big ten"),
            "secn" to listOf("sec network", "secn"),
            "sec network" to listOf("sec network", "secn"),
            "accn" to listOf("acc network", "accn"),
            "acc network" to listOf("acc network", "accn"),
            "golf" to listOf("golf channel", "golf"),
            "golf channel" to listOf("golf channel", "golf"),
            "tennis channel" to listOf("tennis channel"),
            "fox deportes" to listOf("fox deportes"),
            "tudn" to listOf("tudn"),
            "univision" to listOf("univision"),
            "telemundo" to listOf("telemundo"),
            "universo" to listOf("universo"),
            "peacock" to listOf("peacock"),
            "prime video" to listOf("prime video", "amazon prime"),
            "paramount plus" to listOf("paramount plus", "paramount"),
            "apple tv" to listOf("apple tv", "mls season pass"),
            "netflix" to listOf("netflix"),
            "bein sports" to listOf("bein sports", "bein"),
            "max" to listOf("max", "hbo max"),
            // ---- UK
            "sky sports" to listOf("sky sports"),
            "sky sports main event" to listOf("sky sports main event", "sky sports 1"),
            "sky sports football" to listOf("sky sports football"),
            "sky sports premier league" to listOf("sky sports premier league", "sky sports prem"),
            "sky sports arena" to listOf("sky sports arena"),
            "sky sports racing" to listOf("sky sports racing"),
            "sky sports f1" to listOf("sky sports f1", "sky sports formula 1"),
            "sky sports cricket" to listOf("sky sports cricket"),
            "sky sports golf" to listOf("sky sports golf"),
            "sky sports tennis" to listOf("sky sports tennis"),
            "tnt sports" to listOf("tnt sports"),
            "tnt sports 1" to listOf("tnt sports 1"),
            "tnt sports 2" to listOf("tnt sports 2"),
            "dazn" to listOf("dazn"),
            "itv" to listOf("itv1", "itv"),
            "itv4" to listOf("itv4"),
            "bbc" to listOf("bbc one", "bbc two", "bbc"),
            "bbc iplayer" to listOf("bbc iplayer", "bbc one", "bbc two"),
            // ---- Canada
            "tsn" to listOf("tsn"),
            "sportsnet" to listOf("sportsnet"),
            "sportsnet one" to listOf("sportsnet one"),
            "ctv" to listOf("ctv"),
            "cbc" to listOf("cbc"),
            // ---- Australia
            "fox sports australia" to listOf("fox sports", "foxsports"),
            "kayo" to listOf("kayo"),
            "stan sport" to listOf("stan sport"),
            "seven" to listOf("channel 7", "seven network", "7mate"),
            "nine" to listOf("channel 9", "nine network", "9gem"),
            "optus sport" to listOf("optus sport"),
            // ---- bein sports numbered variants (ESPN lists these for several international competitions)
            "bein sports 1" to listOf("bein sports 1", "bein 1"),
            "bein sports 2" to listOf("bein sports 2", "bein 2"),
            "bein sports 3" to listOf("bein sports 3", "bein 3")
        )

        /** Channel-name words that rule a channel out for that network (FOX News isn't FOX). */
        private val NETWORK_NOT: Map<String, List<String>> = mapOf(
            "fox" to listOf("news", "business", "deportes", "sports 1", "sports 2", "fs1", "fs2", "soccer", "weather", "life", "kids", "movies", "classics", "comedy", "nation", "family", "crime", "reality"),
            "cbs" to listOf("news", "sports", "cbssn", "golazo", "reality", "drama", "action"),
            "nbc" to listOf("news", "nbcsn", "sports", "universo", "golf"),
            "abc" to listOf("news", "family", "kids", "me", "australia"),
            "espn" to listOf("2", "u", "news", "deportes", "classic", "plus", "espn2", "espnu", "espnews"),
            "tnt" to listOf("sports 1", "sports 2", "sports 3", "sports 4", "sports 5", "drama", "series", "film", "comedy"),
            "tbs" to listOf("news"),
            "max" to listOf("cinemax", "maximum", "maxx", "movies"),
            "golf" to listOf("tv"),
            "paramount plus" to listOf("network", "comedy"),
            "itv" to listOf("itv2", "itv3", "itv4", "itvbe"),
            "bbc" to listOf("news", "parliament", "alba", "four"),
            "seven" to listOf("news", "mate extra"),
            "nine" to listOf("news", "gem extra")
        )

        fun nameQuality(name: String): String? {
            val n = norm(name)
            return when {
                listOf("4k", "uhd", "2160", "2160p").any { hasWord(n, it) } -> "4K"
                listOf("fhd", "1080", "1080p", "1080i").any { hasWord(n, it) } -> "FHD"
                listOf("hd", "720", "720p").any { hasWord(n, it) } -> "HD"
                listOf("sd", "480", "576").any { hasWord(n, it) } -> "SD"
                else -> null
            }
        }

        fun qualityRank(q: String?): Int = when (q) {
            "4K" -> 4; "FHD" -> 3; "HD" -> 2; "SD" -> 1; else -> 2
        }

        /** Your own region's words, as channel names and groups use them ("UK: TNT Sports 1", group "UK | Sports"). */
        internal val REGION_WORDS = mapOf(
            "UK" to listOf("uk", "gb", "united kingdom", "britain", "england", "scotland"),
            "IE" to listOf("ie", "ireland", "irish", "irl", "eir"),
            "US" to listOf("us", "usa", "united states"),
            "CA" to listOf("ca", "canada", "can"),
            "AU" to listOf("au", "aus", "australia", "nz", "new zealand")
        )

        /**
         * How much a channel is preferred for any game: 2 in a group you chose (Sports settings),
         * 1 from your own region (its group or the start of its name says so), else 0.
         */
        /** Words that mark a channel's language, at the start of its name or in its group ("ES: ESPN", "Deportes | Spanish"). */
        private val LANGUAGE_WORDS = mapOf(
            "es" to listOf("es", "esp", "spain", "spanish", "espanol", "latino", "latin", "latam", "mx", "mexico", "argentina", "deportes"),
            "fr" to listOf("fr", "fra", "france", "french", "francais"),
            "de" to listOf("de", "ger", "germany", "german", "deutsch", "deutschland"),
            "it" to listOf("it", "ita", "italy", "italia", "italian", "italiano"),
            "pt" to listOf("pt", "por", "portugal", "portuguese", "br", "brasil", "brazil"),
            "nl" to listOf("nl", "netherlands", "dutch", "nederland"),
            "pl" to listOf("pl", "poland", "polish", "polska"),
            "tr" to listOf("tr", "turkey", "turkish", "turkiye"),
            "ar" to listOf("arabic", "arab", "mena"),
            "ru" to listOf("ru", "russia", "russian")
        )
        private val LANGUAGE_TAG = Regex("[\\[(]([A-Za-z]{2,3})[\\])]")

        /** The language a channel's name or group says it's in, if any. */
        private fun channelLanguage(ch: LiveChannel): String? {
            val prefix = if (ch.name.contains(':') || ch.name.contains('|'))
                norm(ch.name.substringBefore(':').substringBefore('|').take(16)) else " "
            val tags = norm(LANGUAGE_TAG.findAll(ch.name).joinToString(" ") { it.groupValues[1] })
            val group = norm(ch.group.substringBefore('|').substringBefore(':'))
            return LANGUAGE_WORDS.entries.firstOrNull { (_, w) ->
                w.any { hasWord(prefix, it) || hasWord(tags, it) || (it.length > 3 && hasWord(group, it)) }
            }?.key
        }

        /**
         * Where a channel says it's from: a language other than English ("lang:es", from its
         * name or group: "ES: ESPN", "USA | Latino"), else a region ("UK", "US", "IE", "CA", "AU",
         * from its group, the start of its name, or a tag like "[UK]"). Null when it doesn't say.
         */
        fun channelRegionCode(ch: LiveChannel): String? {
            channelLanguage(ch)?.let { return "lang:$it" }
            val prefix = if (ch.name.contains(':') || ch.name.contains('|'))
                norm(ch.name.substringBefore(':').substringBefore('|').take(16)) else " "
            val tags = norm(LANGUAGE_TAG.findAll(ch.name).joinToString(" ") { it.groupValues[1] })
            val group = norm(ch.group)
            return REGION_WORDS.entries.firstOrNull { (_, w) ->
                w.any { hasWord(prefix, it) || hasWord(tags, it) || hasWord(group, it) }
            }?.key
        }

        /** Words that only say which copy of a channel it is (its quality, a backup feed). */
        private val COPY_WORDS = setOf(
            "4k", "uhd", "2160", "2160p", "fhd", "1080", "1080p", "1080i", "hd", "720", "720p", "sd", "480", "576",
            "hevc", "h265", "h264", "50fps", "60fps", "fps", "backup", "alt"
        )

        /** The same channel's copies (ESPN FHD, ESPN HD, ESPN SD) share this; other regions' don't. */
        fun copyKey(ch: LiveChannel): String =
            norm(ch.name.replace("+", " plus ")).split(' ').filter { it.isNotEmpty() && it !in COPY_WORDS }.joinToString(" ")

        /**
         * [list] (in order) with each channel's copies together: one entry per channel, the first
         * copy in the list leading (or [playingKey]'s, while it plays).
         */
        fun groupCopies(list: List<ChannelMatch>, playingKey: String? = null): List<List<ChannelMatch>> {
            val groups = LinkedHashMap<String, MutableList<ChannelMatch>>()
            list.forEach { m -> groups.getOrPut(copyKey(m.channel).ifBlank { m.channel.key }) { ArrayList() } += m }
            return groups.values.map { g ->
                val i = g.indexOfFirst { it.channel.key == playingKey }
                if (i > 0) listOf(g[i]) + g.filterIndexed { j, _ -> j != i } else g
            }
        }

        fun preferenceRank(settings: SportsSettings): (LiveChannel) -> Int {
            val groups = settings.preferredGroups.map { it.lowercase().trim() }.toSet()
            val words = REGION_WORDS[SportsCatalog.regionOf(settings)].orEmpty()
            // Channels in another language than the TV's go below the rest (a Spanish feed of the
            // game isn't what most English speakers want first). Your own groups still come first.
            val myLanguage = java.util.Locale.getDefault().language.lowercase()
            return { ch ->
                when {
                    groups.isNotEmpty() && ch.group.lowercase().trim() in groups -> 2
                    channelLanguage(ch)?.let { it != myLanguage } == true -> -1
                    words.isNotEmpty() && (
                        norm(ch.group).let { g -> words.any { hasWord(g, it) } } ||
                            norm(ch.name.substringBefore(':').substringBefore('|').take(16)).let { n ->
                                (ch.name.contains(':') || ch.name.contains('|')) && words.any { hasWord(n, it) }
                            }
                        ) -> 1
                    else -> 0
                }
            }
        }

        fun sortMatches(list: List<ChannelMatch>, bestFirst: Boolean, rank: (LiveChannel) -> Int = { 0 }): List<ChannelMatch> {
            fun band(score: Int) = when {
                score >= 150 -> 3
                score >= 80 -> 2
                score >= 55 -> 1
                else -> 0
            }
            // Your own picks first; then your groups and region; then how sure and how good.
            return if (bestFirst) list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { band(it.score) }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.name.length }
            ) else list.sortedWith(
                compareByDescending<ChannelMatch> { if (band(it.score) == 3) 1 else 0 }
                    .thenByDescending { rank(it.channel) }
                    .thenByDescending { it.score }
                    .thenBy { it.channel.number }
            )
        }
    }
}

/** The key a team's own channel is saved under: the same club or country in every competition. */
internal fun teamChannelKey(leagueId: String, teamId: String): String = SportsCatalog.teamFamily(leagueId) + "|" + teamId
