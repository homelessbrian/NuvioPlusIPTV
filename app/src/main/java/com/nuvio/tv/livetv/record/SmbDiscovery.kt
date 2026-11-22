package com.nuvio.tv.livetv.record

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/** A shared folder a computer or NAS offers. */
data class SmbShare(val name: String, val remark: String = "")

/**
 * The parts of "Find on network" that don't need Android: checking an address for file sharing,
 * asking a computer its name (NetBIOS), the addresses on the TV's home network, and asking a
 * computer which shared folders it has. Everything here blocks: call it off the main thread.
 */
object SmbDiscovery {

    /**
     * True if [ip] takes file sharing connections (port 445) within [timeoutMs]. [bind] can tie the
     * socket to a network first (the home Wi-Fi or Ethernet, not a VPN).
     */
    fun hasFileSharing(ip: String, timeoutMs: Int = 600, bind: (Socket) -> Unit = {}): Boolean = try {
        Socket().use { runCatching { bind(it) }; it.connect(InetSocketAddress(ip, 445), timeoutMs); true }
    } catch (_: Exception) { false }

    /**
     * The addresses on the home network the TV is on ([ip] with [prefix], like 192.168.1.7/24),
     * not counting the TV's own. Big networks (more than about a thousand addresses) are cut to
     * the 256 addresses around the TV, which is where home devices nearly always are.
     */
    fun networkAddresses(ip: String, prefix: Int): List<String> {
        val own = ipv4ToLong(ip) ?: return emptyList()
        val p = if (prefix in 22..30) prefix else 24
        val size = 1L shl (32 - p)
        val base = own and (0xFFFFFFFFL shl (32 - p) and 0xFFFFFFFFL)
        return (1 until size - 1).map { base + it }.filter { it != own }.map(::longToIpv4)
    }

    fun ipv4ToLong(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (part in parts) {
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            n = (n shl 8) or v.toLong()
        }
        return n
    }

    fun longToIpv4(n: Long): String = "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"

    // ── A computer's name (NetBIOS node status: Windows PCs and most NAS boxes answer) ──────

    /** The name [ip] calls itself (like BRIAN-PC), or null if it doesn't answer in [timeoutMs]. */
    fun netbiosName(ip: String, timeoutMs: Int = 700, bind: (DatagramSocket) -> Unit = {}): String? = try {
        DatagramSocket().use { s ->
            runCatching { bind(s) }
            s.soTimeout = timeoutMs
            val id = (System.nanoTime() and 0x7FFF).toInt()
            val req = nodeStatusRequest(id)
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            s.receive(packet)
            parseNodeStatus(buf, packet.length, id)
        }
    } catch (_: Exception) { null }

