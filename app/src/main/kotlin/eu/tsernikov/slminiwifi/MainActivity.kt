package eu.tsernikov.slminiwifi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.tsernikov.slminiwifi.data.PasswordGenerator
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.ProfileStore
import eu.tsernikov.slminiwifi.data.WifiRules
import eu.tsernikov.slminiwifi.data.createProfileStore
import eu.tsernikov.slminiwifi.net.AndroidWifiAutoConnect
import eu.tsernikov.slminiwifi.net.OkHttpTransport
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.net.StarlinkNetworks
import eu.tsernikov.slminiwifi.net.WifiAutoConnect
import eu.tsernikov.slminiwifi.net.WifiJoinOutcome
import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.MiniClient
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.MiniStateDetector
import eu.tsernikov.slminiwifi.state.WifiOffConnection
import eu.tsernikov.slminiwifi.state.WifiOffError
import eu.tsernikov.slminiwifi.state.WifiOffFlow
import eu.tsernikov.slminiwifi.state.WifiOffObservation
import eu.tsernikov.slminiwifi.state.WifiOffOutcome
import eu.tsernikov.slminiwifi.state.WifiOnFlow
import eu.tsernikov.slminiwifi.state.WifiOnOutcome
import eu.tsernikov.slminiwifi.state.probeWifiOff
import eu.tsernikov.slminiwifi.ui.DetailsScreen
import eu.tsernikov.slminiwifi.ui.HomeScreen
import eu.tsernikov.slminiwifi.ui.MiniTheme
import eu.tsernikov.slminiwifi.ui.ResetGuideScreen
import eu.tsernikov.slminiwifi.ui.WifiOffConfirmScreen
import eu.tsernikov.slminiwifi.ui.WifiOffErrorScreen
import eu.tsernikov.slminiwifi.ui.WifiOffProgressScreen
import eu.tsernikov.slminiwifi.ui.WifiOffResultScreen
import eu.tsernikov.slminiwifi.ui.WifiOnAutoConnectScreen
import eu.tsernikov.slminiwifi.ui.WifiOnConfirmScreen
import eu.tsernikov.slminiwifi.ui.WifiOnProgressScreen
import eu.tsernikov.slminiwifi.ui.WifiOnResultScreen
import eu.tsernikov.slminiwifi.ui.WifiOnSetupScreen
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Owns foreground, app-local connections. Writes require a separate confirmation tap. */
class MainActivity : ComponentActivity() {
    private enum class Screen {
        Home, Details, WifiOff, WifiOffProgress, WifiOffResult, WifiOffError, ResetGuide,
        WifiOn, WifiOnConfirm, WifiOnProgress, WifiOnResult, WifiOnConnect,
    }
    private enum class PermissionAction { Check, Connect, SecureWifi }

