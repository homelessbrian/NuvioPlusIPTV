package com.nuvio.tv.livetv.crash

import android.content.res.Resources
import android.view.View
import com.nuvio.tv.R
import com.nuvio.tv.livetv.sync.LiveTvBackupFile
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * "Back up or restore with your phone": a small page on the home network where the phone saves
 * this TV's Live TV backup (a .nptv file) or sends one back to restore. Like [AddFromPhoneServer]:
 * it only runs while the TV's dialog is open, nothing leaves the home network, and the address
 * has a random code in it so only someone who can see the TV can use it. A restore is only
 * confirmed on the TV itself.
 */
object BackupPhoneServer {

    /** The page's words, taken from the app's resources in its language (nothing else is kept). */
    data class Texts(
        val languageTag: String,
        val isRtl: Boolean,
        val title: String,
        val brandName: String,
        val connectedTo: String,
        val saveHeading: String,
        val saveText: String,
        val saveButton: String,
        val restoreHeading: String,
        val restoreText: String,
        val chooseFile: String,
        val restoreButton: String,
        val sending: String,
        val unreachable: String,
        val notFound: String,
        val notBackup: String,
        val privacyNote: String,
        val networkNote: String,
    ) {
        companion object {
            fun from(resources: Resources, tvName: String): Texts {
                val configuration = resources.configuration
                val languageTag = configuration.locales.takeIf { !it.isEmpty }
                    ?.get(0)?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en"
                return Texts(
                    languageTag = languageTag,
                    isRtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                    title = resources.getString(R.string.iptv_backup_phone_title),
                    brandName = resources.getString(R.string.iptv_phone_brand_name),
                    connectedTo = resources.getString(R.string.iptv_phone_connected_to, tvName),
                    saveHeading = resources.getString(R.string.iptv_backup_phone_save_heading),
                    saveText = resources.getString(R.string.iptv_backup_phone_save_text),
                    saveButton = resources.getString(R.string.iptv_backup_phone_save_button),
                    restoreHeading = resources.getString(R.string.iptv_backup_phone_restore_heading),
                    restoreText = resources.getString(R.string.iptv_backup_phone_restore_text),
                    chooseFile = resources.getString(R.string.iptv_backup_phone_choose_file),
                    restoreButton = resources.getString(R.string.iptv_backup_phone_restore_button),
                    sending = resources.getString(R.string.iptv_phone_sending),
                    unreachable = resources.getString(R.string.iptv_phone_unreachable),
                    notFound = resources.getString(R.string.iptv_phone_not_found),
                    notBackup = resources.getString(R.string.iptv_backup_not_a_backup),
                    privacyNote = resources.getString(R.string.iptv_backup_private_note),
                    networkNote = resources.getString(R.string.iptv_phone_network_note),
                )
            }
        }
    }

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /**
     * Null when there's no home network. [backup] makes the file to save (name and contents).
     * [onRestore] runs on a background thread with a backup the phone sent and returns the line
     * shown on the phone (the TV then asks whether to restore it).
     */
    fun start(
        texts: Texts,
        accent: String,
        logo: () -> ByteArray?,
        backup: () -> Pair<String, ByteArray>?,
        onRestore: (LiveTvBackupFile.Backup) -> String,
    ): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, texts, accent, logo, backup, onRestore) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "backup-phone"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8696..8705) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun serve(
        client: Socket,
        token: String,
        texts: Texts,
        accent: String,
        logo: () -> ByteArray?,
        backup: () -> Pair<String, ByteArray>?,
        onRestore: (LiveTvBackupFile.Backup) -> String,
    ) {
        runCatching {
            client.use { c ->
                c.soTimeout = 30_000
                val input = c.getInputStream().buffered()
                val request = readLine(input).orEmpty()
                var length = 0
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("content-length:", ignoreCase = true)) length = l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val method = request.substringBefore(' ')
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?').trim('/')
                val out = c.getOutputStream()
                fun send(status: String, type: String, body: ByteArray, extra: String = "") {
                    out.write(
                        ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\n${extra}Connection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                    out.flush()
                }
                fun notFound() = send("404 Not Found", "text/plain; charset=utf-8", texts.notFound.toByteArray(Charsets.UTF_8))
                when {
                    method == "GET" && path == token ->
                        send("200 OK", "text/html; charset=utf-8", page(token, texts, accent).toByteArray(Charsets.UTF_8))
                    method == "GET" && path == "$token/logo.png" -> logo()?.let { send("200 OK", "image/png", it) } ?: notFound()
                    method == "GET" && path == "$token/backup" -> {
                        val (name, bytes) = backup() ?: return@use notFound()
                        // The name in plain ASCII and in full (RFC 5987), so every phone keeps ".nptv".
                        val ascii = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
                        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                        send(
                            "200 OK", LiveTvBackupFile.MIME, bytes,
                            "Content-Disposition: attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded\r\n"
                        )
                    }
                    method == "POST" && path == "$token/restore" -> {
                        if (length !in 1..LiveTvBackupFile.MAX_BYTES) {
                            send("200 OK", "application/json", json(false, texts.notBackup))
                            return@use
                        }
                        val bytes = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(bytes, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        val found = LiveTvBackupFile.decode(bytes.copyOf(read))
                        if (found == null) send("200 OK", "application/json", json(false, texts.notBackup))
                        else send("200 OK", "application/json", json(true, runCatching { onRestore(found) }.getOrElse { texts.notBackup }))
                    }
                    else -> notFound()
                }
            }
        }
    }

    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                else -> if (ch.code < 0x20) append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    private fun json(ok: Boolean, message: String) = "{\"ok\":$ok,\"message\":${quote(message)}}".toByteArray(Charsets.UTF_8)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(token: String, texts: Texts, accent: String): String = """<!doctype html>
<html lang="${esc(texts.languageTag)}" dir="${if (texts.isRtl) "rtl" else "ltr"}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(texts.title)}</title>
<style>
:root{--accent:${esc(accent)}}
*{box-sizing:border-box}
body{margin:0;background:#0E0E12;color:#F2F2F5;font-family:-apple-system,Roboto,"Segoe UI",sans-serif}
main{max-width:480px;margin:0 auto;padding:28px 20px 32px;display:flex;flex-direction:column;gap:20px}
.top{display:flex;flex-direction:column;align-items:center;gap:14px}
.logo{width:220px;height:auto}
.chip{display:flex;align-items:center;gap:8px;background:#17171D;border:1px solid #26262F;border-radius:999px;padding:6px 12px;font-size:13px;color:#C9C9D3}
.dot{width:8px;height:8px;border-radius:50%;background:#3DDC97}
.card{background:#15151B;border:1px solid #24242D;border-radius:14px;padding:18px;display:flex;flex-direction:column;gap:12px}
h2{margin:0;font-size:19px}
.sub{margin:0;font-size:14px;color:#A9A9B5;line-height:1.45}
.btn{display:flex;align-items:center;justify-content:center;height:52px;border:0;border-radius:12px;background:var(--accent);color:#0B0B10;font-size:16px;font-weight:700;font-family:inherit;text-decoration:none}
.btn.alt{background:#2A2A35;color:#F2F2F5}
.btn:disabled{opacity:.6}
#file{display:none}
#fname{font-size:13px;color:#C9C9D3;text-align:center;min-height:18px;word-break:break-all}
#msg{min-height:20px;font-size:15px;text-align:center;line-height:1.4}
#msg.ok{color:#3DDC97}#msg.bad{color:#FF8A80}
.note{margin:0;font-size:12px;color:#8E8E9B;text-align:center;line-height:1.5}
</style></head><body><main>
<div class="top">
<img class="logo" src="/${esc(token)}/logo.png" alt="${esc(texts.brandName)}" onerror="this.hidden=true">
<div class="chip"><span class="dot"></span><span>${esc(texts.connectedTo)}</span></div>
</div>
<div class="card"><h2>${esc(texts.saveHeading)}</h2><p class="sub">${esc(texts.saveText)}</p>
<a class="btn" href="/${esc(token)}/backup" download>${esc(texts.saveButton)}</a></div>
<div class="card"><h2>${esc(texts.restoreHeading)}</h2><p class="sub">${esc(texts.restoreText)}</p>
<input id="file" type="file" accept=".${LiveTvBackupFile.EXTENSION},application/json,application/octet-stream">
<button class="btn alt" type="button" id="pick">${esc(texts.chooseFile)}</button>
<div id="fname"></div>
<button class="btn" type="button" id="send" disabled>${esc(texts.restoreButton)}</button>
<div id="msg" role="status"></div></div>
<p class="note">${esc(texts.privacyNote)}</p>
<p class="note">${esc(texts.networkNote)}</p>
</main>
<script>
var labels={sending:${quote(texts.sending)},unreachable:${quote(texts.unreachable)}};
var file=document.getElementById('file'),send=document.getElementById('send');
function msg(t,c){var m=document.getElementById('msg');m.textContent=t;m.className=c}
document.getElementById('pick').onclick=function(){file.click()};
file.onchange=function(){var f=file.files[0];document.getElementById('fname').textContent=f?f.name:'';send.disabled=!f;msg('','')};
send.onclick=function(){var f=file.files[0];if(!f)return;send.disabled=true;msg(labels.sending,'');
f.arrayBuffer().then(function(b){return fetch(location.pathname.replace(/\/$/,'')+'/restore',{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:b})})
.then(function(r){return r.json()}).then(function(j){msg(j.message,j.ok?'ok':'bad')})
.catch(function(){msg(labels.unreachable,'bad')})
.finally(function(){send.disabled=!file.files[0]})};
</script>
</body></html>"""
}
