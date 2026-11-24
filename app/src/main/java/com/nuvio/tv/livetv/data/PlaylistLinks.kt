package com.nuvio.tv.livetv.data

/** Link markers apply to the whole link, including its query, as in the original M3U filter. */
internal object PlaylistLinks {
    enum class Kind { UNMARKED, LIVE, VOD }

    // Keep Java's Unicode case folding for non-ASCII links; the common ASCII path needs no Matcher.
    private val vodPath = Regex("""/(movie|movies|series|vod)/""", RegexOption.IGNORE_CASE)
    private val vodExtension = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm|flv)(\?|$)""", RegexOption.IGNORE_CASE)
    private val livePath = Regex("""/live/""", RegexOption.IGNORE_CASE)

    fun classify(url: String): Kind {
        var live = false
        for (i in url.indices) {
            val c = url[i]
            if (c.code > 127) return when {
                vodPath.containsMatchIn(url) || vodExtension.containsMatchIn(url) -> Kind.VOD
                livePath.containsMatchIn(url) -> Kind.LIVE
                else -> Kind.UNMARKED
            }
            if (i + 1 == url.length) continue
            when (c) {
                '/' -> when (url[i + 1].code or 32) {
                    'm'.code -> if (pathAt(url, i + 1, "movie") || pathAt(url, i + 1, "movies")) return Kind.VOD
                    's'.code -> if (pathAt(url, i + 1, "series")) return Kind.VOD
                    'v'.code -> if (pathAt(url, i + 1, "vod")) return Kind.VOD
                    'l'.code -> if (pathAt(url, i + 1, "live")) live = true
                }
                '.' -> if (when (url[i + 1].code or 32) {
                    'm'.code -> extensionAt(url, i + 1, "mp4") || extensionAt(url, i + 1, "mkv") ||
                        extensionAt(url, i + 1, "mov") || extensionAt(url, i + 1, "m4v")
                    'a'.code -> extensionAt(url, i + 1, "avi")
                    'w'.code -> extensionAt(url, i + 1, "wmv") || extensionAt(url, i + 1, "webm")
                    'f'.code -> extensionAt(url, i + 1, "flv")
                    else -> false
                }) return Kind.VOD
            }
        }
        return if (live) Kind.LIVE else Kind.UNMARKED
    }

    fun hasLivePath(url: String): Boolean {
        for (i in url.indices) {
            if (url[i].code > 127) return livePath.containsMatchIn(url)
            if (url[i] == '/' && pathAt(url, i + 1, "live")) return true
        }
        return false
    }

    private fun pathAt(url: String, from: Int, word: String): Boolean =
        matches(url, from, word) && from + word.length < url.length && url[from + word.length] == '/'

    private fun extensionAt(url: String, from: Int, word: String): Boolean {
        if (!matches(url, from, word)) return false
        val end = from + word.length
        return end == url.length || url[end] == '?' ||
            // Regex '$' also accepts the position before the final ASCII line terminator.
            end == url.length - 1 && (url[end] == '\n' || url[end] == '\r') ||
            end == url.length - 2 && url[end] == '\r' && url[end + 1] == '\n'
    }

    private fun matches(url: String, from: Int, word: String): Boolean {
        if (from + word.length > url.length) return false
        for (i in word.indices) if ((url[from + i].code or 32) != word[i].code) return false
        return true
    }
}
