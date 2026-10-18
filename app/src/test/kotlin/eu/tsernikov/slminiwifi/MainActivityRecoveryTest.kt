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

    @Test fun confirmationRecreationNeedsReconnectAndCannotResumeWrite() =
        assertInterruptedScreen("WifiOnConfirm")

    @Test fun progressRecreationNeedsReconnectAndCannotResumeWrite() =
        assertInterruptedScreen("WifiOnProgress")

    @Test fun setupRecreationDoesNotRestoreCredentialsOrPendingChoice() =
        assertInterruptedScreen("WifiOn")

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
    fun encryptionFailureStopsBeforeAWriteJobCanStart() {
        // Any accidentally launched write is queued and cannot open a socket in this test.
        Dispatchers.setMain(StandardTestDispatcher())
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
                    error("Encryption unavailable")
                }
                override fun decrypt(ciphertext: ByteArray): ByteArray = error("Unused")
            }, object : ProfileBlob {
                override fun read(): ByteArray? = null
                override fun write(ciphertext: ByteArray) = error("Must not persist after encryption failure")
            }))
            setState(activity, "profile", credentials)
            setState(activity, "snapshot", MiniSnapshot(MiniState.SetupPending))
            setScreen(activity, "WifiOnConfirm")
            MainActivity::class.java.getDeclaredMethod("confirmWifiOn").apply { isAccessible = true }.invoke(activity)
            assertEquals(1, encryptionAttempts)
            assertEquals("WifiOn", state<Any>(activity, "screen").toString())
            assertEquals(R.string.wifi_on_store_before_setup_failed, state<Int?>(activity, "profileError"))
            assertNull(field(activity, "writing"))
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

    @Test fun quickLaunchReturnsToHomeButKeepsAnActiveConfirmationResultWait() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            for (origin in listOf("FieldGuide", "ResetGuide", "Details", "WifiOnConfirm")) {
                setScreen(activity, origin)
                controller.newIntent(SetupLaunch.intent(activity))
                assertEquals("Home", state<Any>(activity, "screen").toString())
                assertNull(field(activity, "writing"))
            }
            for (progress in listOf("WifiOffProgress", "WifiOnProgress")) {
                setScreen(activity, progress)
                controller.newIntent(SetupLaunch.intent(activity))
                assertEquals(progress, state<Any>(activity, "screen").toString())
                assertNull(field(activity, "writing"))
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
            val restore = MainActivity::class.java.getDeclaredMethod("restoreHelpReturn", String::class.java)
                .apply { isAccessible = true }
            restore.invoke(activity, "WifiOnResult")
            invoke(activity, "closeHelp")
            assertEquals("WifiOnResult", state<Any>(activity, "screen").toString())
            assertEquals(credentials, state<Profile>(activity, "profile"))
            setState<Profile?>(activity, "savedProfile", null)
            setScreen(activity, "ResetGuide")
            restore.invoke(activity, "WifiOnResult")
            invoke(activity, "closeHelp")
            assertEquals("Home", state<Any>(activity, "screen").toString())
            assertNull(field(activity, "writing"))
        } finally { controller.destroy() }
    }

    private fun invoke(activity: MainActivity, method: String) {
        MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(activity)
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
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun setField(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): T =
        (field(activity, "$name\$delegate") as MutableState<T>).value

    @Suppress("UNCHECKED_CAST")
    private fun <T> setState(activity: MainActivity, name: String, value: T) {
        (field(activity, "$name\$delegate") as MutableState<T>).value = value
    }
}
