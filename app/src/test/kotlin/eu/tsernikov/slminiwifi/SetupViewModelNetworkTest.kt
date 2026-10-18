package eu.tsernikov.slminiwifi

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.lifecycle.ViewModelStore
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.protocol.WifiGetStatusResponse
import eu.tsernikov.slminiwifi.protocol.Wire
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniState
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

/** Real Android network selection and protocol parsing, with all HTTP replaced by local fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Suppress("DEPRECATION")
class SetupViewModelNetworkTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val manager get() = application.getSystemService(ConnectivityManager::class.java)
    private val connectivity get() = shadowOf(manager)
    private lateinit var model: SetupViewModel
    private lateinit var store: ViewModelStore
    private val opened = mutableListOf<StarlinkConnection>()

    @Before fun setUp() {
        connectivity.clearAllNetworks()
        model = SetupViewModel(application)
        store = ViewModelStore().apply { put("setup", model) }
        // Fail loudly if a test forgets to install its fixture; never open a socket.
        model.transportFactory = { _, _ -> error("Missing fake transport") }
    }

    @After fun tearDown() = store.clear()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumeKeepsTypedFormAndRequestsWifiOnlyAfterReconnectTap() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        val credentials = Profile("TypedName", "TypedPassword77")
        try {
            for (screen in listOf(SetupViewModel.Screen.WifiOn, SetupViewModel.Screen.WifiOnConfirm,
                SetupViewModel.Screen.WifiOff)) {
                val original = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
                model.screen = screen
                model.profile = credentials
                model.ownPassword = true
                model.confirmedConnection = StarlinkConnection(original, "192.168.1.1")
                model.confirmationIsWifi = true
                model.snapshot = eu.tsernikov.slminiwifi.state.MiniSnapshot(MiniState.SetupPending)
                model.transportFactory = { _, _ -> ReadOnlyTransport() }
                model.stop()
                connectivity.clearAllNetworks()
                model.start()
                testScheduler.runCurrent()
                assertEquals(screen, model.screen)
                assertSame(credentials, model.profile)
                assertTrue(model.ownPassword)
                assertFalse(model.canConfirm)
                assertTrue(connectivity.networkCallbacks.isEmpty())

                model.requestAccess(SetupViewModel.PermissionAction.Connect)
                testScheduler.runCurrent()
                val declined = connectivity.networkCallbacks.single()
                // onStart during Android's dialog must not cancel or repeat the requested connection.
                model.start()
                testScheduler.runCurrent()
                assertSame(declined, connectivity.networkCallbacks.single())
                manager.unregisterNetworkCallback(declined)
                declined.onUnavailable()
                testScheduler.runCurrent()
                assertEquals(screen, model.screen)
                assertSame(credentials, model.profile)
                assertFalse(model.canConfirm)
                assertFalse(model.checking)
                assertFalse(model.requestingWifi)
                assertTrue(connectivity.networkCallbacks.isEmpty())

                model.requestAccess(SetupViewModel.PermissionAction.Connect)
                testScheduler.runCurrent()
                val accepted = connectivity.networkCallbacks.single()
                val replacement = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
                val properties = manager.getLinkProperties(replacement)!!.apply {
                    dhcpServerAddress = InetAddress.getByName("192.168.1.1") as java.net.Inet4Address
                }
                connectivity.setLinkProperties(replacement, properties)
                accepted.onAvailable(replacement)
                accepted.onLinkPropertiesChanged(replacement, properties)
                testScheduler.runCurrent()
                assertEquals(screen, model.screen)
                assertSame(credentials, model.profile)
                assertTrue(model.canConfirm)
                assertEquals(replacement, model.confirmedConnection?.network)
                org.junit.Assert.assertNull(model.writing)
                model.stop()
            }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun failedResumeProbeKeepsTypedFormAndConfirmationDisabled() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
            for (screen in listOf(SetupViewModel.Screen.WifiOn, SetupViewModel.Screen.WifiOnConfirm)) {
                model.screen = screen
                val credentials = Profile("TypedName", "TypedPassword77")
                model.profile = credentials
                model.transportFactory = { _, _ -> throw IllegalStateException("Probe unavailable") }
                model.start()
                testScheduler.runCurrent()
                assertEquals(screen, model.screen)
                assertSame(credentials, model.profile)
                assertFalse(model.canConfirm)
                assertFalse(model.checking)
                assertTrue(connectivity.networkCallbacks.isEmpty())
                model.stop()
            }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun failedEthernetProbesFallThroughToAvailableStarlinkWifi() = runTest {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        model.transportFactory = { connection, _ ->
            opened += connection
            ReadOnlyTransport(unreachable = connection.network == ethernet)
        }

        val (selected, snapshot) = model.detectAvailable()

        assertEquals(listOf(ethernet, wifi), opened.map { it.network })
        assertEquals(wifi, selected?.network)
        assertEquals(MiniState.SetupPending, snapshot.state)
        assertEquals("Router-test", snapshot.details.routerInfo?.id)
    }

    @Test fun replacementStarlinkHandlePreventsWifiLossEvidence() = runTest {
        val original = StarlinkConnection(addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK"), "192.168.1.1")
        model.confirmationIsWifi = true
        connectivity.clearAllNetworks()
        val replacement = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        model.transportFactory = { connection, _ ->
            opened += connection
            ReadOnlyTransport()
        }

        val observation = model.observeWifiOff(original)

        assertFalse(model.networks.isPresent(original))
        assertTrue(observation.originalWifiPresent)
        assertTrue(observation.routerAnswered)
        assertEquals(listOf(replacement), opened.map { it.network })
    }

    @Test fun wifiOffVerificationRefreshesDishDetailsAndKeepsLastRouterSnapshot() = runTest {
        val original = StarlinkConnection(addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET), "192.168.1.1")
        val previous = MiniDetails(
            routerInfo = DeviceInfo(id = "Router-before", softwareVersion = "router-before"),
            dishInfo = DeviceInfo(id = "ut-test", softwareVersion = "dish-before"),
            config = WifiConfig(setupComplete = false),
            status = WifiGetStatusResponse(ipv4WanAddress = "100.64.1.2"),
            routerAddress = original.routerAddress,
        )
        model.lastDetails = previous
        model.transportFactory = { _, _ -> ReadOnlyTransport(routerSilent = true) }

        val observation = model.observeWifiOff(original)

        assertFalse(observation.routerAnswered)
        assertTrue(observation.dishReachable)
        assertEquals("dish-after", model.lastDetails.dishInfo?.softwareVersion)
        assertSame(previous.routerInfo, model.lastDetails.routerInfo)
        assertSame(previous.config, model.lastDetails.config)
        assertSame(previous.status, model.lastDetails.status)
        assertEquals(original.routerAddress, model.lastDetails.routerAddress)
    }

    @Test fun secureWifiVerificationRefreshesDetailsWithReadBackConfig() = runTest {
        val original = StarlinkConnection(addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET), "192.168.1.1")
        val previousRouter = DeviceInfo(id = "Router-before")
        val previousDish = DeviceInfo(id = "ut-before")
        model.lastDetails = MiniDetails(routerInfo = previousRouter, dishInfo = previousDish, config = WifiConfig())
        model.transportFactory = { _, _ -> ReadOnlyTransport(secured = true) }

        val result = model.observeWifiOn(original, Profile("Mini-field", "TestPassword23"))

        assertTrue(result is ClientResult.Ok)
        val config = model.lastDetails.config!!
        assertTrue(config.setupComplete)
        val bss = config.networks.single().basicServiceSets.single()
        assertEquals("Mini-field", bss.ssid)
        assertEquals(AuthKind.WPA2_WPA3, bss.authKind)
        assertSame(previousRouter, model.lastDetails.routerInfo)
        assertSame(previousDish, model.lastDetails.dishInfo)
        assertEquals(original.routerAddress, model.lastDetails.routerAddress)
    }

    private class ReadOnlyTransport(
        private val unreachable: Boolean = false,
        private val routerSilent: Boolean = false,
        private val secured: Boolean = false,
    ) : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            // The test must reject a setup request, even if the production path regresses.
            check(listOf(Requests.wifiGetConfig(), Requests.getDeviceInfo(), Requests.getStatus())
                .any { it.contentEquals(body) }) { "Unexpected write in read-only operation" }
            if (unreachable) throw IOException("Unrelated Ethernet network")
            val dish = url.startsWith("http://192.168.100.1:9201/")
            if (!dish && routerSilent) throw IOException("Router stopped answering")
            return when {
                body.contentEquals(Requests.wifiGetConfig()) -> {
                    val config = Wire.Writer().varint(7, if (secured) 1 else 0).apply {
                        if (secured) bytes(1100, Wire.Writer().bytes(1007,
                            Wire.Writer().string(1005, "Mini-field").bytes(2004, byteArrayOf()).toByteArray(),
                        ).toByteArray())
                    }.toByteArray()
                    response(3009, Wire.Writer().bytes(1, config).toByteArray())
                }
                body.contentEquals(Requests.getDeviceInfo()) -> response(1004,
                    Wire.Writer().bytes(1, Wire.Writer()
                        .string(1, if (dish) "ut-test" else "Router-test")
                        .string(3, if (dish) "dish-after" else "router-after")
                        .toByteArray()).toByteArray(),
                )
                else -> response(3004)
            }
        }
    }

    private fun addNetwork(id: Int, transport: Int, ssid: String? = null): Network {
        val network = ShadowNetwork.newInstance(id)
        connectivity.addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, transport, 0, true, true,
        ))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        if (ssid != null) {
            val info = ShadowWifiInfo.newInstance()
            shadowOf(info).setSSID(ssid)
            shadowOf(capabilities).setTransportInfo(info)
        }
        connectivity.setNetworkCapabilities(network, capabilities)
        connectivity.setLinkProperties(network, LinkProperties().apply {
            interfaceName = if (transport == NetworkCapabilities.TRANSPORT_ETHERNET) "eth0" else "wlan0"
            setLinkAddresses(listOf(ReflectionHelpers.callConstructor(
                LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 42))),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 24),
            )))
        })
        return network
    }

    companion object {
        private fun response(field: Int, value: ByteArray = byteArrayOf()) = HttpResponse(
            200, mapOf("grpc-status" to "0"),
            Frames.request(Wire.Writer().varint(1, 1).bytes(field, value).toByteArray()),
        )
    }
}
