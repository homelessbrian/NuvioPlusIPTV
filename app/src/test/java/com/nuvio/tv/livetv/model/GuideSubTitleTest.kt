package com.nuvio.tv.livetv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideSubTitleTest {
    private fun show(description: String?) = EpgProgram(startMs = 0, stopMs = 1, title = "College Football", description = description)

    @Test
    fun subTitleLeadsTheDescription() {
        val p = show("${SUB_TITLE_MARK}Texas at LSU\nThe Longhorns visit Baton Rouge.")
        assertEquals("Texas at LSU", p.subTitle)
        assertEquals("The Longhorns visit Baton Rouge.", p.descriptionText)
        assertEquals("College Football : Texas at LSU", guideTitleWithSub(p.title, p.subTitle))
    }

    @Test
    fun subTitleOnly() {
        val p = show("${SUB_TITLE_MARK}Texas at LSU")
        assertEquals("Texas at LSU", p.subTitle)
        assertNull(p.descriptionText)
    }

    @Test
    fun oldSavedGuideHasNoSubTitle() {
        val p = show("Texas at LSU\nThe Longhorns visit Baton Rouge.")
        assertNull(p.subTitle)
        assertEquals("Texas at LSU\nThe Longhorns visit Baton Rouge.", p.descriptionText)
    }

    @Test
    fun subTitleAlreadyInTheTitleIsNotRepeated() {
        assertEquals("Texas at LSU", guideTitleWithSub("Texas at LSU", "texas at lsu"))
    }
}
