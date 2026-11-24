package com.nuvio.tv.livetv.record

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A shared folder on another computer or a NAS (Windows file sharing, "SMB"), where
 * recordings can be kept. [host] is the computer's IP address (or name), [share] the shared
 * folder's name, and [path] an optional folder inside it ("TV/Recordings").
 */
data class NetworkFolderConfig(
    val host: String,
    val share: String,
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    /** How it's shown: \\192.168.1.20\Recordings\TV */
    val label: String get() = "\\\\" + host + "\\" + share + (if (path.isEmpty()) "" else "\\" + path.replace('/', '\\'))

    /** What's typed in the Address box. */
    val address: String get() = label

    companion object {
        /**
         * Reads an address typed any common way: \\host\share\folder, //host/share/folder,
         * smb://host/share/folder or host/share/folder. Null when there's no shared folder name.
         */
        fun parseAddress(text: String): Triple<String, String, String>? {
            var t = text.trim()
            t = t.removePrefix("smb://").removePrefix("SMB://").removePrefix("Smb://")
            val parts = t.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            return Triple(parts[0], parts[1], parts.drop(2).joinToString("/"))
        }
    }
}

/**
 * Reads and writes files in a [NetworkFolderConfig] folder. One connection is kept open and
 * reused; when it drops (the computer slept, Wi-Fi blipped) the next call connects again.
 * Every call blocks, so call it off the main thread.
 */
class SmbFolder(val config: NetworkFolderConfig) : Closeable {
    private val client = newClient()
    private val lock = Any()
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null

    /** When the last call worked (0 = not yet). */
    @Volatile var lastOkAtMs: Long = 0L
        private set

    private fun share(): DiskShare = synchronized(lock) {
        disk?.takeIf { it.isConnected }?.let { return it }
        dropLocked()
        val (host, port) = hostAndPort(config.host)
        val c = if (port != null) client.connect(host, port) else client.connect(host)
        connection = c
        val auth = if (config.username.isBlank()) AuthenticationContext.guest()
            else AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
        val s = c.authenticate(auth)
        session = s
        val d = s.connectShare(config.share) as? DiskShare
            ?: throw NetworkFolderException("\"${config.share}\" on ${config.host} isn't a shared folder")
        disk = d
        d
    }

    private fun dropLocked() {
        runCatching { disk?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close(true) }
        disk = null; session = null; connection = null
    }

    /** Runs [block], connecting again once if the old connection had dropped. */
    private fun <T> call(block: (DiskShare) -> T): T {
        var first = true
        while (true) {
            try {
                val result = block(share())
                lastOkAtMs = System.currentTimeMillis()
                return result
            } catch (e: Exception) {
                val broken = e !is SMBApiException || e.status in RECONNECT_STATUSES
                synchronized(lock) { if (broken) dropLocked() }
                if (!first || !broken) throw e
                first = false
            }
        }
    }

    /** Full path inside the share for [rel] (a path under the chosen folder, with / between parts). */
    private fun p(rel: String): String =
        (config.path.split('/', '\\') + rel.split('/', '\\')).filter { it.isNotEmpty() }.joinToString("\\")

