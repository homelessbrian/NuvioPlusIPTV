package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.LiveChannel
import java.text.Normalizer

/**
 * Name (A–Z) for channels, the way people read the names rather than how providers type them:
 * - tags in front ("US: ", "UK | ", "[HD] ", "4K ", "★ ") don't count, so "US: ESPN" sits with E;
 * - fancy letters (𝐄𝐒𝐏𝐍, ＥＳＰＮ, Ⓔ, ᴇsᴘɴ) and accents count as the plain letters;
 * - numbers go by value, so "Channel 2" comes before "Channel 10".
 * Names that tie keep their playlist order.
 */
internal object ChannelNameSort {

    fun sort(channels: List<LiveChannel>): List<LiveChannel> {
        if (channels.size < 2) return channels
        val keyed = channels.map { it to key(it.name) }
        return keyed.sortedWith { a, b -> compareNatural(a.second, b.second) }.map { it.first }
    }

    /** The part of [name] that's sorted on: plain lower-case letters and digits, tags in front left out. */
    fun key(name: String): String {
        KEYS[name]?.let { return it }
        val folded = if (name.all { it.code < 0x80 }) name
        else Normalizer.normalize(foldFancy(name), Normalizer.Form.NFD).replace(MARKS, "")
        val plain = folded.lowercase()
        val key = stripLeadingTags(plain).ifEmpty { plain.trim() }
        // Guide rebuilds sort the same names again and again; big playlists stay well under the cap.
        if (KEYS.size > MAX_KEYS) KEYS.clear()
        KEYS[name] = key
        return key
    }

    private const val MAX_KEYS = 150_000
    private val KEYS = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Leading tags: symbols, "[…]" / "(…)" / "|…|", "us:" / "uk |" / "fhd|", "us - ", "hd ". */
    private fun stripLeadingTags(text: String): String {
        var s = text
        while (true) {
            val before = s
            s = s.trimStart { !it.isLetterOrDigit() && it != '[' && it != '(' && it != '|' }
            if (s.isEmpty()) return s
            // Bracketed tag: "[HD] ", "(US) ", "|UK| ".
            val close = when (s[0]) { '[' -> ']'; '(' -> ')'; '|' -> '|'; else -> null }
            if (close != null) {
                val end = s.indexOf(close, 1)
                s = if (end in 1..12) s.substring(end + 1) else s.substring(1)
                continue
            }
            // Short code followed by ":" or "|" ("us:", "uk |", "4k|", "vip:").
            val word = s.takeWhile { it.isLetterOrDigit() }
            val rest = s.substring(word.length).trimStart(' ')
            if ((word.length in 1..3 || word in QUALITY) && rest.isNotEmpty() && rest[0] in SEPARATORS) {
                s = rest.substring(1)
                continue
            }
            // Two-letter country code then a dash ("us - espn"); longer words stay ("abc - east").
            if (word.length == 2 && word.all { it.isLetter() } && (rest.startsWith("- ") || rest.startsWith("– "))) {
                s = rest.substring(2)
                continue
            }
            // A quality tag on its own in front ("hd espn", "4k sky").
            if (word in QUALITY && rest.length < s.length - word.length && rest.isNotEmpty()) {
                s = rest
                continue
            }
            if (s == before) return s
        }
    }

    /** Letters and digits compared as text, digit runs compared by value. */
    fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                // Spaces and punctuation sort before letters and digits ("sky one" before "skyone").
                val ra = rank(ca)
                val rb = rank(cb)
                if (ra != rb) return ra - rb
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun rank(c: Char): Int = when {
        c.isWhitespace() -> 0
        !c.isLetterOrDigit() -> 1
        c.isDigit() -> 2
        else -> 3
    }

    /** Fancy Unicode letters (math bold/italic, full-width, circled, squared, small caps) as plain ones. */
    fun foldFancy(text: String): String {
        if (text.all { it.code < 0x80 }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp: Int
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                cp = ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                cp = c.code
                i++
            }
            val plain = plainOf(cp)
            if (plain != null) out.append(plain)
            else if (cp >= 0x10000) out.appendCodePoint(cp)
            else out.append(cp.toChar())
        }
        return out.toString()
    }

    private fun plainOf(cp: Int): Char? = when (cp) {
        in 0xFF01..0xFF5E -> (cp - 0xFEE0).toChar()                    // ＡＢＣ
        in 0x1D400..0x1D6A3 -> letter((cp - 0x1D400) % 52)              // 𝐀𝐁𝐂, 𝘈, 𝔸…
        in 0x1D7CE..0x1D7FF -> ('0' + (cp - 0x1D7CE) % 10)              // 𝟏𝟐𝟑
        in 0x24B6..0x24CF -> ('A' + (cp - 0x24B6))                      // Ⓐ
        in 0x24D0..0x24E9 -> ('a' + (cp - 0x24D0))                      // ⓐ
        in 0x1F130..0x1F149 -> ('A' + (cp - 0x1F130))                   // 🄰
        in 0x1F150..0x1F169 -> ('A' + (cp - 0x1F150))                   // 🅐
        in 0x1F170..0x1F189 -> ('A' + (cp - 0x1F170))                   // 🅰
        in 0x1F1E6..0x1F1FF -> ('A' + (cp - 0x1F1E6))                   // 🇦 (regional letters)
        in 0x2460..0x2468 -> ('1' + (cp - 0x2460))                      // ①
        else -> SMALL[cp]
    }

    private fun letter(i: Int): Char = if (i < 26) 'A' + i else 'a' + (i - 26)

    /** Small capitals and raised letters/digits (ᴇsᴘɴ, ᴴᴰ, ²). */
    private val SMALL: Map<Int, Char> = mapOf(
        0x1D00 to 'a', 0x0299 to 'b', 0x1D04 to 'c', 0x1D05 to 'd', 0x1D07 to 'e', 0xA730 to 'f',
        0x0262 to 'g', 0x029C to 'h', 0x026A to 'i', 0x1D0A to 'j', 0x1D0B to 'k', 0x029F to 'l',
        0x1D0D to 'm', 0x0274 to 'n', 0x1D0F to 'o', 0x1D18 to 'p', 0x0280 to 'r', 0xA731 to 's',
        0x1D1B to 't', 0x1D1C to 'u', 0x1D20 to 'v', 0x1D21 to 'w', 0x028F to 'y', 0x1D22 to 'z',
        0x1D2C to 'A', 0x1D2E to 'B', 0x1D30 to 'D', 0x1D31 to 'E', 0x1D33 to 'G', 0x1D34 to 'H',
        0x1D35 to 'I', 0x1D36 to 'J', 0x1D37 to 'K', 0x1D38 to 'L', 0x1D39 to 'M', 0x1D3A to 'N',
        0x1D3C to 'O', 0x1D3E to 'P', 0x1D3F to 'R', 0x1D40 to 'T', 0x1D41 to 'U', 0x1D42 to 'W',
        0x2070 to '0', 0x00B9 to '1', 0x00B2 to '2', 0x00B3 to '3', 0x2074 to '4', 0x2075 to '5',
        0x2076 to '6', 0x2077 to '7', 0x2078 to '8', 0x2079 to '9',
    )

    private val SEPARATORS = setOf(':', '|', '│', '┃', '¦', '»', '►', '▶')
    private val QUALITY = setOf("4k", "8k", "uhd", "fhd", "hd", "sd", "hevc")
    private val MARKS = Regex("\\p{Mn}+")
}
