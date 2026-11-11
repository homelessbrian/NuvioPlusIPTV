package com.nuvio.tv.livetv.data

/** Navigation metadata is independent of which display pages are currently resident. */
data class GuideSchedulePageState(
    val days: List<Long> = emptyList(),
    val loading: Boolean = false,
    val hasEarlier: Boolean = false,
    val hasLater: Boolean = false,
    /** The requested anchor in display time, including the user's guide offset. */
    val anchorMs: Long = 0L,
    val publicationId: Long = 0L,
    val errorMessage: String? = null,
    val requestSerial: Long = 0L
)

data class GuideFocusPageState(val channelKey: String? = null, val anchorMs: Long = 0,
                               val loading: Boolean = true, val publicationId: Long = 0,
                               val errorMessage: String? = null)

/** Old saved/API-only guides already hold their full schedule; no database page is pending. */
internal fun legacySchedulePage(state: GuideSchedulePageState,
                                programs: List<com.nuvio.tv.livetv.model.EpgProgram>, id: Long): GuideSchedulePageState {
    val calendar = java.util.Calendar.getInstance()
    val days = LinkedHashSet<Long>()
    for (program in programs) {
        calendar.timeInMillis = program.startMs
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        days += calendar.timeInMillis
    }
    return state.copy(days = days.sorted(), loading = false, hasEarlier = false,
        hasLater = false, publicationId = id, errorMessage = null)
}
