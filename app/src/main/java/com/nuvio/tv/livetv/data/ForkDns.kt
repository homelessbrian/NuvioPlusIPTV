package com.nuvio.tv.livetv.data

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.vpn.android.VpnPlaybackProtection
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
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
 * All HTTPS fallback attempts share an eight-second budget. The normal Android resolver
 * retains its platform timing (including local names and lookups while the VPN is on).
 * Per device (not synced): which lookup works depends on the network the device is on.
 */
object ForkDns {
    private const val FILE = "livetv_dns"
    private const val KEY = "choice"
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
        if (value == choice) {
            if (prefs?.contains(KEY) == false) prefs?.edit()?.putString(KEY, value.name)?.apply()
            return
        }
        choice = value
        prefs?.edit()?.putString(KEY, value.name)?.apply()
        cache.clear()
        reported.clear()
        // Connections opened with the old lookup are closed, so the next request uses the new one.
        Thread({ runCatching { ForkHttp.base.connectionPool.evictAll() } }, "dns-change").start()
        LiveTvLoadReport.add("DNS: ${label(value)}")
    }

    /** Sports, Calendar, sync: the chosen service, no automatic retry. */
    val app: Dns = lookup { false }

    /** Clients that only talk to IPTV providers (playlists, guides, On Demand, portals). */
    val provider: Dns = lookup { host -> addProviderHost(host); true }

    /** The player: the chosen service; the automatic retry only for IPTV provider servers. */
    val playback: Dns = lookup { host -> host in providerHosts }

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

    private fun lookup(retryFor: (String) -> Boolean): Dns = ForkDnsLookup(
        retryFor, { choice }, ::vpnIsOn, ::cached, ::resolve,
        report = { host, service ->
            if (reported.add(host)) LiveTvLoadReport.add(
                if (service == null) "DNS: $host not found (normal lookup, Cloudflare and Google)"
                else "DNS: normal lookup couldn't find $host; ${label(service)} found it"
            )
        }
    )

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

    private val fallback by lazy { DnsFallbackResolver(client, ::endpoints) }

    private fun resolve(services: List<DnsChoice>, hostname: String): DnsFallbackAnswer? {
        val answer = fallback.resolve(services, hostname) ?: return null
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[hostname.lowercase()] = Answer(answer.addresses, System.currentTimeMillis() + answer.ttlSeconds * 1000)
        return answer
    }

    fun label(value: DnsChoice): String = when (value) {
        DnsChoice.SYSTEM -> "System"
        DnsChoice.CLOUDFLARE -> "Cloudflare (1.1.1.1)"
        DnsChoice.GOOGLE -> "Google (8.8.8.8)"
    }
}
