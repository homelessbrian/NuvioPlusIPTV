package com.nuvio.tv.livetv.player

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Saving an HLS (m3u8) channel's pieces to a folder, shared by the pause buffer and recordings.
 *
 *  - Live channels start at the newest pieces (about [Track.saveNew]'s lead), the way players
 *    do, not at the start of the channel's look-back window (some channels list an hour or more,
 *    which took so long to save that the pause buffer gave up).
 *  - Channels that send their sound as a separate stream (ARD, ZDF…) get that saved too, with a
 *    small local master playlist joining picture and sound.
 *  - The channel's own break marks (#EXT-X-DISCONTINUITY, e.g. around ads) are kept.
 */
internal object HlsPieces {

    data class Piece(val seq: Long, val dur: Double, val uri: String, val disc: Boolean)

    data class Media(
        val target: Int,
        val pieces: List<Piece>,
        val mapUri: String?,
        val ended: Boolean,
        val encrypted: Boolean
    )

    /** The variant picked from a master playlist, and its separate sound stream (if any). */
    data class Choice(
        val videoUrl: String,
        val audioUrl: String?,
        val audioLanguage: String?,
        /** BANDWIDTH / CODECS / RESOLUTION / FRAME-RATE of the variant, for the local master. */
        val streamAttrs: String
    )

