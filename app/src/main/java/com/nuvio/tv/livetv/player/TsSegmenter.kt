package com.nuvio.tv.livetv.player

import java.io.File
import java.io.FileOutputStream
import java.io.BufferedOutputStream

/**
 * Cuts a live MPEG-TS stream into short pieces with a local HLS playlist, as it arrives, so a
 * recording can be played, paused and skipped through while it's still being made (the same
 * way the player handles HLS channels). Used by pause and rewind, and by recordings.
 *
 *  - Pieces are about [targetSec] long (the first one shorter, so playback can start soon),
 *    cut where a picture starts cleanly when the stream marks one.
 *  - Every piece starts with the stream's program tables (PAT / PMT), so each one plays on
 *    its own.
 *  - Lengths come from the stream's own clock (PCR) when it has one, else the wall clock.
 *  - [keepSec]: older pieces are dropped past this much (null: keep everything, recordings).
 *
 * Feed it bytes with [write] (any sizes); call [finish] at the end.
 */
class TsSegmenter(
    private val dir: File,
    private val targetSec: Double = 6.0,
    private val keepSec: Double? = null,
    /** A piece was saved (its length in seconds); the playlist is already updated. */
    private val onSegment: (Double) -> Unit = {}
) {
    data class Piece(val seq: Long, val name: String, val durationSec: Double, val discontinuity: Boolean = false) {
        internal val playlistEntry = LivePlaylistWriter.entry(durationSec, name, discontinuity)
    }

    val playlist: File = File(dir, "index.m3u8")

    private val pieces = ArrayList<Piece>()
    private var maxPieceDurationSec = 0.0
    @Volatile var trimmedSec: Double = 0.0
        private set
    @Volatile var totalSec: Double = 0.0
        private set

    // Packet assembly (input arrives in any sizes; TS packets are 188 bytes).
    private val pkt = ByteArray(PACKET)
    private var pktFill = 0
    private var synced = false

    // Program tables, repeated at the start of every piece.
    private var pat: ByteArray? = null
    private val pmtPids = HashSet<Int>()
    private val pmts = HashMap<Int, ByteArray>()
    private var pcrPid = -1

    // The piece being written.
    private var seq = 0L
    private var out: BufferedOutputStream? = null
    private var outFile: File? = null
    private var pieceStartPcr = -1L
    private var lastPcr = -1L
    private var pieceStartWall = 0L
    private var pieceBytes = 0L
    private var pieceDiscontinuity = false

    fun write(buf: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (!synced) {
                // Find the sync byte that starts a packet.
                if (buf[i] != SYNC) { i++; continue }
                synced = true
                pktFill = 0
            }
            val take = minOf(PACKET - pktFill, end - i)
            System.arraycopy(buf, i, pkt, pktFill, take)
            pktFill += take
            i += take
            if (pktFill == PACKET) {
                pktFill = 0
                if (pkt[0] != SYNC) { synced = false; continue }
                packet(pkt)
            }
        }
    }

    /**
     * The connection dropped and a new one is starting: close the current piece and mark the
     * next as a fresh start (its clock starts over), so the player carries on across the gap.
     */
    fun reconnected() {
        closePiece()
        synced = false
        pktFill = 0
        lastPcr = -1L
        nextIsDiscontinuity = pieces.isNotEmpty()
    }
    private var nextIsDiscontinuity = false

    /** Closes the last piece and marks the playlist finished (no more to come). */
    fun finish(ended: Boolean = true) {
        closePiece()
        writePlaylist(ended)
    }

    /** Releases an abandoned writer without publishing its unfinished piece or ending its playlist. */
    fun discard() {
        val stream = out
        val file = outFile
        out = null
        outFile = null
        try { stream?.close() } finally { file?.delete() }
    }

    private fun packet(p: ByteArray) {
        val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
        val pusi = (p[1].toInt() and 0x40) != 0
        val afc = (p[3].toInt() shr 4) and 0x3
        var randomAccess = false
        var pcr = -1L
        if ((afc == 2 || afc == 3) && (p[4].toInt() and 0xFF) > 0) {
            val flags = p[5].toInt() and 0xFF
            randomAccess = (flags and 0x40) != 0
            if ((flags and 0x10) != 0 && (p[4].toInt() and 0xFF) >= 7) {
                pcr = ((p[6].toLong() and 0xFF) shl 25) or ((p[7].toLong() and 0xFF) shl 17) or
                    ((p[8].toLong() and 0xFF) shl 9) or ((p[9].toLong() and 0xFF) shl 1) or
                    ((p[10].toLong() and 0x80) shr 7)
            }
        }
        // Tables: keep the latest copy of each.
        if (pid == 0 && pusi) {
            pat = p.copyOf()
            readPat(p)
        } else if (pid in pmtPids && pusi) {
            pmts[pid] = p.copyOf()
            readPmt(p)
        }
        if (pcr >= 0 && (pcrPid < 0 || pid == pcrPid)) {
            if (pcrPid < 0) pcrPid = pid
            // Tables can open the first piece before its clock arrives. Start measuring it
            // from that first PCR too, rather than timing a buffered download by wall clock.
            if (out != null && pieceStartPcr < 0) pieceStartPcr = pcr
            lastPcr = pcr
        }

        // Time to start a new piece?
        if (out == null) {
            // Wait for the tables before the first piece, so it plays on its own.
            if (pat == null || pmts.isEmpty()) return
            openPiece()
        } else {
            val len = currentLengthSec()
            val target = if (pieces.isEmpty()) 2.0 else targetSec
            val cleanCut = randomAccess && len >= target
            val forcedCut = len >= target * 2 && pusi && pid == pcrPid
            if (cleanCut || forcedCut || len >= target * 3) {
                closePiece()
                openPiece()
            }
        }
        out?.write(p)
        pieceBytes += PACKET
    }

    private fun currentLengthSec(): Double {
        if (pieceStartPcr >= 0 && lastPcr >= pieceStartPcr) {
            val d = (lastPcr - pieceStartPcr) / 90_000.0
            if (d < 120) return d
        }
        return (System.currentTimeMillis() - pieceStartWall) / 1000.0
    }

    private fun openPiece() {
        val f = File(dir, "seg$seq.ts")
        outFile = f
        out = BufferedOutputStream(FileOutputStream(f), 64 * 1024).also { o ->
            pat?.let { o.write(it) }
            pmts.values.forEach { o.write(it) }
        }
        pieceStartPcr = lastPcr
        pieceStartWall = System.currentTimeMillis()
        pieceBytes = 0
        pieceDiscontinuity = nextIsDiscontinuity
        nextIsDiscontinuity = false
    }

    private fun closePiece() {
        val o = out ?: return
        val f = outFile ?: return
        out = null
        outFile = null
        // A piece must be fully on disk before its playlist entry or callback exposes it.
        // Propagate a failed flush to the recorder instead of publishing a truncated piece.
        o.close()
        if (pieceBytes == 0L) { f.delete(); return }
        val len = currentLengthSec().coerceAtLeast(0.1)
        pieces += Piece(seq, f.name, len, discontinuity = pieceDiscontinuity)
        maxPieceDurationSec = maxOf(maxPieceDurationSec, len)
        totalSec += len
        seq++
        // Rewind buffer: drop what's past the time limit.
        keepSec?.let { keep ->
            var kept = pieces.sumOf { it.durationSec }
            var removedLongest = false
            while (pieces.size > 3 && kept > keep) {
                val first = pieces.removeAt(0)
                if (first.durationSec == maxPieceDurationSec) removedLongest = true
                kept -= first.durationSec
                trimmedSec += first.durationSec
                runCatching { File(dir, first.name).delete() }
            }
            if (removedLongest) maxPieceDurationSec = pieces.maxOf { it.durationSec }
        }
        writePlaylist(ended = false)
        onSegment(len)
    }

    private fun writePlaylist(ended: Boolean) {
        if (pieces.isEmpty()) return
        // A steady target length from the start (pieces can run longer than planned when the
        // stream rarely marks a clean cut): a target that grows mid-way, or pieces longer than
        // it, made the player think the playlist had stalled and give up.
        val target = maxOf(20, kotlin.math.ceil(maxPieceDurationSec).toInt())
        runCatching {
            LivePlaylistWriter.write(playlist) { writer ->
                writer.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:").append(target.toString()).append('\n')
                writer.append("#EXT-X-MEDIA-SEQUENCE:").append(pieces.first().seq.toString()).append('\n')
                // A rewind buffer drops pieces, which an EVENT playlist cannot do.
                if (keepSec == null) writer.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
                pieces.forEach { writer.write(it.playlistEntry) }
                if (ended) writer.append("#EXT-X-ENDLIST\n")
            }
        }
    }

    /** The PMT packet ids listed in the PAT. */
    private fun readPat(p: ByteArray) {
        val start = payloadStart(p) ?: return
        var i = start + 1 + (p[start].toInt() and 0xFF) // pointer field
        if (i + 8 > PACKET) return
        val sectionLen = ((p[i + 1].toInt() and 0x0F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val endOfPrograms = minOf(i + 3 + sectionLen - 4, PACKET)
        i += 8
        val found = HashSet<Int>()
        while (i + 4 <= endOfPrograms) {
            val program = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            val pid = ((p[i + 2].toInt() and 0x1F) shl 8) or (p[i + 3].toInt() and 0xFF)
            if (program != 0) found += pid
            i += 4
        }
        if (found.isNotEmpty() && found != pmtPids) {
            pmtPids.clear(); pmtPids += found
            pmts.keys.retainAll(found)
        }
    }

    /** The PCR packet id from a PMT (the stream's clock). */
    private fun readPmt(p: ByteArray) {
        val start = payloadStart(p) ?: return
        val i = start + 1 + (p[start].toInt() and 0xFF)
        if (i + 10 > PACKET) return
        val pid = ((p[i + 8].toInt() and 0x1F) shl 8) or (p[i + 9].toInt() and 0xFF)
        if (pid != 0x1FFF) pcrPid = pid
    }

    private fun payloadStart(p: ByteArray): Int? {
        val afc = (p[3].toInt() shr 4) and 0x3
        return when (afc) {
            1 -> 4
            3 -> (5 + (p[4].toInt() and 0xFF)).takeIf { it < PACKET }
            else -> null
        }
    }

    private companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
    }
}
