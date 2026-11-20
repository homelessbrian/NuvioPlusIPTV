package com.nuvio.tv.livetv.ondemand

/**
 * Providers' own language labels on groups ("FR | Films", "|AR| Movies", "Bollywood", "TURKISH
 * SERIES") and on titles ("DE - Der Film"). Used by "Hide non-English titles" to set titles
 * aside straight from the catalog, with no lookups at all. Only labels for other languages count:
 * an "EN" label says what the audio is, not that the film was made in English (that is checked
 * with TMDB, see [OnDemandLanguages]).
 */
object VodLanguageTags {

    /** Short language / country codes, only counted as a label (in front, or in brackets / bars). */
    private val CODES = setOf(
        "FR", "FRA", "FRE", "VF", "VFF", "VOSTFR", "DE", "GER", "DEU", "IT", "ITA", "ES", "ESP", "SPA", "LAT", "LATAM", "MX",
        "PT", "POR", "BR", "PTBR", "NL", "NLD", "DUT", "PL", "POL", "RU", "RUS", "TR", "TUR", "AR", "ARA", "ARB",
        "HI", "HIN", "IN", "IND", "KR", "KO", "KOR", "JP", "JA", "JPN", "CN", "CHI", "ZH", "HK", "TW",
        "SE", "SV", "SWE", "NO", "NOR", "DK", "DA", "DAN", "FI", "FIN", "GR", "EL", "GRE", "HU", "HUN",
        "CZ", "CS", "CZE", "SK", "SVK", "RO", "ROM", "RON", "BG", "BUL", "HR", "CRO", "RS", "SR", "SRB",
        "BA", "BIH", "SI", "SLO", "MK", "MKD", "AL", "ALB", "SQ", "YU", "EXYU", "UA", "UKR", "IR", "FA", "PER",
        "UR", "PK", "TH", "THA", "VN", "VI", "VIE", "ID", "INDO", "MY", "MAL", "HE", "HEB", "IL", "KU", "KUR",
        "TA", "TAM", "TE", "TEL", "ML", "PA", "PUN", "BN", "BEN", "AF", "AFG", "SO", "SOM", "AZ", "AZE",
        "GE", "GEO", "AM", "ARM", "LT", "LV", "EE", "IS", "ISL"
    )

    /** Language and country names: counted anywhere in a group's name. */
    private val WORDS = setOf(
        "ARABIC", "ARAB", "FRENCH", "FRANCAIS", "FRANÇAIS", "FRANCE", "GERMAN", "DEUTSCH", "GERMANY", "ITALIAN", "ITALIANO", "ITALY",
        "SPANISH", "ESPANOL", "ESPAÑOL", "CASTELLANO", "SPAIN", "LATINO", "LATINA", "MEXICAN", "MEXICO",
        "PORTUGUESE", "PORTUGUES", "PORTUGUÊS", "PORTUGAL", "BRAZIL", "BRASIL", "BRAZILIAN", "DUTCH", "NEDERLANDS", "NETHERLANDS",
        "POLISH", "POLSKI", "POLAND", "RUSSIAN", "RUSSIA", "TURKISH", "TURKCE", "TÜRKÇE", "TURK", "TÜRK",
        "HINDI", "INDIAN", "INDIA", "BOLLYWOOD", "TOLLYWOOD", "KOLLYWOOD", "TAMIL", "TELUGU", "MALAYALAM", "KANNADA",
        "PUNJABI", "BENGALI", "BANGLA", "MARATHI", "GUJARATI", "URDU", "PAKISTANI", "PERSIAN", "FARSI", "IRANIAN", "KURDISH",
        "KOREAN", "KOREA", "KDRAMA", "JAPANESE", "JAPAN", "CHINESE", "CHINA", "MANDARIN", "CANTONESE", "TAIWANESE",
        "THAI", "VIETNAMESE", "INDONESIAN", "MALAY", "FILIPINO", "TAGALOG", "PINOY", "GREEK", "HEBREW", "ISRAELI",
        "SWEDISH", "NORWEGIAN", "DANISH", "FINNISH", "NORDIC", "SCANDINAVIAN", "ICELANDIC", "ALBANIAN", "ROMANIAN",
        "HUNGARIAN", "CZECH", "SLOVAK", "SERBIAN", "CROATIAN", "BOSNIAN", "SLOVENIAN", "MACEDONIAN", "BULGARIAN",
        "UKRAINIAN", "ARMENIAN", "GEORGIAN", "AFGHAN", "PASHTO", "SOMALI", "AMHARIC", "AZERBAIJANI",
        // "NETFLIX ASIA", "ASIAN MOVIES": Asian-language films.
        "ASIA", "ASIAN"
    )

    /** "(EN Subs)", "(Eng Sub)", "SUB ENG": shown with English subtitles, so made in another language. */
    private val ENGLISH_SUBS = Regex("""(?i)[\[(]\s*(?:en|eng|english)\s*-?\s*subs?(?:titles|titled)?\s*[\])]|\bsub(?:s|bed)?\s*-?\s*eng\b|\beng\s*-?\s*sub(?:s|bed)?\b""")

    /** Letters of scripts other than Latin (Arabic, Hebrew, Cyrillic, Greek, Devanagari, Thai, CJK, Hangul). */
    private fun isOtherScript(c: Char) = c in '\u0370'..'\u03FF' || c in '\u0400'..'\u04FF' || c in '\u0590'..'\u06FF' ||
        c in '\u0900'..'\u097F' || c in '\u0E00'..'\u0E7F' || c in '\u3040'..'\u30FF' || c in '\u3400'..'\u9FFF' || c in '\uAC00'..'\uD7AF'

    /** A code standing as a label: at the start before a separator, or inside brackets or bars. */
    private val LEADING = Regex("""^\s*[\[(|]?\s*([\p{L}]{2,6})\s*(?:[\])|:]|\s[-–—]\s|-\s)""")
    private val BRACKETED = Regex("""[\[(|]\s*([\p{L}]{2,6})\s*[\])|]""")
    private val NON_LETTERS = Regex("""[^\p{L}]+""")

    /** A provider group whose name says it's in another language. */
    fun groupIsForeign(name: String): Boolean {
        val up = name.uppercase()
        if (up.split(NON_LETTERS).any { it.isNotEmpty() && (it in WORDS || it.removePrefix("K-") in WORDS) }) return true
        if (up.contains("K-DRAMA") || up.contains("EX-YU")) return true
        LEADING.find(up)?.groupValues?.get(1)?.let { if (it in CODES) return true }
        return BRACKETED.findAll(up).any { it.groupValues[1] in CODES }
    }

    /**
     * A title whose provider label (one the provider puts in front of many titles, learned from
     * its catalog: see [OnDemandDatabase.knownPrefixes]) is another language. A one-off "IT - …"
     * is a film called It, not an Italian label.
     */
    fun titleIsForeign(name: String): Boolean {
        if (ENGLISH_SUBS.containsMatchIn(name)) return true
        // A name written mostly in another script ("火锅之王 (2022)").
        val letters = name.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.count { isOtherScript(it) } * 2 > letters.length) return true
        val seg = OnDemandDatabase.leadingSegment(name) ?: return false
        if (seg !in OnDemandDatabase.knownPrefixes) return false
        return seg.uppercase().split(NON_LETTERS).any { it.isNotEmpty() && (it in CODES || it in WORDS) }
    }
}
