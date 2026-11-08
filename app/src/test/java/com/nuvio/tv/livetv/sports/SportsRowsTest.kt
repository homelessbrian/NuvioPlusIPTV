package com.nuvio.tv.livetv.sports

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SportsRowsTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun followed(league: String = "football/nfl", id: String = "1", name: String = id) =
        FollowedTeam(league, id, name, id, null, null)

    private fun event(
        id: String, league: String = "football/nfl", at: Long = now + hour,
        state: GameState = GameState.PRE, away: String? = "1", home: String? = "2",
        word: String? = null, timed: Boolean = true
    ): SportsEvent {
        fun side(id: String, home: Boolean) = Side(
            TeamInfo(league, id, id, id, id, null, null, null, null),
            null, null, false, home
        )
        return SportsEvent(id, league, at, timed, state, id, null, null,
            away?.let { side(it, false) }, home?.let { side(it, true) }, word = word)
    }

    private fun equivalent(known: List<SportsEvent>, follows: SportsFollows, sport: Sport? = null): MyTeamsPage {
        val expected = ReferenceSportsRows.build(known, follows, now, sport) { isMine(it, follows) }
        val actual = SportsRows.build(known, follows, now, sport) { isMine(it, follows) }
        assertEvents(expected.heroes, actual.heroes)
        assertEquals(expected.rows.size, actual.rows.size)
        expected.rows.zip(actual.rows).forEach { (a, b) ->
            assertEquals(a.key, b.key)
            assertEquals(a.title, b.title)
            assertEquals(a.kind, b.kind)
            assertSame(a.team, b.team)
            assertSame(a.league, b.league)
            assertEquals(a.startIndex, b.startIndex)
            assertEvents(a.events, b.events)
        }
        return actual
    }

    private fun assertEvents(a: List<SportsEvent>, b: List<SportsEvent>) {
        assertEquals(a.size, b.size)
        a.indices.forEach { assertSame("Event order/object at $it", a[it], b[it]) }
    }

    @Test
    fun `empty teams remain as empty rows while empty leagues and names are omitted`() {
        val f = SportsFollows(teams = listOf(followed()), leagues = setOf("football/nfl"), words = listOf("Name"))
        val page = equivalent(emptyList(), f)
        assertEquals(listOf("t:football/nfl:1"), page.rows.map { it.key })
        assertEquals(0, page.rows.single().startIndex)
    }

    @Test
    fun `team families share games across competitions without crossing unrelated sports`() {
        val events = listOf(event("epl", "soccer/eng.1"), event("cup", "soccer/uefa.champions"),
            event("nfl"), event("college", "football/college-football"))
        val f = SportsFollows(teams = listOf(followed("soccer/eng.1"), followed()))
        val teams = equivalent(events, f).rows.filter { it.kind == SportsRow.Kind.TEAM }
        assertEquals(listOf("epl", "cup"), teams[0].events.map { it.id })
        assertEquals(listOf("nfl"), teams[1].events.map { it.id })
    }

    @Test
    fun `family matching uses the event league rather than the side metadata league`() {
        val base = event("game")
        val ev = base.copy(away = base.away!!.copy(team = base.away.team.copy(leagueId = "soccer/eng.1")))
        val f = SportsFollows(teams = listOf(followed(), followed("soccer/eng.1")))
        val teams = equivalent(listOf(ev), f).rows.filter { it.kind == SportsRow.Kind.TEAM }
        assertEquals(listOf("game"), teams[0].events.map { it.id })
        assertTrue(teams[1].events.isEmpty())
    }

    @Test
    fun `null sides and repeated side ids match once per input occurrence`() {
        val both = event("both", away = "1", home = "1")
        val events = listOf(event("none", away = null, home = null), event("away", home = null),
            event("home", away = null, home = "1"), both, both)
        val row = equivalent(events, SportsFollows(teams = listOf(followed()))).rows.single { it.kind == SportsRow.Kind.TEAM }
        assertEquals(listOf("away", "home", "both", "both"), row.events.map { it.id })
    }

    @Test
    fun `duplicate followed teams preserve their rows names and stable order`() {
        val f = SportsFollows(teams = listOf(
            followed("soccer/eng.1", name = "First"), followed("soccer/uefa.champions", name = "Second"),
            followed("soccer/eng.1", name = "Again"), followed(id = "TEAM")
        ))
        val events = listOf(event("club", "soccer/eng.1"), event("upper", away = "TEAM"), event("lower", away = "team"))
        val rows = equivalent(events, f).rows.filter { it.kind == SportsRow.Kind.TEAM }
        assertEquals(listOf("First", "Again", "Second", "TEAM"), rows.map { it.title })
        assertEquals(listOf("upper"), rows.last().events.map { it.id })
    }

    @Test
    fun `names retain case-insensitive Unicode empty and duplicate matching`() {
        val words = listOf("I", "\u0130", "\u0131", "SS", "\u00df", "\u03a3", "\u03c2", "\u03c3", "", "Name", "name", "Name")
        val events = words.distinct().mapIndexed { i, w -> event("word-$i", word = w, away = null, home = null) } +
            event("null", away = null, home = null)
        val page = equivalent(events, SportsFollows(words = words + "absent"))
        assertEquals(words.size, page.rows.count { it.kind == SportsRow.Kind.WORD })
        assertEquals(2, page.rows.count { it.key == "w:Name" })
    }

    @Test
    fun `pinned and followed leagues overlap once and unknown leagues are omitted`() {
        val f = SportsFollows(pins = listOf("basketball/nba", "football/nfl", "basketball/nba"),
            leagues = linkedSetOf("football/nfl", "hockey/nhl", "unknown"))
        val events = listOf(event("nfl"), event("nba", "basketball/nba"), event("unknown", "unknown"))
        val rows = equivalent(events, f).rows.filter { it.kind == SportsRow.Kind.LEAGUE }
        assertEquals(listOf("l:basketball/nba", "l:football/nfl"), rows.map { it.key })
    }

    @Test
    fun `live upcoming and hero caps keep stable input order on tied starts`() {
        val events = List(40) { event("live-$it", at = now, state = GameState.LIVE, timed = it % 3 != 0) }
        val page = equivalent(events, SportsFollows(teams = listOf(followed())))
        assertEvents(events.take(24), page.rows.first().events)
        assertEvents(events.take(24).filter { it.hasTime }.take(8), page.heroes)
    }

    @Test
    fun `team and name timelines keep five results and league timelines keep eight`() {
        val past = List(12) { event("done-$it", at = now - (12 - it) * hour, state = GameState.POST, word = "Name") }
        val future = List(4) { event("next-$it", at = now + (it + 1) * hour, word = "Name") }
        val f = SportsFollows(teams = listOf(followed()), leagues = setOf("football/nfl"), words = listOf("Name"))
        val rows = equivalent(past + future, f).rows.filter { it.kind != SportsRow.Kind.UPCOMING }
        assertEquals(listOf(5, 8, 5), rows.map { it.startIndex })
        assertEquals(listOf(9, 12, 9), rows.map { it.events.size })
        assertEquals(listOf(4, 7, 4), equivalent(past, f).rows.map { it.startIndex })
    }

    @Test
    fun `postponed time boundaries and duplicate done ids retain timeline rules`() {
        val events = listOf(
            event("old", at = now - 2 * day - 1, state = GameState.POST),
            event("edge", at = now - 2 * day, state = GameState.POST),
            event("off-before", at = now - 3 * hour - 1, state = GameState.OFF),
            event("off-edge", at = now - 3 * hour, state = GameState.OFF),
            event("off-now", at = now, state = GameState.OFF),
            event("duplicate", at = now - hour, state = GameState.POST),
            event("duplicate", at = now + hour))
        val page = equivalent(events, SportsFollows(teams = listOf(followed()), leagues = setOf("football/nfl")))
        val team = page.rows.single { it.kind == SportsRow.Kind.TEAM }
        assertFalse(team.events.any { it.id == "duplicate" && it.state == GameState.PRE })
        assertTrue(team.events.any { it.id == "off-now" })
        assertFalse(page.rows.single { it.kind == SportsRow.Kind.LEAGUE }.events.any { it.id == "old" })
    }

    @Test
    fun `upcoming and hero date boundaries remain as before`() {
        val offsets = listOf(-3 * hour - 1, -3 * hour, -hour - 1, -hour, 8 * day, 8 * day + 1)
        val events = offsets.mapIndexed { i, delta -> event("pre-$i", at = now + delta) }
        val page = equivalent(events, SportsFollows(teams = listOf(followed())))
        assertEquals(listOf("pre-1", "pre-2", "pre-3", "pre-4"), page.rows.first().events.map { it.id })
        assertEquals(listOf("pre-3", "pre-4"), page.heroes.map { it.id })
    }

    @Test
    fun `saved row order unknown keys and duplicate order entries keep their meaning`() {
        val f = SportsFollows(teams = listOf(followed()), pins = listOf("football/nfl"), words = listOf("Name"),
            order = listOf("w:Name", "missing", "l:football/nfl", "w:Name", "t:football/nfl:1"))
        val page = equivalent(listOf(event("game", word = "Name")), f)
        assertEquals("up", page.rows.first().key)
        assertEquals(listOf("l:football/nfl", "w:Name", "t:football/nfl:1"), page.rows.drop(1).map { it.key })
    }

    @Test
    fun `sport filtering keeps the membership callback and excludes word rows`() {
        val events = listOf(event("nfl"), event("club", "soccer/eng.1"), event("name", "soccer/eng.1", word = "Name"),
            event("unknown", "unknown"))
        val f = SportsFollows(teams = listOf(followed(), followed("soccer/eng.1")),
            pins = listOf("football/nfl", "soccer/eng.1"), words = listOf("Name"))
        for (sport in listOf(Sport.FOOTBALL, Sport.AMERICAN_FOOTBALL, Sport.BASKETBALL)) {
            val page = equivalent(events, f, sport)
            assertFalse(page.rows.any { it.kind == SportsRow.Kind.WORD })
            assertTrue(page.rows.flatMap { it.events }.all { SportsCatalog.league(it.leagueId)?.sport == sport })
        }
    }

    @Test
    fun `replacement data or score states do not retain earlier rows`() {
        val f = SportsFollows(teams = listOf(followed()))
        val first = event("shared", state = GameState.LIVE)
        equivalent(listOf(first), f)
        val replacement = first.copy(state = GameState.POST, title = "New title")
        assertSame(replacement, equivalent(listOf(replacement), f).rows.single().events.single())
        assertTrue(equivalent(listOf(event("other", away = "3", home = "4")), SportsFollows()).rows.isEmpty())
        assertTrue(equivalent(emptyList(), SportsFollows()).rows.isEmpty())
    }

    @Test
    fun `row grouping uses one extra source pass and membership keeps its call order`() {
        class Counted(private val data: List<SportsEvent>) : AbstractList<SportsEvent>() {
            var reads = 0
            override val size get() = data.size
            override fun get(index: Int) = data[index].also { reads++ }
        }
        val input = List(1_000) { event("game-$it", away = "${it % 20}", home = "${(it + 1) % 20}") }
        val f = SportsFollows(teams = List(10) { followed(id = "$it") },
            leagues = linkedSetOf("football/nfl", "basketball/nba", "hockey/nhl"), words = listOf("A", "B"))
        val old = Counted(input)
        ReferenceSportsRows.build(old, f, now) { false }
        assertEquals(16_000, old.reads)
        val current = Counted(input)
        val seen = ArrayList<SportsEvent>()
        SportsRows.build(current, f, now) { seen += it; false }
        assertEquals(2_000, current.reads)
        assertEvents(input, seen)
    }

    @Test
    fun `randomized follows games duplicates and sport filters match the original builder`() {
        val random = Random(86)
        val leagues = listOf("football/nfl", "basketball/nba", "soccer/eng.1", "soccer/uefa.champions",
            "rugby/union", "rugby/league", "lacrosse/pll", "tsdb/one", "tsdb/two", "unknown")
        val words = listOf<String?>(null, null, "Name", "name", "\u0130", "i", "")
        repeat(300) { revision ->
            val events = List(random.nextInt(0, 100)) { i ->
                event("id-${random.nextInt(30)}", leagues.random(random), now + random.nextInt(-72, 220) * hour,
                    GameState.entries.random(random), if (random.nextInt(5) == 0) null else "${random.nextInt(8)}",
                    if (random.nextInt(5) == 0) null else "${random.nextInt(8)}",
                    words.random(random), random.nextBoolean()).copy(title = "revision-$revision-$i")
            }.shuffled(random)
            val teams = List(random.nextInt(0, 12)) { followed(leagues.random(random), "${random.nextInt(8)}", "Team $it") }
            val f = SportsFollows(teams = teams, leagues = leagues.shuffled(random).take(random.nextInt(5)).toSet(),
                pins = leagues.shuffled(random).take(random.nextInt(5)), words = List(random.nextInt(6)) { words.random(random).orEmpty() },
                reminders = events.take(3).map { SportsReminder(it.id, it.title, it.startMs) })
            equivalent(events, f)
            equivalent(events, f.copy(order = SportsRows.rowKeys(f).shuffled(random)))
            equivalent(events, f, Sport.FOOTBALL)
        }
    }
}

