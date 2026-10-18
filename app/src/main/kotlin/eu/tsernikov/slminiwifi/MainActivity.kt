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
import eu.tsernikov.slminiwifi.net.StarlinkNetworks
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

/** Owns only foreground, app-local connections. Every Mini operation is a read. */
class MainActivity : ComponentActivity() {
    private enum class Screen { Home, Details, WifiOff, WifiOn }
    private enum class PermissionAction { Check, Connect }

    private lateinit var networks: StarlinkNetworks
    private val detector = MiniStateDetector()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var connecting: Job? = null
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
        enableEdgeToEdge()
        setContent {
            MiniTheme {
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (screen) {
                    Screen.Home -> HomeScreen(
                        snapshot = snapshot,
                        isChecking = checking,
                        isConnecting = requestingWifi,
                        message = message,
                        onRefresh = { requestAccess(PermissionAction.Check) },
                        onConnect = { requestAccess(PermissionAction.Connect) },
                        onDetails = { screen = Screen.Details },
                        onSetupChoice = { wifiOff -> screen = if (wifiOff) Screen.WifiOff else Screen.WifiOn },
                        onPermissions = if (permissionDenied) ({
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }) else null,
                    )
                    Screen.Details -> DetailsScreen(snapshot.details) { screen = Screen.Home }
                    Screen.WifiOff, Screen.WifiOn -> ComingSoonScreen(screen == Screen.WifiOff) { screen = Screen.Home }
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
        super.onSaveInstanceState(outState)
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
        if (!foreground || requestingWifi || !hasLocalAccess()) return
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
