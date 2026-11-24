package com.nuvio.tv.livetv.data

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Content identity for downloaded guides, independent of gzip headers/packing. */
internal object DownloadContent {
    data class Comparison(val same: Boolean, val decodedFingerprint: String?)
    private data class Attributes(val size: Long, val modified: Long, val stamp: String)

    private fun attributes(file: File) = Attributes(file.length(), file.lastModified(),
        runCatching { File(file.parentFile, file.name + ".stamp").readText().trim() }.getOrDefault(""))
    private fun sidecar(file: File) = File(file.parentFile, file.name + ".decoded")
    private val fingerprintPattern = Regex("[0-9a-f]{32}")

    fun fingerprint(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    fun cached(file: File): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val parts = sidecar(file).readLines()
        val current = attributes(file)
        parts.getOrNull(4)?.takeIf {
            parts.getOrNull(0) == "2" && parts.getOrNull(1)?.toLongOrNull() == current.size &&
                parts.getOrNull(2)?.toLongOrNull() == current.modified && parts.getOrNull(3) == current.stamp && fingerprintPattern.matches(it)
        }
    }.getOrNull()

    fun remember(file: File, fingerprint: String?) {
        if (fingerprint == null) { runCatching { sidecar(file).delete() }; return }
        runCatching {
            val current = attributes(file)
            val target = sidecar(file)
            val tmp = File(target.parentFile, target.name + ".tmp")
            try {
                tmp.writeText("2\n${current.size}\n${current.modified}\n${current.stamp}\n$fingerprint\n")
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            } finally { tmp.delete() }
        }
    }

    /** Freshness checks change mtime, not content; keep a valid decoded identity with it. */
    fun touch(file: File, now: Long = System.currentTimeMillis()) {
        val known = cached(file)
        file.setLastModified(now)
        if (known != null) remember(file, known)
    }

    /** Identical wire bytes take the fast path: no gzip decoding or decoded-cache seeding. */
    fun compare(previous: File, candidate: File, candidateDecoded: String? = null): Comparison {
        if (!previous.exists()) return Comparison(false, candidateDecoded)
        if (previous.length() == candidate.length() && rawFingerprint(previous) == rawFingerprint(candidate)) {
            return Comparison(true, candidateDecoded ?: cached(previous))
        }
        val after = candidateDecoded ?: decodedFingerprint(candidate)
        // A broken old cache must not prevent a valid replacement from repairing it.
        val before = cached(previous) ?: runCatching { decodedFingerprint(previous) }.getOrNull()?.also { remember(previous, it) }
        return Comparison(before == after, after)
    }

    private fun rawFingerprint(file: File): String = file.inputStream().use { digest(it) }
    private fun decodedFingerprint(file: File): String = openDecoded(file).use { digest(it) }

    private fun openDecoded(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream(), 64 * 1024)
        input.mark(2)
        val zipped = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (zipped) try { GZIPInputStream(input, 64 * 1024) } catch (error: Throwable) {
            input.close()
            throw error
        } else input
    }

    private fun digest(input: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            md.update(buffer, 0, size)
        }
        return fingerprint(md)
    }

    /** Hash the parser's existing decoded reads, never an extra decode pass. Partial probes
     * and failed/truncated reads cannot publish a fingerprint. */
    fun observe(file: File, decoded: InputStream): InputStream {
        if (cached(file) != null) return decoded
        val original = attributes(file)
        val digest = MessageDigest.getInstance("MD5")
        return object : FilterInputStream(decoded) {
            private var completed = false

            private fun finish() {
                if (completed) return
                completed = true
                if (attributes(file) == original) remember(file, fingerprint(digest))
            }

            override fun read(): Int = decoded.read().also { if (it < 0) finish() else digest.update(it.toByte()) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = decoded.read(bytes, offset, length).also {
                if (it < 0) finish() else if (it > 0) digest.update(bytes, offset, it)
            }
            // Skips must contribute too; mark/reset is unsupported to avoid counting bytes twice.
            override fun skip(count: Long): Long {
                val bytes = ByteArray(minOf(count.coerceAtLeast(0), 8_192L).toInt())
                var skipped = 0L
                while (skipped < count) {
                    val size = read(bytes, 0, minOf(bytes.size.toLong(), count - skipped).toInt())
                    if (size < 0) break
                    skipped += size
                }
                return skipped
            }
            override fun markSupported() = false
            override fun mark(readlimit: Int) = Unit
            override fun reset(): Unit = throw java.io.IOException("mark/reset not supported")
        }
    }
}
