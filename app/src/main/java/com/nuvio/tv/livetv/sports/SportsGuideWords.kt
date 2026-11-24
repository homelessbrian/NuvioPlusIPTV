package com.nuvio.tv.livetv.sports

/** Token lookup without a split list or a new String for every word of every description. */
internal class SportsGuideWords(words: Collection<String>) {
    val byFirst: Map<String, List<String>> = words.groupBy { it.substringBefore(' ') }
    private val byHash = byFirst.entries.groupBy { it.key.hashCode() }

    fun matched(title: String, description: String): HashSet<String>? {
        var result: HashSet<String>? = null
        fun look(text: String) {
            var start = 0
            while (start < text.length) {
                while (start < text.length && text[start] == ' ') start++
                var end = start
                var hash = 0
                while (end < text.length && text[end] != ' ') { hash = 31 * hash + text[end].code; end++ }
                if (end > start) for (entry in byHash[hash].orEmpty()) {
                    if (entry.key.length != end - start || !text.regionMatches(start, entry.key, 0, entry.key.length)) continue
                    for (word in entry.value) {
                        if (result?.contains(word) == true) continue
                        if (SportsCatalog.hasWord(title, word) || SportsCatalog.hasWord(description, word)) {
                            (result ?: HashSet<String>().also { result = it }).add(word)
                        }
                    }
                }
                start = end + 1
            }
        }
        look(title)
        look(description)
        return result
    }
}