    private val MEDIA_SEQ = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
    private val TARGET = Regex("""#EXT-X-TARGETDURATION:(\d+)""")
    private val MAP = Regex("""#EXT-X-MAP:.*?URI="([^"]+)"""")

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun parseMedia(text: String, fallbackTarget: Int = 6): Media {
        var seq = MEDIA_SEQ.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val target = TARGET.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: fallbackTarget
        val out = ArrayList<Piece>()
        var dur = target.toDouble()
        var disc = false
        var encrypted = false
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXTINF:") -> dur = l.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: target.toDouble()
                l == "#EXT-X-DISCONTINUITY" -> disc = true
                l.startsWith("#EXT-X-KEY") -> if (!l.contains("METHOD=NONE")) encrypted = true
                l.isNotEmpty() && !l.startsWith("#") -> {
                    out += Piece(seq, dur, l, disc)
                    seq++
                    disc = false
                    dur = target.toDouble()
                }
            }
        }
        return Media(
            target = target,
            pieces = out,
            mapUri = MAP.find(text)?.groupValues?.get(1),
            ended = text.contains("#EXT-X-ENDLIST"),
            encrypted = encrypted
        )
    }

    private fun attr(line: String, name: String): String? =
        Regex("""(?:^|[,:])$name=("([^"]*)"|[^,]*)""").find(line)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }?.takeIf { it.isNotEmpty() }

    private fun iso3(lang: String?): String? =
        lang?.substringBefore('-')?.substringBefore('_')?.lowercase()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Locale(it).isO3Language }.getOrNull() ?: it }

    /**
     * Picks the best-quality variant of a master playlist (as before) and, if its sound comes
     * separately, the sound stream: [language] if offered, else the channel's default, else the first.
     */
    fun pick(masterUrl: String, text: String, language: String?): Choice? {
        val lines = text.lines().map { it.trim() }
        var best = -1L
        var bestUri: String? = null
        var bestLine = ""
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = attr(l, "BANDWIDTH")?.toLongOrNull() ?: 0L
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (uri != null && bw >= best) { best = bw; bestUri = uri; bestLine = l }
            }
        }
        val video = resolve(masterUrl, bestUri ?: return null)
        val attrs = buildList {
            add("BANDWIDTH=${best.coerceAtLeast(1)}")
            attr(bestLine, "CODECS")?.let { add("CODECS=\"$it\"") }
            attr(bestLine, "RESOLUTION")?.let { add("RESOLUTION=$it") }
            attr(bestLine, "FRAME-RATE")?.let { add("FRAME-RATE=$it") }
        }.joinToString(",")
        val group = attr(bestLine, "AUDIO") ?: return Choice(video, null, null, attrs)
        val renditions = lines.filter {
            it.startsWith("#EXT-X-MEDIA:") && attr(it, "TYPE") == "AUDIO" && attr(it, "GROUP-ID") == group
        }
        val want = iso3(language)
        val chosen = renditions.firstOrNull { want != null && iso3(attr(it, "LANGUAGE")) == want }
            ?: renditions.firstOrNull { attr(it, "DEFAULT") == "YES" }
            ?: renditions.firstOrNull()
            ?: return Choice(video, null, null, attrs)
        // No URI: that sound is inside the picture's pieces already.
        val audio = attr(chosen, "URI")?.let { resolve(masterUrl, it) }
        return Choice(video, audio, attr(chosen, "LANGUAGE"), attrs)
    }

    /** Index in [pieces] to start from so about [leadMs] up to the newest piece is saved (at least one piece). */
    fun startIndex(pieces: List<Piece>, leadMs: Long): Int {
        if (pieces.isEmpty()) return 0
        var acc = 0.0
        var i = pieces.size
        while (i > 0 && acc * 1000 < leadMs) { i--; acc += pieces[i].dur }
        return i.coerceIn(0, pieces.size - 1)
    }

    /** Where the sound stream starts, to line up with where the picture started. */
    fun matchingStart(audio: List<Piece>, video: Track): Int {
        val first = video.firstSource ?: return 0
        if (audio.isEmpty()) return 0
        // Most packagers number sound and picture pieces alike.
        if (video.lastSourceSeq == audio.last().seq) audio.indexOfFirst { it.seq == first.seq }.takeIf { it >= 0 }?.let { return it }
        return startIndex(audio, (video.sourceLeadSec * 1000).toLong())
    }

    fun resolve(base: String, ref: String): String = runCatching { URL(URL(base), ref).toString() }.getOrDefault(ref)

    /** A local master playlist joining the saved picture and sound playlists. */
    fun writeMaster(file: File, choice: Choice, videoName: String, audioName: String) {
        val lang = choice.audioLanguage?.let { ",LANGUAGE=\"$it\"" } ?: ""
        val text = "#EXTM3U\n#EXT-X-VERSION:6\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Audio\"$lang,DEFAULT=YES,AUTOSELECT=YES,URI=\"$audioName\"\n" +
            "#EXT-X-STREAM-INF:${choice.streamAttrs},AUDIO=\"aud\"\n$videoName\n"
        writeAtomically(file, text)
    }

    /** Seconds saved in a local playlist (for a master: in its picture playlist). */
    fun savedSec(playlist: File): Double = runCatching {
        var text = playlist.readText()
        if (isMaster(text)) {
            val name = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@runCatching 0.0
            text = File(playlist.parentFile, name).readText()
        }
        text.lines().filter { it.startsWith("#EXTINF:") }
            .sumOf { it.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0 }
    }.getOrDefault(0.0)

    /** The playlists in a local master that hold pieces (just [playlist] if it isn't a master). */
    fun mediaPlaylists(playlist: File): List<File> = runCatching {
        val text = playlist.readText()
        if (!isMaster(text)) return@runCatching listOf(playlist)
        val names = text.lines().map { it.trim() }.mapNotNull { l ->
            when {
                l.startsWith("#EXT-X-MEDIA:") -> attr(l, "URI")
                l.isNotEmpty() && !l.startsWith("#") -> l
                else -> null
            }
        }
        names.map { File(playlist.parentFile, it) }.filter { it.isFile }
    }.getOrDefault(listOf(playlist))

    fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    /** One playlist being saved: the picture (with or without sound) or a separate sound stream. */
    class Track(
        var url: String,
        private val dir: File,
        /** Put before every file name ("v", "a"; "" for a single playlist). */
        private val prefix: String,
        private val headers: Map<String, String>,
        private val connect: (String, Map<String, String>) -> HttpURLConnection
    ) {
        data class Saved(val dur: Double, val name: String, val disc: Boolean)

        val saved = ArrayList<Saved>()
        var target = 6
            private set
        private var lastSeq = -1L
        private var mapLine: String? = null
        private var named = 0L
        private var removed = 0L
        private var discSeq = 0L
        private var gap = false

        /** The provider's first piece this track saved from (after a start or a restart), and how much was listed from it to the newest. */
        var firstSource: Piece? = null
            private set
        var sourceLeadSec = 0.0
            private set
        var lastSourceSeq = -1L
            private set

        val totalSec: Double get() = saved.sumOf { it.dur }

        /** Checked while a piece downloads: true stops it part way (a long piece can take minutes). */
        var stopNow: () -> Boolean = { false }
        /** When [stopNow] cuts a piece: true keeps what came in (stopped by hand), false drops it. */
        var keepCut: () -> Boolean = { false }

        fun fetch(): Media {
            val (t, u) = LiveHttp.fetch(url, headers)
            url = u
            return HlsPieces.parseMedia(t, target).also { target = it.target }
        }

        /** After a lost connection on a live channel: start again at the newest pieces, with a break mark. */
        fun restart() {
            lastSeq = -1L
            gap = saved.isNotEmpty()
        }

        /** Marks a break before the next piece (a lost connection while saving a whole show). */
        fun markGap() { gap = saved.isNotEmpty() }

        /**
         * Saves the pieces in [m] not saved yet. The first time (and after [restart]) it starts
         * at [startAt]'s index. Returns the seconds added.
         */
        fun saveNew(
            m: Media,
            /** Tries per piece (a catch-up save can't come back for a piece it missed). */
            tries: Int = 1,
            /** After each piece saved (a long catch-up list shows progress as it goes). */
            onPiece: (() -> Unit)? = null,
            startAt: (Media) -> Int
        ): Double {
            if (mapLine == null && m.mapUri != null) {
                val init = "${prefix}init.mp4"
                download(HlsPieces.resolve(url, m.mapUri), File(dir, init))
                mapLine = "#EXT-X-MAP:URI=\"$init\""
            }
            if (m.pieces.isEmpty()) return 0.0
            lastSourceSeq = m.pieces.last().seq
            // The channel started its numbering again: carry on from its newest pieces.
            if (lastSeq >= 0 && m.pieces.last().seq < lastSeq) restart()
            val from = if (lastSeq < 0) startAt(m).coerceIn(0, m.pieces.size - 1) else 0
            if (lastSeq < 0) {
                firstSource = m.pieces[from]
                sourceLeadSec = m.pieces.drop(from).sumOf { it.dur }
            }
            var added = 0.0
            for (i in from until m.pieces.size) {
                if (stopNow()) break
                val p = m.pieces[i]
                if (p.seq <= lastSeq) continue
                val name = "${prefix}seg$named.${if (mapLine != null) "m4s" else "ts"}"
                // 1.0 = the whole piece; less = cut part way by a stop (its share of the length).
                var got: Double? = null
                for (t in 1..tries.coerceAtLeast(1)) {
                    got = download(HlsPieces.resolve(url, p.uri), File(dir, name))
                    if (got != null || stopNow()) break
                    Thread.sleep(1_000L * t)
                }
                if (got == null) continue
                val dur = p.dur * got
                saved += Saved(dur, name, saved.isNotEmpty() && (gap || (p.disc && i > from)))
                gap = false
                named++
                lastSeq = p.seq
                added += dur
                onPiece?.invoke()
                if (got < 1.0) break
            }
            return added
        }

        /** Drops the oldest pieces past [keepSec] (keeps at least 3). Returns the seconds dropped. */
        fun trim(keepSec: Double): Double {
            var total = totalSec
            var dropped = 0.0
            while (saved.size > 3 && total > keepSec) {
                val first = saved.removeAt(0)
                removed++
                if (first.disc) discSeq++
                total -= first.dur
                dropped += first.dur
                runCatching { File(dir, first.name).delete() }
            }
            return dropped
        }

        fun write(file: File, ended: Boolean) {
            if (saved.isEmpty()) return
            val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-TARGETDURATION:").append(target + 1).append('\n')
            sb.append("#EXT-X-MEDIA-SEQUENCE:").append(removed).append('\n')
            if (discSeq > 0) sb.append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discSeq).append('\n')
            sb.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
            mapLine?.let { sb.append(it).append('\n') }
            // A break mark stays on the oldest piece too (it's counted in the sequence above once dropped).
            saved.forEach { s ->
                if (s.disc) sb.append("#EXT-X-DISCONTINUITY\n")
                sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.dur)).append(",\n").append(s.name).append('\n')
            }
            if (ended) sb.append("#EXT-X-ENDLIST\n")
            writeAtomically(file, sb.toString())
        }

        /**
         * Downloads one piece. Returns 1.0 when it all came in, the share that came in when a stop
         * cut it and the cut part is kept, or null (failed, or cut and dropped).
         */
        private fun download(url: String, to: File): Double? {
            val tmp = File(to.parentFile, to.name + ".part")
            return runCatching {
                val c = connect(url, headers)
                val length = c.contentLengthLong
                var got = 0L
                var cut = false
                c.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (stopNow()) { cut = true; break }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                        }
                    }
                }
                if (cut) runCatching { c.disconnect() }
                val share = when {
                    !cut -> 1.0
                    got > 0 && length > 0 && keepCut() -> (got.toDouble() / length).coerceIn(0.01, 0.99)
                    else -> null
                }
                if (share == null) { tmp.delete(); null }
                else if (tmp.renameTo(to)) share else null
            }.getOrElse { tmp.delete(); null }
        }
    }
}