    /** Makes the folder [rel] (and the ones above it) if it isn't there. */
    fun mkdirs(rel: String) = call { d ->
        var at = ""
        for (part in p(rel).split('\\').filter { it.isNotEmpty() }) {
            at = if (at.isEmpty()) part else "$at\\$part"
            if (!folderThere(d, at)) {
                try { d.mkdir(at) } catch (e: SMBApiException) {
                    if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) throw e
                }
            }
        }
    }

    /**
     * Copies [local] to [rel], replacing what's there, then checks the copy is the full size.
     * Returns the size copied.
     */
    fun upload(local: File, rel: String): Long = call { d ->
        val expected = local.length()
        d.openFile(
            p(rel),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f ->
            local.inputStream().use { input ->
                withBuffer { buf ->
                    val startNs = System.nanoTime()
                    var at = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        var off = 0
                        while (off < n) {
                            val w = f.write(buf, at, off, n - off).toInt()
                            if (w <= 0) throw java.io.IOException("the network folder stopped taking data")
                            off += w; at += w
                        }
                        pace(startNs, at)
                    }
                }
            }
        }
        val got = d.getFileInformation(p(rel)).standardInformation.endOfFile
        if (got != expected) throw java.io.IOException("the copy on the network folder came out the wrong size ($got of $expected bytes)")
        expected
    }

    /**
     * Writes all of [local] into [rel] starting at [offset] (making [rel] if it isn't there),
     * then checks [rel] reaches at least that far. Writing at a set place means a try that broke
     * part way is simply written over by the next one. Returns the bytes written.
     */
    fun writeAt(rel: String, offset: Long, local: File): Long = call { d ->
        val expected = local.length()
        d.openFile(
            p(rel),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f ->
            local.inputStream().use { input ->
                withBuffer { buf ->
                    val startNs = System.nanoTime()
                    var at = offset
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        var off = 0
                        while (off < n) {
                            val w = f.write(buf, at, off, n - off).toInt()
                            if (w <= 0) throw java.io.IOException("the network folder stopped taking data")
                            off += w; at += w
                        }
                        pace(startNs, at - offset)
                    }
                }
            }
            val got = f.fileInformation.standardInformation.endOfFile
            if (got < offset + expected) throw java.io.IOException("the network folder kept only $got of ${offset + expected} bytes")
        }
        expected
    }

    /** Cuts (or grows) [rel] to exactly [length] bytes. */
    fun setLength(rel: String, length: Long) = call { d ->
        d.openFile(
            p(rel),
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f -> f.setLength(length) }
    }

    /** Renames (or moves, within the shared folder) [from] to [to]; fails if [to] is there. */
    fun rename(from: String, to: String) = call { d ->
        d.openFile(
            p(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f -> f.rename(p(to), false) }
    }

    /** True if the file [rel] is there. */
    fun fileExists(rel: String): Boolean = call { d -> fileThere(d, p(rel)) }

    /** The names of the folders in the folder [rel]; empty if it isn't there. */
    fun listFolders(rel: String): List<String> = call { d ->
        if (!folderThere(d, p(rel))) emptyList()
        else d.list(p(rel))
            .filter { it.fileName != "." && it.fileName != ".." }
            .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L }
            .map { it.fileName }
    }

    /** The folder [rel], only if it's empty (a show's folder once its last episode is deleted). */
    fun deleteFolderIfEmpty(rel: String) = call { d ->
        if (folderThere(d, p(rel)) && d.list(p(rel)).none { it.fileName != "." && it.fileName != ".." }) d.rmdir(p(rel), false)
    }

    /** Writes [bytes] to [rel], replacing what's there. */
    fun writeBytes(rel: String, bytes: ByteArray) = call { d ->
        d.openFile(
            p(rel),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f ->
            var off = 0
            while (off < bytes.size) {
                val w = f.write(bytes, off.toLong(), off, bytes.size - off).toInt()
                if (w <= 0) throw java.io.IOException("the network folder stopped taking data")
                off += w
            }
        }
    }

    /** The size of [rel], or null if it isn't there. */
    fun size(rel: String): Long? = call { d ->
        if (!fileThere(d, p(rel))) null else d.getFileInformation(p(rel)).standardInformation.endOfFile
    }

    /** Copies [count] bytes of [rel] from [offset] into [out] (fewer if the file is shorter). */
    fun read(rel: String, offset: Long, count: Long, out: OutputStream) = call { d ->
        d.openFile(
            p(rel),
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
        ).use { f ->
            // Never asks past the end (some servers answer that badly and drop the connection).
            val length = f.fileInformation.standardInformation.endOfFile
            var at = offset
            var left = minOf(count, (length - offset).coerceAtLeast(0L))
            withBuffer { buf ->
                while (left > 0) {
                    val n = f.read(buf, at, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    at += n; left -= n
                }
            }
        }
    }

    /** Copies [rel] to [local]; returns the size. */
    fun download(rel: String, local: File): Long {
        local.parentFile?.mkdirs()
        local.outputStream().use { o -> read(rel, 0, Long.MAX_VALUE, o) }
        return local.length()
    }

    /** The names of the files (not folders) in the folder [rel]; empty if it isn't there. */
    fun listFiles(rel: String): List<String> = call { d ->
        if (!folderThere(d, p(rel))) emptyList()
        else d.list(p(rel))
            .filter { it.fileName != "." && it.fileName != ".." }
            .filter { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L }
            .map { it.fileName }
    }

    /** Deletes the file [rel] if it's there. */
    fun delete(rel: String) = call { d -> if (fileThere(d, p(rel))) d.rm(p(rel)) }

    /** Deletes the folder [rel] and everything in it, if it's there. */
    fun deleteFolder(rel: String) = call { d -> if (folderThere(d, p(rel))) d.rmdir(p(rel), true) }

    /**
     * Free space and total size of the shared folder's drive (free counts the user's quota), or
     * null when the computer won't say (some small NAS boxes don't): that's "unknown", not "full".
     */
    fun space(): Pair<Long, Long>? = call { d ->
        try {
            val info = d.shareInformation
            (info.callerFreeSpace to info.totalSpace).takeIf { it.second > 0 }
        } catch (e: com.hierynomus.smbj.common.SMBRuntimeException) {
            if (e is SMBApiException && e.status != NtStatus.STATUS_NOT_SUPPORTED && e.status != NtStatus.STATUS_INVALID_INFO_CLASS) throw e
            null
        }
    }

    /**
     * Checks the folder works for recordings: connects, makes the folder, writes a small file,
     * reads its size back and deletes it. Returns the free space and total size (null: unknown).
     */
    fun test(): Pair<Long, Long>? {
        mkdirs("")
        val name = ".nuvio-test-" + System.currentTimeMillis()
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        writeBytes(name, bytes)
        val got = size(name)
        runCatching { delete(name) }
        if (got != bytes.size.toLong()) throw NetworkFolderException("A test file couldn't be saved there in full")
        return space()
    }

    override fun close() {
        synchronized(lock) { dropLocked() }
        runCatching { client.close() }
    }

    companion object {
        /** Copy chunk size. Smaller chunks mean far less memory thrown away per piece copied. */
        internal const val BUFFER = 256 * 1024

        /**
         * Most a recording is copied across at (bytes a second): well above any live stream, so
         * copying keeps up, but catching up on a backlog no longer takes over the processor.
         */
        private const val MAX_COPY_BYTES_PER_SEC = 8L * 1024 * 1024

        /** One copy buffer kept for reuse (a second one at the same time is made fresh). */
        @PublishedApi internal val spare = AtomicReference<ByteArray?>(null)

        internal inline fun <T> withBuffer(block: (ByteArray) -> T): T {
            val buf = spare.getAndSet(null) ?: ByteArray(BUFFER)
            try { return block(buf) } finally { spare.set(buf) }
        }

        /** Waits as needed so [sent] bytes since [startNs] stay under the copy speed limit. */
        private fun pace(startNs: Long, sent: Long) {
            val dueMs = sent * 1000 / MAX_COPY_BYTES_PER_SEC
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            if (dueMs > tookMs) Thread.sleep(dueMs - tookMs)
        }

        /** A file sharing client set up the way every network folder connection uses. */
        internal fun newClient(): SMBClient = SMBClient(
            SmbConfig.builder()
                // Android's own security code for signing/encrypting; the bundled library only for
                // what Android lacks (like the MD4 step Windows logins need).
                .withSecurityProvider(SmbSecurity())
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withMultiProtocolNegotiate(true)
                .withDfsEnabled(false)
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(45, TimeUnit.SECONDS)
                // Smaller pieces per network message: much less memory made and thrown away.
                .withWriteBufferSize(BUFFER)
                .build()
        )

        /** Statuses that mean the connection itself has gone (connect again), not a file problem. */
        private val RECONNECT_STATUSES = setOf(
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_USER_SESSION_DELETED,
        )

        /** "Not there" answers (servers word it differently). */
        private val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
            NtStatus.STATUS_DELETE_PENDING,
        )

        private fun folderThere(d: DiskShare, path: String): Boolean =
            try { d.folderExists(path) } catch (e: SMBApiException) { if (e.status in NOT_FOUND) false else throw e }

        private fun fileThere(d: DiskShare, path: String): Boolean =
            try { d.fileExists(path) } catch (e: SMBApiException) { if (e.status in NOT_FOUND) false else throw e }

        internal fun hostAndPort(host: String): Pair<String, Int?> {
            val h = host.trim()
            // [fe80::1]:445, or a plain IPv6 address (several colons) with no port.
            if (h.startsWith("[")) {
                val end = h.indexOf(']')
                if (end > 0) return h.substring(1, end) to h.substring(end + 1).removePrefix(":").toIntOrNull()
            }
            if (h.count { it == ':' } == 1) return h.substringBefore(':') to h.substringAfter(':').toIntOrNull()
            return h to null
        }

        /** A problem with the network folder, in plain words for the screen. */
        fun describe(e: Throwable, config: NetworkFolderConfig): String {
            val host = config.host
            var t: Throwable? = e
            while (t != null) {
                when (t) {
                    is NetworkFolderException -> return t.message ?: "The network folder didn't work"
                    is SMBApiException -> return when (t.status) {
                        NtStatus.STATUS_LOGON_FAILURE -> "Wrong username or password for $host"
                        NtStatus.STATUS_ACCOUNT_DISABLED -> "That account is turned off on $host"
                        NtStatus.STATUS_PASSWORD_EXPIRED -> "That account's password has expired on $host"
                        NtStatus.STATUS_BAD_NETWORK_NAME -> "There's no shared folder called \"${config.share}\" on $host"
                        NtStatus.STATUS_ACCESS_DENIED -> "That folder can't be saved to. Share it with Read/Write for this user"
                        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_OBJECT_NAME_NOT_FOUND -> "That folder isn't there and couldn't be made"
                        NtStatus.STATUS_DISK_FULL -> "The network folder is full"
                        NtStatus.STATUS_NOT_SUPPORTED -> "$host doesn't allow this. Make sure SMB 2 or 3 file sharing is turned on"
                        else -> "$host said no (${t.status})"
                    }
                    is java.net.UnknownHostException -> return "Couldn't find \"$host\". Try the computer's IP address instead (like 192.168.1.20)"
                    is java.net.ConnectException, is java.net.NoRouteToHostException ->
                        return "Couldn't reach $host. Check it's turned on, on the same network, and that file sharing is on"
                    is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException ->
                        return "$host didn't answer in time. Check it's awake and on the same network"
                }
                t = t.cause
            }
            val msg = e.message.orEmpty()
            if (msg.contains("negotiat", ignoreCase = true) || msg.contains("dialect", ignoreCase = true))
                return "$host only offers old file sharing (SMB 1). Turn on SMB 2 or 3 on it"
            return "The network folder didn't work (${msg.ifBlank { e.javaClass.simpleName }})"
        }
    }
}

class NetworkFolderException(message: String) : java.io.IOException(message)
