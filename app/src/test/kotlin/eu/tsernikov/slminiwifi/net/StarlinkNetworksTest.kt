package eu.tsernikov.slminiwifi.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class StarlinkNetworksTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(ConnectivityManager::class.java)
    private val shadow get() = shadowOf(manager)
    private lateinit var networks: StarlinkNetworks

    @Before fun setUp() {
        shadow.clearAllNetworks()
        networks = StarlinkNetworks(context)
    }

    @After fun tearDown() = networks.close()

    @Test fun ethernetTakesPriorityOverStarlinkWifiWithoutBindingProcess() {
        addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        val ethernet = addNetwork(2, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(ethernet, networks.findAvailable()?.network)
        assertNull(manager.boundNetworkForProcess)
    }

    @Test fun usesCurrentStarlinkWifiAndRejectsOtherWifiAndCellular() {
        addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "Other network")
        addNetwork(2, NetworkCapabilities.TRANSPORT_CELLULAR)
        assertNull(networks.findAvailable())
        val starlink = addNetwork(3, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        assertEquals(starlink, networks.findAvailable()?.network)
    }

    @Test fun ethernetAwaitingDhcpDoesNotMaskStarlinkWifi() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties())
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        assertEquals(wifi, networks.findAvailable()?.network)
    }

    @Test fun ethernetWithCarrierAddressIsUsableWithoutRouterGateway() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("100.76.233.16", 10)))
        })
        assertEquals(ethernet, networks.findAvailable()?.network)
    }

    @Test fun ethernetWithOnlyLinkLocalAddressDoesNotMaskStarlinkWifi() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("169.254.2.42", 16)))
        })
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        assertEquals(wifi, networks.findAvailable()?.network)
    }

    @Test fun redactedWifiCapabilitiesUsePrimarySsidOnlyForMatchingLinkAddress() {
        val unrelated = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        val starlink = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI)
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID("STARLINK")
        shadowOf(info).setInetAddress(ip("192.168.1.42"))
        shadowOf(context.getSystemService(WifiManager::class.java)).setConnectionInfo(info)
        shadow.setLinkProperties(unrelated, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("192.168.2.42")))
        })
        shadow.setLinkProperties(starlink, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("192.168.1.42")))
        })
        assertEquals(starlink, networks.findAvailable()?.network)
    }

    @Test fun defaultRouteGatewayTakesPriorityOverDhcpServer() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            interfaceName = "eth0"
            setLinkAddresses(listOf(linkAddress("192.168.2.42")))
            dhcpServerAddress = ip("192.168.2.2")
            addRoute(defaultRoute("192.168.2.1"))
        })
        assertEquals("192.168.2.1", networks.findAvailable()?.routerAddress)
    }

    @Test fun usesDhcpServerWhenThereIsNoDefaultRoute() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("192.168.3.42")))
            dhcpServerAddress = ip("192.168.3.1")
        })
        assertEquals("192.168.3.1", networks.findAvailable()?.routerAddress)
    }

    @Test fun missingGatewayFallsBackToFactoryAddress() {
        addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals("192.168.1.1", networks.findAvailable()?.routerAddress)
    }

    @Test fun requestedWifiWaitsForLinkPropertiesAndIsReleasedWhenDone() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val callback = shadow.networkCallbacks.single()
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(wifi)
        assertFalse(request.isCompleted)
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.4.1")
        })
        assertEquals(StarlinkConnection(wifi, "192.168.4.1"), request.await())
        assertEquals(wifi, networks.findAvailable()?.network)
        assertNull(manager.boundNetworkForProcess)
        networks.releaseRequest()
        assertTrue(shadow.networkCallbacks.isEmpty())
        assertNull(networks.findAvailable())
    }

    @Test fun cancellingRequestUnregistersCallback() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        assertEquals(1, shadow.networkCallbacks.size)
        request.cancel()
        request.join()
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun releaseCompletesPendingRequestWithoutAConnection() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        networks.releaseRequest()
        assertNull(request.await())
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun unavailableOrLostRequestedWifiDoesNotRemainSelected() = runTest {
        val unavailable = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val firstCallback = shadow.networkCallbacks.single()
        // Android removes the request itself before delivering onUnavailable.
        manager.unregisterNetworkCallback(firstCallback)
        firstCallback.onUnavailable()
        assertNull(unavailable.await())
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val callback = shadow.networkCallbacks.single()
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(wifi)
        callback.onLinkPropertiesChanged(wifi, LinkProperties())
        request.await()
        callback.onLost(wifi)
        assertNull(networks.findAvailable())
    }

    private fun addNetwork(id: Int, transport: Int, ssid: String? = null): Network {
        val network = ShadowNetwork.newInstance(id)
        shadow.addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, transport, 0, true, true,
        ))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        if (ssid != null) {
            val info = ShadowWifiInfo.newInstance()
            shadowOf(info).setSSID(ssid)
            shadowOf(capabilities).setTransportInfo(info)
        }
        shadow.setNetworkCapabilities(network, capabilities)
        if (transport == NetworkCapabilities.TRANSPORT_ETHERNET) {
            shadow.setLinkProperties(network, LinkProperties().apply {
                setLinkAddresses(listOf(linkAddress("192.168.1.42")))
            })
        }
        return network
    }

    private fun ip(value: String) = InetAddress.getByAddress(ipv4Bytes(value)!!) as Inet4Address

    private fun linkAddress(value: String, prefix: Int = 24): LinkAddress = ReflectionHelpers.callConstructor(
        LinkAddress::class.java,
        ClassParameter.from(InetAddress::class.java, ip(value)),
        ClassParameter.from(Int::class.javaPrimitiveType!!, prefix),
    )

    private fun defaultRoute(gateway: String): RouteInfo = ReflectionHelpers.callConstructor(
        RouteInfo::class.java,
        ClassParameter.from(IpPrefix::class.java, IpPrefix(ip("0.0.0.0"), 0)),
        ClassParameter.from(InetAddress::class.java, ip(gateway)),
        ClassParameter.from(String::class.java, "eth0"),
        ClassParameter.from(Int::class.javaPrimitiveType!!, RouteInfo.RTN_UNICAST),
    )
}
