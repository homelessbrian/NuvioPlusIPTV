package com.nuvio.tv.livetv.record

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * join.idx in a recording's folder: where each recorded piece ended up once it was joined into
 * one file per show. The recording's playlists keep naming the pieces (so nothing else has to
 * change); the recordings server looks a piece up here and reads its part of the joined file.
 *
 * A recording is joined only when its pieces can simply be put end to end (plain TS pieces, one
 * stream). Others (picture and sound saved separately, fMP4 pieces) stay as pieces ([Mode.PIECES]).
 *
 * Text, one item per line, tab-separated:
 *   NUVIO-JOIN 1
 *   M JOIN | PIECES                (missing until the first pieces show which it is)
 *   B <name>                       (the joined file's name, without ".ts")
 *   F <no> L|N <0|1 done> <path>   (L: a file in this folder; N: a path in the network folder)
 *   P <piece> <no> <offset> <length>
 * Pieces are added as lines at the end; the F lines are rewritten (whole file) when a file is
 * finished and renamed.
 */
internal class JoinIndex private constructor(
    val mode: Mode?,
    /** The joined file's name without ".ts" (kept so a second part gets the same name). */
    val base: String?,
    val files: List<JoinFile>,
    val pieces: Map<String, JoinPiece>,
) {
    enum class Mode { JOIN, PIECES }

    data class JoinFile(val no: Int, val onNetwork: Boolean, val done: Boolean, val path: String)
    data class JoinPiece(val name: String, val file: Int, val offset: Long, val length: Long)

    fun file(no: Int): JoinFile? = files.firstOrNull { it.no == no }

    /** How far file [no] has been written (its pieces end to end). */
    fun lengthOf(no: Int): Long = pieces.values.filter { it.file == no }.maxOfOrNull { it.offset + it.length } ?: 0L

    /** The file new pieces go into: the newest one not finished. */
    val current: JoinFile? get() = files.filter { !it.done }.maxByOrNull { it.no }

    companion object {
        const val NAME = "join.idx"
        /** Written when the joining is all finished (so a folder can be checked without reading the index). */
        const val DONE_NAME = "join.ok"
        private const val HEADER = "NUVIO-JOIN 1"

        fun exists(dir: File): Boolean = File(dir, NAME).isFile
        fun finished(dir: File): Boolean = File(dir, DONE_NAME).isFile

        /** Starts an index for a new recording (it's joined, or not, once its first pieces are in). */
        fun start(dir: File) {
            runCatching { File(dir, NAME).writeText("$HEADER\n") }
        }

        fun read(dir: File): JoinIndex? = runCatching { parse(File(dir, NAME).readLines()) }.getOrNull()

        fun parse(lines: List<String>): JoinIndex? {
            if (lines.firstOrNull()?.trim() != HEADER) return null
            var mode: Mode? = null
            var base: String? = null
            val files = LinkedHashMap<Int, JoinFile>()
            val pieces = LinkedHashMap<String, JoinPiece>()
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                when (f.getOrNull(0)) {
                    "M" -> mode = runCatching { Mode.valueOf(f[1]) }.getOrNull()
                    "B" -> base = f.drop(1).joinToString("\t").ifBlank { null }
                    "F" -> if (f.size >= 5) f[1].toIntOrNull()?.let { no ->
                        files[no] = JoinFile(no, f[2] == "N", f[3] == "1", f.drop(4).joinToString("\t"))
                    }
                    "P" -> if (f.size >= 5) {
                        val no = f[2].toIntOrNull(); val off = f[3].toLongOrNull(); val len = f[4].toLongOrNull()
                        if (no != null && off != null && len != null) pieces[f[1]] = JoinPiece(f[1], no, off, len)
                    }
                }
            }
            return JoinIndex(mode, base, files.values.toList(), pieces)
        }

        /** Rewrites the whole index (mode and files changed). */
        fun write(dir: File, mode: Mode?, base: String?, files: List<JoinFile>, pieces: Collection<JoinPiece>) {
            val sb = StringBuilder(HEADER).append('\n')
            mode?.let { sb.append("M\t").append(it.name).append('\n') }
            base?.let { sb.append("B\t").append(it).append('\n') }
            files.forEach { f -> sb.append("F\t").append(f.no).append('\t').append(if (f.onNetwork) "N" else "L").append('\t').append(if (f.done) "1" else "0").append('\t').append(f.path).append('\n') }
            pieces.forEach { p -> sb.append(pieceLine(p)) }
            val tmp = File(dir, "$NAME.tmp")
            tmp.writeText(sb.toString())
            val f = File(dir, NAME)
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        }

        /** Adds one joined piece. */
        fun addPiece(dir: File, p: JoinPiece) {
            java.io.FileOutputStream(File(dir, NAME), true).use { it.write(pieceLine(p).toByteArray()) }
        }

        private fun pieceLine(p: JoinPiece) = "P\t${p.name}\t${p.file}\t${p.offset}\t${p.length}\n"
    }
}

/** Names for joined recordings: "Show - S01E02.ts", or "Show - 2026-11-21 21.00.ts". */
internal object RecordingNames {
    private val EPISODE_NUMBERS = Regex("""(?i)s\s*0*(\d{1,3})\s*[.\- ]?\s*e\s*0*(\d{1,4})""")

    /** A name that works on Windows, NAS boxes and USB drives (no \ / : * ? " < > |). */
    fun clean(text: String): String {
        val t = text.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.', ' ')
        return t.take(100).trimEnd('.', ' ').ifBlank { "Recording" }
    }

    /** The show's folder name on the network folder. */
    fun showFolder(rec: LiveTvRecording): String = clean(rec.title)

    /** The file name without ".ts". */
    fun baseName(rec: LiveTvRecording): String {
        val title = clean(rec.title)
        val ep = rec.episode?.let { EPISODE_NUMBERS.find(it) }
        if (ep != null) return String.format(Locale.US, "%s - S%02dE%02d", title, ep.groupValues[1].toInt(), ep.groupValues[2].toInt())
        val aired = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs
        return title + " - " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(aired))
    }

    /** "Name.ts", or "Name - part 2.ts" for the second file of a long recording. */
    fun fileName(base: String, part: Int): String = if (part <= 1) "$base.ts" else "$base - part $part.ts"

    const val IN_PROGRESS = ".part"
}
