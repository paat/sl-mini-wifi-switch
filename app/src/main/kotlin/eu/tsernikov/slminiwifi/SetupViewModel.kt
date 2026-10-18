package eu.tsernikov.slminiwifi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.tsernikov.slminiwifi.data.PasswordGenerator
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.WifiRules
import eu.tsernikov.slminiwifi.data.createProfileStore
import eu.tsernikov.slminiwifi.net.OkHttpTransport
import eu.tsernikov.slminiwifi.net.StarlinkConnection
import eu.tsernikov.slminiwifi.net.StarlinkNetworks
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
import eu.tsernikov.slminiwifi.ui.GuideStep
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext

/** Retains confirmed operations and their read-only verification across configuration changes. */
internal class SetupViewModel(application: Application) : AndroidViewModel(application) {
    enum class Screen {
        Home, Details, WifiOff, WifiOffProgress, WifiOffResult, WifiOffError, ResetGuide, FieldGuide,
        WifiOn, WifiOnConfirm, WifiOnProgress, WifiOnResult, WifiOnConnect,
    }
    enum class PermissionAction { Check, Connect, SecureWifi }

    val networks = StarlinkNetworks(application)
    internal var transportFactory: (StarlinkConnection, () -> Unit) -> eu.tsernikov.slminiwifi.protocol.Transport = { connection, onWritten ->
        OkHttpTransport(connection.network, connection.routerAddress, onRequestWritten = onWritten)
    }

    private suspend fun <T> withTransport(connection: StarlinkConnection, onWritten: () -> Unit = {},
        block: suspend (eu.tsernikov.slminiwifi.protocol.Transport) -> T): T {
        val transport = transportFactory(connection, onWritten)
        try { return block(transport) } finally { (transport as? java.io.Closeable)?.close() }
    }
    var profiles = createProfileStore(application)
    val generator = PasswordGenerator()
    var profile by mutableStateOf(Profile(generator.name(), generator.password()))
    var savedProfile by mutableStateOf<Profile?>(null)
    var ownPassword by mutableStateOf(false)
    var profileSaved by mutableStateOf(false)
    var wifiVerified by mutableStateOf(false)
    var profileError by mutableStateOf<Int?>(null)
    var joinMessage by mutableStateOf<Int?>(null)
    var secureConnection: StarlinkConnection? = null
    var secureRequestAttempted = false
    var reconnectAvailable by mutableStateOf(false)
    val isWriting get() = screen == Screen.WifiOffProgress || screen == Screen.WifiOnProgress
    val canConfirm get() = !checking && confirmedConnection != null
    var lastDetails by mutableStateOf(eu.tsernikov.slminiwifi.state.MiniDetails())
    val hasDetails get() = lastDetails.routerInfo != null || lastDetails.dishInfo != null ||
        lastDetails.config != null || lastDetails.status != null
    var secureFlow by mutableStateOf(false)
    val detector = MiniStateDetector()
    val scope = viewModelScope
    var polling: Job? = null
    var connecting: Job? = null
    var writing: Job? = null
    var detectedConnection: StarlinkConnection? = null
    var confirmedConnection by mutableStateOf<StarlinkConnection?>(null)
    var confirmedInterface: String? = null
    var confirmationIsWifi by mutableStateOf(false)
    var wifiOffError by mutableStateOf(WifiOffError.CantReach)
    var likelySuccess by mutableStateOf(false)
    var detailsReturn = Screen.Home
    var helpReturn = Screen.Home
    var guideStep by mutableStateOf(GuideStep.Reset)
    var guideCheckBack = GuideStep.Cable
    var foreground = false
    var pendingPermissionAction = PermissionAction.Check
    var snapshot by mutableStateOf(MiniSnapshot(MiniState.NotFound))
    var screen by mutableStateOf(Screen.Home)
    var checking by mutableStateOf(false)
    var requestingWifi by mutableStateOf(false)
    var message by mutableStateOf<Int?>(null)
    var permissionDenied by mutableStateOf(false)


    var permissionRequest by mutableStateOf<List<String>?>(null)
    var initialized = false

