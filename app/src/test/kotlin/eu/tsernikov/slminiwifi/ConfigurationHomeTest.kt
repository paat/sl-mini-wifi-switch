package eu.tsernikov.slminiwifi

import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import eu.tsernikov.slminiwifi.ui.HomeScreen
import eu.tsernikov.slminiwifi.ui.ResetGuideScreen
import eu.tsernikov.slminiwifi.ui.show
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class ConfigurationHomeTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

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

    @Test fun backFromSaveFailureHelpKeepsConfirmWorkingWithoutPolling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.SetupPending
            model.start()
            runCurrent()
            model.openWifiOn()
            val draft = Profile("My field Mini", "MyDraftPassword77")
            model.profile = draft
            var storageFails = true
            model.profiles = ProfileStore(object : ProfileCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray {
                    if (storageFails) throw IOException("Storage unavailable")
                    return plaintext.copyOf()
                }
                override fun decrypt(ciphertext: ByteArray): ByteArray = error("Unused")
            }, object : ProfileBlob {
                override fun read(): ByteArray? = null
                override fun write(ciphertext: ByteArray) {}
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
            // Return while the fake Mini is still silent: help must not replace setup evidence.
            model.closeHelp()
            assertEquals(Screen.WifiOn, model.screen)
            assertSame(draft, model.profile)
            assertEquals(R.string.wifi_on_store_before_setup_failed, model.profileError)
            assertTrue(model.canConfirm)
            storageFails = false
            var writes = 0
            model.transportFactory = { _, _ -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    assertArrayEquals(Requests.setupWifi(draft.name, draft.password), body)
                    writes++
                    return HttpResponse(200, mapOf("grpc-status" to "9"), byteArrayOf())
                }
            } }
            model.screen = Screen.WifiOnConfirm
            model.confirmWifiOn()
            model.writing!!.join()
            assertEquals("Confirm sends exactly once after Back", 1, writes)
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
            assertFalse(model.polling?.isActive == true)
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

    @Test fun resetGuideKeepsPollingAfterRebootDeadlineWithoutTap() = runTest {
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
            assertTrue(model.polling!!.isActive)
            val afterDeadlineReads = reads
            advanceTimeBy(2_999)
            runCurrent()
            assertEquals(afterDeadlineReads, reads)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(reads > afterDeadlineReads)
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(MiniState.SetupPending, model.snapshot.state)
            assertEquals(Screen.Home, model.screen)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun initiallyMissingMiniIsDetectedWhileResetHelpRemainsOpen() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.NotFound
            model.start()
            runCurrent()
            model.openHelp()
            runCurrent()
            val initialReads = reads
            advanceTimeBy(183_000)
            runCurrent()
            assertTrue(model.polling!!.isActive)
            assertTrue(reads > initialReads)
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(Screen.Home, model.screen)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun otherHelpOriginsNeverPollIncludingAfterStopStart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.start()
            runCurrent()
            for (origin in listOf(Screen.WifiOn, Screen.WifiOnConfirm, Screen.WifiOff,
                Screen.WifiOffResult, Screen.WifiOnResult, Screen.WifiOffError)) {
                model.screen = origin
                model.wifiVerified = false
                model.wifiOffError = eu.tsernikov.slminiwifi.state.WifiOffError.CantReach
                model.openHelp()
                val beforeHelpReads = reads
                runCurrent()
                model.stop()
                model.start()
                advanceTimeBy(6_000)
                runCurrent()
                assertEquals("No reads from $origin help", beforeHelpReads, reads)
                assertFalse(model.polling?.isActive == true)
                model.closeHelp()
                assertEquals(origin, model.screen)
            }
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun immediateNavigationKeepsInFlightPollAndChecking() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = newModel()
        try {
            val transport = model.transportFactory
            val resume = CompletableDeferred<Unit>()
            var startedReads = 0
            model.transportFactory = { connection, written -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    startedReads++
                    resume.await()
                    return transport(connection, written).post(url, headers, body)
                }
            } }
            model.screen = Screen.Details
            model.start()
            model.returnHome()
            assertEquals("returnHome starts only one read", 1, startedReads)
            val job = model.polling
            assertTrue(model.checking)
            model.openHelp()
            assertSame(job, model.polling)
            assertEquals(1, startedReads)
            assertTrue(model.checking)
            resume.complete(Unit)
            assertFalse(model.checking)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun immediateAutoAdvanceKeepsJobAndNextReadStaysQuiet() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.start()
            model.openHelp()
            val job = model.polling
            val beforeTransitionReads = reads
            state = MiniState.SetupPending
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(Screen.Home, model.screen)
            assertSame("Auto-advance must retain the poll", job, model.polling)
            assertEquals("One four-request probe, no immediate re-probe", beforeTransitionReads + 4, reads)
            assertFalse(model.checking)
            val resume = CompletableDeferred<Unit>()
            val transport = model.transportFactory
            model.transportFactory = { connection, written -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    resume.await()
                    return transport(connection, written).post(url, headers, body)
                }
            } }
            advanceTimeBy(3_000)
            runCurrent()
            assertFalse("Background polls must not report explicit checking", model.checking)
            resume.complete(Unit)
            assertFalse(model.checking)
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun pendingHomeKeepsBothChoicesStillAcrossBackgroundReads() = quietHome(1f)
    @Test fun pendingHomeKeepsBothChoicesStillAcrossBackgroundReadsAtLargeFont() = quietHome(2f)

    private fun quietHome(fontScale: Float) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val model = newModel()
        try {
            state = MiniState.SetupPending
            var resume = CompletableDeferred<Unit>()
            val transport = model.transportFactory
            model.transportFactory = { connection, written -> object : Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                    resume.await()
                    return transport(connection, written).post(url, headers, body)
                }
            } }
            model.start()
            compose.show(false, fontScale) {
                HomeScreen(model.snapshot, isChecking = model.checking,
                    isPolling = model.polling?.isActive == true, onRefresh = {}, onConnect = {},
                    onDetails = {}, onSetupChoice = {}, animateGuides = false)
            }
            compose.onNodeWithText("Checking your Mini…").assertExists()
            compose.onNodeWithText("Check again").assertDoesNotExist()
            resume.complete(Unit)
            val labels = listOf("Wi-Fi off", "Wi-Fi on with password")
            val bounds = labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            val job = model.polling
            repeat(3) {
                resume = CompletableDeferred()
                advanceTimeBy(3_000)
                runCurrent()
                assertSame(job, model.polling)
                assertTrue(model.polling!!.isActive)
                for (text in listOf("Checking your Mini…", "Waiting for the Mini to answer",
                    application.getString(R.string.checking_help), "Check again")) {
                    compose.onNodeWithText(text).assertDoesNotExist()
                }
                assertEquals(bounds, labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot })
                resume.complete(Unit)
                assertEquals(bounds, labels.map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot })
            }
        } finally { model.stop(); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun savedOfflineHelpNeverPollsOrAutoAdvancesAfterProcessDeath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (origin in listOf(Screen.WifiOn, Screen.WifiOnConfirm, Screen.WifiOff,
                Screen.WifiOffResult, Screen.WifiOnResult, Screen.WifiOffError)) {
                val original = newModel()
                original.screen = origin
                original.openHelp()
                val saved = Bundle().also(original::saveState)
                store.clear()
                val restored = newModel()
                restored.restore(saved)
                assertEquals(Screen.ResetGuide, restored.screen)
                assertFalse("Offline help from $origin", restored.resetHelpAutoDetect)
                val beforeReads = reads
                state = MiniState.SetupPending
                restored.start()
                advanceTimeBy(9_000)
                runCurrent()
                assertEquals("No reads after restoring $origin help", beforeReads, reads)
                assertFalse(restored.polling?.isActive == true)
                assertEquals(Screen.ResetGuide, restored.screen)
                assertNull(restored.writing)
                restored.stop()
                store.clear()
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun savedAutoDetectHelpStillFindsReadinessAfterProcessDeath() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (origin in listOf(Screen.Home, Screen.WifiOffError, Screen.WifiOnResult)) {
                val original = newModel()
                original.screen = origin
                original.wifiOffError = eu.tsernikov.slminiwifi.state.WifiOffError.AlreadySetUp
                original.wifiVerified = true
                original.openHelp()
                val saved = Bundle().also(original::saveState)
                store.clear()
                val restored = newModel()
                restored.restore(saved)
                assertTrue("Auto-detect help from $origin", restored.resetHelpAutoDetect)
                state = MiniState.SetupPending
                restored.start()
                runCurrent()
                assertEquals(Screen.Home, restored.screen)
                assertEquals(MiniState.SetupPending, restored.snapshot.state)
                assertNull(restored.writing)
                restored.stop()
                store.clear()
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun legacyHelpWithoutSavedEligibilityStaysOffline() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = newModel()
        try {
            model.restore(Bundle().apply {
                putString("screen", Screen.ResetGuide.name)
                putString("helpReturn", "UnknownOldScreen")
            })
            state = MiniState.SetupPending
            model.start()
            advanceTimeBy(9_000)
            runCurrent()
            assertFalse(model.resetHelpAutoDetect)
            assertEquals(0, reads)
            assertEquals(Screen.ResetGuide, model.screen)
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
            assertFalse(model.polling?.isActive == true)
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

    @Test fun pollExceptionEndsJobAndOffersCheckAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val shown = androidx.compose.runtime.mutableStateOf(eu.tsernikov.slminiwifi.state.MiniSnapshot(MiniState.Configured))
        val active = androidx.compose.runtime.mutableStateOf(true)
        val shownMessage = androidx.compose.runtime.mutableStateOf<Int?>(null)
        compose.show(false) {
            HomeScreen(shown.value, isChecking = false, isPolling = active.value, message = shownMessage.value,
                onRefresh = {}, onConnect = {}, onDetails = {}, onSetupChoice = {}, animateGuides = false)
        }
        try {
            for (target in listOf(MiniState.Configured, MiniState.SetupPending, MiniState.Rebooting)) {
                store.clear()
                state = if (target == MiniState.Rebooting) MiniState.Configured else target
                val model = newModel()
                model.start()
                runCurrent()
                assertTrue(model.polling!!.isActive)
                if (target == MiniState.Rebooting) {
                    state = MiniState.Rebooting
                    advanceTimeBy(3_000)
                    runCurrent()
                    assertEquals(MiniState.Rebooting, model.snapshot.state)
                    assertTrue(model.polling!!.isActive)
                }
                model.transportFactory = { _, _ -> throw IOException("poll ended") }
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(target, model.snapshot.state)
                assertFalse(model.polling?.isActive == true)
                assertEquals(R.string.connection_failed, model.message)
                compose.runOnIdle {
                    shown.value = model.snapshot
                    active.value = model.polling?.isActive == true
                    shownMessage.value = model.message
                }
                compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(application.getString(R.string.connection_failed))
                    .performScrollTo().assertIsDisplayed()
                for (text in listOf("Checking your Mini…", "Waiting for the Mini to answer",
                    application.getString(R.string.checking_help))) {
                    compose.onNodeWithText(text).assertDoesNotExist()
                }
                compose.onNodeWithText("every 3 seconds", substring = true).assertDoesNotExist()
                model.stop()
                store.clear()
            }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun missingLocalNetworkPermissionOnResetGuideOffersCheckAgain() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val sdk = android.os.Build.VERSION.SDK_INT
        val model = newModel()
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(
                android.os.Build.VERSION::class.java, "SDK_INT", 37)
            shadowOf(application).denyPermissions(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
            model.restore(Bundle().apply {
                putString("screen", Screen.ResetGuide.name)
                putString("helpReturn", Screen.Home.name)
                putBoolean("resetHelpAutoDetect", true)
            })
            model.start()
            runCurrent()
            assertEquals(Screen.ResetGuide, model.screen)
            assertTrue(model.resetHelpAutoDetect)
            assertFalse(model.polling?.isActive == true)
            assertTrue(model.permissionDenied)
            assertEquals(R.string.permission_needed, model.message)
            compose.show(false) {
                ResetGuideScreen({}, animateGuides = false, autoDetect = model.resetHelpAutoDetect,
                    isPolling = model.polling?.isActive == true, message = model.message,
                    onRefresh = {}, onPermissions = if (model.permissionDenied) ({}) else null)
            }
            compose.onNodeWithText(application.getString(R.string.permission_needed)).assertIsDisplayed()
            compose.onNodeWithText("Open phone settings").assertIsDisplayed()
            compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("every 3 seconds", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Waiting for the Mini to answer").assertDoesNotExist()
        } finally {
            model.stop()
            store.clear()
            org.robolectric.util.ReflectionHelpers.setStaticField(
                android.os.Build.VERSION::class.java, "SDK_INT", sdk)
            Dispatchers.resetMain()
        }
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
