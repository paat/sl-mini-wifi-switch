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
import eu.tsernikov.slminiwifi.net.OkHttpTransport
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.net.StarlinkNetworks
import eu.tsernikov.slminiwifi.protocol.MiniClient
import eu.tsernikov.slminiwifi.state.WifiOffConnection
import eu.tsernikov.slminiwifi.state.WifiOffError
import eu.tsernikov.slminiwifi.state.WifiOffFlow
import eu.tsernikov.slminiwifi.state.WifiOffObservation
import eu.tsernikov.slminiwifi.state.WifiOffOutcome
import eu.tsernikov.slminiwifi.state.probeWifiOff
import eu.tsernikov.slminiwifi.ui.WifiOffConfirmScreen
import eu.tsernikov.slminiwifi.ui.WifiOffProgressScreen
import eu.tsernikov.slminiwifi.ui.WifiOffResultScreen
import eu.tsernikov.slminiwifi.ui.WifiOffErrorScreen
import eu.tsernikov.slminiwifi.ui.ResetGuideScreen
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.MiniStateDetector
import eu.tsernikov.slminiwifi.ui.ComingSoonScreen
import eu.tsernikov.slminiwifi.ui.DetailsScreen
import eu.tsernikov.slminiwifi.ui.HomeScreen
import eu.tsernikov.slminiwifi.ui.MiniTheme
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
    private enum class Screen { Home, Details, WifiOff, WifiOffProgress, WifiOffResult, WifiOffError, ResetGuide, WifiOn }
    private enum class PermissionAction { Check, Connect }

    private lateinit var networks: StarlinkNetworks
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
            }
        } else {
            permissionDenied = true
            message = R.string.permission_needed
            // Ethernet is still usable if only the Wi-Fi identification permission was refused.
            startPolling(showProgress = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networks = StarlinkNetworks(this)
        screen = savedInstanceState?.getString("screen")?.let { name ->
            Screen.entries.firstOrNull { it.name == name }
        } ?: Screen.Home
        wifiOffError = savedInstanceState?.getString("wifiOffError")?.let { name ->
            WifiOffError.entries.firstOrNull { it.name == name }
        } ?: WifiOffError.CantReach
        // Never restore a send or stale confirmation after process/activity recreation.
        if (screen == Screen.WifiOff || screen == Screen.WifiOffProgress) {
            screen = Screen.WifiOffError
            wifiOffError = WifiOffError.CantReach
        }
        likelySuccess = savedInstanceState?.getBoolean("likelySuccess") ?: false
        detailsReturn = if (savedInstanceState?.getBoolean("detailsFromResult") == true) {
            Screen.WifiOffResult
        } else Screen.Home
        enableEdgeToEdge()
        setContent {
            MiniTheme {
                BackHandler(enabled = screen != Screen.Home) {
                    if (screen != Screen.WifiOffProgress) {
                        if (screen == Screen.Details && detailsReturn == Screen.WifiOffResult) {
                            screen = Screen.WifiOffResult
                        } else returnHome()
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
                                if (wifiOff) openWifiOff() else screen = Screen.WifiOn
                            }
                        },
                        onPermissions = if (permissionDenied) ({
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) else null,
                    )
                    Screen.Details -> DetailsScreen(snapshot.details) {
                        if (detailsReturn == Screen.WifiOffResult) screen = Screen.WifiOffResult else returnHome()
                    }
                    Screen.WifiOn -> ComingSoonScreen { returnHome() }
                    Screen.WifiOff -> WifiOffConfirmScreen(confirmationIsWifi, ::confirmWifiOff, ::returnHome)
                    Screen.WifiOffProgress -> WifiOffProgressScreen()
                    Screen.WifiOffResult -> WifiOffResultScreen(likelySuccess,
                        onDetails = { detailsReturn = Screen.WifiOffResult; screen = Screen.Details },
                        onDone = ::returnHome)
                    Screen.WifiOffError -> WifiOffErrorScreen(wifiOffError, onNext = {
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
        networks.releaseRequest()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        networks.close()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("wifiOffError", wifiOffError.name)
        outState.putBoolean("likelySuccess", likelySuccess)
        outState.putBoolean("detailsFromResult", detailsReturn == Screen.WifiOffResult)
        super.onSaveInstanceState(outState)
    }

    private fun returnHome() {
        confirmedConnection = null
        screen = Screen.Home
        startPolling(showProgress = true)
    }

    private fun openWifiOff() {
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
                    val connection = networks.findAvailable()
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
