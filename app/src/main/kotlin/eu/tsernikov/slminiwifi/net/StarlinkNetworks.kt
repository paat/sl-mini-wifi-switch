package eu.tsernikov.slminiwifi.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.io.Closeable
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

data class StarlinkConnection(val network: Network, val routerAddress: String)

/** Owns only this app's STARLINK request. It never changes the process's default network. */
class StarlinkNetworks(context: Context) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = Any()
    private var requestedNetwork: Network? = null
    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingRequest: CancellableContinuation<StarlinkConnection?>? = null

    /** Call again after a link change so DHCP changes are reflected in the returned gateway. */
    @SuppressLint("MissingPermission") // Activity obtains runtime Wi-Fi/local-network permissions.
    fun findAvailable(): StarlinkConnection? {
        val networks = connectivity.allNetworks
        val ethernet = networks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true && usableEthernet(network)
        }
        if (ethernet != null) return connection(ethernet)
        val requested = synchronized(lock) { requestedNetwork }
        if (requested != null && requested in networks) return connection(requested)
        val starlink = networks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                isStarlink(network, capabilities)
        }
        return starlink?.let(::connection)
    }

    private fun usableEthernet(network: Network): Boolean {
        val properties = connectivity.getLinkProperties(network) ?: return false
        val gateway = ipv4Bytes(connection(network, properties).routerAddress) ?: return false
        return properties.linkAddresses.any { link ->
            val address = link.address
            if (address !is Inet4Address || address.isLinkLocalAddress ||
                address.isLoopbackAddress || address.isAnyLocalAddress
            ) return@any false
            val bytes = address.address
            val carrierAddress = (bytes[0].toInt() and 255) == 100 &&
                (bytes[1].toInt() and 255) in 64..127
            carrierAddress || (ipv4Number(bytes) ushr (32 - link.prefixLength)) ==
                (ipv4Number(gateway) ushr (32 - link.prefixLength))
        }
    }

    private fun ipv4Number(bytes: ByteArray): Long =
        bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun isStarlink(network: Network, capabilities: NetworkCapabilities): Boolean {
        val info = capabilities.transportInfo as? WifiInfo
        if (info?.ssid?.removeSurrounding("\"") == "STARLINK") return true
        // getNetworkCapabilities may redact SSID even after permission is granted. The legacy
        // primary Wi-Fi info remains available; match its IP to avoid selecting another Wi-Fi link.
        val primary = wifi.connectionInfo ?: return false
        if (primary.ssid.removeSurrounding("\"") != "STARLINK" || primary.ipAddress == 0) return false
        val address = ByteArray(4) { index -> (primary.ipAddress ushr (index * 8)).toByte() }
        return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .any { it.address.address.contentEquals(address) }
    }

    private fun connection(
        network: Network,
        properties: LinkProperties? = connectivity.getLinkProperties(network),
    ): StarlinkConnection {
        val gateway = properties?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress
        }?.gateway as? Inet4Address
        val address = gateway ?: if (Build.VERSION.SDK_INT >= 30) properties?.dhcpServerAddress else null
        return StarlinkConnection(network, address?.hostAddress ?: "192.168.1.1")
    }

    /** Invoke only after the user's Connect tap. Android supplies the connection confirmation. */
    @SuppressLint("MissingPermission")
    suspend fun requestStarlink(): StarlinkConnection? {
        releaseRequest()
        return suspendCancellableCoroutine { continuation ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(lock) {
                        if (requestCallback !== this) return
                        requestedNetwork = network
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    synchronized(lock) {
                        if (requestCallback !== this || requestedNetwork != network) return
                        pendingRequest = null
                        if (continuation.isActive) continuation.resume(connection(network, properties))
                    }
                }

                override fun onUnavailable() {
                    synchronized(lock) {
                        if (requestCallback === this) {
                            requestCallback = null
                            requestedNetwork = null
                            pendingRequest = null
                        }
                    }
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onLost(network: Network) {
                    synchronized(lock) {
                        if (requestCallback === this && requestedNetwork == network) requestedNetwork = null
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid("STARLINK").build())
                .build()
            synchronized(lock) {
                requestCallback = callback
                pendingRequest = continuation
                try {
                    connectivity.requestNetwork(request, callback, 60_000)
                } catch (failure: RuntimeException) {
                    requestCallback = null
                    pendingRequest = null
                    throw failure
                }
            }
            continuation.invokeOnCancellation { releaseCallback(callback) }
        }
    }

    fun releaseRequest() {
        val callback = synchronized(lock) { requestCallback } ?: return
        releaseCallback(callback)
    }

    private fun releaseCallback(callback: ConnectivityManager.NetworkCallback) {
        synchronized(lock) {
            if (requestCallback !== callback) return
            requestCallback = null
            requestedNetwork = null
            pendingRequest?.let { if (it.isActive) it.resume(null) }
            pendingRequest = null
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Android has already released a request after onUnavailable.
            }
        }
    }

    override fun close() = releaseRequest()
}
