package com.nuvio.tv.livetv.record

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** A computer or NAS found on the home network that has file sharing. */
data class FoundComputer(
    /** Its IP address (what's saved in the folder's address). */
    val host: String,
    /** Its name, when it says one (like BRIAN-PC or DS920); null shows the address only. */
    val name: String? = null,
)

/** What a search has found so far. */
data class NetworkSearchState(
    val found: List<FoundComputer> = emptyList(),
    /** Still looking. */
    val searching: Boolean = true,
    /** A VPN carries this app's connections, so only devices that announce themselves can be found. */
    val vpnInTheWay: Boolean = false,
    /** The TV isn't on a home network (no Wi-Fi or Ethernet address). */
    val noNetwork: Boolean = false,
)

/**
 * "Find on network": looks for computers and NAS boxes with file sharing on the TV's home
 * network, three ways at once (each finds devices the others miss):
 *  - devices that announce file sharing themselves (most NAS boxes, Macs, Linux),
 *  - every address on the home network, checked for file sharing (Windows PCs, quiet NAS boxes),
 *  - then each one found is asked its name (NetBIOS), so it's shown as "BRIAN-PC", not an address.
 * Takes about ten seconds; results come in as they're found.
 */
object NetworkFolderFinder {
    private const val SERVICE_TYPE = "_smb._tcp."
    private const val ANNOUNCE_LISTEN_MS = 9_000L
    private const val PARALLEL_CHECKS = 48

    fun search(context: Context): Flow<NetworkSearchState> = channelFlow {
        val app = context.applicationContext
        val home = homeNetwork(app)
        val vpn = vpnCarriesApp(app)
        val found = ConcurrentHashMap<String, FoundComputer>()
        val lock = Any()
        var state = NetworkSearchState(vpnInTheWay = vpn, noNetwork = home == null)

        suspend fun publish(searching: Boolean = true) {
            val list = found.values.sortedWith(
                compareBy<FoundComputer> { it.name == null }
                    .thenBy { it.name?.lowercase() }
                    .thenBy { SmbDiscovery.ipv4ToLong(it.host) ?: Long.MAX_VALUE }
            )
            val next = synchronized(lock) { state.copy(found = list, searching = searching).also { state = it } }
            send(next)
        }

        fun add(host: String, name: String?) {
            found.compute(host) { _, old ->
                FoundComputer(host, old?.name ?: name?.takeIf { it.isNotBlank() })
            }
        }

        publish()
        coroutineScope {
            // 1. Devices that announce file sharing.
            launch {
                listenForAnnounced(app) { host, name -> add(host, name); publish() }
            }
            // 2. Every address on the home network, then 3. each one's name.
            if (home != null) launch {
                val (ip, prefix, network) = home
                val gate = Semaphore(PARALLEL_CHECKS)
                coroutineScope {
                    for (address in SmbDiscovery.networkAddresses(ip, prefix)) launch {
                        val open = gate.withPermit {
                            SmbDiscovery.hasFileSharing(address, bind = { s -> network?.bindSocket(s) })
                        }
                        if (!open) return@launch
                        add(address, null)
                        publish()
                        if (found[address]?.name == null) {
                            val name = SmbDiscovery.netbiosName(address, bind = { s -> network?.bindSocket(s) })
                            if (name != null) {
                                found.computeIfPresent(address) { _, old -> if (old.name == null) old.copy(name = name) else old }
                                publish()
                            }
                        }
                    }
                }
            }
        }
        publish(searching = false)
    }.flowOn(Dispatchers.IO)

    /**
     * The TV's own address on its home network (Ethernet first, then Wi-Fi; never a VPN), its
     * prefix length, and that network (to tie checks to it, past a VPN where Android allows).
     */
    private fun homeNetwork(context: Context): Triple<String, Int, Network?>? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        @Suppress("DEPRECATION")
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
        val candidates = networks.mapNotNull { n ->
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val rank = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                else -> return@mapNotNull null
            }
            val link = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: return@mapNotNull null
            val address = link.linkAddresses.firstOrNull {
                val a = it.address
                a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress
            } ?: return@mapNotNull null
            Triple(rank, address, n)
        }
        val best = candidates.minByOrNull { it.first } ?: return null
        return Triple(best.second.address.hostAddress ?: return null, best.second.prefixLength, best.third)
    }

    /** True when this app's own connections go through a VPN (then the address checks may not get through). */
    private fun vpnCarriesApp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)

    /**
     * Listens for devices announcing file sharing for a few seconds, calling [onFound] with each
     * one's IPv4 address and name. Android looks these up one at a time, so they're queued.
     */
    private suspend fun listenForAnnounced(context: Context, onFound: suspend (String, String) -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { queue.close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return
        try {
            withTimeoutOrNull(ANNOUNCE_LISTEN_MS) {
                for (info in queue) {
                    val resolved = withTimeoutOrNull(3_000) { resolve(nsd, info) } ?: continue
                    @Suppress("DEPRECATION")
                    val address = resolved.host as? Inet4Address ?: continue
                    val host = address.hostAddress ?: continue
                    onFound(host, resolved.serviceName ?: info.serviceName ?: host)
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val ok = runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            }.isSuccess
            if (!ok && cont.isActive) cont.resume(null)
        }
}
