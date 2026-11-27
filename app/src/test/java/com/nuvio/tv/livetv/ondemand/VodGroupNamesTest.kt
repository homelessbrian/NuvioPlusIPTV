package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.livetv.model.LiveTvSettings
import org.junit.Assert.*
import org.junit.Test

class VodGroupNamesTest {
    private val raw = "[UK] Action [4K]"

    @Test fun `cleanup is opt in and uses the shared boundary-aware words`() {
        val settings = LiveTvSettings(nameRemovals = "UK, 4K", cleanGroupNames = true)
        assertEquals(raw, VodGroupNames.from(settings).display(raw))
        val policy = VodGroupNames.from(settings.copy(cleanVodGroupNames = true))
        assertEquals("Action", policy.display(raw))
        assertEquals("UKTV Drama", policy.display("UKTV Drama"))
        assertEquals("USA Network", policy.display("USA Network"))
        assertEquals("[UK]", policy.display("[UK]"))
        assertEquals(raw, VodGroupNames(false, "UK, 4K").display(raw))
    }

    @Test fun `manual names win exactly and empty cleanup never creates an empty label`() {
        val policy = VodGroupNames(true, "UK, 4K")
        assertEquals("[UK] My Movies [4K]", policy.display(raw, "[UK] My Movies [4K]"))
        assertEquals("Action", policy.display(raw, "Action"))
        assertEquals(raw, policy.display(raw, raw))
        assertEquals("Action", policy.display(raw, "  "))
        assertEquals("[4K]", policy.display("[4K]"))
    }

    @Test fun `automatic labels are presentation only and do not merge equal names`() {
        val policy = VodGroupNames(true, "UK, US, 4K")
        val groups = listOf(
            VodCategory("p", VodKind.MOVIE, "1", raw, 20),
            VodCategory("p", VodKind.MOVIE, "2", "[US] Action [4K]", 30)
        )
        val rendered = groups.map { it.uid to policy.display(it.name) }
        assertEquals(listOf("Action", "Action"), rendered.map { it.second })
        assertEquals(2, rendered.map { it.first }.distinct().size)
        assertEquals(raw, groups[0].name)
        assertEquals("1", groups[0].id)
        assertEquals(20, groups[0].count)
        val entry = VodGroupEntry(groups[0].uid, raw, raw, true, VodKind.MOVIE, cleanedDefaultName = "Action")
        assertEquals("Action", entry.displayName)
        assertEquals(raw, entry.name)
        assertEquals(raw, entry.defaultName)
        assertEquals("Action", entry.copy(name = "Action", manuallyNamed = true).displayName)
        assertEquals(raw, entry.copy(manuallyNamed = true).displayName)
    }
}
