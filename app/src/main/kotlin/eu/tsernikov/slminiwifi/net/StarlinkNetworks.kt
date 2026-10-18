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
import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.MiniClient
import java.io.Closeable
import java.net.Inet4Address
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class StarlinkConnection(val network: Network, val routerAddress: String)

/** Owns only this app's temporary Wi-Fi request. Never changes the process's default network. */
class StarlinkNetworks(context: Context) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val lock = Any()
    private var requestedNetwork: Network? = null
    private var requestedWifiName: String? = null
    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingRequest: CancellableContinuation<StarlinkConnection?>? = null

    /** Observe the selected link, never the phone's default (possibly mobile) network. */
    fun isPresent(connection: StarlinkConnection): Boolean = connection.network in connectivity.allNetworks

    fun isEthernet(connection: StarlinkConnection): Boolean =
        connectivity.getNetworkCapabilities(connection.network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true

    fun ipv4Addresses(connection: StarlinkConnection): List<String> =
        connectivity.getLinkProperties(connection.network)?.linkAddresses.orEmpty()
            .mapNotNull { (it.address as? Inet4Address)?.hostAddress }

    fun interfaceName(connection: StarlinkConnection): String? =
        connectivity.getLinkProperties(connection.network)?.interfaceName

    /** DHCP may replace a Network handle; only the same cable adapter can verify the write. */
    fun findEthernetOnInterface(name: String?): StarlinkConnection? {
        if (name == null) return null
        return connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true &&
                connectivity.getLinkProperties(network)?.interfaceName == name && usableEthernet(network)
        }?.let(::connection)
    }

    /** Probe each candidate in order: an unrelated cable must not mask setup Wi-Fi. */
    @SuppressLint("MissingPermission")
    fun findCandidates(): List<StarlinkConnection> {
        val networks = connectivity.allNetworks
        val ethernet = networks.filter { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true && usableEthernet(network)
        }
        val requested = synchronized(lock) { requestedNetwork }?.takeIf { it in networks }
        val starlink = networks.filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                isNamedWifi(network, capabilities, "STARLINK")
        }
        return (ethernet + listOfNotNull(requested) + starlink).distinct().map(::connection)
    }

    /** Call again after a link change so DHCP changes are reflected in the returned gateway. */
    fun findAvailable(): StarlinkConnection? = findCandidates().firstOrNull()

    /** A saved name alone is not identity. Only its discovered gateway may be checked first. */
    suspend fun findVerifiedSavedWifi(
        name: String,
        readDeviceInfo: suspend (StarlinkConnection) -> DeviceInfo? = { candidate ->
            OkHttpTransport(candidate.network, candidate.routerAddress).use { transport ->
                (MiniClient(transport, "http://${candidate.routerAddress}:9001").getDeviceInfo()
                    as? ClientResult.Ok)?.response?.deviceInfo
            }
        },
    ): StarlinkConnection? {
        val candidate = findWifi(name) ?: return null
        val gateway = gateway(connectivity.getLinkProperties(candidate.network)) ?: return null
        val discovered = candidate.copy(routerAddress = gateway.hostAddress!!)
        val info = readDeviceInfo(discovered)
        return discovered.takeIf { info?.id?.startsWith("Router-") == true }
    }

    /** For a network just created by this app. Home must use findVerifiedSavedWifi instead. */
    @SuppressLint("MissingPermission")
    fun findWifi(name: String): StarlinkConnection? {
        val available = connectivity.allNetworks
        // Android can redact SSIDs even with Nearby Wi-Fi permission. The active
        // specifier itself identifies replacement handles delivered to this request.
        val requested = synchronized(lock) {
            requestedNetwork?.takeIf { requestedWifiName == name && it in available }
        }
        if (requested != null) return connection(requested)
        return available.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                isNamedWifi(network, capabilities, name)
        }?.let(::connection)
    }

    private fun usableEthernet(network: Network): Boolean {
        val properties = connectivity.getLinkProperties(network) ?: return false
        val gateway = ipv4Bytes(connection(network, properties).routerAddress) ?: return false
        return properties.linkAddresses.any { link ->
            val address = link.address
            if (address !is Inet4Address || address.isLinkLocalAddress ||
                address.isLoopbackAddress || address.isAnyLocalAddress
            ) return@any false
            if (link.prefixLength !in 1..32) return@any false
            val bytes = address.address
            val carrierAddress = (bytes[0].toInt() and 255) == 100 &&
                (bytes[1].toInt() and 255) in 64..127
            carrierAddress || sameSubnet(bytes, gateway, link.prefixLength)
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun isNamedWifi(network: Network, capabilities: NetworkCapabilities, name: String): Boolean {
        val info = capabilities.transportInfo as? WifiInfo
        if (info?.ssid?.removeSurrounding("\"") == name) return true
        // getNetworkCapabilities may redact SSID even after permission is granted. The legacy
        // primary Wi-Fi info remains available; match its IP to avoid selecting another Wi-Fi link.
        val primary = wifi.connectionInfo ?: return false
        if (primary.ssid.removeSurrounding("\"") != name || primary.ipAddress == 0) return false
        val address = ByteArray(4) { index -> (primary.ipAddress ushr (index * 8)).toByte() }
        return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .any { it.address.address.contentEquals(address) }
    }

    private fun connection(
        network: Network,
        properties: LinkProperties? = connectivity.getLinkProperties(network),
    ): StarlinkConnection {
        return StarlinkConnection(network, gateway(properties)?.hostAddress ?: "192.168.1.1")
    }

    private fun gateway(properties: LinkProperties?): Inet4Address? {
        val routeGateway = properties?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress
        }?.gateway as? Inet4Address
        return routeGateway ?: if (Build.VERSION.SDK_INT >= 30) {
            properties?.dhcpServerAddress?.takeUnless { it.isAnyLocalAddress }
        } else null
    }

    /** Invoke only after the user's Connect tap. Android supplies the connection confirmation. */
    suspend fun requestStarlink(): StarlinkConnection? = requestWifi("STARLINK", null, 60_000)

    /** Reconnect after the confirmed setup write, to verify over the newly secured Wi-Fi. */
    suspend fun requestSecureWifi(name: String, password: String): StarlinkConnection? {
        require(password.isNotEmpty()) { "A password is required" }
        return requestWifi(name, password, 120_000)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestWifi(name: String, password: String?, timeoutMillis: Int): StarlinkConnection? {
        releaseRequest()
        // Android's timeout stops after onAvailable, which can precede DHCP indefinitely.
        return withTimeoutOrNull(timeoutMillis.toLong()) {
            suspendCancellableCoroutine { continuation ->
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
                            if (gateway(properties) == null) return
                            pendingRequest = null
                            if (continuation.isActive) continuation.resume(connection(network, properties))
                        }
                    }

                    override fun onUnavailable() {
                        synchronized(lock) {
                            if (requestCallback === this) {
                                requestCallback = null
                                requestedNetwork = null
                                requestedWifiName = null
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
                    .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(name).apply {
                        if (password != null) setWpa2Passphrase(password)
                    }.build())
                    .build()
                synchronized(lock) {
                    requestCallback = callback
                    requestedWifiName = name
                    pendingRequest = continuation
                    try {
                        connectivity.requestNetwork(request, callback, timeoutMillis)
                    } catch (failure: RuntimeException) {
                        requestCallback = null
                        requestedWifiName = null
                        pendingRequest = null
                        throw failure
                    }
                }
                continuation.invokeOnCancellation { releaseCallback(callback) }
            }
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
            requestedWifiName = null
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

/** Invalid or catch-all prefixes are not evidence that a cable reaches the router. */
internal fun sameSubnet(address: ByteArray, gateway: ByteArray, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    fun number(bytes: ByteArray) = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
    return (number(address) ushr (32 - prefixLength)) == (number(gateway) ushr (32 - prefixLength))
}
