package com.nuvio.tv.livetv.data

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.vpn.android.VpnPlaybackProtection
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Which service turns a server's name (e.g. "line.provider.com") into its address. */
enum class DnsChoice { SYSTEM, CLOUDFLARE, GOOGLE }

/**
 * The app's address lookups for Live TV, On Demand, Sports, sync and playback.
 *
 * - "System" (the default) uses the phone/TV's normal lookup, like every other app.
 * - Cloudflare or Google ask that service instead (over HTTPS, so an internet company's own
 *   lookup can't block it). If it finds nothing (a home server name like "nas.local", or Wi-Fi
 *   that blocks the service), the normal lookup is used after all.
 * - On "System", an IPTV provider's server the normal lookup can't find is tried once more with
 *   Cloudflare, then Google. Only provider addresses get that retry, so home ad blockers and
 *   family filters keep working for everything else.
 * - While the VPN is on, the normal lookup is always used: the VPN already gets around blocks,
 *   and its own lookup stays in charge.
 *
 * Answers are remembered for a few minutes, so only the first lookup of a server is slower.
 * Per device (not synced): which lookup works depends on the network the device is on.
 */
object ForkDns {
    private const val FILE = "livetv_dns"
    private const val KEY = "choice"
    private const val MIN_TTL_S = 60L
    private const val MAX_TTL_S = 600L
    private const val MAX_CACHE = 256
    private const val MAX_PROVIDER_HOSTS = 500
    private const val VPN_CHECK_MS = 5_000L

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile var choice: DnsChoice = DnsChoice.SYSTEM
        private set

    private class Answer(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Answer>()
    private val providerHosts = ConcurrentHashMap.newKeySet<String>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var vpnOn = false
    @Volatile private var vpnCheckedAt = 0L

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        choice = p.getString(KEY, null)?.let { runCatching { DnsChoice.valueOf(it) }.getOrNull() } ?: DnsChoice.SYSTEM
        if (choice != DnsChoice.SYSTEM) LiveTvLoadReport.add("DNS: ${label(choice)}")
    }

    fun set(context: Context, value: DnsChoice) {
        if (prefs == null) init(context)
        if (value == choice) return
        choice = value
        prefs?.edit()?.putString(KEY, value.name)?.apply()
        cache.clear()
        reported.clear()
        // Connections opened with the old lookup are closed, so the next request uses the new one.
        Thread({ runCatching { ForkHttp.base.connectionPool.evictAll() } }, "dns-change").start()
        LiveTvLoadReport.add("DNS: ${label(value)}")
    }

    /** Sports, Calendar, sync: the chosen service, no automatic retry. */
    val app: Dns = Lookup { false }

    /** Clients that only talk to IPTV providers (playlists, guides, On Demand, portals). */
    val provider: Dns = Lookup { host -> addProviderHost(host); true }

    /** The player: the chosen service; the automatic retry only for IPTV provider servers. */
    val playback: Dns = Lookup { host -> host in providerHosts }

    /** The servers a playlist's channels play from, so playback gets the retry for them too. */
    fun addProviderHosts(channels: List<LiveChannel>) {
        if (providerHosts.size >= MAX_PROVIDER_HOSTS) return
        val seen = HashSet<String>()
        for (c in channels) {
            val host = hostOf(c.url) ?: continue
            if (seen.add(host)) addProviderHost(host)
            if (providerHosts.size >= MAX_PROVIDER_HOSTS) return
        }
    }

    private fun addProviderHost(host: String) {
        if (providerHosts.size < MAX_PROVIDER_HOSTS) providerHosts.add(host.lowercase())
    }

