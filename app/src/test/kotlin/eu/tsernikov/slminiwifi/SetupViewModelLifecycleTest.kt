package eu.tsernikov.slminiwifi

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import androidx.lifecycle.ViewModelStore
import eu.tsernikov.slminiwifi.SetupViewModel.Screen
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.ProfileBlob
import eu.tsernikov.slminiwifi.data.ProfileCipher
import eu.tsernikov.slminiwifi.data.ProfileStore
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.Wire
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import java.io.IOException
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class SetupViewModelLifecycleTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val manager get() = application.getSystemService(ConnectivityManager::class.java)

    @org.junit.Before fun disableAnimations() {
        android.provider.Settings.Global.putFloat(application.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun rotationRetainsBothConfirmationScreensAndUnsavedCredentialsWithoutAWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (screen in listOf(Screen.WifiOff, Screen.WifiOnConfirm)) {
                shadowOf(manager).clearAllNetworks()
                val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
                try {
                    val model = controller.get().model
                    model.screen = screen
                    model.profile = Profile("RetainedName", "RetainedPassword77")
                    model.transportFactory = { _, _ -> error("Confirmation must not send a write") }
                    controller.recreate()
                    assertSame(model, controller.get().model)
                    assertEquals(screen, model.screen)
                    assertEquals("RetainedPassword77", model.profile.password)
                    assertNull(model.writing)
                    assertFalse(model.canConfirm)
                } finally { controller.pause().stop().destroy() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun rotationAndBackgroundKeepAnInFlightWriteAndVerificationAliveWithoutResending() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val model = controller.get().model
            val connection = ethernet()
            val releaseWrite = CompletableDeferred<Unit>()
            var writes = 0
            model.transportFactory = { _, onWritten -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    if (body.contentEquals(Requests.setupBypass())) {
                        writes++
                        onWritten()
                        releaseWrite.await()
                        return response(3003)
                    }
                    if (url.contains("192.168.100.1")) {
                        return response(1004, Wire.Writer().bytes(1,
                            Wire.Writer().string(2, "mini1_prod1").toByteArray()).toByteArray())
                    }
                    throw IOException("Router stopped answering")
                }
            } }
            model.polling?.cancel()
            model.snapshot = MiniSnapshot(MiniState.SetupPending)
            model.confirmedConnection = connection
            model.screen = Screen.WifiOff
            model.checking = false
            model.confirmWifiOff()
            runCurrent()
            assertEquals(1, writes)
            assertEquals(Screen.WifiOffProgress, model.screen)
            controller.recreate()
            assertSame(model, controller.get().model)
            assertTrue(model.writing!!.isActive)
            controller.pause().stop()
            assertTrue(model.writing!!.isActive)
            releaseWrite.complete(Unit)
            runCurrent()
            assertEquals(Screen.WifiOffResult, model.screen)
            assertFalse(model.likelySuccess)
            assertEquals(1, writes)
            controller.start().resume()
        } finally {
            controller.pause().stop().destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun quickLaunchDuringSecurePermissionPromptStillSendsExactlyOnceAfterGrant() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val model = controller.get().model
            model.confirmedConnection = ethernet()
            model.confirmationIsWifi = true
            model.snapshot = MiniSnapshot(MiniState.SetupPending)
            model.screen = Screen.WifiOnConfirm
            model.profile = Profile("SavedName", "SavedPassword77")
            model.profiles = memoryStore(model.profile)
            val writeStarted = CompletableDeferred<Unit>()
            var writes = 0
            model.transportFactory = { _, _ -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    assertArrayEquals(Requests.setupWifi(model.profile.name, model.profile.password), body)
                    writes++
                    writeStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            } }
            shadowOf(application).denyPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            model.confirmWifiOn()
            assertTrue(model.awaitingPermissionResult)
            assertNotNull(model.permissionRequest)
            model.permissionRequest = null // Dialog now owns the request.
            controller.newIntent(SetupLaunch.intent(controller.get()))
            assertEquals(Screen.WifiOnConfirm, model.screen)
            assertNotNull(model.confirmedConnection)
            assertNull(model.writing)
            shadowOf(application).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            model.permissionResult(mapOf(Manifest.permission.NEARBY_WIFI_DEVICES to true))
            writeStarted.await()
            assertFalse(model.awaitingPermissionResult)
            assertEquals(Screen.WifiOnProgress, model.screen)
            controller.newIntent(SetupLaunch.intent(controller.get()))
            model.confirmWifiOn()
            assertEquals(1, writes)
            assertEquals(Screen.WifiOnProgress, model.screen)
            model.writing!!.cancel()
            model.writing!!.join()
        } finally { controller.destroy(); Dispatchers.resetMain() }
    }

    @Test fun returningToConfirmationRequiresFreshReadBeforeTheTapCanWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = SetupViewModel(application)
        val store = ViewModelStore().apply { put("setup", model) }
        try {
            val connection = ethernet()
            val allowRead = CompletableDeferred<Unit>()
            var reads = 0
            model.transportFactory = { _, _ -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    assertFalse(body.contentEquals(Requests.setupBypass()))
                    reads++
                    allowRead.await()
                    return when {
                        body.contentEquals(Requests.wifiGetConfig()) -> response(3009, Wire.Writer().bytes(1, byteArrayOf()).toByteArray())
                        body.contentEquals(Requests.getStatus()) -> response(3004)
                        else -> response(1004)
                    }
                }
            } }
            model.screen = Screen.WifiOff
            model.snapshot = MiniSnapshot(MiniState.SetupPending)
            model.confirmedConnection = connection
            model.stop()
            assertFalse(model.canConfirm)
            model.start()
            assertFalse(model.canConfirm)
            model.confirmWifiOff()
            assertNull(model.writing)
            runCurrent()
            assertTrue(reads > 0)
            assertFalse(model.canConfirm)
            allowRead.complete(Unit)
            runCurrent()
            assertEquals(Screen.WifiOff, model.screen)
            assertTrue(model.canConfirm)
            assertNull(model.writing)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun reconnectAfterBackgroundedErrorAndDeclinedDialogOnlyRequestsWifiAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = SetupViewModel(application)
        val store = ViewModelStore().apply { put("setup", model) }
        try {
            shadowOf(manager).clearAllNetworks()
            val original = StarlinkConnection(ShadowNetwork.newInstance(90), "192.168.1.1")
            model.confirmedConnection = original
            model.confirmationIsWifi = true
            model.savedProfile = Profile("SavedName", "SavedPassword77")
            model.profileSaved = true
            model.secureFlow = true
            model.reconnectAvailable = true
            model.screen = Screen.WifiOffError
            model.transportFactory = { _, _ -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse =
                    error("No HTTP request can be sent before Connect is accepted")
            } }
            model.stop()
            model.start()
            assertSame(original, model.confirmedConnection)
            model.reconnectForVerification()
            runCurrent()
            val callback = shadowOf(manager).networkCallbacks.single()
            assertEquals(Screen.WifiOnProgress, model.screen)
            manager.unregisterNetworkCallback(callback)
            callback.onUnavailable()
            runCurrent()
            assertTrue(model.reconnectAvailable)
            model.reconnectForVerification()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(1, shadowOf(manager).networkCallbacks.size)
            assertNotSame(callback, shadowOf(manager).networkCallbacks.single())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun nearbyPermissionGrantedInSettingsClearsTheCorrectDeniedBanner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = SetupViewModel(application)
        val store = ViewModelStore().apply { put("setup", model) }
        try {
            model.screen = Screen.ResetGuide // no network work required for permission reconciliation
            model.pendingPermissionAction = SetupViewModel.PermissionAction.Connect
            model.permissionDenied = true
            model.message = R.string.permission_needed
            shadowOf(application).grantPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
            shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            model.start()
            assertFalse(model.permissionDenied)
            assertNull(model.message)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun savedPasswordCanBeReopenedAfterDoneAndAfterFreshLaunchWithoutClaimingVerification() {
        val profile = Profile("SavedName", "SavedPassword77")
        val model = SetupViewModel(application)
        model.profiles = memoryStore(profile)
        model.restore(null)
        repeat(2) {
            model.showSavedPassword()
            assertEquals(Screen.WifiOnResult, model.screen)
            assertEquals(profile.name, model.profile.name)
            assertEquals(profile.password, model.profile.password)
            assertFalse(model.wifiVerified)
            model.returnHome()
            assertEquals(Screen.Home, model.screen)
        }
        model.networks.close()
    }

    @Test fun profileEncryptionAndPersistenceRunOffTheCallingThread() = runTest {
        val model = SetupViewModel(application)
        val caller = Thread.currentThread()
        var encryptionThread: Thread? = null
        var writeThread: Thread? = null
        model.profiles = ProfileStore(object : ProfileCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                encryptionThread = Thread.currentThread()
                return byteArrayOf(1)
            }
            override fun decrypt(ciphertext: ByteArray): ByteArray = error("unused")
        }, object : ProfileBlob {
            override fun read(): ByteArray? = null
            override fun write(ciphertext: ByteArray) { writeThread = Thread.currentThread() }
        })
        assertTrue(model.saveProfile(Profile("SavedName", "SavedPassword77")))
        assertNotSame(caller, encryptionThread)
        assertNotSame(caller, writeThread)
        model.networks.close()
    }

    @Test fun processDeathOnWifiOffConfirmationNeverReportsAnInterruptedWrite() {
        val model = SetupViewModel(application)
        model.restore(Bundle().apply { putString("screen", Screen.WifiOff.name) })
        assertEquals(Screen.WifiOff, model.screen)
        assertNull(model.writing)
        assertFalse(model.canConfirm)
        model.networks.close()
    }

    private fun ethernet(): StarlinkConnection {
        shadowOf(manager).clearAllNetworks()
        val network = ShadowNetwork.newInstance(71)
        shadowOf(manager).addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, NetworkCapabilities.TRANSPORT_ETHERNET, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        shadowOf(manager).setLinkProperties(network, LinkProperties().apply {
            interfaceName = "eth0"
            setLinkAddresses(listOf(org.robolectric.util.ReflectionHelpers.callConstructor(LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(InetAddress::class.java, InetAddress.getByAddress(byteArrayOf(100, 70, 1, 2))),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 10))))
        })
        return StarlinkConnection(network, "192.168.1.1")
    }

    private fun memoryStore(profile: Profile): ProfileStore {
        var bytes: ByteArray? = null
        return ProfileStore(object : ProfileCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.copyOf()
            override fun decrypt(ciphertext: ByteArray) = ciphertext.copyOf()
        }, object : ProfileBlob {
            override fun read() = bytes
            override fun write(ciphertext: ByteArray) { bytes = ciphertext }
        }).also { it.save(profile) }
    }

    private fun response(field: Int, value: ByteArray = byteArrayOf()) = HttpResponse(
        200, mapOf("grpc-status" to "0"),
        Frames.request(Wire.Writer().varint(1, 1).bytes(field, value).toByteArray()))
}
