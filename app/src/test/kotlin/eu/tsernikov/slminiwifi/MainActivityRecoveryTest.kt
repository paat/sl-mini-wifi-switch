package eu.tsernikov.slminiwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import androidx.compose.runtime.MutableState
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.ProfileBlob
import eu.tsernikov.slminiwifi.data.ProfileCipher
import eu.tsernikov.slminiwifi.data.ProfileStore
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.ui.GuideStep
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import eu.tsernikov.slminiwifi.state.WifiOffError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.annotation.Config

/** Only create/save/destroy: no foreground polling or real network transport is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityRecoveryTest {
    private val credentials = Profile("RecoveryPrivateName", "RecoveryPrivatePassword7")

    @Test fun instanceStateContainsNeitherWifiNameNorPassword() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            setState(activity, "profile", credentials)
            setState(activity, "savedProfile", credentials)
            setState(activity, "profileSaved", true)
            setScreen(activity, "WifiOnResult")
            val saved = Bundle()
            controller.saveInstanceState(saved)
            assertEquals("WifiOnResult", saved.getString("screen"))
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                val bytes = parcel.marshall()
                for (encoding in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                    val text = bytes.toString(encoding)
                    assertFalse("Saved state contains a Wi-Fi name", text.contains(credentials.name))
                    assertFalse("Saved state contains a Wi-Fi password", text.contains(credentials.password))
                }
            } finally {
                parcel.recycle()
            }
        } finally {
            controller.destroy()
        }
    }

    @Test fun processRecreationOnConfirmationReturnsToEditingWithoutClaimingAWrite() =
        assertUnsentScreen("WifiOnConfirm")

    @Test fun progressRecreationNeedsReconnectAndCannotResumeWrite() =
        assertInterruptedScreen("WifiOnProgress")

    @Test fun setupRecreationDoesNotRestoreCredentialsOrPendingChoice() =
        assertUnsentScreen("WifiOn")

    @Test fun resultWithoutEncryptedProfileReturnsHome() = assertResultFallsBack("WifiOnResult")

    @Test fun connectExplanationWithoutEncryptedProfileReturnsHome() = assertResultFallsBack("WifiOnConnect")

    @Test fun unreadableEncryptedProfileCannotRestoreResult() {
        // A structurally valid ciphertext envelope with no usable key/authentication tag.
        val invalidCiphertext = ByteArray(29).also { it[0] = 1 }
        RuntimeEnvironment.getApplication().getSharedPreferences("wifi_profile", Context.MODE_PRIVATE)
            .edit().putString("encrypted_profile", Base64.encodeToString(invalidCiphertext, Base64.NO_WRAP)).commit()
        assertResultFallsBack("WifiOnResult")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("DEPRECATION")
    fun saveFailureThenRetryAndConfirmSendsExactlyOneWrite() = kotlinx.coroutines.test.runTest {
        // Any accidentally launched write is queued and cannot open a socket in this test.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val manager = activity.getSystemService(ConnectivityManager::class.java)
            val network = ShadowNetwork.newInstance(81)
            shadowOf(manager).clearAllNetworks()
            shadowOf(manager).addNetwork(network, ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, NetworkCapabilities.TRANSPORT_ETHERNET, 0, true, true,
            ))
            setField(activity, "confirmedConnection", StarlinkConnection(network, "192.168.1.1"))
            var encryptionAttempts = 0
            setField(activity, "profiles", ProfileStore(object : ProfileCipher {
                override fun encrypt(plaintext: ByteArray): ByteArray {
                    encryptionAttempts++
                    if (encryptionAttempts == 1) error("Encryption unavailable")
                    return plaintext.copyOf()
                }
                override fun decrypt(ciphertext: ByteArray): ByteArray = error("Unused")
            }, object : ProfileBlob {
                override fun read(): ByteArray? = null
                override fun write(ciphertext: ByteArray) = Unit
            }))
            var writes = 0
            activity.model.transportFactory = { _, _ -> object : eu.tsernikov.slminiwifi.protocol.Transport {
                override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): eu.tsernikov.slminiwifi.protocol.HttpResponse {
                    org.junit.Assert.assertArrayEquals(
                        eu.tsernikov.slminiwifi.protocol.Requests.setupWifi(credentials.name, credentials.password), body)
                    writes++
                    return eu.tsernikov.slminiwifi.protocol.HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
                }
            } }
            setState(activity, "profile", credentials)
            setState(activity, "snapshot", MiniSnapshot(MiniState.SetupPending))
            setScreen(activity, "WifiOnConfirm")
            SetupViewModel::class.java.getDeclaredMethod("confirmWifiOn").apply { isAccessible = true }.invoke(activity.model)
            activity.model.writing!!.join()
            assertEquals(1, encryptionAttempts)
            assertEquals("WifiOn", state<Any>(activity, "screen").toString())
            assertEquals(R.string.wifi_on_store_before_setup_failed, state<Int?>(activity, "profileError"))
            assertEquals(true, activity.model.writing?.isCompleted)
            assertEquals(0, writes)
            assertEquals(MiniState.SetupPending, activity.model.snapshot.state)
            assertEquals(credentials.password, activity.model.profile.password)
            // The form's Review this choice action returns to confirmation after Try again.
            setScreen(activity, "WifiOnConfirm")
            activity.model.confirmWifiOn()
            activity.model.confirmWifiOn() // A repeated tap while saving cannot send twice.
            activity.model.writing!!.join()
            assertEquals(2, encryptionAttempts)
            assertEquals(1, writes)
        } finally {
            controller.destroy()
            Dispatchers.resetMain()
        }
    }

    @Test fun guideWalksBothConnectionPathsAndOnlyReturnsToDetection() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            invoke(activity, "openGuide")
            assertEquals("FieldGuide", state<Any>(activity, "screen").toString())
            assertEquals(GuideStep.Reset, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideNext")
            assertEquals(GuideStep.Light, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideNext")
            assertEquals(GuideStep.Cable, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideNext")
            assertEquals(GuideStep.Check, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideBack")
            assertEquals(GuideStep.Cable, state<GuideStep>(activity, "guideStep"))
            setState(activity, "guideStep", GuideStep.Wifi)
            invoke(activity, "guideNext")
            assertEquals(GuideStep.AndroidConnect, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideNext")
            invoke(activity, "guideBack")
            assertEquals(GuideStep.AndroidConnect, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideNext")
            invoke(activity, "guideNext")
            assertEquals("Home", state<Any>(activity, "screen").toString())
            assertNull(field(activity, "writing"))
            assertNull(field(activity, "confirmedConnection"))
        } finally { controller.destroy() }
    }

    @Test fun guideRestoresStepAndBackAtFirstStepReturnsHome() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply {
            putString("screen", "FieldGuide")
            putString("guideStep", "Light")
        })
        try {
            val activity = controller.get()
            assertEquals(GuideStep.Light, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideBack")
            assertEquals(GuideStep.Reset, state<GuideStep>(activity, "guideStep"))
            invoke(activity, "guideBack")
            assertEquals("Home", state<Any>(activity, "screen").toString())
            assertNull(field(activity, "writing"))
        } finally { controller.destroy() }
    }

    @Test fun offlineHelpReturnsToEachOriginWithoutStartingWork() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            for (origin in listOf("Home", "WifiOffError", "WifiOn", "WifiOnResult")) {
                setScreen(activity, origin)
                invoke(activity, "openHelp")
                assertEquals("ResetGuide", state<Any>(activity, "screen").toString())
                assertNull(field(activity, "writing"))
                invoke(activity, "closeHelp")
                assertEquals(origin, state<Any>(activity, "screen").toString())
            }
        } finally { controller.destroy() }
    }

    @Test fun helpBackReturnsToResultAndPreservesCredentials() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val model = controller.get().model
            model.profile = credentials
            model.savedProfile = credentials
            for (origin in listOf(SetupViewModel.Screen.WifiOnResult, SetupViewModel.Screen.WifiOffResult)) {
                model.screen = origin
                model.openHelp()
                model.closeHelp()
                assertEquals(origin, model.screen)
                assertEquals(credentials.password, model.profile.password)
                assertNull(model.polling)
                assertNull(model.writing)
            }
            model.screen = SetupViewModel.Screen.WifiOffError
            model.openHelp()
            model.closeHelp()
            assertEquals(SetupViewModel.Screen.WifiOffError, model.screen)
        } finally { controller.destroy() }
    }

    @Test fun quickLaunchRoutesOnlyIdleScreensHomeAndPreservesSetupAndResults() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            for (origin in listOf("FieldGuide", "ResetGuide", "Details", "WifiOffError")) {
                setScreen(activity, origin)
                controller.newIntent(SetupLaunch.intent(activity))
                assertEquals("Home", state<Any>(activity, "screen").toString())
                assertNull(field(activity, "writing"))
            }
            for (progress in listOf("WifiOff", "WifiOn", "WifiOnConfirm", "WifiOffProgress", "WifiOnProgress",
                "WifiOffResult", "WifiOnResult", "WifiOnConnect")) {
                setScreen(activity, progress)
                controller.newIntent(SetupLaunch.intent(activity))
                assertEquals(progress, state<Any>(activity, "screen").toString())
                assertNull(field(activity, "writing"))
            }
        } finally { controller.destroy() }
    }

    @Test fun coldQuickLaunchAppliesIdleRuleAfterSavedStateRestoration() {
        for (origin in listOf("FieldGuide", "ResetGuide", "Details", "WifiOffError", "WifiOff", "WifiOnConfirm", "WifiOffResult")) {
            val controller = Robolectric.buildActivity(MainActivity::class.java,
                SetupLaunch.intent(RuntimeEnvironment.getApplication())).create(Bundle().apply {
                    putString("screen", origin)
                    putString("setupLaunchId", "previous-process-entry")
                })
            try {
                val expected = when (origin) {
                    "WifiOff", "WifiOffResult" -> origin
                    "WifiOnConfirm" -> "WifiOn" // Process death never restores an unsent write.
                    else -> "Home"
                }
                assertEquals(expected, controller.get().model.screen.name)
                assertNull(controller.get().model.writing)
            } finally { controller.destroy() }
        }
    }

    @Test fun restoringTheSameQuickIntentAfterProcessDeathKeepsTheGuide() {
        val intent = SetupLaunch.intent(RuntimeEnvironment.getApplication())
        val first = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        first.get().model.openGuide()
        val saved = Bundle()
        first.saveInstanceState(saved).destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java, android.content.Intent(intent)).create(saved)
        try {
            assertEquals(SetupViewModel.Screen.FieldGuide, restored.get().model.screen)
            assertNull(restored.get().model.writing)
        } finally { restored.destroy() }
    }

    @Test fun secondQuickLaunchRecreatedWithNewIntentDoesNotReplay() {
        for (screen in listOf(SetupViewModel.Screen.FieldGuide, SetupViewModel.Screen.WifiOffError)) {
            val first = Robolectric.buildActivity(MainActivity::class.java,
                SetupLaunch.intent(RuntimeEnvironment.getApplication())).create()
            val latest = SetupLaunch.intent(first.get())
            first.get().model.openGuide()
            first.newIntent(latest)
            assertEquals(SetupViewModel.Screen.Home, first.get().model.screen)
            first.get().model.screen = screen
            val saved = Bundle()
            first.saveInstanceState(saved).destroy()
            // Android relaunches with the latest delivered intent, not the original one.
            val restored = Robolectric.buildActivity(MainActivity::class.java,
                android.content.Intent(latest)).create(saved)
            try {
                assertEquals(screen, restored.get().model.screen)
                assertNull(restored.get().model.writing)
            } finally { restored.destroy() }
        }
    }

    @Test fun coldQuickLaunchKeepsRestoredSavedCredentials() {
        val controller = Robolectric.buildActivity(MainActivity::class.java,
            SetupLaunch.intent(RuntimeEnvironment.getApplication()))
        // Install the same store boundary used in production before onCreate restores state.
        val model = androidx.lifecycle.ViewModelProvider(controller.get())[SetupViewModel::class.java]
        var bytes: ByteArray? = null
        model.profiles = ProfileStore(object : ProfileCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.copyOf()
            override fun decrypt(ciphertext: ByteArray) = ciphertext.copyOf()
        }, object : ProfileBlob {
            override fun read() = bytes
            override fun write(ciphertext: ByteArray) { bytes = ciphertext }
        }).also { it.save(credentials) }
        controller.create(Bundle().apply {
            putString("screen", "WifiOnConnect")
            putBoolean("profileSaved", true)
        })
        try {
            assertEquals(SetupViewModel.Screen.WifiOnResult, model.screen)
            assertEquals(credentials.password, model.profile.password)
            assertNull(model.writing)
        } finally { controller.destroy() }
    }

    @Test fun quickLaunchKeepsPermissionStateEvenAfterDialogRequestIsConsumed() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val model = controller.get().model
            for (screen in listOf(SetupViewModel.Screen.Home, SetupViewModel.Screen.WifiOnConfirm)) {
                model.screen = screen
                model.pendingPermissionAction = SetupViewModel.PermissionAction.SecureWifi
                model.awaitingPermissionResult = true
                model.permissionRequest = null // Activity has already launched the system dialog.
                val connection = StarlinkConnection(ShadowNetwork.newInstance(72), "192.168.1.1")
                model.confirmedConnection = connection
                controller.newIntent(SetupLaunch.intent(controller.get()))
                assertEquals(screen, model.screen)
                assertEquals(connection, model.confirmedConnection)
                assertEquals(SetupViewModel.PermissionAction.SecureWifi, model.pendingPermissionAction)
                assertEquals(true, model.awaitingPermissionResult)
                assertNull(model.writing)
            }
        } finally { controller.destroy() }
    }

    @Test fun helpRestoresTheResultOnlyWithALoadedEncryptedProfile() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            // Supply the profile-store result to exercise the same restoration used by onCreate.
            setState(activity, "savedProfile", credentials)
            setState(activity, "profileSaved", true)
            setScreen(activity, "ResetGuide")
            val restore = SetupViewModel::class.java.getDeclaredMethod("restoreHelpReturn", String::class.java)
                .apply { isAccessible = true }
            restore.invoke(activity.model, "WifiOnResult")
            invoke(activity, "closeHelp")
            assertEquals("WifiOnResult", state<Any>(activity, "screen").toString())
            assertEquals(credentials, state<Profile>(activity, "profile"))
            setState<Profile?>(activity, "savedProfile", null)
            setScreen(activity, "ResetGuide")
            restore.invoke(activity.model, "WifiOnResult")
            invoke(activity, "closeHelp")
            assertEquals("Home", state<Any>(activity, "screen").toString())
            assertNull(field(activity, "writing"))
        } finally { controller.destroy() }
    }

    private fun invoke(activity: MainActivity, method: String) {
        SetupViewModel::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity.model)
    }

    private fun assertUnsentScreen(screen: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply {
            putString("screen", screen)
        })
        try {
            assertEquals("WifiOn", controller.get().model.screen.name)
            assertNull(controller.get().model.writing)
        } finally { controller.destroy() }
    }

    private fun assertInterruptedScreen(screen: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply {
            putString("screen", screen)
            putBoolean("secureFlow", true)
        })
        try {
            val activity = controller.get()
            assertEquals("WifiOffError", state<Any>(activity, "screen").toString())
            assertEquals(WifiOffError.CantReach, state<WifiOffError>(activity, "wifiOffError"))
            assertEquals(true, state<Boolean>(activity, "secureFlow"))
            assertNull(field(activity, "writing"))
            assertNull(field(activity, "confirmedConnection"))
        } finally {
            controller.destroy()
        }
    }

    private fun assertResultFallsBack(screen: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle().apply {
            putString("screen", screen)
            putBoolean("secureFlow", true)
            putBoolean("profileSaved", true)
        })
        try {
            val activity = controller.get()
            assertEquals("Home", state<Any>(activity, "screen").toString())
            assertNull(state<Profile?>(activity, "savedProfile"))
            assertNull(field(activity, "writing"))
        } finally {
            controller.destroy()
        }
    }

    private fun setScreen(activity: MainActivity, screen: String) {
        val current = state<Any>(activity, "screen")
        val value = current.javaClass.enumConstants.single { (it as Enum<*>).name == screen }
        setState(activity, "screen", value)
    }

    private fun field(activity: MainActivity, name: String): Any? =
        if (name == "confirmedConnection") activity.model.confirmedConnection
        else SetupViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity.model)

    private fun setField(activity: MainActivity, name: String, value: Any) {
        if (name == "confirmedConnection") activity.model.confirmedConnection = value as StarlinkConnection
        else SetupViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity.model, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): T =
        if (name == "screen") activity.model.screen as T else (field(activity, "$name\$delegate") as MutableState<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(activity: MainActivity, name: String, value: T) {
        if (name == "screen") activity.model.screen = value as SetupViewModel.Screen
        else (field(activity, "$name\$delegate") as MutableState<T>).value = value
    }
}
