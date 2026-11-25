package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.nuvio.vpn.android.VpnPlaybackProtection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** App-scoped recovery signal for metadata; ordinary VPN traffic polls are deliberately ignored. */
@Singleton
class SourceNetworkRecovery @Inject constructor(@ApplicationContext context: Context) {
    private val _generation = MutableStateFlow(0L)
    val generation: StateFlow<Long> = _generation.asStateFlow()
    private val state = SourceNetworkRecoveryState { _generation.update { it + 1L } }
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var vpnGeneration = VpnPlaybackProtection.networkRecoveryRevision()
    // This singleton lives with the process. Register before the final read to avoid losing a
    // connection that becomes ready between initialisation and listener registration.
    private val vpnSubscription = VpnPlaybackProtection.observeNetworkRecovery { vpnRecovered() }

    init {
        updateDefaultNetwork()
        vpnRecovered()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultNetwork()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateDefaultNetwork()
            override fun onLost(network: Network) {
                if (manager?.activeNetwork == network || manager?.activeNetwork == null) {
                    state.update(null, false, false, false)
                } else updateDefaultNetwork()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) manager?.registerDefaultNetworkCallback(callback)
            else manager?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        }
    }

    @Synchronized private fun vpnRecovered() {
        val current = VpnPlaybackProtection.networkRecoveryRevision()
        if (current == vpnGeneration) return
        vpnGeneration = current
        _generation.update { it + 1L }
    }

    private fun updateDefaultNetwork() {
        runCatching {
            val network = manager?.activeNetwork
            val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
            state.update(
                network?.toString(),
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        }
    }
}

/** Keep initial/duplicate callbacks and unverified Android VPN interfaces from triggering retries. */
internal class SourceNetworkRecoveryState(private val recovered: () -> Unit) {
    private var initialised = false
    private var network: String? = null
    private var available = false
    private var validated = false

    @Synchronized fun update(next: String?, hasInternet: Boolean, isVpn: Boolean, isValidated: Boolean = false) {
        val usable = next != null && (hasInternet || isVpn)
        val changed = next != network
        val wasAvailable = available
        val wasValidated = validated
        network = next
        available = usable
        validated = isValidated
        if (!initialised) { initialised = true; return }
        // A Full app interface appears before its handshake. The verified VPN callback owns it.
        if (!isVpn && usable && (!wasAvailable || changed || (isValidated && !wasValidated))) recovered()
    }
}
