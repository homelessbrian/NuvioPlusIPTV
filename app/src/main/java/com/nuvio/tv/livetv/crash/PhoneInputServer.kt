package com.nuvio.tv.livetv.crash

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A small page on the home network for typing something on a phone instead of with the remote
 * (a logo's web address, for now). Like [ReportServer]: only while the screen that asked for it
 * is open, nothing leaves the home network, and the address has a random code in it.
 */
object PhoneInputServer {

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /** Null when there's no home network. [onValue] is called (on a background thread) with what was sent. */
    fun start(title: String, prompt: String, placeholder: String, onValue: (String) -> Unit): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, title, prompt, placeholder, onValue) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "phone-input"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8091..8099) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun serve(client: Socket, token: String, title: String, prompt: String, placeholder: String, onValue: (String) -> Unit) {
        runCatching {
            client.use { c ->
                c.soTimeout = 5_000
                val reader = c.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val target = request.split(' ').getOrNull(1).orEmpty()
                val path = target.substringBefore('?').trim('/')
                val query = target.substringAfter('?', "")
                val out = c.getOutputStream()
                val html: String? = when (path) {
                    token -> page(title, prompt, placeholder, null)
                    "$token/send" -> {
                        val v = query.split('&').firstOrNull { it.startsWith("v=") }?.substringAfter("v=")
                            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim().orEmpty()
                        if (v.isNotEmpty()) onValue(v)
                        page(title, prompt, placeholder, if (v.isNotEmpty()) "Sent. Look at your TV." else "Nothing to send.")
                    }
                    else -> null
                }
                if (html == null) {
                    val body = "Not found".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                } else {
                    val body = html.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(title: String, prompt: String, placeholder: String, msg: String?): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:18px}
h1{font-size:21px;margin:0 0 8px}
p{color:#aaa;font-size:15px;margin:6px 0 14px}
input{width:100%;box-sizing:border-box;font-size:17px;padding:14px;border-radius:10px;border:1px solid #333;background:#1b1b1f;color:#eee}
button{margin-top:12px;font-size:18px;padding:14px 22px;border:0;border-radius:10px;background:#fff;color:#111;font-weight:600;width:100%}
#msg{color:#7f7;font-size:16px;margin-top:14px}
</style></head><body>
<h1>${esc(title)}</h1>
<p>${esc(prompt)}</p>
<form action="send" method="get" onsubmit="this.action=location.pathname.replace(/\/send$/,'').replace(/\/$/,'')+'/send'">
<input name="v" placeholder="${esc(placeholder)}" autocomplete="off" autocapitalize="off" autocorrect="off" inputmode="url">
<button type="submit">Send to TV</button>
</form>
<div id="msg">${esc(msg ?: "")}</div>
</body></html>"""
}
