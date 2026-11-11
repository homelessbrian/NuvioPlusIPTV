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
    val errorMessage: String? = null
)

data class GuideFocusPageState(val channelKey: String? = null, val anchorMs: Long = 0,
                               val loading: Boolean = true, val publicationId: Long = 0,
                               val errorMessage: String? = null)
