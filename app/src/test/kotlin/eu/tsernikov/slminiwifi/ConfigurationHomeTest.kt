package eu.tsernikov.slminiwifi

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.lifecycle.ViewModelStore
import eu.tsernikov.slminiwifi.SetupViewModel.Screen
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.ProfileBlob
import eu.tsernikov.slminiwifi.data.ProfileCipher
import eu.tsernikov.slminiwifi.data.ProfileStore
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.Wire
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.ui.GuideStep
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class ConfigurationHomeTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private var state = MiniState.Configured
    private var reads = 0

    @org.junit.Before fun disableAnimations() {
        android.provider.Settings.Global.putFloat(application.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun resetGuideDetectsConfiguredThenSilenceThenSetupPendingWithoutTap() = resetSequence(true)
    @Test fun homeDetectsConfiguredThenSilenceThenSetupPendingWithoutTap() = resetSequence(false)

    private fun resetSequence(help: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.start()
            runCurrent()
            assertEquals(MiniState.Configured, model.snapshot.state)
            if (help) model.openHelp()
            runCurrent()
            assertTrue(model.polling!!.isActive)
            state = MiniState.Rebooting
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(MiniState.Rebooting, model.snapshot.state)
            assertEquals(if (help) Screen.ResetGuide else Screen.Home, model.screen)
            assertNull(model.message)
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            assertEquals(Screen.Home, model.screen)
            assertTrue(model.polling!!.isActive)
            assertNotNull(model.detectedConnection)
            assertNull(model.writing)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun alreadyPendingResetGuideStaysOpenAcrossSeveralPolls() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.SetupPending
            model.start()
            runCurrent()
            model.openHelp()
            val initialReads = reads
            runCurrent()
            repeat(3) {
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(Screen.ResetGuide, model.screen)
            }
            assertTrue(reads > initialReads)
            model.closeHelp()
            assertEquals(Screen.Home, model.screen)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun saveFailureResetHelpKeepsDraftEvenAcrossAReadinessTransition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.SetupPending
            model.start()
            runCurrent()
            model.openWifiOn()
            val draft = Profile("My field Mini", "MyDraftPassword77")
            model.profile = draft
            model.profiles = ProfileStore(object : ProfileCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray = throw IOException("Storage unavailable")
                override fun decrypt(ciphertext: ByteArray): ByteArray = error("Unused")
            }, object : ProfileBlob {
                override fun read(): ByteArray? = null
                override fun write(ciphertext: ByteArray) = error("Encryption must fail first")
            })
            model.screen = Screen.WifiOnConfirm
            model.confirmWifiOn()
            model.writing!!.join()
            assertEquals(Screen.WifiOn, model.screen)
            assertEquals(R.string.wifi_on_store_before_setup_failed, model.profileError)
            model.openHelp()
            runCurrent()
            repeat(3) {
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(Screen.ResetGuide, model.screen)
            }
            state = MiniState.Rebooting
            advanceTimeBy(3_000)
            runCurrent()
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(Screen.ResetGuide, model.screen)
            model.closeHelp()
            assertEquals(Screen.WifiOn, model.screen)
            assertSame(draft, model.profile)
            assertEquals(R.string.wifi_on_store_before_setup_failed, model.profileError)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun unverifiedResultResetHelpKeepsCredentialsAcrossAReadinessTransition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.start()
            runCurrent()
            val saved = Profile("Saved Mini", "SavedPassword77")
            model.savedProfile = saved
            model.screen = Screen.WifiOffError
            model.showSavedPassword()
            model.openHelp()
            runCurrent()
            state = MiniState.SetupPending
            repeat(3) {
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(Screen.ResetGuide, model.screen)
            }
            model.closeHelp()
            assertEquals(Screen.WifiOnResult, model.screen)
            assertSame(saved, model.profile)
            assertFalse(model.wifiVerified)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun resetGuideFromCompletedSetupCanAdvanceOnReadinessTransition() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            for (origin in listOf(Screen.WifiOffError, Screen.WifiOnResult)) {
                state = MiniState.Configured
                model.returnHome()
                model.start()
                runCurrent()
                model.screen = origin
                model.wifiOffError = eu.tsernikov.slminiwifi.state.WifiOffError.AlreadySetUp
                model.wifiVerified = true
                model.openHelp()
                runCurrent()
                state = MiniState.Rebooting
                advanceTimeBy(3_000)
                runCurrent()
                state = MiniState.SetupPending
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(Screen.Home, model.screen)
                assertNull(model.writing)
            }
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun guideStopStartPausesReadsAndResumesAutomaticDetection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.start()
            runCurrent()
            model.openHelp()
            runCurrent()
            model.stop()
            val stoppedReads = reads
            state = MiniState.SetupPending
            advanceTimeBy(9_000)
            runCurrent()
            assertEquals(stoppedReads, reads)
            assertEquals(Screen.ResetGuide, model.screen)
            model.start()
            runCurrent()
            assertEquals(Screen.Home, model.screen)
            assertEquals(MiniState.SetupPending, model.snapshot.state)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun activityRecreationRetainsResetGuideAndResumesItsPolling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val model = controller.get().model
        configure(model)
        try {
            controller.start().resume()
            runCurrent()
            model.openHelp()
            runCurrent()
            controller.recreate()
            assertSame(model, controller.get().model)
            assertEquals(Screen.ResetGuide, model.screen)
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(Screen.Home, model.screen)
            assertEquals(MiniState.SetupPending, model.snapshot.state)
        } finally { controller.pause().stop().destroy(); Dispatchers.resetMain() }
    }

    @Test fun navigationStopsPollingForEveryOtherScreenAndEveryFieldGuidePage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            for (screen in Screen.entries - setOf(Screen.Home, Screen.ResetGuide)) {
                state = MiniState.Configured
                model.returnHome()
                model.start()
                runCurrent()
                model.screen = screen
                state = MiniState.SetupPending
                val previousReads = reads
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals("No background Home read on $screen", previousReads, reads)
                assertEquals(screen, model.screen)
                assertFalse(model.polling?.isActive == true)
                assertNull(model.writing)
            }
            model.openGuide()
            for (step in GuideStep.entries) {
                model.guideStep = step
                model.stop()
                model.start()
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(Screen.FieldGuide, model.screen)
                assertEquals(step, model.guideStep)
                assertFalse(model.polling?.isActive == true)
            }
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun notFoundStopsPollingUntilExplicitRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.NotFound
            model.start()
            runCurrent()
            assertEquals(MiniState.NotFound, model.snapshot.state)
            assertFalse(model.polling!!.isActive)
            val stoppedReads = reads
            state = MiniState.SetupPending
            advanceTimeBy(6_000)
            runCurrent()
            assertEquals(stoppedReads, reads)
            model.requestAccess(SetupViewModel.PermissionAction.Check)
            runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            assertTrue(model.polling!!.isActive)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun resetGuideTimeoutStopsAtOriginalDeadlineAndCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        org.robolectric.util.ReflectionHelpers.setField(model, "detector",
            eu.tsernikov.slminiwifi.state.MiniStateDetector { testScheduler.currentTime })
        try {
            model.start()
            runCurrent()
            model.openHelp()
            runCurrent()
            state = MiniState.Rebooting
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(MiniState.Rebooting, model.snapshot.state)
            model.stop()
            advanceTimeBy(30_000)
            model.start()
            runCurrent()
            advanceTimeBy(149_999)
            runCurrent()
            assertEquals(MiniState.Rebooting, model.snapshot.state)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(MiniState.NotFound, model.snapshot.state)
            assertEquals(Screen.ResetGuide, model.screen)
            assertFalse(model.polling!!.isActive)
            state = MiniState.SetupPending
            model.checkAfterHelp()
            runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            assertEquals(Screen.Home, model.screen)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun leavingDuringSuspendedProbeCannotAdvanceOrOverwriteConfirmation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            val transport = model.transportFactory
            val resumeRead = kotlinx.coroutines.CompletableDeferred<Unit>()
            model.transportFactory = { connection, written -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    resumeRead.await()
                    return transport(connection, written).post(url, headers, body)
                }
            } }
            state = MiniState.SetupPending
            model.start()
            runCurrent()
            model.screen = Screen.WifiOnConfirm
            resumeRead.complete(Unit)
            runCurrent()
            assertEquals(Screen.WifiOnConfirm, model.screen)
            assertEquals(0, reads)
            assertFalse(model.polling!!.isActive)
            assertNull(model.writing)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun bypassHomeKeepsPolling() = continuousHome(MiniState.Bypass)
    @Test fun unknownHomeKeepsPolling() = continuousHome(MiniState.Unknown)

    private fun continuousHome(initial: MiniState) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = initial
            model.start()
            runCurrent()
            assertEquals(initial, model.snapshot.state)
            val firstReads = reads
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(reads > firstReads)
            assertTrue(model.polling!!.isActive)
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            assertEquals(Screen.Home, model.screen)
            assertNull(model.writing)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    private fun newModel() = SetupViewModel(application).also {
        store.put("setup", it)
        configure(it)
    }

    private fun configure(model: SetupViewModel) {
        val manager = shadowOf(application.getSystemService(ConnectivityManager::class.java))
        manager.clearAllNetworks()
        val network = ShadowNetwork.newInstance(71)
        manager.addNetwork(network, ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, NetworkCapabilities.TRANSPORT_ETHERNET, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        manager.setNetworkCapabilities(network, capabilities)
        manager.setLinkProperties(network, LinkProperties().apply {
            interfaceName = "eth0"
            setLinkAddresses(listOf(org.robolectric.util.ReflectionHelpers.callConstructor(LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.net.InetAddress::class.java,
                    java.net.InetAddress.getByAddress(byteArrayOf(100, 70, 1, 42))),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 24))))
        })
        model.transportFactory = { _, _ -> object : Transport {
            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                check(listOf(Requests.wifiGetConfig(), Requests.getDeviceInfo(), Requests.getStatus())
                    .any { it.contentEquals(body) }) { "Unexpected write" }
                reads++
                if (state == MiniState.Rebooting || state == MiniState.NotFound) throw IOException("Silent Mini")
                if (state == MiniState.Bypass && !url.contains("192.168.100.1")) throw IOException("Silent router")
                if (state == MiniState.Unknown && body.contentEquals(Requests.wifiGetConfig())) {
                    return HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
                }
                val response = when {
                    body.contentEquals(Requests.wifiGetConfig()) -> Wire.Writer().bytes(3009,
                        Wire.Writer().bytes(1, Wire.Writer().varint(7,
                            if (state == MiniState.Configured) 1 else 0).toByteArray()).toByteArray())
                    body.contentEquals(Requests.getStatus()) -> Wire.Writer().bytes(3004, byteArrayOf())
                    else -> Wire.Writer().bytes(1004,
                        Wire.Writer().bytes(1, Wire.Writer().string(1, "ut-test").toByteArray()).toByteArray())
                }
                return HttpResponse(200, mapOf("grpc-status" to "0"),
                    Frames.request(Wire.Writer().varint(1, 1).toByteArray() + response.toByteArray()))
            }
        } }
    }
}