    private lateinit var networks: StarlinkNetworks
    private lateinit var profiles: ProfileStore
    private lateinit var autoConnect: WifiAutoConnect
    private val generator = PasswordGenerator()
    private var profile by mutableStateOf(Profile(generator.name(), generator.password()))
    private var savedProfile by mutableStateOf<Profile?>(null)
    private var ownPassword by mutableStateOf(false)
    private var profileSaved by mutableStateOf(false)
    private var wifiVerified by mutableStateOf(false)
    private var profileError by mutableStateOf<Int?>(null)
    private var joinMessage by mutableStateOf<Int?>(null)
    private var secureConnection: StarlinkConnection? = null
    private var secureRequestAttempted = false
    private var secureFlow by mutableStateOf(false)
    private val saveNetwork = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        joinMessage = joinMessage(autoConnect.activityResult(result.resultCode, result.data))
    }
    private val detector = MiniStateDetector()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var connecting: Job? = null
    private var writing: Job? = null
    private var detectedConnection: StarlinkConnection? = null
    private var confirmedConnection: StarlinkConnection? = null
    private var confirmedInterface: String? = null
    private var confirmationIsWifi by mutableStateOf(false)
    private var wifiOffError by mutableStateOf(WifiOffError.CantReach)
    private var likelySuccess by mutableStateOf(false)
    private var detailsReturn = Screen.Home
    private var foreground = false
    private var pendingPermissionAction = PermissionAction.Check
    private var snapshot by mutableStateOf(MiniSnapshot(MiniState.NotFound))
    private var screen by mutableStateOf(Screen.Home)
    private var checking by mutableStateOf(false)
    private var requestingWifi by mutableStateOf(false)
    private var message by mutableStateOf<Int?>(null)
    private var permissionDenied by mutableStateOf(false)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            permissionDenied = false
            message = null
            when (pendingPermissionAction) {
                PermissionAction.Check -> startPolling(showProgress = true)
                PermissionAction.Connect -> connectWifi()
                PermissionAction.SecureWifi -> confirmWifiOn()
            }
        } else {
            permissionDenied = true
            message = R.string.permission_needed
            if (pendingPermissionAction == PermissionAction.SecureWifi) screen = Screen.Home
            // Ethernet is still usable if only the Wi-Fi identification permission was refused.
            startPolling(showProgress = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networks = StarlinkNetworks(this)
        profiles = createProfileStore(this)
        savedProfile = try { profiles.load() } catch (_: Exception) { null }
        autoConnect = AndroidWifiAutoConnect(this, networks::releaseRequest) { saveNetwork.launch(it) }
        screen = savedInstanceState?.getString("screen")?.let { name ->
            Screen.entries.firstOrNull { it.name == name }
        } ?: Screen.Home
        wifiOffError = savedInstanceState?.getString("wifiOffError")?.let { name ->
            WifiOffError.entries.firstOrNull { it.name == name }
        } ?: WifiOffError.CantReach
        // Never restore a send or stale confirmation after process/activity recreation.
        if (screen == Screen.WifiOff || screen == Screen.WifiOffProgress ||
            screen == Screen.WifiOn || screen == Screen.WifiOnConfirm || screen == Screen.WifiOnProgress) {
            screen = Screen.WifiOffError
            wifiOffError = WifiOffError.CantReach
        }
        secureFlow = savedInstanceState?.getBoolean("secureFlow") ?: false
        profileSaved = savedInstanceState?.getBoolean("profileSaved") ?: false
        wifiVerified = savedInstanceState?.getBoolean("wifiVerified") ?: false
        if (screen == Screen.WifiOnResult || screen == Screen.WifiOnConnect) {
            val restored = savedProfile
            if (profileSaved && restored != null) {
                profile = restored
                screen = Screen.WifiOnResult
            } else screen = Screen.Home
        }
        likelySuccess = savedInstanceState?.getBoolean("likelySuccess") ?: false
        detailsReturn = when (savedInstanceState?.getString("detailsReturn")) {
            Screen.WifiOnResult.name -> if (profileSaved && savedProfile != null) {
                profile = savedProfile!!
                Screen.WifiOnResult
            } else Screen.Home
            Screen.WifiOffResult.name -> Screen.WifiOffResult
            else -> if (savedInstanceState?.getBoolean("detailsFromResult") == true) Screen.WifiOffResult else Screen.Home
        }
        enableEdgeToEdge()
        setContent {
            MiniTheme {
                BackHandler(enabled = screen != Screen.Home) {
                    if (screen != Screen.WifiOffProgress && screen != Screen.WifiOnProgress) {
                        when {
                            screen == Screen.Details && detailsReturn != Screen.Home -> screen = detailsReturn
                            screen == Screen.WifiOnConnect -> screen = Screen.WifiOnResult
                            screen == Screen.WifiOnConfirm -> screen = Screen.WifiOn
                            else -> returnHome()
                        }
                    }
                }
                when (screen) {
                    Screen.Home -> HomeScreen(
                        snapshot = snapshot,
                        isChecking = checking,
                        isConnecting = requestingWifi,
                        message = message,
                        onRefresh = { requestAccess(PermissionAction.Check) },
                        onConnect = { requestAccess(PermissionAction.Connect) },
                        onDetails = { detailsReturn = Screen.Home; screen = Screen.Details },
                        onSetupChoice = { wifiOff ->
                            if (snapshot.state == MiniState.SetupPending) {
                                if (wifiOff) openWifiOff() else openWifiOn()
                            }
                        },
                        onPermissions = if (permissionDenied) ({
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) else null,
                    )
                    Screen.Details -> DetailsScreen(snapshot.details) {
                        if (detailsReturn != Screen.Home) screen = detailsReturn else returnHome()
                    }
                    Screen.WifiOn -> WifiOnSetupScreen(
                        profile, ownPassword, savedProfile != null,
                        onNameChange = { profile = Profile(it, profile.password) },
                        onPasswordChange = { profile = Profile(profile.name, it) },
                        onOwnPassword = { ownPassword = true; profile = Profile(profile.name, "") },
                        onGeneratedPassword = { ownPassword = false; profile = Profile(profile.name, generator.password()) },
                        onUseSaved = { savedProfile?.let { profile = it; ownPassword = true } },
                        onContinue = { if (WifiRules.isValid(profile)) screen = Screen.WifiOnConfirm },
                        onBack = ::returnHome, message = profileError,
                    )
                    Screen.WifiOnConfirm -> WifiOnConfirmScreen(profile.name, ::confirmWifiOn) { screen = Screen.WifiOn }
                    Screen.WifiOnProgress -> WifiOnProgressScreen()
                    Screen.WifiOnResult -> WifiOnResultScreen(profile,
                        onConnect = { screen = Screen.WifiOnConnect },
                        onDetails = { detailsReturn = Screen.WifiOnResult; screen = Screen.Details },
                        onDone = ::returnHome, connectionMessage = joinMessage, verified = wifiVerified)
                    Screen.WifiOnConnect -> WifiOnAutoConnectScreen(Build.VERSION.SDK_INT == 29,
                        onContinue = {
                            screen = Screen.WifiOnResult
                            joinMessage = autoConnect.connect(profile.name, profile.password)?.let(::joinMessage)
                        }, onBack = { screen = Screen.WifiOnResult })
                    Screen.WifiOff -> WifiOffConfirmScreen(confirmationIsWifi, ::confirmWifiOff, ::returnHome)
                    Screen.WifiOffProgress -> WifiOffProgressScreen()
                    Screen.WifiOffResult -> WifiOffResultScreen(likelySuccess,
                        onDetails = { detailsReturn = Screen.WifiOffResult; screen = Screen.Details },
                        onDone = ::returnHome)
                    Screen.WifiOffError -> WifiOffErrorScreen(wifiOffError,
                        unreachableBody = if (secureFlow) R.string.wifi_on_unreachable_body else R.string.wifi_off_unreachable_body,
                        onShowCredentials = if (secureFlow && profileSaved && savedProfile != null && wifiOffError == WifiOffError.CantReach) ({
                            profile = savedProfile!!
                            wifiVerified = false
                            screen = Screen.WifiOnResult
                        }) else null,
                        onNext = {
                        if (wifiOffError == WifiOffError.AlreadySetUp) screen = Screen.ResetGuide else returnHome()
                    })
                    Screen.ResetGuide -> ResetGuideScreen(onDone = ::returnHome)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (hasLocalAccess()) {
            if (permissionDenied && wifiPermissions(PermissionAction.Check).all(::granted)) {
                permissionDenied = false
                message = null
            }
            startPolling(showProgress = true)
        } else {
            message = R.string.no_network_permission
        }
    }

    override fun onStop() {
        foreground = false
        polling?.cancel()
        connecting?.cancel()
        if (screen == Screen.WifiOffProgress) {
            writing?.cancel()
            wifiOffError = WifiOffError.CantReach
            screen = Screen.WifiOffError
        }
        // Android's temporary Wi-Fi Connect dialog may cover the activity while verification
        // runs. Keep that bounded read-only phase alive; destruction still cancels it.
        if (screen != Screen.WifiOnProgress) networks.releaseRequest()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        networks.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("secureFlow", secureFlow)
        outState.putBoolean("profileSaved", profileSaved)
        outState.putBoolean("wifiVerified", wifiVerified)
        outState.putString("screen", screen.name)
        outState.putString("wifiOffError", wifiOffError.name)
        outState.putBoolean("likelySuccess", likelySuccess)
        outState.putString("detailsReturn", detailsReturn.name)
        super.onSaveInstanceState(outState)
    }

    private fun returnHome() {
        confirmedConnection = null
        screen = Screen.Home
        startPolling(showProgress = true)
    }

    private fun openWifiOn() {
        secureFlow = true
        val connection = detectedConnection ?: return
        if (snapshot.state != MiniState.SetupPending || !networks.isPresent(connection)) return
        polling?.cancel()
        confirmedConnection = connection
        confirmedInterface = networks.interfaceName(connection)
        confirmationIsWifi = !networks.isEthernet(connection)
        secureConnection = null
        secureRequestAttempted = false
        profileSaved = false
        wifiVerified = false
        profile = Profile(generator.name(), generator.password())
        ownPassword = false
        profileError = null
        joinMessage = null
        screen = Screen.WifiOn
    }

    private fun confirmWifiOn() {
        if (screen != Screen.WifiOnConfirm || snapshot.state != MiniState.SetupPending || !WifiRules.isValid(profile)) return
        val connection = confirmedConnection
        if (connection == null || !networks.isPresent(connection)) {
            wifiOffError = WifiOffError.CantReach
            screen = Screen.WifiOffError
            return
        }
        val missing = if (confirmationIsWifi) wifiPermissions(PermissionAction.Connect).filterNot(::granted) else emptyList()
        if (missing.isNotEmpty()) {
            pendingPermissionAction = PermissionAction.SecureWifi
            permissions.launch(missing.toTypedArray())
            return
        }
        val confirmedProfile = profile
        // Persist only encrypted bytes, before the network disappears. Never put credentials
        // in savedInstanceState; a recreation can recover them from this encrypted profile.
        profileSaved = try {
            profiles.save(confirmedProfile)
            savedProfile = confirmedProfile
            true
        } catch (_: Exception) { false }
        if (!profileSaved) {
            profileError = R.string.wifi_on_store_before_setup_failed
            screen = Screen.WifiOn
            return
        }
        profileError = null
        screen = Screen.WifiOnProgress
        snapshot = MiniSnapshot(MiniState.NotFound)
        detectedConnection = null
        writing = scope.launch {
            try {
                val outcome = OkHttpTransport(connection.network, connection.routerAddress).use { transport ->
                    WifiOnFlow(MiniClient(transport, "http://${connection.routerAddress}:9001"),
                        observe = { remaining ->
                            withTimeoutOrNull(remaining) { observeWifiOn(connection, confirmedProfile) }
                                ?: ClientResult.TransportError(IOException("Mini verification timed out"))
                        },
                    ).confirm(MiniState.SetupPending, confirmedProfile)
                }
                when (outcome) {
                    WifiOnOutcome.Success -> {
                        wifiVerified = true
                        networks.releaseRequest()
                        screen = Screen.WifiOnConnect
                    }
                    is WifiOnOutcome.Failure -> {
                        networks.releaseRequest()
                        wifiOffError = outcome.error
                        screen = Screen.WifiOffError
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                networks.releaseRequest()
                wifiOffError = WifiOffError.CantReach
                screen = Screen.WifiOffError
            }
        }
    }

    private suspend fun observeWifiOn(original: StarlinkConnection, credentials: Profile): ClientResult {
        val selected = if (!confirmationIsWifi) {
            if (networks.isPresent(original)) original else networks.findEthernetOnInterface(confirmedInterface)
        } else {
            if (networks.isPresent(original) && !secureRequestAttempted) original else {
                if (!secureRequestAttempted) {
                    secureRequestAttempted = true
                    secureConnection = networks.requestSecureWifi(credentials.name, credentials.password)
                }
                secureConnection?.takeIf(networks::isPresent) ?: networks.findWifi(credentials.name)
            }
        } ?: return ClientResult.TransportError(IOException("Mini connection unavailable"))
        return OkHttpTransport(selected.network, original.routerAddress).use {
            MiniClient(it, "http://${original.routerAddress}:9001").wifiGetConfig()
        }
    }

    private fun joinMessage(outcome: WifiJoinOutcome): Int = when (outcome) {
        WifiJoinOutcome.SAVED -> R.string.wifi_on_saved
        WifiJoinOutcome.ALREADY_EXISTS -> R.string.wifi_on_already_exists
        WifiJoinOutcome.DECLINED -> R.string.wifi_on_declined
        WifiJoinOutcome.NOTIFICATION -> R.string.wifi_on_notification
        WifiJoinOutcome.UNAVAILABLE -> R.string.wifi_on_connect_failed
    }

    private fun openWifiOff() {
        secureFlow = false
        val connection = detectedConnection ?: return
        if (snapshot.state != MiniState.SetupPending || !networks.isPresent(connection)) return
        polling?.cancel()
        confirmedConnection = connection
        confirmedInterface = networks.interfaceName(connection)
        confirmationIsWifi = !networks.isEthernet(connection)
        screen = Screen.WifiOff
    }

    private fun confirmWifiOff() {
        if (screen != Screen.WifiOff || snapshot.state != MiniState.SetupPending) return
        val connection = confirmedConnection
        if (connection == null || !networks.isPresent(connection)) {
            wifiOffError = WifiOffError.CantReach
            screen = Screen.WifiOffError
            return
        }
        // Change screen synchronously: a second tap cannot launch a second write.
        screen = Screen.WifiOffProgress
        snapshot = MiniSnapshot(MiniState.NotFound)
        detectedConnection = null
        writing = scope.launch {
            try {
                val outcome = OkHttpTransport(connection.network, connection.routerAddress).use { transport ->
                    WifiOffFlow(
                        MiniClient(transport, "http://${connection.routerAddress}:9001"),
                        if (confirmationIsWifi) WifiOffConnection.Wifi else WifiOffConnection.Ethernet,
                        observe = { remaining ->
                            withTimeoutOrNull(remaining) { observeWifiOff(connection) }
                                ?: WifiOffObservation(
                                    originalWifiPresent = networks.isPresent(connection),
                                    routerAnswered = true, dishReachable = false,
                                )
                        },
                    ).confirm(MiniState.SetupPending)
                }
                when (outcome) {
                    is WifiOffOutcome.Success -> {
                        likelySuccess = outcome.likely
                        screen = Screen.WifiOffResult
                    }
                    is WifiOffOutcome.Failure -> {
                        wifiOffError = outcome.error
                        screen = Screen.WifiOffError
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                wifiOffError = WifiOffError.CantReach
                screen = Screen.WifiOffError
            }
        }
    }

    private suspend fun observeWifiOff(original: StarlinkConnection): WifiOffObservation {
        val present = networks.isPresent(original)
        if (confirmationIsWifi && !present) return WifiOffObservation(false, false, false)
        // Ethernet may be re-created by Android after DHCP changes. Never switch to Wi-Fi.
        val selected = if (present) original else networks.findEthernetOnInterface(confirmedInterface)
        if (selected == null) return WifiOffObservation(present, false, false)
        // Probe the pre-write router address, not the new Starlink upstream gateway.
        return OkHttpTransport(selected.network, original.routerAddress).use { transport ->
            val observation = probeWifiOff(transport, original.routerAddress, present, networks.ipv4Addresses(selected))
            observation.copy(originalWifiPresent = networks.isPresent(original))
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 || granted(Manifest.permission.ACCESS_LOCAL_NETWORK)

    private fun wifiPermissions(action: PermissionAction): List<String> =
        if (action == PermissionAction.Connect && Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // SSID identification still needs precise location, even on Android 13+.
            // We never derive, store, or transmit a physical location.
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requestAccess(action: PermissionAction) {
        if (requestingWifi) return
        pendingPermissionAction = action
        val needed = buildList {
            if (!hasLocalAccess()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            // A cable needs no Wi-Fi/location permission.
            if (action == PermissionAction.Connect || networks.findAvailable() == null) addAll(wifiPermissions(action))
        }.distinct().filterNot(::granted)
        if (needed.isNotEmpty()) {
            permissions.launch(needed.toTypedArray())
        } else {
            message = null
            if (action == PermissionAction.Connect) connectWifi() else startPolling(showProgress = true)
        }
    }

    private fun connectWifi() {
        polling?.cancel()
        connecting?.cancel()
        connecting = scope.launch {
            requestingWifi = true
            message = null
            try {
                val connection = networks.requestStarlink()
                if (connection == null) message = R.string.connection_failed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                permissionDenied = true
                message = R.string.permission_needed
            } catch (_: Exception) {
                message = R.string.connection_failed
            } finally {
                requestingWifi = false
                if (foreground) startPolling(showProgress = true)
            }
        }
    }

    private fun startPolling(showProgress: Boolean) {
        polling?.cancel()
        if (!foreground || requestingWifi || !hasLocalAccess() || screen != Screen.Home) return
        polling = scope.launch {
            checking = showProgress
            try {
                while (isActive) {
                    val connection = networks.findAvailable() ?: savedProfile?.let { networks.findWifi(it.name) }
                    val probe: suspend () -> MiniSnapshot = {
                        if (connection == null) {
                            detector.detect(null)
                        } else {
                            OkHttpTransport(connection.network, connection.routerAddress).use {
                                detector.detect(it, connection.routerAddress)
                            }
                        }
                    }
                    val remaining = detector.rebootTimeRemainingMillis
                    snapshot = if (remaining == null) probe() else {
                        withTimeoutOrNull(remaining) { probe() } ?: detector.detect(null)
                    }
                    detectedConnection = connection
                    checking = false
                    delay(minOf(MiniStateDetector.POLL_INTERVAL_MILLIS,
                        detector.rebootTimeRemainingMillis ?: MiniStateDetector.POLL_INTERVAL_MILLIS))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                permissionDenied = true
                message = R.string.permission_needed
            } catch (_: Exception) {
                message = R.string.connection_failed
            } finally {
                checking = false
            }
        }
    }
}
