package com.nuvio.tv.livetv.data

import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ForkDnsLookupTest {
    private val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 7))
    private fun system(action: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = action(hostname)
    }
    private fun lookup(
        selected: DnsChoice = DnsChoice.SYSTEM, vpn: Boolean = false, retry: Boolean = true,
        cache: List<InetAddress>? = null,
        systemDns: Dns = system { listOf(address) },
        resolve: (List<DnsChoice>, String) -> DnsFallbackAnswer? = { _, _ -> throw AssertionError("Unexpected public DNS request") },
        report: (String, DnsChoice?) -> Unit = { _, _ -> }
    ) = ForkDnsLookup({ retry }, { selected }, { vpn }, { cache }, resolve, report, systemDns)

    @Test fun `home names and literal addresses always use system DNS`() {
        val queried = ArrayList<String>()
        val resolver = lookup(DnsChoice.CLOUDFLARE, systemDns = system { queried += it; listOf(address) })
        for (host in listOf("nas.local", "nas.home.arpa", "nas.lan", "localhost", "printer", "192.168.1.2", "[2001:db8::1]", "2001:db8::1")) {
            assertEquals(listOf(address), resolver.lookup(host))
        }
        assertEquals(8, queried.size)
    }

    @Test fun `VPN keeps both explicit and automatic lookups on the system resolver`() {
        for (selected in DnsChoice.entries) {
            val failure = UnknownHostException("fixture blocked")
            val resolver = lookup(selected, vpn = true, systemDns = system { throw failure })
            try { resolver.lookup("provider.example"); fail("Expected system resolver failure") }
            catch (actual: UnknownHostException) { assertSame(failure, actual) }
        }
    }

    @Test fun `chosen Google service does not query Cloudflare and can return to system DNS`() {
        var calls = 0
        val resolver = lookup(DnsChoice.GOOGLE, resolve = { services, host ->
            assertEquals(listOf(DnsChoice.GOOGLE), services)
            assertEquals("provider.example", host)
            calls++
            null
        })
        assertEquals(listOf(address), resolver.lookup("provider.example"))
        assertEquals(1, calls)
    }

    @Test fun `healthy system and non-provider failures do not query public fallback services`() {
        assertEquals(listOf(address), lookup().lookup("provider.example"))
        val failure = UnknownHostException("fixture no retry")
        val resolver = lookup(retry = false, systemDns = system { throw failure })
        try { resolver.lookup("ordinary.example"); fail("Expected original failure") }
        catch (actual: UnknownHostException) { assertSame(failure, actual) }
    }

    @Test fun `automatic provider retry keeps service order and original failure on exhaustion`() {
        val failure = UnknownHostException("original system failure")
        val reports = ArrayList<DnsChoice?>()
        val resolver = lookup(systemDns = system { throw failure }, resolve = { services, _ ->
            assertEquals(listOf(DnsChoice.CLOUDFLARE, DnsChoice.GOOGLE), services)
            null
        }, report = { _, service -> reports += service })
        try { resolver.lookup("provider.example"); fail("Expected original failure") }
        catch (actual: UnknownHostException) { assertSame(failure, actual) }
        assertEquals(listOf<DnsChoice?>(null), reports)
    }

    @Test fun `cached custom answer avoids both fallback and system calls`() {
        val resolver = lookup(DnsChoice.CLOUDFLARE, cache = listOf(address), systemDns = system { throw AssertionError("Unexpected system lookup") })
        assertEquals(listOf(address), resolver.lookup("provider.example"))
    }
}