    /** A NetBIOS "node status" question for the name "*" (every computer answers that one). */
    fun nodeStatusRequest(id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 255); out.write(id and 255)
        out.write(0); out.write(0)            // flags: a plain question
        out.write(0); out.write(1)            // one question
        repeat(6) { out.write(0) }            // no answers / authority / extra
        out.write(32)                         // the encoded name is 32 bytes
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (b in name) {
            val v = b.toInt() and 255
            out.write('A'.code + (v shr 4)); out.write('A'.code + (v and 15))
        }
        out.write(0)
        out.write(0); out.write(0x21)         // NBSTAT
        out.write(0); out.write(1)            // IN
        return out.toByteArray()
    }

    /**
     * Reads a node status answer: the computer's own (unique) name, preferring the one its file
     * sharing uses. Null when it isn't a proper answer to question [id].
     */
    fun parseNodeStatus(buf: ByteArray, len: Int, id: Int): String? {
        if (len < 12) return null
        if (((buf[0].toInt() and 255) shl 8 or (buf[1].toInt() and 255)) != id) return null
        if ((buf[2].toInt() and 0x80) == 0) return null            // not an answer
        var at = 12
        // Skip the name (labels, or a pointer to an earlier one).
        while (at < len) {
            val l = buf[at].toInt() and 255
            if (l == 0) { at++; break }
            if (l and 0xC0 == 0xC0) { at += 2; break }
            at += l + 1
        }
        at += 2 + 2 + 4 + 2                                         // type, class, ttl, length
        if (at >= len) return null
        val count = buf[at].toInt() and 255
        at++
        var fileServer: String? = null
        var workstation: String? = null
        for (i in 0 until count) {
            if (at + 18 > len) break
            val name = String(buf, at, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
            val suffix = buf[at + 15].toInt() and 255
            val group = (buf[at + 16].toInt() and 0x80) != 0
            at += 18
            if (group || name.isEmpty()) continue
            if (suffix == 0x20 && fileServer == null) fileServer = name
            if (suffix == 0x00 && workstation == null) workstation = name
        }
        return fileServer ?: workstation
    }

    // ── Which shared folders a computer has (the "srvsvc" list Windows Explorer asks for) ────

    /**
     * Logs in to [host] (guest when [username] is blank) and lists its shared folders, leaving out
     * hidden and system ones (C$, IPC$, printers). Throws (see [SmbFolder.describe]) on failure.
     */
    fun listShares(host: String, username: String, password: String, domain: String = ""): List<SmbShare> {
        val client = SmbFolder.newClient()
        try {
            val (h, port) = SmbFolder.hostAndPort(host)
            val connection = if (port != null) client.connect(h, port) else client.connect(h)
            connection.use { c ->
                val auth = if (username.isBlank()) AuthenticationContext.guest()
                    else AuthenticationContext(username.trim(), password.toCharArray(), domain.ifBlank { null })
                val session = c.authenticate(auth)
                try {
                    val ipc = session.connectShare("IPC$") as? PipeShare
                        ?: throw NetworkFolderException("$host didn't offer its list of shared folders")
                    ipc.use { share ->
                        val pipe = share.open(
                            "srvsvc",
                            SMB2ImpersonationLevel.Impersonation,
                            EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
                            EnumSet.noneOf(com.hierynomus.msfscc.FileAttributes::class.java),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OPEN,
                            EnumSet.noneOf(SMB2CreateOptions::class.java)
                        )
                        pipe.use { p ->
                            // Written, then read back piece by piece (a single "transact" breaks
                            // on long lists with some servers).
                            val exchange = { request: ByteArray ->
                                p.write(request)
                                readRest(ByteArray(0)) { buf -> p.read(buf) }
                            }
                            val ack = exchange(bindRequest())
                            checkBindAck(ack)
                            val reply = exchange(shareEnumRequest("\\\\" + h))
                            return parseShareEnumReply(reply)
                                .filter { it.type and 0x0FFFFFFF == 0L && it.type and 0x80000000L == 0L }
                                .filterNot { it.name.endsWith("$") }
                                .map { SmbShare(it.name, it.remark) }
                                .distinctBy { it.name.lowercase() }
                                .sortedBy { it.name.lowercase() }
                        }
                    }
                } finally {
                    runCatching { session.close() }
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    /** One RPC answer can come in several pieces: reads until the last one is in. */
    internal fun readRest(first: ByteArray, read: (ByteArray) -> Int): ByteArray {
        val all = ByteArrayOutputStream()
        var pending = first
        var guard = 0
        while (true) {
            // Make sure the whole piece is in hand.
            while (pending.size < 16 || pending.size < fragLength(pending)) {
                val buf = ByteArray(READ_SIZE)
                val n = read(buf)
                if (n <= 0) throw java.io.IOException("the list of shared folders was cut off")
                pending += buf.copyOf(n)
            }
            val len = fragLength(pending)
            all.write(pending, 0, len)
            val last = (pending[3].toInt() and 0x02) != 0
            pending = pending.copyOfRange(len, pending.size)
            if (last || ++guard > 512) break
        }
        return all.toByteArray()
    }

    /** One piece at most (the size agreed in [bindRequest]), so no server is asked for more. */
    private const val READ_SIZE = 4280

    private fun fragLength(b: ByteArray): Int = (b[8].toInt() and 255) or ((b[9].toInt() and 255) shl 8)

    private val SRVSVC = uuidBytes("4b324fc8-1670-01d3-1278-5a47bf6ee188")
    private val NDR = uuidBytes("8a885d04-1ceb-11c9-9fe8-08002b104860")

    private fun uuidBytes(text: String): ByteArray {
        val hex = text.replace("-", "")
        val raw = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // The first three parts are stored low byte first.
        return byteArrayOf(raw[3], raw[2], raw[1], raw[0], raw[5], raw[4], raw[7], raw[6]) + raw.copyOfRange(8, 16)
    }

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun header(type: Int, body: ByteArray, callId: Int): ByteArray {
        val b = le(16 + body.size)
        b.put(5).put(0).put(type.toByte()).put(0x03)       // version 5.0, first + last piece
        b.put(byteArrayOf(0x10, 0, 0, 0))                   // little endian, ASCII, IEEE
        b.putShort((16 + body.size).toShort()).putShort(0).putInt(callId)
        b.put(body)
        return b.array()
    }

    internal fun bindRequest(): ByteArray {
        val b = le(12 + 4 + 4 + 20 + 20)
        b.putShort(4280).putShort(4280).putInt(0)           // biggest pieces, new association
        b.put(1).put(0).put(0).put(0)                       // one context
        b.putShort(0).put(1).put(0)                         // context 0, one transfer syntax
        b.put(SRVSVC).putShort(3).putShort(0)
        b.put(NDR).putInt(2)
        return header(11, b.array().copyOf(b.position()), 1)
    }

    internal fun checkBindAck(pdu: ByteArray) {
        val type = pdu.getOrNull(2)?.toInt() ?: -1
        if (type == 13) throw NetworkFolderException("The computer wouldn't give its list of shared folders")
        if (type != 12) throw java.io.IOException("unexpected answer to the shared folder list request ($type)")
        val b = ByteBuffer.wrap(pdu).order(ByteOrder.LITTLE_ENDIAN)
        b.position(24)
        val addrLen = b.short.toInt() and 0xFFFF
        b.position(b.position() + addrLen)
        while ((b.position() % 4) != 0) b.get()
        val results = b.get().toInt() and 255
        b.position(b.position() + 3)
        if (results < 1 || b.short.toInt() != 0) throw NetworkFolderException("The computer wouldn't give its list of shared folders")
    }

    /** NetrShareEnum (level 1) as a request piece. */
    internal fun shareEnumRequest(serverName: String): ByteArray {
        val stub = Ndr()
        stub.u32(0x00020000)                                // server name (pointer)
        stub.string(serverName)
        stub.u32(1)                                         // level 1
        stub.u32(1)                                         // union: level 1
        stub.u32(0x00020004)                                // pointer to the (empty) container
        stub.u32(0)                                         //   entries read
        stub.u32(0)                                         //   no buffer yet
        stub.u32(0xFFFFFFFF.toInt())                        // as many as there are
        stub.u32(0x00020008)                                // resume handle (pointer)
        stub.u32(0)
        val s = stub.bytes()
        val body = le(8 + s.size)
        body.putInt(s.size).putShort(0).putShort(15)        // alloc hint, context 0, opnum 15
        body.put(s)
        return header(0, body.array(), 2)
    }

    internal data class RawShare(val name: String, val type: Long, val remark: String)

    /** Reads a NetrShareEnum answer (one or more response pieces). */
    internal fun parseShareEnumReply(pdus: ByteArray): List<RawShare> {
        // Join the stub data of every piece.
        val stub = ByteArrayOutputStream()
        var at = 0
        while (at + 16 <= pdus.size) {
            val type = pdus[at + 2].toInt()
            val len = (pdus[at + 8].toInt() and 255) or ((pdus[at + 9].toInt() and 255) shl 8)
            val authLen = (pdus[at + 10].toInt() and 255) or ((pdus[at + 11].toInt() and 255) shl 8)
            if (type == 3) {
                val status = ByteBuffer.wrap(pdus, at + 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (status == 5) throw NetworkFolderException("This user isn't allowed to see the list of shared folders")
                throw java.io.IOException("the shared folder list failed (fault 0x${Integer.toHexString(status)})")
            }
            if (type != 2 || len < 24) throw java.io.IOException("unexpected answer to the shared folder list request ($type)")
            val end = at + len - (if (authLen > 0) authLen + 8 else 0)
            stub.write(pdus, at + 24, end - (at + 24))
            at += len
        }
        val r = NdrReader(stub.toByteArray())
        r.u32()                                             // level
        r.u32()                                             // union switch
        if (r.u32() == 0L) return emptyList()               // no container
        val entries = r.u32().toInt()
        if (r.u32() == 0L) return emptyList()               // no array
        val count = r.u32().toInt()
        if (count < 0 || count > 10_000 || entries < 0) throw java.io.IOException("the shared folder list didn't make sense")
        val heads = (0 until count).map { Triple(r.u32(), r.u32(), r.u32()) }
        val out = ArrayList<RawShare>()
        for ((namePtr, type, remarkPtr) in heads) {
            val name = if (namePtr != 0L) r.string() else ""
            val remark = if (remarkPtr != 0L) r.string() else ""
            out.add(RawShare(name, type, remark))
        }
        r.u32()                                             // total entries
        if (r.u32() != 0L) r.u32()                          // resume handle
        val status = r.u32()
        if (status != 0L && status != 234L) {               // 234: more data (the list we have is still good)
            if (status == 5L) throw NetworkFolderException("This user isn't allowed to see the list of shared folders")
            if (out.isEmpty()) throw java.io.IOException("the shared folder list failed (error $status)")
        }
        return out
    }

    /** Writes NDR (the RPC data format): little endian, everything lined up on 4 bytes. */
    private class Ndr {
        private val out = ByteArrayOutputStream()
        fun u32(v: Int) { align(); out.write(v and 255); out.write(v shr 8 and 255); out.write(v shr 16 and 255); out.write(v ushr 24) }
        fun string(s: String) {
            val chars = s + "\u0000"
            u32(chars.length); u32(0); u32(chars.length)
            for (c in chars) { out.write(c.code and 255); out.write(c.code shr 8) }
            align()
        }
        private fun align() { while (out.size() % 4 != 0) out.write(0) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class NdrReader(private val b: ByteArray) {
        private var at = 0
        private fun align() { while (at % 4 != 0) at++ }
        fun u32(): Long {
            align()
            if (at + 4 > b.size) throw java.io.IOException("the shared folder list was cut off")
            val v = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            at += 4
            return v
        }
        fun string(): String {
            u32()                                           // max count
            val offset = u32().toInt()
            val actual = u32().toInt()
            if (actual < 0 || at + (offset + actual) * 2 > b.size) throw java.io.IOException("the shared folder list was cut off")
            at += offset * 2
            val s = String(b, at, actual * 2, Charsets.UTF_16LE)
            at += actual * 2
            return s.trimEnd('\u0000')
        }
    }
}
