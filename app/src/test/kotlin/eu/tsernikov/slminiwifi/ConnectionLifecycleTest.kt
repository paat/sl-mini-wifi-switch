package eu.tsernikov.slminiwifi

import android.Manifest
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import eu.tsernikov.slminiwifi.SetupViewModel.Screen
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.Wire
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.ui.GuideStep
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Real Activity recreation and UI wiring; every device read is an in-memory fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class ConnectionLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val application get() = RuntimeEnvironment.getApplication()
    private val manager get() = application.getSystemService(ConnectivityManager::class.java)
    private val connectivity get() = shadowOf(manager)
    private val reads = mutableListOf<Network>()

    @Before fun prepare() {
        connectivity.clearAllNetworks()
        Settings.Global.putFloat(application.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun requestedStarlinkSurvivesHomeRecreationAndReleasesOnFinish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            lateinit var model: SetupViewModel
            lateinit var request: ConnectivityManager.NetworkCallback
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    model = it.model
                    installTransport(model)
                    shadowOf(application).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
                    model.requestAccess(SetupViewModel.PermissionAction.Connect)
                }
                runCurrent()
                request = connectivity.networkCallbacks.single()
                // Deliberately redacted SSID: only the app-owned request identifies this Wi-Fi.
                val wifi = addNetwork(1, NetworkCapabilities.TRANSPORT_WIFI)
                request.onAvailable(wifi)
                request.onLinkPropertiesChanged(wifi, manager.getLinkProperties(wifi)!!)
                runCurrent()
                assertEquals(MiniState.SetupPending, model.snapshot.state)
                compose.waitForIdle()
                scenario.recreate()
                runCurrent()
                scenario.onActivity { assertSame(model, it.model) }
                assertEquals(MiniState.SetupPending, model.snapshot.state)
                assertSame(request, connectivity.networkCallbacks.single())
                assertEquals(wifi, model.detectedConnection?.network)
                assertNull(model.writing)

                scenario.onActivity {
                    model.releaseConnection()
                    model.start()
                }
                runCurrent()
                assertTrue(connectivity.networkCallbacks.isEmpty())
                assertEquals(MiniState.NotFound, model.snapshot.state)
                compose.onNodeWithText(application.getString(R.string.join_starlink))
                    .performScrollTo().assertIsDisplayed().performClick()
                runCurrent()
                assertEquals(1, connectivity.networkCallbacks.size)
            }
            assertTrue("Finishing clears the ViewModel's request", connectivity.networkCallbacks.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun quickLaunchRunsOnceAcrossActivityScenarioRecreate() {
        val launch = SetupLaunch.intent(application)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            lateinit var model: SetupViewModel
            scenario.onActivity {
                model = it.model
                installTransport(model)
                assertEquals(Screen.Home, model.screen)
                model.openGuide()
                model.guideStep = GuideStep.AndroidConnect
            }
            compose.waitForIdle()
            scenario.recreate()
            scenario.onActivity {
                assertSame(model, it.model)
                assertEquals(Screen.FieldGuide, model.screen)
                assertEquals(GuideStep.AndroidConnect, model.guideStep)
                // A genuinely new quick entry still runs, even with a retained model.
                val latest = SetupLaunch.intent(it)
                ReflectionHelpers.callInstanceMethod<Unit>(it, "onNewIntent",
                    ClassParameter.from(Intent::class.java, latest))
                // Robolectric's relaunch still uses its original constructor intent.
                // Android updates its client record on delivery of the new intent.
                launch.replaceExtras(latest)
                assertEquals(Screen.Home, model.screen)
                model.screen = Screen.WifiOffError
            }
            compose.waitForIdle()
            scenario.recreate()
            scenario.onActivity { assertEquals(Screen.WifiOffError, it.model.screen) }
            assertNull(model.writing)
        }
    }

    @Test fun deniedReconnectShowsMessageAndSettingsOnEverySetupScreen() = deniedReconnect(permanent = false)
    @Test fun permanentlyDeniedReconnectShowsMessageAndSettingsOnEverySetupScreen() = deniedReconnect(permanent = true)

    private fun deniedReconnect(permanent: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (screen in listOf(Screen.WifiOn, Screen.WifiOnConfirm, Screen.WifiOff)) {
                    lateinit var model: SetupViewModel
                    scenario.onActivity {
                        model = it.model
                        installTransport(model)
                        model.screen = screen
                        model.profile = Profile("FieldName", "FieldPassword77")
                        shadowOf(application).denyPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
                        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.NEARBY_WIFI_DEVICES, !permanent)
                    }
                    runCurrent()
                    compose.onNodeWithText(application.getString(R.string.setup_reconnect)).performScrollTo().performClick()
                    runCurrent()
                    scenario.onActivity {
                        model.permissionRequest = null
                        model.permissionResult(mapOf(Manifest.permission.NEARBY_WIFI_DEVICES to false))
                    }
                    compose.onNodeWithText("Allow access in the phone settings, then try again.")
                        .performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("Open phone settings").performScrollTo().performClick()
                    scenario.onActivity {
                        val settings = shadowOf(it).nextStartedActivity
                        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
                        assertEquals("package:${application.packageName}", settings.data.toString())
                        assertEquals(screen, model.screen)
                        assertEquals("FieldPassword77", model.profile.password)
                        assertNull(model.writing)
                    }
                }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun retainedConfirmationPermissionCopyDoesNotNameHiddenReconnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    val model = it.model
                    installTransport(model)
                    model.screen = Screen.WifiOnConfirm
                    model.profile = Profile("FieldName", "FieldPassword77")
                    model.confirmedConnection = eu.tsernikov.slminiwifi.net.StarlinkConnection(
                        addNetwork(9, NetworkCapabilities.TRANSPORT_WIFI), "192.168.1.1")
                    model.snapshot = eu.tsernikov.slminiwifi.state.MiniSnapshot(MiniState.SetupPending)
                    model.confirmationIsWifi = true
                    model.checking = false
                    model.permissionResult(mapOf(Manifest.permission.NEARBY_WIFI_DEVICES to false))
                }
                runCurrent()
                compose.onNodeWithText("Reconnect to the Mini").assertDoesNotExist()
                compose.onNodeWithText("Allow access in the phone settings, then try again.")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Open phone settings").performScrollTo().assertIsDisplayed()
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun confirmationReconnectReadsCableBeforeRequestingWifi() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: SetupViewModel
                scenario.onActivity {
                    model = it.model
                    installTransport(model)
                    model.screen = Screen.WifiOff
                    model.confirmationIsWifi = false
                }
                runCurrent()
                val cable = addNetwork(2, NetworkCapabilities.TRANSPORT_ETHERNET)
                compose.onNodeWithText(application.getString(R.string.setup_reconnect)).performScrollTo().performClick()
                runCurrent()
                assertEquals("Reconnect must read the available cable", listOf(cable), reads)
                assertEquals(cable, model.confirmedConnection?.network)
                assertTrue(model.canConfirm)
                assertNull(model.permissionRequest)
                assertFalse(model.awaitingPermissionResult)
                assertTrue(connectivity.networkCallbacks.isEmpty())
                assertNull(model.writing)
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun installTransport(model: SetupViewModel) {
        model.transportFactory = { connection, _ ->
            reads += connection.network
            object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    val field = when {
                        body.contentEquals(Requests.wifiGetConfig()) -> 3009
                        body.contentEquals(Requests.getDeviceInfo()) -> 1004
                        body.contentEquals(Requests.getStatus()) -> 3004
                        else -> error("Unexpected write")
                    }
                    return HttpResponse(200, mapOf("grpc-status" to "0"), Frames.request(
                        Wire.Writer().varint(1, 1).bytes(field,
                            if (field == 3009) Wire.Writer().bytes(1, byteArrayOf()).toByteArray() else byteArrayOf()
                        ).toByteArray()))
                }
            }
        }
    }

    private fun addNetwork(id: Int, transport: Int): Network {
        val network = ShadowNetwork.newInstance(id)
        connectivity.addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, transport, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        connectivity.setNetworkCapabilities(network, capabilities)
        connectivity.setLinkProperties(network, LinkProperties().apply {
            interfaceName = if (transport == NetworkCapabilities.TRANSPORT_ETHERNET) "eth0" else "wlan0"
            dhcpServerAddress = InetAddress.getByName("192.168.1.1") as Inet4Address
            setLinkAddresses(listOf(ReflectionHelpers.callConstructor(LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName("192.168.1.42")),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 24))))
        })
        return network
    }
}
