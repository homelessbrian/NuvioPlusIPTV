package com.nuvio.tv.livetv.data

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/** Resolver policy stays separate from the bounded HTTPS fallback operation. */
internal class ForkDnsLookup(
    private val retryFor: (String) -> Boolean,
    private val choice: () -> DnsChoice,
    private val vpnOn: () -> Boolean,
    private val cached: (String) -> List<InetAddress>?,
    private val resolve: (List<DnsChoice>, String) -> DnsFallbackAnswer?,
    private val report: (String, DnsChoice?) -> Unit,
    private val system: Dns = Dns.SYSTEM
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()
        val allowRetry = retryFor(host)
        if (!isPublicName(host)) return system.lookup(hostname)
        val selected = choice()
        if (selected != DnsChoice.SYSTEM && !vpnOn()) {
            cached(host)?.let { return it }
            resolve(listOf(selected), hostname)?.let { return it.addresses }
            return system.lookup(hostname)
        }
        try {
            return system.lookup(hostname)
        } catch (notFound: UnknownHostException) {
            if (!allowRetry || vpnOn()) throw notFound
            cached(host)?.let { return it }
            resolve(listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE), hostname)?.let {
                report(host, it.service)
                return it.addresses
            }
            report(host, null)
            throw notFound
        }
    }

    private fun isPublicName(host: String): Boolean {
        if (host.isEmpty() || '.' !in host || host == "localhost") return false
        if (host.startsWith("[") || ':' in host || host.all { it.isDigit() || it == '.' }) return false
        return listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa").none { host.endsWith(it) }
    }
}
