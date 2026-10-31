package com.nuvio.tv.livetv.crash

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Shows a report as a small web page on the home network, so it can be opened (and copied) on a
 * phone on the same Wi-Fi. Nothing leaves the home network, there is no size limit (a QR code
 * holds very little), and it only runs while the report screen is open. The address includes a
 * random code, so only someone given the address can read it.
 */
object ReportServer {

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /**
     * Starts serving [text] (read again on every page load); null when there's no home network.
     * [short]: a version that fits one Discord message, offered first when given.
     */
    fun start(title: String, short: (() -> String)? = null, text: () -> String): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, title, short, text) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "report-server"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8080..8090) {
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

    /** This device's address on the home network (Wi-Fi or cable), or null. */
    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun serve(client: Socket, token: String, title: String, short: (() -> String)?, text: () -> String) {
        runCatching {
            client.use { c ->
                c.soTimeout = 5_000
                val reader = c.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?')
                val out = c.getOutputStream()
                if (path.trim('/') != token) {
                    val body = "Not found".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                } else {
                    val body = page(title, short?.invoke(), text()).toByteArray(Charsets.UTF_8)
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

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(title: String, short: String?, text: String): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:16px}
h1{font-size:20px;margin:0 0 6px}
p{color:#aaa;font-size:14px;margin:6px 0 12px}
button{font-size:17px;padding:13px 18px;border:0;border-radius:10px;background:#fff;color:#111;font-weight:600;margin:0 8px 8px 0}
button.alt{background:#2a2d35;color:#eee}
a{color:#8cf;font-size:15px}
textarea{width:100%;box-sizing:border-box;margin-top:10px;height:55vh;background:#1b1b1f;color:#ddd;border:1px solid #333;border-radius:8px;font-family:monospace;font-size:12px;padding:8px}
#msg{color:#7f7;font-size:15px;min-height:20px}
#full{display:none}
</style></head><body>
<h1>${esc(title)}</h1>
${if (short != null) """<p><b>Copy short report</b> fits in one Discord message and keeps everything important. Send the full one only if you're asked for it.</p>
<button onclick="copyIt('s')">Copy short report</button><button class="alt" onclick="copyIt('r')">Copy full report</button>""" else """<p>Tap <b>Copy report</b>, then paste it into your message to the developer.</p>
<button onclick="copyIt('r')">Copy report</button>"""} <a href="">Reload</a>
<div id="msg"></div>
${if (short != null) """<textarea id="s" readonly>${esc(short)}</textarea>
<p><a href="#" onclick="document.getElementById('full').style.display='block';this.style.display='none';return false">Show the full report</a></p>
<div id="full"><textarea id="r" readonly>${esc(text)}</textarea></div>""" else """<textarea id="r" readonly>${esc(text)}</textarea>"""}
<script>
function copyIt(id){
  var t=document.getElementById(id);var box=document.getElementById('full');
  if(id=='r'&&box)box.style.display='block';
  t.focus();t.select();t.setSelectionRange(0,t.value.length);
  var ok=false;try{ok=document.execCommand('copy')}catch(e){}
  if(navigator.clipboard&&!ok){navigator.clipboard.writeText(t.value).then(function(){document.getElementById('msg').textContent='Copied ('+t.value.length+' characters). Now paste it into your message.'});return}
  document.getElementById('msg').textContent=ok?'Copied ('+t.value.length+' characters). Now paste it into your message.':'Press and hold the text, choose Select all, then Copy.';
}
</script></body></html>"""
}