    private fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase().takeIf { it.isNotEmpty() }
    }

    private class Lookup(private val retryFor: (String) -> Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase()
            val allowRetry = retryFor(host)
            if (!isPublicName(host)) return Dns.SYSTEM.lookup(hostname)
            val chosen = choice
            if (chosen != DnsChoice.SYSTEM && !vpnIsOn()) {
                cached(host)?.let { return it }
                resolve(chosen, hostname)?.let { return it }
                return Dns.SYSTEM.lookup(hostname)
            }
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (notFound: UnknownHostException) {
                if (!allowRetry || vpnIsOn()) throw notFound
                cached(host)?.let { return it }
                for (service in listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE)) {
                    val found = resolve(service, hostname) ?: continue
                    if (reported.add(host)) LiveTvLoadReport.add("DNS: normal lookup couldn't find $host; ${label(service)} found it")
                    return found
                }
                if (reported.add(host)) LiveTvLoadReport.add("DNS: $host not found (normal lookup, Cloudflare and Google)")
                throw notFound
            }
        }
    }

    /** Home-network names, plain IPs and "localhost" always use the normal lookup. */
    private fun isPublicName(host: String): Boolean {
        if (host.isEmpty() || '.' !in host || host == "localhost") return false
        if (host.startsWith("[") || ':' in host) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        val local = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa")
        return local.none { host.endsWith(it) }
    }

    private fun vpnIsOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - vpnCheckedAt > VPN_CHECK_MS) {
            vpnOn = VpnPlaybackProtection.isOn()
            vpnCheckedAt = now
        }
        return vpnOn
    }

    private fun cached(host: String): List<InetAddress>? {
        val answer = cache[host] ?: return null
        if (answer.expiresAt < System.currentTimeMillis()) { cache.remove(host); return null }
        return answer.addresses
    }

    private val client: OkHttpClient by lazy {
        // The services are reached by their IP address, so no lookup is needed to reach them.
        ForkHttp.base.newBuilder()
            .dns(Dns.SYSTEM)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun endpoints(service: DnsChoice): List<String> = when (service) {
        DnsChoice.CLOUDFLARE -> listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")
        DnsChoice.GOOGLE -> listOf("https://8.8.8.8/resolve", "https://8.8.4.4/resolve")
        DnsChoice.SYSTEM -> emptyList()
    }

    /** The service's answer (IPv4 first, then IPv6), or null if it found nothing or couldn't be reached. */
    private fun resolve(service: DnsChoice, hostname: String): List<InetAddress>? {
        for (endpoint in endpoints(service)) {
            val v4 = query(endpoint, hostname, type = 1) ?: continue
            val all = if (v4.first.isNotEmpty()) v4 else (query(endpoint, hostname, type = 28) ?: v4)
            if (all.first.isEmpty()) return null
            val ttl = all.second.coerceIn(MIN_TTL_S, MAX_TTL_S)
            if (cache.size >= MAX_CACHE) cache.clear()
            cache[hostname.lowercase()] = Answer(all.first, System.currentTimeMillis() + ttl * 1000)
            return all.first
        }
        return null
    }

    /** One question to one server: (addresses, shortest TTL), or null if the server couldn't be asked. */
    private fun query(endpoint: String, hostname: String, type: Int): Pair<List<InetAddress>, Long>? = try {
        val request = Request.Builder()
            .url("$endpoint?name=${java.net.URLEncoder.encode(hostname, "UTF-8")}&type=$type")
            .header("Accept", "application/dns-json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string().orEmpty())
            val status = json.optInt("Status", -1)
            // 0 = found, 3 = no such name: both are real answers. Anything else: ask the next server.
            if (status != 0 && status != 3) return null
            val answers = json.optJSONArray("Answer")
            val list = ArrayList<InetAddress>()
            var ttl = MAX_TTL_S
            if (answers != null) for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type") != type) continue
                val data = a.optString("data")
                val literal = when (type) {
                    1 -> data.takeIf { d -> d.isNotEmpty() && d.all { it.isDigit() || it == '.' } }
                    else -> data.takeIf { d -> d.isNotEmpty() && ':' in d && d.all { it.isLetterOrDigit() || it == ':' || it == '.' } }
                } ?: continue
                // A literal address: no lookup happens here.
                val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: continue
                list += InetAddress.getByAddress(hostname, parsed.address)
                ttl = minOf(ttl, a.optLong("TTL", MAX_TTL_S))
            }
            list to ttl
        }
    } catch (_: Exception) {
        null
    }

    fun label(value: DnsChoice): String = when (value) {
        DnsChoice.SYSTEM -> "System"
        DnsChoice.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
        DnsChoice.GOOGLE -> "Google (8.8.8.8)"
    }
}
