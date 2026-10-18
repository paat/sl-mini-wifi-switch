package eu.tsernikov.slminiwifi.net

import android.app.Activity
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiAutoConnectTest {
    private class FakePlatform : WifiJoinPlatform {
        val calls = mutableListOf<String>()
        var status = WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        var fail = false
        override fun releaseRequest() { calls += "release" }
        override fun launchSave(name: String, password: String) {
            calls += "save"
            assertEquals("Mini-field", name)
            assertTrue(password == "TestPassword23")
            if (fail) throw SecurityException("Platform declined")
        }
        override fun suggest(name: String, password: String): Int {
            calls += "suggest"
            assertEquals("Mini-field", name)
            assertTrue(password == "TestPassword23")
            return status
        }
    }

    @Test fun api30ReleasesTemporaryRequestBeforeLaunchingSave() {
        val fake = FakePlatform()
        assertNull(AndroidWifiAutoConnect(fake, 30).connect("Mini-field", "TestPassword23"))
        assertEquals(listOf("release", "save"), fake.calls)
    }

    @Test fun api29ReleasesRequestBeforeSuggestionAndExplainsNotification() {
        val fake = FakePlatform()
        assertEquals(WifiJoinOutcome.NOTIFICATION,
            AndroidWifiAutoConnect(fake, 29).connect("Mini-field", "TestPassword23"))
        assertEquals(listOf("release", "suggest"), fake.calls)
    }

    @Test fun api29DuplicateStillNeedsNotificationRatherThanClaimingSaved() {
        val fake = FakePlatform().apply { status = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE }
        assertEquals(WifiJoinOutcome.NOTIFICATION,
            AndroidWifiAutoConnect(fake, 29).connect("Mini-field", "TestPassword23"))
    }

    @Test fun api29DeniedSuggestionsKeepManualJoinAvailable() {
        val fake = FakePlatform().apply { status = WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED }
        assertEquals(WifiJoinOutcome.UNAVAILABLE,
            AndroidWifiAutoConnect(fake, 29).connect("Mini-field", "TestPassword23"))
    }

    @Test fun launchFailureIsAnOutcomeWithoutLeakingPlatformException() {
        val fake = FakePlatform().apply { fail = true }
        assertEquals(WifiJoinOutcome.UNAVAILABLE,
            AndroidWifiAutoConnect(fake, 35).connect("Mini-field", "TestPassword23"))
    }

    @Test fun saveResultIsMappedPerNetwork() {
        val join = AndroidWifiAutoConnect(FakePlatform(), 35)
        assertEquals(WifiJoinOutcome.SAVED, join.activityResult(Activity.RESULT_OK, result(0)))
        assertEquals(WifiJoinOutcome.ALREADY_EXISTS, join.activityResult(Activity.RESULT_OK, result(2)))
        assertEquals(WifiJoinOutcome.UNAVAILABLE, join.activityResult(Activity.RESULT_OK, result(1)))
    }

    @Test fun cancelledAndMalformedSaveResultsDoNotClaimSuccess() {
        val join = AndroidWifiAutoConnect(FakePlatform(), 35)
        assertEquals(WifiJoinOutcome.DECLINED, join.activityResult(Activity.RESULT_CANCELED, null))
        assertEquals(WifiJoinOutcome.UNAVAILABLE, join.activityResult(Activity.RESULT_OK, null))
        assertEquals(WifiJoinOutcome.UNAVAILABLE, join.activityResult(Activity.RESULT_OK, result()))
        assertEquals(WifiJoinOutcome.UNAVAILABLE, join.activityResult(Activity.RESULT_OK, result(0, 2)))
    }

    @Suppress("DEPRECATION")
    @Test fun realAdapterBuildsSystemSaveIntentWithSecuredSuggestion() {
        var released = false
        var launched: Intent? = null
        val join = AndroidWifiAutoConnect(RuntimeEnvironment.getApplication(),
            releaseRequest = { released = true },
            launchSave = { assertTrue(released); launched = it },
        )
        assertNull(join.connect("Mini-field", "TestPassword23"))
        assertEquals(Settings.ACTION_WIFI_ADD_NETWORKS, launched?.action)
        val suggestion = launched!!.getParcelableArrayListExtra<WifiNetworkSuggestion>(
            Settings.EXTRA_WIFI_NETWORK_LIST)!!.single()
        assertEquals("Mini-field", suggestion.ssid)
        assertTrue(suggestion.passphrase == "TestPassword23")
    }

    @Config(sdk = [29])
    @Test fun realApi29AdapterAddsSuggestionAfterRelease() {
        val context = RuntimeEnvironment.getApplication()
        val wifi = context.getSystemService(WifiManager::class.java)
        var released = false
        val join = AndroidWifiAutoConnect(context,
            releaseRequest = { released = true },
            launchSave = { error("API 29 must use suggestions") },
        )
        assertEquals(WifiJoinOutcome.NOTIFICATION, join.connect("Mini-field", "TestPassword23"))
        assertTrue(released)
        val suggestions = ReflectionHelpers.callInstanceMethod<List<WifiNetworkSuggestion>>(
            shadowOf(wifi), "getNetworkSuggestions")
        assertEquals(1, suggestions.size)
    }

    private fun result(vararg status: Int) = Intent().putIntegerArrayListExtra(
        Settings.EXTRA_WIFI_NETWORK_RESULT_LIST, ArrayList(status.toList()),
    )
}
