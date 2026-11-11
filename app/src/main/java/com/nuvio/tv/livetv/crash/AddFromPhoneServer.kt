package com.nuvio.tv.livetv.crash

import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * "Add from your phone": a small page on the home network for adding an M3U playlist, an Xtream
 * login or a TV guide (EPG) from a phone instead of typing with the remote. Like
 * [PhoneInputServer]: only runs while the screen that asked for it is open, nothing leaves the
 * home network, and the address has a random code in it so only someone who sees the TV can use it.
 */
object AddFromPhoneServer {

    /** What the phone sent. [kind] is "m3u", "xtream" or "epg". */
    data class Entry(
        val kind: String,
        val name: String,
        val url: String,
        val epgUrl: String,
        val userAgent: String,
        val server: String,
        val username: String,
        val password: String,
        val includeLive: Boolean,
        val includeVod: Boolean
    )

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /**
     * Null when there's no home network. [onEntry] runs on a background thread with a checked
     * entry and returns the line shown on the phone ("Added …").
     */
    fun start(
        tvName: String,
        accent: String,
        logo: () -> ByteArray?,
        onEntry: (Entry) -> String
    ): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, tvName, accent, logo, onEntry) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "add-from-phone"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8686..8695) {
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

    /** One line of the request head, read byte by byte (the body that follows is read as bytes). */
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
        tvName: String,
        accent: String,
        logo: () -> ByteArray?,
        onEntry: (Entry) -> String
    ) {
        runCatching {
            client.use { c ->
                c.soTimeout = 10_000
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
                fun send(status: String, type: String, body: ByteArray) {
                    out.write(
                        ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                    out.flush()
                }
                when {
                    method == "GET" && path == token ->
                        send("200 OK", "text/html; charset=utf-8", page(token, tvName, accent).toByteArray(Charsets.UTF_8))
                    method == "GET" && path == "$token/logo.png" -> {
                        val bytes = logo()
                        if (bytes != null) send("200 OK", "image/png", bytes)
                        else send("404 Not Found", "text/plain", "Not found".toByteArray())
                    }
                    method == "POST" && path == "$token/add" -> {
                        if (length !in 1..65_536) {
                            send("400 Bad Request", "application/json", json(false, "Nothing to send."))
                            return@use
                        }
                        val bytes = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(bytes, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        val form = parseForm(String(bytes, 0, read, Charsets.UTF_8))
                        val (entry, problem) = validate(form)
                        if (entry == null) {
                            send("200 OK", "application/json", json(false, problem ?: "Something is missing."))
                        } else {
                            val message = runCatching { onEntry(entry) }.getOrElse { "The TV couldn't add that. Try again." }
                            send("200 OK", "application/json", json(true, message))
                        }
                    }
                    else -> send("404 Not Found", "text/plain", "Not found".toByteArray())
                }
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
            dec(k) to dec(v).trim()
        }

    private fun isWeb(s: String) = s.startsWith("http://", true) || s.startsWith("https://", true)

    /** The entry, or why it can't be added (shown on the phone). */
    private fun validate(f: Map<String, String>): Pair<Entry?, String?> {
        val kind = f["kind"].orEmpty()
        val name = f["name"].orEmpty().take(80)
        val url = f["url"].orEmpty()
        val epg = f["epg"].orEmpty()
        val server = f["server"].orEmpty()
        val user = f["user"].orEmpty()
        val pass = f["pass"].orEmpty()
        val entry = Entry(
            kind = kind, name = name, url = url, epgUrl = epg, userAgent = f["ua"].orEmpty().take(300),
            server = server, username = user, password = pass,
            includeLive = f["live"] != "0", includeVod = f["vod"] != "0"
        )
        return when (kind) {
            "m3u" -> when {
                url.isEmpty() -> null to "Paste the playlist link."
                !isWeb(url) -> null to "The playlist link should start with http:// or https://."
                epg.isNotEmpty() && !isWeb(epg) -> null to "The EPG link should start with http:// or https://."
                else -> entry to null
            }
            "xtream" -> when {
                server.isEmpty() -> null to "Enter the server address."
                !isWeb(server) && !server.contains('.') && !server.contains(':') -> null to "That doesn't look like a server address."
                (user.isEmpty() || pass.isEmpty()) && !server.contains("username=", true) -> null to "Enter the username and password."
                !entry.includeLive && !entry.includeVod -> null to "Turn on TV channels or movies & series."
                else -> entry to null
            }
            "epg" -> when {
                url.isEmpty() -> null to "Paste the EPG link."
                !isWeb(url) -> null to "The EPG link should start with http:// or https://."
                else -> entry to null
            }
            else -> null to "Pick what to add."
        }
    }

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private fun json(ok: Boolean, message: String) = "{\"ok\":$ok,\"message\":${quote(message)}}".toByteArray(Charsets.UTF_8)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(token: String, tvName: String, accent: String): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Add to Nuvio + IPTV</title>
<style>
:root{--accent:${esc(accent)}}
*{box-sizing:border-box}
body{margin:0;background:#0E0E12;color:#F2F2F5;font-family:-apple-system,Roboto,"Segoe UI",sans-serif}
main{max-width:480px;margin:0 auto;padding:28px 20px 32px;display:flex;flex-direction:column;gap:20px}
.top{display:flex;flex-direction:column;align-items:center;gap:14px}
.logo{width:220px;height:auto}
.chip{display:flex;align-items:center;gap:8px;background:#17171D;border:1px solid #26262F;border-radius:999px;padding:6px 12px;font-size:13px;color:#C9C9D3}
.dot{width:8px;height:8px;border-radius:50%;background:#3DDC97}
h1{margin:0 0 4px;font-size:24px}
.sub{margin:0;font-size:14px;color:#A9A9B5;line-height:1.45}
.tabs{display:grid;grid-template-columns:1fr 1fr 1fr;gap:4px;background:#17171D;border-radius:12px;padding:4px}
.tabs button{height:44px;border:0;border-radius:9px;background:transparent;color:#A9A9B5;font-size:14px;font-weight:500;font-family:inherit}
.tabs button[aria-pressed=true]{background:#2A2A35;color:#fff;font-weight:600}
form{display:flex;flex-direction:column;gap:16px}
.field{display:flex;flex-direction:column;gap:6px}
label{font-size:13px;font-weight:600;color:#C9C9D3}
label span{font-weight:400;color:#8E8E9B}
input[type=text],input[type=url],input[type=password]{height:48px;background:#17171D;border:1px solid #2E2E39;border-radius:10px;padding:0 14px;color:#F2F2F5;font-size:16px;font-family:inherit;width:100%}
input:focus{outline:none;border-color:var(--accent)}
.check{display:flex;align-items:center;gap:10px;font-size:15px;color:#C9C9D3;min-height:44px}
.check input{width:22px;height:22px;accent-color:var(--accent)}
.send{height:52px;border:0;border-radius:12px;background:var(--accent);color:#0B0B10;font-size:16px;font-weight:700;font-family:inherit;margin-top:4px}
.send:disabled{opacity:.6}
#msg{min-height:20px;font-size:15px;text-align:center;line-height:1.4}
#msg.ok{color:#3DDC97}#msg.bad{color:#FF8A80}
.note{margin:0;font-size:12px;color:#8E8E9B;text-align:center;line-height:1.5}
[hidden]{display:none!important}
</style></head><body><main>
<div class="top">
<img class="logo" src="/$token/logo.png" alt="Nuvio + IPTV" onerror="this.hidden=true">
<div class="chip"><span class="dot"></span><span>Connected to ${esc(tvName)}</span></div>
</div>
<div><h1>Add to your TV</h1><p class="sub">Fill this in and tap Send to TV. It shows up on your TV right away.</p></div>
<div class="tabs" role="group" aria-label="What to add">
<button type="button" data-kind="m3u" aria-pressed="true">M3U link</button>
<button type="button" data-kind="xtream" aria-pressed="false">Xtream</button>
<button type="button" data-kind="epg" aria-pressed="false">EPG only</button>
</div>
<form id="f" autocomplete="off">
<div class="field"><label for="name">Name <span>(optional)</span></label><input id="name" name="name" type="text" placeholder="My provider"></div>
<div class="field" data-for="m3u"><label for="url">Playlist link</label><input id="url" name="url" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://…/playlist.m3u"></div>
<div class="field" data-for="m3u"><label for="epg">EPG link <span>(optional)</span></label><input id="epg" name="epg" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://…/epg.xml.gz"></div>
<div class="field" data-for="m3u"><label for="ua">User agent <span>(optional)</span></label><input id="ua" name="ua" type="text" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="Leave empty for default"></div>
<div class="field" data-for="xtream" hidden><label for="server">Server</label><input id="server" name="server" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://host:port"></div>
<div class="field" data-for="xtream" hidden><label for="user">Username</label><input id="user" name="user" type="text" autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<div class="field" data-for="xtream" hidden><label for="pass">Password</label><input id="pass" name="pass" type="password" autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<label class="check" data-for="xtream" hidden><input id="live" type="checkbox" checked>Include TV channels</label>
<label class="check" data-for="xtream" hidden><input id="vod" type="checkbox" checked>Include movies &amp; series (On Demand)</label>
<div class="field" data-for="epg" hidden><label for="eurl">EPG link</label><input id="eurl" type="url" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="http://…/epg.xml.gz"></div>
<button class="send" type="submit" id="send">Send to TV</button>
<div id="msg" role="status"></div>
</form>
<p class="note">Only works on the same Wi‑Fi as your TV. This page stops working when you close the screen on your TV.</p>
</main>
<script>
var kind='m3u';
var tabs=document.querySelectorAll('.tabs button');
function show(k){kind=k;tabs.forEach(function(b){b.setAttribute('aria-pressed',b.dataset.kind===k)});
document.querySelectorAll('[data-for]').forEach(function(e){e.hidden=e.dataset.for!==k});
document.getElementById('name').placeholder=k==='epg'?'Guide':'My provider';msg('', '')}
tabs.forEach(function(b){b.onclick=function(){show(b.dataset.kind)}});
function msg(t,c){var m=document.getElementById('msg');m.textContent=t;m.className=c}
function v(id){return document.getElementById(id).value.trim()}
document.getElementById('f').onsubmit=function(e){e.preventDefault();
var p=new URLSearchParams();p.set('kind',kind);p.set('name',v('name'));
if(kind==='m3u'){p.set('url',v('url'));p.set('epg',v('epg'));p.set('ua',v('ua'))}
else if(kind==='xtream'){p.set('server',v('server'));p.set('user',v('user'));p.set('pass',document.getElementById('pass').value);
p.set('live',document.getElementById('live').checked?'1':'0');p.set('vod',document.getElementById('vod').checked?'1':'0')}
else{p.set('url',v('eurl'))}
var b=document.getElementById('send');b.disabled=true;msg('Sending…','');
fetch(location.pathname.replace(/\/$/,'')+'/add',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:p.toString()})
.then(function(r){return r.json()}).then(function(j){
if(j.ok){msg(j.message,'ok');document.querySelectorAll('input[type=text],input[type=url],input[type=password]').forEach(function(i){i.value=''})}
else msg(j.message,'bad')})
.catch(function(){msg("Couldn't reach your TV. Make sure the Add from your phone screen is still open and you're on the same Wi‑Fi.",'bad')})
.finally(function(){b.disabled=false})};
</script>
</body></html>"""
}
