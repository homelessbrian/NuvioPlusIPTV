package com.nuvio.tv.livetv.crash

import android.content.res.Resources
import android.view.View
import com.nuvio.tv.R
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

    /**
     * An immutable snapshot from the Activity's app-localized resources. No Resources or
     * Context survives collection, and each server instance keeps its own language.
     */
    data class Texts(
        val languageTag: String,
        val isRtl: Boolean,
        val title: String,
        val brandName: String,
        val connectedTo: String,
        val heading: String,
        val instructions: String,
        val whatToAdd: String,
        val m3uTab: String,
        val xtreamTab: String,
        val epgTab: String,
        val nameLabel: String,
        val providerPlaceholder: String,
        val playlistLabel: String,
        val playlistPlaceholder: String,
        val epgOptionalLabel: String,
        val epgPlaceholder: String,
        val userAgentLabel: String,
        val userAgentPlaceholder: String,
        val serverLabel: String,
        val serverPlaceholder: String,
        val usernameLabel: String,
        val passwordLabel: String,
        val includeLive: String,
        val includeVod: String,
        val epgLabel: String,
        val send: String,
        val networkNote: String,
        val guidePlaceholder: String,
        val sending: String,
        val unreachable: String,
        val notFound: String,
        val nothingToSend: String,
        val missing: String,
        val couldNotAdd: String,
        val playlistMissing: String,
        val playlistScheme: String,
        val epgScheme: String,
        val serverMissing: String,
        val serverInvalid: String,
        val credentialsMissing: String,
        val inclusionMissing: String,
        val epgMissing: String,
        val kindMissing: String
    ) {
        companion object {
            fun from(resources: Resources, tvName: String): Texts {
                val configuration = resources.configuration
                val languageTag = configuration.locales.takeIf { !it.isEmpty }
                    ?.get(0)?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en"
                return Texts(
                    languageTag = languageTag,
                    isRtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                    title = resources.getString(R.string.iptv_phone_title),
                    brandName = resources.getString(R.string.iptv_phone_brand_name),
                    connectedTo = resources.getString(R.string.iptv_phone_connected_to, tvName),
                    heading = resources.getString(R.string.iptv_phone_heading),
                    instructions = resources.getString(R.string.iptv_phone_instructions),
                    whatToAdd = resources.getString(R.string.iptv_phone_what_to_add),
                    m3uTab = resources.getString(R.string.iptv_phone_m3u_tab),
                    xtreamTab = resources.getString(R.string.iptv_phone_xtream_tab),
                    epgTab = resources.getString(R.string.iptv_phone_epg_tab),
                    nameLabel = resources.getString(R.string.iptv_phone_name_label),
                    providerPlaceholder = resources.getString(R.string.iptv_phone_provider_placeholder),
                    playlistLabel = resources.getString(R.string.iptv_phone_playlist_label),
                    playlistPlaceholder = resources.getString(R.string.iptv_phone_playlist_placeholder),
                    epgOptionalLabel = resources.getString(R.string.iptv_phone_epg_optional_label),
                    epgPlaceholder = resources.getString(R.string.iptv_phone_epg_placeholder),
                    userAgentLabel = resources.getString(R.string.iptv_phone_user_agent_label),
                    userAgentPlaceholder = resources.getString(R.string.iptv_phone_user_agent_placeholder),
                    serverLabel = resources.getString(R.string.iptv_phone_server_label),
                    serverPlaceholder = resources.getString(R.string.iptv_phone_server_placeholder),
                    usernameLabel = resources.getString(R.string.iptv_phone_username_label),
                    passwordLabel = resources.getString(R.string.iptv_phone_password_label),
                    includeLive = resources.getString(R.string.iptv_phone_include_live),
                    includeVod = resources.getString(R.string.iptv_phone_include_vod),
                    epgLabel = resources.getString(R.string.iptv_phone_epg_label),
                    send = resources.getString(R.string.iptv_phone_send),
                    networkNote = resources.getString(R.string.iptv_phone_network_note),
                    guidePlaceholder = resources.getString(R.string.iptv_phone_guide_placeholder),
                    sending = resources.getString(R.string.iptv_phone_sending),
                    unreachable = resources.getString(R.string.iptv_phone_unreachable),
                    notFound = resources.getString(R.string.iptv_phone_not_found),
                    nothingToSend = resources.getString(R.string.iptv_phone_nothing_to_send),
                    missing = resources.getString(R.string.iptv_phone_missing),
                    couldNotAdd = resources.getString(R.string.iptv_phone_could_not_add),
                    playlistMissing = resources.getString(R.string.iptv_phone_playlist_missing),
                    playlistScheme = resources.getString(R.string.iptv_phone_playlist_scheme),
                    epgScheme = resources.getString(R.string.iptv_phone_epg_scheme),
                    serverMissing = resources.getString(R.string.iptv_phone_server_missing),
                    serverInvalid = resources.getString(R.string.iptv_phone_server_invalid),
                    credentialsMissing = resources.getString(R.string.iptv_phone_credentials_missing),
                    inclusionMissing = resources.getString(R.string.iptv_phone_inclusion_missing),
                    epgMissing = resources.getString(R.string.iptv_phone_epg_missing),
                    kindMissing = resources.getString(R.string.iptv_phone_kind_missing)
                )
            }
        }
    }

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
        texts: Texts,
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
                Thread { serve(client, token, texts, accent, logo, onEntry) }.apply { isDaemon = true; start() }
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
        texts: Texts,
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
                        send("200 OK", "text/html; charset=utf-8", page(token, texts, accent).toByteArray(Charsets.UTF_8))
                    method == "GET" && path == "$token/logo.png" -> {
                        val bytes = logo()
                        if (bytes != null) send("200 OK", "image/png", bytes)
                        else send("404 Not Found", "text/plain; charset=utf-8", texts.notFound.toByteArray(Charsets.UTF_8))
                    }
                    method == "POST" && path == "$token/add" -> {
                        if (length !in 1..65_536) {
                            send("400 Bad Request", "application/json", json(false, texts.nothingToSend))
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
                        val (entry, problem) = validate(form, texts)
                        if (entry == null) {
                            send("200 OK", "application/json", json(false, problem ?: texts.missing))
                        } else {
                            val message = runCatching { onEntry(entry) }.getOrElse { texts.couldNotAdd }
                            send("200 OK", "application/json", json(true, message))
                        }
                    }
                    else -> send("404 Not Found", "text/plain; charset=utf-8", texts.notFound.toByteArray(Charsets.UTF_8))
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
    private fun validate(f: Map<String, String>, texts: Texts): Pair<Entry?, String?> {
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
                url.isEmpty() -> null to texts.playlistMissing
                !isWeb(url) -> null to texts.playlistScheme
                epg.isNotEmpty() && !isWeb(epg) -> null to texts.epgScheme
                else -> entry to null
            }
            "xtream" -> when {
                server.isEmpty() -> null to texts.serverMissing
                !isWeb(server) && !server.contains('.') && !server.contains(':') -> null to texts.serverInvalid
                (user.isEmpty() || pass.isEmpty()) && !server.contains("username=", true) -> null to texts.credentialsMissing
                !entry.includeLive && !entry.includeVod -> null to texts.inclusionMissing
                else -> entry to null
            }
            "epg" -> when {
                url.isEmpty() -> null to texts.epgMissing
                !isWeb(url) -> null to texts.epgScheme
                else -> entry to null
            }
            else -> null to texts.kindMissing
        }
    }

    /** JSON string literals also safe inside an HTML script block. */
    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
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
<img class="logo" src="/${esc(token)}/logo.png" alt="${esc(texts.brandName)}" onerror="this.hidden=true">
<div class="chip"><span class="dot"></span><span>${esc(texts.connectedTo)}</span></div>
</div>
<div><h1>${esc(texts.heading)}</h1><p class="sub">${esc(texts.instructions)}</p></div>
<div class="tabs" role="group" aria-label="${esc(texts.whatToAdd)}">
<button type="button" data-kind="m3u" aria-pressed="true">${esc(texts.m3uTab)}</button>
<button type="button" data-kind="xtream" aria-pressed="false">${esc(texts.xtreamTab)}</button>
<button type="button" data-kind="epg" aria-pressed="false">${esc(texts.epgTab)}</button>
</div>
<form id="f" autocomplete="off">
<div class="field"><label for="name">${esc(texts.nameLabel)}</label><input id="name" name="name" type="text" dir="auto" placeholder="${esc(texts.providerPlaceholder)}"></div>
<div class="field" data-for="m3u"><label for="url">${esc(texts.playlistLabel)}</label><input id="url" name="url" type="url" dir="ltr" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(texts.playlistPlaceholder)}"></div>
<div class="field" data-for="m3u"><label for="epg">${esc(texts.epgOptionalLabel)}</label><input id="epg" name="epg" type="url" dir="ltr" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(texts.epgPlaceholder)}"></div>
<div class="field" data-for="m3u"><label for="ua">${esc(texts.userAgentLabel)}</label><input id="ua" name="ua" type="text" dir="ltr" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(texts.userAgentPlaceholder)}"></div>
<div class="field" data-for="xtream" hidden><label for="server">${esc(texts.serverLabel)}</label><input id="server" name="server" type="url" dir="ltr" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(texts.serverPlaceholder)}"></div>
<div class="field" data-for="xtream" hidden><label for="user">${esc(texts.usernameLabel)}</label><input id="user" name="user" type="text" dir="auto" autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<div class="field" data-for="xtream" hidden><label for="pass">${esc(texts.passwordLabel)}</label><input id="pass" name="pass" type="password" dir="ltr" autocapitalize="off" autocorrect="off" spellcheck="false"></div>
<label class="check" data-for="xtream" hidden><input id="live" type="checkbox" checked>${esc(texts.includeLive)}</label>
<label class="check" data-for="xtream" hidden><input id="vod" type="checkbox" checked>${esc(texts.includeVod)}</label>
<div class="field" data-for="epg" hidden><label for="eurl">${esc(texts.epgLabel)}</label><input id="eurl" type="url" dir="ltr" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="${esc(texts.epgPlaceholder)}"></div>
<button class="send" type="submit" id="send">${esc(texts.send)}</button>
<div id="msg" role="status"></div>
</form>
<p class="note">${esc(texts.networkNote)}</p>
</main>
<script>
var labels={provider:${quote(texts.providerPlaceholder)},guide:${quote(texts.guidePlaceholder)},sending:${quote(texts.sending)},unreachable:${quote(texts.unreachable)}};
var kind='m3u';
var tabs=document.querySelectorAll('.tabs button');
function show(k){kind=k;tabs.forEach(function(b){b.setAttribute('aria-pressed',b.dataset.kind===k)});
document.querySelectorAll('[data-for]').forEach(function(e){e.hidden=e.dataset.for!==k});
document.getElementById('name').placeholder=k==='epg'?labels.guide:labels.provider;msg('', '')}
tabs.forEach(function(b){b.onclick=function(){show(b.dataset.kind)}});
function msg(t,c){var m=document.getElementById('msg');m.textContent=t;m.className=c}
function v(id){return document.getElementById(id).value.trim()}
document.getElementById('f').onsubmit=function(e){e.preventDefault();
var p=new URLSearchParams();p.set('kind',kind);p.set('name',v('name'));
if(kind==='m3u'){p.set('url',v('url'));p.set('epg',v('epg'));p.set('ua',v('ua'))}
else if(kind==='xtream'){p.set('server',v('server'));p.set('user',v('user'));p.set('pass',document.getElementById('pass').value);
p.set('live',document.getElementById('live').checked?'1':'0');p.set('vod',document.getElementById('vod').checked?'1':'0')}
else{p.set('url',v('eurl'))}
var b=document.getElementById('send');b.disabled=true;msg(labels.sending,'');
fetch(location.pathname.replace(/\/$/,'')+'/add',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:p.toString()})
.then(function(r){return r.json()}).then(function(j){
if(j.ok){msg(j.message,'ok');document.querySelectorAll('input[type=text],input[type=url],input[type=password]').forEach(function(i){i.value=''})}
else msg(j.message,'bad')})
.catch(function(){msg(labels.unreachable,'bad')})
.finally(function(){b.disabled=false})};
</script>
</body></html>"""
}
