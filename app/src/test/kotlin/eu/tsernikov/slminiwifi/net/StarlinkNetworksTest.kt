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
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    @Test fun verificationObservesOnlyTheSelectedLinkAndItsChangingAddress() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        val selected = networks.findAvailable()!!
        assertTrue(networks.isPresent(selected))
        assertTrue(networks.isEthernet(selected))
        assertEquals(listOf("192.168.1.42"), networks.ipv4Addresses(selected))
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("100.76.233.16", 10)))
        })
        assertEquals(listOf("100.76.233.16"), networks.ipv4Addresses(selected))
        shadow.clearAllNetworks()
        addNetwork(2, NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(networks.isPresent(selected))
        assertNull(networks.findAvailable())
    }

    @Test fun replacementEthernetMustBelongToTheOriginalCableAdapter() {
        val original = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(original, LinkProperties().apply { interfaceName = "eth0" })
        val originalName = networks.interfaceName(StarlinkConnection(original, "192.168.1.1"))
        shadow.clearAllNetworks()
        val unrelated = addNetwork(2, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(unrelated, LinkProperties().apply {
            interfaceName = "eth1"
            setLinkAddresses(listOf(linkAddress("100.70.0.1", 10)))
        })
        assertNull(networks.findEthernetOnInterface(originalName))
        assertNull(networks.findEthernetOnInterface(null))
        val replacement = addNetwork(3, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(replacement, LinkProperties().apply {
            interfaceName = "eth0"
            setLinkAddresses(listOf(linkAddress("100.76.233.16", 10)))
        })
        assertEquals(replacement, networks.findEthernetOnInterface(originalName)?.network)
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

    @Test fun secureRequestReplacesSetupRequestAndCanVerifyNewNetwork() = runTest {
        val setup = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val setupCallback = shadow.networkCallbacks.single()
        val secure = async(start = CoroutineStart.UNDISPATCHED) {
            networks.requestSecureWifi("Mini-field", "TestPassword23")
        }
        assertNull(setup.await())
        assertEquals(1, shadow.networkCallbacks.size)
        assertFalse(setupCallback in shadow.networkCallbacks)
        val callback = shadow.networkCallbacks.single()
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "Mini-field")
        callback.onAvailable(wifi)
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.1.1")
        })
        assertEquals(wifi, secure.await()?.network)
        assertEquals(wifi, networks.findAvailable()?.network)
        networks.releaseRequest()
        assertTrue(shadow.networkCallbacks.isEmpty())
        // A remembered arbitrary Wi-Fi name cannot enter the setup discovery path.
        assertNull(networks.findAvailable())
    }

    @Test fun replacementRequestedStarlinkWithRedactedSsidStillCountsAsPresent() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val callback = shadow.networkCallbacks.single()
        val original = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(original)
        callback.onLinkPropertiesChanged(original, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.1.1")
        })
        request.await()
        callback.onLost(original)
        shadow.clearAllNetworks()
        val replacement = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(replacement)
        callback.onLinkPropertiesChanged(replacement, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.1.1")
        })
        assertEquals(replacement, networks.findWifi("STARLINK")?.network)
        networks.releaseRequest()
        assertNull(networks.findWifi("STARLINK"))
    }

    @Test fun redactedSecureRequestIsNotMistakenForSetupStarlink() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            networks.requestSecureWifi("Mini-field", "TestPassword23")
        }
        val callback = shadow.networkCallbacks.single()
        val secured = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(secured)
        callback.onLinkPropertiesChanged(secured, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.1.1")
        })
        request.await()
        assertEquals(secured, networks.findWifi("Mini-field")?.network)
        assertNull(networks.findWifi("STARLINK"))
        networks.releaseRequest()
        assertNull(networks.findWifi("Mini-field"))
    }

    @Test fun secureRequestRejectsEmptyPasswordBeforeRequestingOpenNetwork() = runTest {
        val failure = runCatching { networks.requestSecureWifi("Mini-field", "") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun savedProfileRecoverySelectsOnlyItsExactWifiName() {
        addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "Other Wi-Fi")
        assertNull(networks.findWifi("Mini-field"))
        val saved = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "Mini-field")
        assertEquals(saved, networks.findWifi("Mini-field")?.network)
        assertNull(networks.findAvailable())
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
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply {
            dhcpServerAddress = ip("192.168.1.1")
        })
        request.await()
        callback.onLost(wifi)
        assertNull(networks.findAvailable())
    }

    @Test fun unrelatedEthernetLeavesStarlinkWifiAsTheNextProbeCandidate() {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        assertEquals(listOf(ethernet, wifi), networks.findCandidates().map { it.network })
    }

    @Test fun zeroOrInvalidSubnetPrefixesAreNotRouterEvidence() {
        val address = ipv4Bytes("192.168.1.42")!!
        val gateway = ipv4Bytes("192.168.1.1")!!
        listOf(-1, 0, 33, 64).forEach { prefix -> assertFalse(sameSubnet(address, gateway, prefix)) }
        assertTrue(sameSubnet(address, gateway, 24))
        assertFalse(sameSubnet(address, gateway, 32))
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        shadow.setLinkProperties(ethernet, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("192.168.1.42", 0)))
        })
        assertNull(networks.findAvailable())
    }

    @Test fun requestedWifiIgnoresInitialLinkPropertiesUntilGatewayArrives() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val callback = shadow.networkCallbacks.single()
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(wifi)
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply {
            setLinkAddresses(listOf(linkAddress("192.168.4.42")))
        })
        runCurrent()
        assertFalse(request.isCompleted)
        callback.onLinkPropertiesChanged(wifi, LinkProperties().apply {
            interfaceName = "eth0"
            addRoute(defaultRoute("192.168.4.1"))
        })
        assertEquals("192.168.4.1", request.await()?.routerAddress)
    }

    @Test fun requestedWifiDhcpWaitIsBoundedAfterAndroidReportsAvailable() = runTest {
        val request = async(start = CoroutineStart.UNDISPATCHED) { networks.requestStarlink() }
        val callback = shadow.networkCallbacks.single()
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
        callback.onAvailable(wifi)
        callback.onLinkPropertiesChanged(wifi, LinkProperties())
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(request.await())
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun savedNameWithoutDhcpGatewayDoesNotProbeFixedAddresses() = runTest {
        addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "Mini-field")
        var probed = false
        val candidate = networks.findVerifiedSavedWifi("Mini-field") { probed = true; DeviceInfo(id = "Router-123") }
        assertNull(candidate)
        assertFalse(probed)
    }

    @Test fun savedNameRequiresStarlinkIdentityAtTheDiscoveredGateway() = runTest {
        val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "Mini-field")
        shadow.setLinkProperties(wifi, LinkProperties().apply {
            interfaceName = "eth0"
            addRoute(defaultRoute("192.168.7.1"))
        })
        val probed = mutableListOf<String>()
        assertNull(networks.findVerifiedSavedWifi("Mini-field") {
            probed += it.routerAddress
            DeviceInfo(id = "Other router")
        })
        val verified = networks.findVerifiedSavedWifi("Mini-field") {
            probed += it.routerAddress
            DeviceInfo(id = "Router-123")
        }
        assertEquals(StarlinkConnection(wifi, "192.168.7.1"), verified)
        assertEquals(listOf("192.168.7.1", "192.168.7.1"), probed)
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
