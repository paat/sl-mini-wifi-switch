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
    // Create after each coroutine test installs Main, so viewModelScope captures that dispatcher.
    private val model by lazy {
        SetupViewModel(application).also {
            store.put("setup", it)
            // Never open a socket if a test forgets its fixture.
            it.transportFactory = { _, _ -> error("Missing fake transport") }
        }
    }
    private lateinit var store: ViewModelStore
    private val opened = mutableListOf<StarlinkConnection>()

    @Before fun setUp() {
        connectivity.clearAllNetworks()
        store = ViewModelStore()
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

                model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
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

                model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
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
                // Each loop is a separate connection session; stopping no longer releases it.
                model.releaseConnection()
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumeDuringReconnectProbeKeepsTheWifiFallback() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        try {
            for (screen in listOf(SetupViewModel.Screen.WifiOn, SetupViewModel.Screen.WifiOnConfirm,
                SetupViewModel.Screen.WifiOff)) {
                addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
                val probe = kotlinx.coroutines.CompletableDeferred<Unit>()
                model.screen = screen
                model.transportFactory = { _, _ -> object : Transport {
                    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                        probe.await()
                        return ReadOnlyTransport(unreachable = true).post(url, headers, body)
                    }
                } }
                model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
                testScheduler.runCurrent()
                val reconnect = model.connecting
                assertTrue(reconnect!!.isActive)
                model.stop()
                model.start()
                testScheduler.runCurrent()
                assertSame("Resume must retain the user's Reconnect on $screen", reconnect, model.connecting)
                probe.complete(Unit)
                testScheduler.runCurrent()
                assertEquals("Reconnect must reach Android's Connect dialog on $screen", 1, connectivity.networkCallbacks.size)
                assertTrue(model.requestingWifi)
                val request = connectivity.networkCallbacks.single()
                manager.unregisterNetworkCallback(request)
                request.onUnavailable()
                testScheduler.runCurrent()
                assertFalse(model.requestingWifi)
                org.junit.Assert.assertNull(model.writing)
                model.stop()
                model.releaseConnection()
            }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun successfulCableReadClearsFailedReconnectFeedback() = cableRecovery(denied = false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun successfulCableReconnectClearsDeniedWifiFeedback() = cableRecovery(denied = true)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun cableRecovery(denied: Boolean) = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            for (screen in listOf(SetupViewModel.Screen.WifiOff, SetupViewModel.Screen.WifiOnConfirm)) {
                connectivity.clearAllNetworks()
                model.screen = screen
                model.confirmedConnection = null
                model.confirmationIsWifi = true
                shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
                testScheduler.runCurrent()
                val request = connectivity.networkCallbacks.single()
                manager.unregisterNetworkCallback(request)
                request.onUnavailable()
                testScheduler.runCurrent()
                assertEquals(R.string.connection_failed, model.message)
                if (denied) {
                    shadowOf(application).denyPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
                    testScheduler.runCurrent()
                    model.permissionRequest = null
                    model.permissionResult(mapOf(android.Manifest.permission.NEARBY_WIFI_DEVICES to false))
                    assertTrue(model.permissionDenied)
                    assertEquals(R.string.permission_needed, model.message)
                }
                val cable = addNetwork(2, NetworkCapabilities.TRANSPORT_ETHERNET)
                model.transportFactory = { _, _ -> ReadOnlyTransport() }
                // Both lifecycle re-reads and an explicit Reconnect must retire old failures.
                if (denied) model.requestAccess(SetupViewModel.PermissionAction.Reconnect) else model.start()
                testScheduler.runCurrent()
                assertEquals(cable, model.confirmedConnection?.network)
                assertTrue(model.canConfirm)
                org.junit.Assert.assertNull(model.message)
                assertFalse(model.permissionDenied)
                assertEquals(screen, model.screen)
                org.junit.Assert.assertNull(model.writing)
                model.stop()
                model.releaseConnection()
            }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun resumeKeepsSecureWifiPermissionDenialOnRetainedRequestedNetwork() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            model.transportFactory = { _, _ -> ReadOnlyTransport() }
            model.requestAccess(SetupViewModel.PermissionAction.Connect)
            testScheduler.runCurrent()
            val request = connectivity.networkCallbacks.single()
            // A redacted SSID is still discoverable through the retained app-owned request.
            val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
            val properties = manager.getLinkProperties(wifi)!!.apply {
                dhcpServerAddress = InetAddress.getByName("192.168.1.1") as java.net.Inet4Address
            }
            connectivity.setLinkProperties(wifi, properties)
            request.onAvailable(wifi)
            request.onLinkPropertiesChanged(wifi, properties)
            testScheduler.runCurrent()
            model.screen = SetupViewModel.Screen.WifiOnConfirm
            model.profile = Profile("TypedName", "TypedPassword77")
            model.start()
            testScheduler.runCurrent()
            assertTrue(model.canConfirm)
            shadowOf(application).denyPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            model.confirmWifiOn()
            assertEquals(listOf(android.Manifest.permission.NEARBY_WIFI_DEVICES), model.permissionRequest)
            model.permissionRequest = null
            model.permissionResult(mapOf(android.Manifest.permission.NEARBY_WIFI_DEVICES to false))
            model.stop()
            model.start()
            testScheduler.runCurrent()
            assertTrue(model.permissionDenied)
            org.junit.Assert.assertNull(model.message)
            assertEquals(wifi, model.confirmedConnection?.network)
            org.junit.Assert.assertNull(model.writing)
            shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            model.stop()
            model.start()
            testScheduler.runCurrent()
            assertFalse(model.permissionDenied)
            org.junit.Assert.assertNull(model.message)
            org.junit.Assert.assertNull(model.writing)
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun immediateReconnectWithNoCandidatesReleasesCallbackWhenLeavingFlow() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        try {
            for (leave in listOf<() -> Unit>(model::returnHome, model::openGuide, model::openHelp)) {
                model.screen = SetupViewModel.Screen.WifiOff
                model.requestAccess(SetupViewModel.PermissionAction.Reconnect)
                assertEquals(1, connectivity.networkCallbacks.size)
                leave()
                assertTrue("Leaving must cancel the tracked Wi-Fi request", connectivity.networkCallbacks.isEmpty())
                assertFalse(model.requestingWifi)
                org.junit.Assert.assertNull(model.writing)
            }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun homeCableDetectionRetiresFailedJoinBeforeOpeningWifiOff() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        try {
            model.start()
            testScheduler.runCurrent()
            assertEquals(MiniState.NotFound, model.snapshot.state)
            model.requestAccess(SetupViewModel.PermissionAction.Connect)
            testScheduler.runCurrent()
            val request = connectivity.networkCallbacks.single()
            manager.unregisterNetworkCallback(request)
            request.onUnavailable()
            testScheduler.runCurrent()
            assertEquals(R.string.connection_failed, model.message)
            addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
            model.transportFactory = { _, _ -> ReadOnlyTransport() }
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            org.junit.Assert.assertNull(model.message)
            model.openWifiOff()
            assertTrue(model.canConfirm)
            org.junit.Assert.assertNull(model.message)
        } finally { model.stop(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun screenChangesRetireConnectionFeedbackAndOnlyClearResolvedDenial() {
        model.message = R.string.connection_failed
        model.openGuide()
        org.junit.Assert.assertNull(model.message)
        model.pendingPermissionAction = SetupViewModel.PermissionAction.Connect
        model.permissionDenied = true
        shadowOf(application).denyPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        model.message = R.string.permission_needed
        model.openHelp()
        org.junit.Assert.assertNull(model.message)
        assertTrue(model.permissionDenied)
        shadowOf(application).grantPermissions(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        model.closeHelp()
        assertFalse(model.permissionDenied)
    }

    @Test fun localNetworkDenialOnResumeOffersSettingsOnEverySetupScreen() {
        val sdk = android.os.Build.VERSION.SDK_INT
        try {
            // Exercise the API 37 permission policy using the installed Robolectric framework.
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 37)
            shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            for (screen in listOf(SetupViewModel.Screen.WifiOn, SetupViewModel.Screen.WifiOnConfirm,
                SetupViewModel.Screen.WifiOff)) {
                model.screen = screen
                model.start()
                assertTrue("Settings must be available on $screen", model.permissionDenied)
                org.junit.Assert.assertNotNull(model.message)
                org.junit.Assert.assertNull(model.writing)
            }
        } finally {
            model.stop()
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun bothConfirmationsRequestMissingLocalAccessBeforeWriting() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val sdk = android.os.Build.VERSION.SDK_INT
        try {
            val cable = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", 37)
            shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            for (screen in listOf(SetupViewModel.Screen.WifiOff, SetupViewModel.Screen.WifiOnConfirm)) {
                model.screen = screen
                model.profile = Profile("FieldName", "FieldPassword77")
                model.confirmedConnection = StarlinkConnection(cable, "192.168.1.1")
                model.snapshot = eu.tsernikov.slminiwifi.state.MiniSnapshot(MiniState.SetupPending)
                if (screen == SetupViewModel.Screen.WifiOff) model.confirmWifiOff() else model.confirmWifiOn()
                assertEquals(listOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK), model.permissionRequest)
                assertEquals(screen, model.screen)
                org.junit.Assert.assertNull(model.writing)
                model.permissionRequest = null
                model.permissionResult(mapOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK to false))
                assertTrue(model.permissionDenied)
                org.junit.Assert.assertNull(model.writing)
                // Granting a retry continues the same confirmed choice, including Wi-Fi off.
                if (screen == SetupViewModel.Screen.WifiOff) model.confirmWifiOff() else model.confirmWifiOn()
                shadowOf(application).grantPermissions(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
                model.permissionRequest = null
                model.permissionResult(mapOf(android.Manifest.permission.ACCESS_LOCAL_NETWORK to true))
                assertEquals(if (screen == SetupViewModel.Screen.WifiOff) SetupViewModel.Screen.WifiOffProgress
                    else SetupViewModel.Screen.WifiOnProgress, model.screen)
                assertFalse(model.permissionDenied)
                model.writing!!.cancel()
                model.writing = null
                shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        } finally {
            store.clear()
            ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "SDK_INT", sdk)
            kotlinx.coroutines.Dispatchers.resetMain()
        }
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

    @Test fun unreadableRouterRemainsUnknownWhenLaterNetworkIsSilent() = runTest {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        model.transportFactory = { connection, _ ->
            opened += connection
            ReadOnlyTransport(unreachable = connection.network == wifi, configUnreadable = true)
        }

        val (_, snapshot) = model.detectAvailable()

        assertEquals(listOf(ethernet, wifi), opened.map { it.network })
        assertEquals(MiniState.Unknown, snapshot.state)
        assertEquals("Router-test", snapshot.details.routerInfo?.id)
        org.junit.Assert.assertNull("An answered candidate must not start a restart wait", model.detector.rebootTimeRemainingMillis)
    }

    @Test fun readableSetupOnLaterNetworkWinsOverEarlierUnknown() = runTest {
        val ethernet = addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        val wifi = addNetwork(2, NetworkCapabilities.TRANSPORT_WIFI, "STARLINK")
        model.transportFactory = { connection, _ ->
            opened += connection
            ReadOnlyTransport(configUnreadable = connection.network == ethernet)
        }

        val (selected, snapshot) = model.detectAvailable()

        assertEquals(listOf(ethernet, wifi), opened.map { it.network })
        assertEquals(wifi, selected?.network)
        assertEquals(MiniState.SetupPending, snapshot.state)
    }

    @Test fun soleUnreadableRouterKeepsUnknownInsteadOfConnectionGuidance() = runTest {
        addNetwork(1, NetworkCapabilities.TRANSPORT_ETHERNET)
        model.transportFactory = { _, _ -> ReadOnlyTransport(configUnreadable = true) }

        val (_, snapshot) = model.detectAvailable()

        assertEquals(MiniState.Unknown, snapshot.state)
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
        private val configUnreadable: Boolean = false,
    ) : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            // The test must reject a setup request, even if the production path regresses.
            check(listOf(Requests.wifiGetConfig(), Requests.getDeviceInfo(), Requests.getStatus())
                .any { it.contentEquals(body) }) { "Unexpected write in read-only operation" }
            if (unreachable) throw IOException("Unrelated Ethernet network")
            if (configUnreadable && body.contentEquals(Requests.wifiGetConfig())) {
                return HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
            }
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