    fun restore(savedInstanceState: Bundle?) {
        if (initialized) return
        initialized = true
        savedProfile = try { profiles.load() } catch (_: Exception) { null }
        screen = savedInstanceState?.getString("screen")?.let { name ->
            Screen.entries.firstOrNull { it.name == name }
        } ?: Screen.Home
        wifiOffError = savedInstanceState?.getString("wifiOffError")?.let { name ->
            WifiOffError.entries.firstOrNull { it.name == name }
        } ?: WifiOffError.CantReach
        if (screen == Screen.WifiOffProgress || screen == Screen.WifiOnProgress) {
            screen = Screen.WifiOffError
            wifiOffError = WifiOffError.CantReach
        }
        if (screen == Screen.WifiOn || screen == Screen.WifiOnConfirm) screen = Screen.WifiOn
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
        guideStep = savedInstanceState?.getString("guideStep")?.let { name ->
            GuideStep.entries.firstOrNull { it.name == name }
        } ?: GuideStep.Reset
        guideCheckBack = if (savedInstanceState?.getString("guideCheckBack") == GuideStep.AndroidConnect.name) {
            GuideStep.AndroidConnect
        } else GuideStep.Cable
        restoreHelpReturn(savedInstanceState?.getString("helpReturn"))
    }

    fun permissionResult(result: Map<String, Boolean>) {
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

    fun saveState(outState: Bundle) {
        outState.putString("guideStep", guideStep.name)
        outState.putString("guideCheckBack", guideCheckBack.name)
        outState.putString("helpReturn", helpReturn.name)
        outState.putBoolean("secureFlow", secureFlow)
        outState.putBoolean("profileSaved", profileSaved)
        outState.putBoolean("wifiVerified", wifiVerified)
        outState.putString("screen", screen.name)
        outState.putString("wifiOffError", wifiOffError.name)
        outState.putBoolean("likelySuccess", likelySuccess)
        outState.putString("detailsReturn", detailsReturn.name)
    }

    fun start() {
        foreground = true
        if (hasLocalAccess()) {
            if (permissionDenied && wifiPermissions(pendingPermissionAction).all(::granted)) {
                permissionDenied = false
                message = null
            }
            if (screen == Screen.WifiOff || screen == Screen.WifiOnConfirm || screen == Screen.WifiOn) {
                reacquireConfirmation()
            } else startPolling(showProgress = true)
        } else message = R.string.no_network_permission
    }

    fun stop() {
        foreground = false
        polling?.cancel()
        if (screen == Screen.WifiOffProgress || screen == Screen.WifiOnProgress) return
        connecting?.cancel()
        releaseConnection()
        if (screen == Screen.WifiOff || screen == Screen.WifiOn || screen == Screen.WifiOnConfirm) {
            confirmedConnection = null
        }
    }

    fun releaseConnection() {
        networks.releaseRequest()
        detector.clearHistory()
    }

    override fun onCleared() {
        networks.close()
    }

    fun openGuide() {
        screen = Screen.FieldGuide
        polling?.cancel()
        connecting?.cancel()
        confirmedConnection = null
        guideStep = GuideStep.Reset
    }

    fun guideNext() {
        guideStep = when (guideStep) {
            GuideStep.Reset -> GuideStep.Light
            GuideStep.Light -> GuideStep.Cable
            GuideStep.Cable -> { guideCheckBack = GuideStep.Cable; GuideStep.Check }
            GuideStep.Wifi -> GuideStep.AndroidConnect
            GuideStep.AndroidConnect -> { guideCheckBack = GuideStep.AndroidConnect; GuideStep.Check }
            GuideStep.Check -> { returnHome(); return }
        }
    }

    fun guideBack() {
        guideStep = when (guideStep) {
            GuideStep.Reset -> { returnHome(); return }
            GuideStep.Light -> GuideStep.Reset
            GuideStep.Cable -> GuideStep.Light
            GuideStep.Wifi -> GuideStep.Cable
            GuideStep.AndroidConnect -> GuideStep.Wifi
            GuideStep.Check -> guideCheckBack
        }
    }

    fun openHelp() {
        helpReturn = screen
        screen = Screen.ResetGuide
        polling?.cancel()
        connecting?.cancel()
    }

    fun restoreHelpReturn(name: String?) {
        helpReturn = when (name) {
            Screen.WifiOffError.name -> Screen.WifiOffError
            Screen.WifiOnResult.name -> if (profileSaved && savedProfile != null) {
                profile = savedProfile!!
                Screen.WifiOnResult
            } else Screen.Home
            else -> Screen.Home
        }
    }

    fun closeHelp() {
        screen = helpReturn
        if (screen == Screen.Home) startPolling(showProgress = true)
    }

    fun returnHome() {
        connecting?.cancel()
        confirmedConnection = null
        screen = Screen.Home
        startPolling(showProgress = true)
    }

    fun openWifiOn() {
        secureFlow = true
        val connection = detectedConnection ?: return
        if (snapshot.state != MiniState.SetupPending || !networks.isPresent(connection)) return
        polling?.cancel()
        confirmedConnection = connection
        confirmedInterface = networks.interfaceName(connection)
        confirmationIsWifi = !networks.isEthernet(connection)
        secureConnection = null
        secureRequestAttempted = false
        reconnectAvailable = false
        profileSaved = false
        wifiVerified = false
        profile = Profile(generator.name(), generator.password())
        ownPassword = false
        profileError = null
        joinMessage = null
        screen = Screen.WifiOn
    }

    fun confirmWifiOn() {
        if (!canConfirm || screen != Screen.WifiOnConfirm || snapshot.state != MiniState.SetupPending || !WifiRules.isValid(profile)) return
        val connection = confirmedConnection
        if (connection == null || !networks.isPresent(connection)) {
            reacquireConfirmation()
            return
        }
        val missing = if (confirmationIsWifi) wifiPermissions(PermissionAction.Connect).filterNot(::granted) else emptyList()
        if (missing.isNotEmpty()) {
            pendingPermissionAction = PermissionAction.SecureWifi
            permissionRequest = missing
            return
        }
        val confirmedProfile = profile
        // Claim the tap before suspending; saving is encrypted and runs off the UI thread.
        screen = Screen.WifiOnProgress
        snapshot = MiniSnapshot(MiniState.NotFound)
        detectedConnection = null
        writing = scope.launch {
            try {
                profileSaved = saveProfile(confirmedProfile)
                if (!profileSaved) {
                    profileError = R.string.wifi_on_store_before_setup_failed
                    screen = Screen.WifiOn
                    return@launch
                }
                savedProfile = confirmedProfile
                profileError = null
                val outcome = withTransport(connection) { transport ->
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
                        releaseConnection()
                        screen = Screen.WifiOnConnect
                    }
                    is WifiOnOutcome.Failure -> {
                        releaseConnection()
                        wifiOffError = outcome.error
                        screen = Screen.WifiOffError
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                releaseConnection()
                wifiOffError = WifiOffError.CantReach
                screen = Screen.WifiOffError
            }
        }
    }

    suspend fun observeWifiOn(original: StarlinkConnection, credentials: Profile): ClientResult {
        val selected = if (!confirmationIsWifi) {
            if (networks.isPresent(original)) original else networks.findEthernetOnInterface(confirmedInterface)
        } else {
            if (networks.isPresent(original) && !secureRequestAttempted) original else {
                if (!secureRequestAttempted) {
                    secureRequestAttempted = true
                    try {
                        secureConnection = networks.requestSecureWifi(credentials.name, credentials.password)
                    } finally { reconnectAvailable = secureConnection == null }
                }
                secureConnection?.takeIf(networks::isPresent) ?: networks.findWifi(credentials.name)
            }
        } ?: return ClientResult.TransportError(IOException("Mini connection unavailable"))
        return withTransport(selected.copy(routerAddress = original.routerAddress)) {
            MiniClient(it, "http://${original.routerAddress}:9001").wifiGetConfig().also { result ->
                (result as? ClientResult.Ok)?.response?.wifiGetConfig?.let { config ->
                    lastDetails = lastDetails.copy(config = config, routerAddress = original.routerAddress)
                }
            }
        }
    }

    fun joinMessage(outcome: WifiJoinOutcome): Int = when (outcome) {
        WifiJoinOutcome.SAVED -> R.string.wifi_on_saved
        WifiJoinOutcome.ALREADY_EXISTS -> R.string.wifi_on_already_exists
        WifiJoinOutcome.DECLINED -> R.string.wifi_on_declined
        WifiJoinOutcome.NOTIFICATION -> R.string.wifi_on_notification
        WifiJoinOutcome.UNAVAILABLE -> R.string.wifi_on_connect_failed
    }

    fun openWifiOff() {
        secureFlow = false
        val connection = detectedConnection ?: return
        if (snapshot.state != MiniState.SetupPending || !networks.isPresent(connection)) return
        polling?.cancel()
        confirmedConnection = connection
        confirmedInterface = networks.interfaceName(connection)
        confirmationIsWifi = !networks.isEthernet(connection)
        screen = Screen.WifiOff
    }

    fun confirmWifiOff() {
        if (!canConfirm || screen != Screen.WifiOff || snapshot.state != MiniState.SetupPending) return
        val connection = confirmedConnection
        if (connection == null || !networks.isPresent(connection)) {
            reacquireConfirmation()
            return
        }
        // Change screen synchronously: a second tap cannot launch a second write.
        screen = Screen.WifiOffProgress
        snapshot = MiniSnapshot(MiniState.NotFound)
        detectedConnection = null
        writing = scope.launch {
            try {
                val requestWritten = java.util.concurrent.atomic.AtomicBoolean(false)
                val outcome = withTransport(connection, { requestWritten.set(true) }) { transport ->
                    WifiOffFlow(
                        MiniClient(transport, "http://${connection.routerAddress}:9001"),
                        if (confirmationIsWifi) WifiOffConnection.Wifi else WifiOffConnection.Ethernet,
                        requestWritten = requestWritten::get,
                        observe = { remaining ->
                            withTimeoutOrNull(remaining) { observeWifiOff(connection) }
                                ?: WifiOffObservation(
                                    originalWifiPresent = networks.isPresent(connection) || networks.findWifi("STARLINK") != null,
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

    suspend fun observeWifiOff(original: StarlinkConnection): WifiOffObservation {
        val replacement = if (confirmationIsWifi) networks.findWifi("STARLINK") else null
        val present = networks.isPresent(original) || replacement != null
        if (confirmationIsWifi && !present) return WifiOffObservation(false, false, false)
        // Ethernet may be re-created by Android after DHCP changes. Never switch to Wi-Fi.
        val selected = if (networks.isPresent(original)) original else replacement ?: networks.findEthernetOnInterface(confirmedInterface)
        if (selected == null) return WifiOffObservation(present, false, false)
        // Probe the pre-write router address, not the new Starlink upstream gateway.
        return withTransport(selected.copy(routerAddress = original.routerAddress)) { transport ->
            val observation = probeWifiOff(transport, original.routerAddress, present, networks.ipv4Addresses(selected))
            lastDetails = lastDetails.copy(
                dishInfo = observation.dishInfo ?: lastDetails.dishInfo,
                routerInfo = observation.routerInfo ?: lastDetails.routerInfo,
            )
            observation.copy(originalWifiPresent = networks.isPresent(original) || networks.findWifi("STARLINK") != null)
        }
    }

    fun granted(permission: String) = getApplication<Application>().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 || granted(Manifest.permission.ACCESS_LOCAL_NETWORK)

    fun wifiPermissions(action: PermissionAction): List<String> =
        if (action != PermissionAction.Check && Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            // SSID identification still needs precise location, even on Android 13+.
            // We never derive, store, or transmit a physical location.
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun requestAccess(action: PermissionAction) {
        if (requestingWifi) return
        pendingPermissionAction = action
        val needed = buildList {
            if (!hasLocalAccess()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            // A cable needs no Wi-Fi/location permission.
            if (action == PermissionAction.Connect || networks.findAvailable() == null) addAll(wifiPermissions(action))
        }.distinct().filterNot(::granted)
        if (needed.isNotEmpty()) {
            permissionRequest = needed
        } else {
            message = null
            if (action == PermissionAction.Connect) connectWifi() else startPolling(showProgress = true)
        }
    }

    fun connectWifi() {
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

    suspend fun saveProfile(value: Profile): Boolean = withContext(Dispatchers.IO) {
        try { profiles.save(value); true } catch (_: Exception) { false }
    }

    fun rememberDetails(value: MiniSnapshot) {
        val details = value.details
        lastDetails = lastDetails.copy(
            routerInfo = details.routerInfo ?: lastDetails.routerInfo,
            dishInfo = details.dishInfo ?: lastDetails.dishInfo,
            config = details.config ?: lastDetails.config,
            status = details.status ?: lastDetails.status,
            routerAddress = details.routerAddress,
        )
    }

    fun showSavedPassword() {
        val saved = savedProfile ?: return
        connecting?.cancel()
        polling?.cancel()
        profile = saved
        profileSaved = true
        // A stored profile is not evidence that a Mini currently uses it.
        wifiVerified = false
        screen = Screen.WifiOnResult
    }

    suspend fun detectAvailable(): Pair<StarlinkConnection?, MiniSnapshot> {
        val candidates = networks.findCandidates().toMutableList()
        savedProfile?.let { saved ->
            networks.findVerifiedSavedWifi(saved.name)?.let { if (it !in candidates) candidates.add(it) }
        }
        if (candidates.isEmpty()) return null to detector.detect(null)
        var result = MiniSnapshot(MiniState.NotFound)
        for ((index, connection) in candidates.withIndex()) {
            result = withTransport(connection) {
                detector.detect(it, connection.routerAddress,
                    ipv4Addresses = if (networks.isEthernet(connection)) networks.ipv4Addresses(connection) else emptyList(),
                    recordMiss = index == candidates.lastIndex)
            }
            if (result.state == MiniState.SetupPending || result.state == MiniState.Configured ||
                result.state == MiniState.Bypass) return connection to result
        }
        return null to result
    }

    fun reacquireConfirmation() {
        connecting?.cancel()
        confirmedConnection = null
        checking = true
        connecting = scope.launch {
            try {
                if (confirmationIsWifi && networks.findWifi("STARLINK") == null) networks.requestStarlink()
                val (connection, result) = detectAvailable()
                snapshot = result
                rememberDetails(result)
                if (connection != null && result.state == MiniState.SetupPending) {
                    confirmedConnection = connection
                    confirmedInterface = networks.interfaceName(connection)
                    confirmationIsWifi = !networks.isEthernet(connection)
                } else {
                    screen = Screen.Home
                    message = if (result.state == MiniState.NotFound) R.string.connection_failed else null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                screen = Screen.Home
                message = R.string.connection_failed
            } finally { checking = false }
        }
    }

    /** A declined Android Connect dialog can be offered again, without resending setup. */
    fun reconnectForVerification() {
        if (!reconnectAvailable) return
        secureRequestAttempted = false
        reconnectAvailable = false
        if (screen == Screen.WifiOnProgress) return
        val original = confirmedConnection ?: return
        val credentials = savedProfile ?: return
        screen = Screen.WifiOnProgress
        writing = scope.launch {
            try {
                val outcome = withTransport(original) { transport ->
                    WifiOnFlow(MiniClient(transport, "http://${original.routerAddress}:9001"),
                        observe = { remaining ->
                            withTimeoutOrNull(remaining) { observeWifiOn(original, credentials) }
                                ?: ClientResult.TransportError(IOException("Mini verification timed out"))
                        }).verify()
                }
                if (outcome == WifiOnOutcome.Success) {
                    wifiVerified = true
                    screen = Screen.WifiOnConnect
                } else {
                    wifiOffError = (outcome as WifiOnOutcome.Failure).error
                    screen = Screen.WifiOffError
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                wifiOffError = WifiOffError.CantReach
                screen = Screen.WifiOffError
            } finally { releaseConnection() }
        }
    }

    fun startPolling(showProgress: Boolean) {
        polling?.cancel()
        if (!foreground || requestingWifi || !hasLocalAccess() || screen != Screen.Home) return
        polling = scope.launch {
            checking = showProgress
            try {
                while (isActive) {
                    val remaining = detector.rebootTimeRemainingMillis
                    val detected = if (remaining == null) detectAvailable() else {
                        withTimeoutOrNull(remaining) { detectAvailable() }
                            ?: (null to detector.probeTimedOut())
                    }
                    detectedConnection = detected.first
                    snapshot = detected.second
                    rememberDetails(snapshot)
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
