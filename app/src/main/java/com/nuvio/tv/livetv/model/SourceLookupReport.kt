package com.nuvio.tv.livetv.model

/** Shareable lookup diagnostics use episode numbers and fixed outcome labels, never the source URL or title. */
internal object SourceLookupReport {
    fun label(type: String, season: Int?, episode: Int?): String = when {
        season != null || episode != null -> "S${season ?: "?"}E${episode ?: "?"}"
        type.equals("series", ignoreCase = true) -> "series"
        type.equals("movie", ignoreCase = true) -> "movie"
        else -> "item"
    }

    fun record(label: String, detail: String) {
        LiveTvLoadReport.add("Source lookup ($label): $detail")
    }
}