/** Compatibility oracle: the pre-optimization builder. Keep its selection/order rules fixed. */
private object ReferenceSportsRows {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    /** Results kept at the start of a team's row. */
    private const val RESULTS = 5

    /** The keys of My Sports' rows (not Live & Upcoming), in your order (see [SportsFollows.order]). */
    fun rowKeys(f: SportsFollows): List<String> {
        val all = LinkedHashSet<String>()
        f.teams.forEach { all += "t:${it.key}" }
        (f.pins + f.leagues).forEach { all += "l:$it" }
        f.words.forEach { all += "w:$it" }
        val placed = f.order.filter { it in all }
        return placed + all.filter { it !in placed }
    }

    /** Sports you follow something in (a team, a whole league, a league on the top bar). */
    fun followedSports(follows: SportsFollows, order: List<Sport>): List<Sport> {
        val ids = follows.teams.map { it.leagueId } + follows.leagues + follows.pins
        val sports = ids.mapNotNull { SportsCatalog.league(it)?.sport }.toSet()
        return sports.sortedBy { s -> order.indexOf(s).let { if (it < 0) 99 else it } }
    }

    /**
     * The rows; with [sport], only that sport's: its teams, its leagues and its games ("Football"
     * from the top bar shows Arsenal, England and the Premier League, not the NFL).
     */
    fun build(known: List<SportsEvent>, follows: SportsFollows, now: Long, sport: Sport? = null, isMine: (SportsEvent) -> Boolean): MyTeamsPage {
        if (sport != null) {
            fun of(id: String) = SportsCatalog.league(id)?.sport == sport
            val f = follows.copy(
                teams = follows.teams.filter { of(it.leagueId) },
                leagues = follows.leagues.filter { of(it) }.toSet(),
                pins = follows.pins.filter { of(it) },
                words = emptyList()
            )
            return build(known.filter { of(it.leagueId) }, f, now, null, isMine)
        }
        val rows = ArrayList<SportsRow>()

        // ---- Live & Upcoming: everything you follow, on now or in the next week
        val soon = known.filter { ev ->
            // A league on your top bar counts too: its live games belong up here, not only in its own row.
            (isMine(ev) || ev.leagueId in follows.pins) && (ev.state == GameState.LIVE ||
                (ev.state == GameState.PRE && ev.startMs in (now - 3 * HOUR)..(now + 8 * DAY)))
        }.sortedWith(compareBy<SportsEvent> { if (it.state == GameState.LIVE) 0 else 1 }.thenBy { it.startMs })
            .take(24)
        if (soon.isNotEmpty()) rows += SportsRow("up", "Live & Upcoming", SportsRow.Kind.UPCOMING, events = soon)

        // ---- a row per team or country
        for (t in follows.teams) {
            val family = SportsCatalog.teamFamily(t.leagueId)
            val games = known.filter { ev ->
                ev.sides.any { s -> s.team.id == t.teamId && (ev.leagueId == t.leagueId || SportsCatalog.teamFamily(ev.leagueId) == family) }
            }
            rows += timeline("t:${t.key}", t.name, SportsRow.Kind.TEAM, games, now, team = t)
        }

        // ---- a row per top-bar league, then whole leagues and sports you follow
        val leagueIds = LinkedHashSet<String>().apply { addAll(follows.pins); addAll(follows.leagues) }
        for (id in leagueIds) {
            val l = SportsCatalog.league(id) ?: continue
            val all = known.filter { it.leagueId == id }
            // This week (with the last two days' results); a league between rounds: its next games.
            // The last couple of days' results, then every game ahead we have (up to five weeks;
            // capped so a busy league like MLB stays a row you can get through).
            val recent = all.filter { it.startMs >= now - 2 * DAY && (it.state == GameState.POST || it.state == GameState.OFF) }
            val ahead = all.filter { it.state == GameState.LIVE || ((it.state == GameState.PRE || it.state == GameState.OFF) && it.startMs >= now - 3 * HOUR) }
                .sortedBy { it.startMs }.take(60)
            val games = recent + ahead
            if (games.isEmpty()) continue
            rows += timeline("l:$id", l.name, SportsRow.Kind.LEAGUE, games.distinctBy { it.id }, now, league = l, results = 8)
        }

        // ---- names you follow
        for (w in follows.words) {
            val games = known.filter { it.word.equals(w, ignoreCase = true) }
            if (games.isEmpty()) continue
            rows += timeline("w:$w", "“$w”", SportsRow.Kind.WORD, games, now)
        }

        // The big card: games and events at a set time (not a week-long tournament).
        val heroes = soon.filter { it.hasTime && (it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs >= now - HOUR)) }.take(8)
        // Your order (Arrange My Sports); Live & Upcoming always first.
        val rank = rowKeys(follows).withIndex().associate { (i, k) -> k to i }
        val sorted = rows.filter { it.kind == SportsRow.Kind.UPCOMING } +
            rows.filter { it.kind != SportsRow.Kind.UPCOMING }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
        return MyTeamsPage(heroes, sorted)
    }

    /** Games in date order: the last few results, then what's on and what's next. */
    private fun timeline(
        key: String, title: String, kind: SportsRow.Kind, games: List<SportsEvent>, now: Long,
        team: FollowedTeam? = null, league: League? = null, results: Int = RESULTS
    ): SportsRow {
        val sorted = games.sortedBy { it.startMs }
        val done = sorted.filter { it.state == GameState.POST || (it.state == GameState.OFF && it.startMs < now) }
        val doneIds = done.mapTo(HashSet()) { it.id }
        val ahead = sorted.filter { it.id !in doneIds }
        val past = done.takeLast(results)
        return SportsRow(key, title, kind, team, league, past + ahead, startIndex = if (ahead.isEmpty()) (past.size - 1).coerceAtLeast(0) else past.size)
    }
}
