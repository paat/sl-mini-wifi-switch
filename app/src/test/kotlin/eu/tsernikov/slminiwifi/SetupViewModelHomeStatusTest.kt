package eu.tsernikov.slminiwifi

import androidx.lifecycle.ViewModelStore
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.Network
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
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
import org.robolectric.annotation.Config

/** Stored credentials belong to the current Mini only when its readable secured name matches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupViewModelHomeStatusTest {
    private lateinit var model: SetupViewModel
    private lateinit var store: ViewModelStore
    private val saved = Profile("Mini-field", "FieldPassword77")

    @Before fun setUp() {
        model = SetupViewModel(RuntimeEnvironment.getApplication())
        store = ViewModelStore().apply { put("setup", model) }
        model.savedProfile = saved
        model.transportFactory = { _, _ -> error("Home credential display must not use a transport") }
    }

    @After fun tearDown() = store.clear()

    @Test fun configuredHomeRefusesSavedPasswordForAnotherWifiName() {
        configured(config(name = "Another-Mini"))
        assertFalse(model.canShowHomeCredentials)

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun configuredHomeCannotUseStaleDetailsWhenCurrentConfigIsUnreadable() {
        model.lastDetails = MiniDetails(config = config())
        configured(null)
        assertFalse(model.canShowHomeCredentials)

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun configuredOpenNetworkDoesNotOfferAnOldSavedPassword() {
        configured(config(auth = AuthKind.OPEN))
        assertFalse(model.canShowHomeCredentials)

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun configuredUnknownAuthenticationDoesNotOfferAnOldSavedPassword() {
        configured(config(auth = null))
        assertFalse(model.canShowHomeCredentials)

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun matchingSecuredNameOpensSavedResultWithoutClaimingVerification() {
        configured(config())
        assertTrue(model.canShowHomeCredentials)
        model.wifiVerified = true

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.WifiOnResult, model.screen)
        assertSame(saved, model.profile)
        assertTrue(model.profileSaved)
        assertFalse(model.wifiVerified)
    }

    @Test fun matchingSecureNameWithoutSavedProfileCannotOpenPassword() {
        configured(config())
        model.savedProfile = null

        assertFalse(model.canShowHomeCredentials)
        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun sameNameWithBothOpenAndSecuredBandsCannotClaimSavedPassword() {
        configured(WifiConfig(setupComplete = true, networks = listOf(Network(basicServiceSets = listOf(
            BasicServiceSet(ssid = saved.name, band = 2, authKind = AuthKind.OPEN),
            BasicServiceSet(ssid = saved.name, band = 5, authKind = AuthKind.WPA2_WPA3),
        )))))

        assertFalse(model.canShowHomeCredentials)
        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.Home, model.screen)
        assertFalse(model.profileSaved)
    }

    @Test fun separateOpenNetworkDoesNotHideMatchingSecuredCredentials() {
        configured(WifiConfig(setupComplete = true, networks = listOf(Network(basicServiceSets = listOf(
            BasicServiceSet(ssid = "Other-network", band = 2, authKind = AuthKind.OPEN),
            BasicServiceSet(ssid = saved.name, band = 5, authKind = AuthKind.WPA2_WPA3),
        )))))

        assertTrue(model.canShowHomeCredentials)
        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.WifiOnResult, model.screen)
        assertSame(saved, model.profile)
        assertFalse(model.wifiVerified)
    }

    @Test fun errorRecoveryCanStillRevealSavedCredentialsWithoutReadableConfig() {
        configured(null)
        model.screen = SetupViewModel.Screen.WifiOffError

        model.showSavedPassword()

        assertEquals(SetupViewModel.Screen.WifiOnResult, model.screen)
        assertSame(saved, model.profile)
        assertFalse(model.wifiVerified)
    }

    private fun configured(config: WifiConfig?) {
        model.snapshot = MiniSnapshot(MiniState.Configured, MiniDetails(config = config))
    }

    private fun config(name: String = saved.name, auth: AuthKind? = AuthKind.WPA2_WPA3) = WifiConfig(
        setupComplete = true,
        networks = listOf(Network(basicServiceSets = listOf(BasicServiceSet(ssid = name, authKind = auth)))),
    )
}
