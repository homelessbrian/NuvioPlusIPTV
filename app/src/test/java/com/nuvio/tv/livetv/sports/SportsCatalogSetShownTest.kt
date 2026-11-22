package com.nuvio.tv.livetv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsCatalogSetShownTest {
    private val rugby = SportsCatalog.leagues.filter { it.sport == Sport.RUGBY }

    @Test
    fun `turning a whole sport off then on restores every league`() {
        assertTrue(rugby.size > 1)
        val s0 = SportsSettings()
        val off = SportsCatalog.setShown(rugby, false, emptySet(), s0)
        assertTrue(rugby.none { SportsCatalog.shown(it, off) })
        val on = SportsCatalog.setShown(rugby, true, emptySet(), off)
        assertTrue(rugby.all { SportsCatalog.shown(it, on) })
    }

    @Test
    fun `leagues you follow are left as they are`() {
        val mine = rugby.first()
        val before = SportsSettings()
        val off = SportsCatalog.setShown(rugby, false, setOf(mine.id), before)
        assertEquals(SportsCatalog.shown(mine, before), SportsCatalog.shown(mine, off))
        assertTrue(rugby.filter { it.id != mine.id }.none { SportsCatalog.shown(it, off) })
    }

    @Test
    fun `turning on what is already on changes nothing`() {
        val s0 = SportsSettings()
        val on = SportsCatalog.setShown(rugby, true, emptySet(), s0)
        val again = SportsCatalog.setShown(rugby, true, emptySet(), on)
        assertEquals(on.hiddenLeagues, again.hiddenLeagues)
        assertFalse(on.hiddenLeagues.any { it in rugby.map { l -> l.id } })
    }

    @Test
    fun `every league off then every league on works across all sports`() {
        val all = SportsCatalog.leagues
        val off = SportsCatalog.setShown(all, false, emptySet(), SportsSettings())
        assertTrue(all.none { SportsCatalog.shown(it, off) })
        val on = SportsCatalog.setShown(all, true, emptySet(), off)
        assertTrue(all.all { SportsCatalog.shown(it, on) })
    }
}
