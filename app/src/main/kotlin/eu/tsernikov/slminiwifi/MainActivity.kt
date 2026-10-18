package eu.tsernikov.slminiwifi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.WifiRules
import eu.tsernikov.slminiwifi.net.AndroidWifiAutoConnect
import eu.tsernikov.slminiwifi.net.WifiAutoConnect
import eu.tsernikov.slminiwifi.state.MiniState
import eu.tsernikov.slminiwifi.state.WifiOffError
import eu.tsernikov.slminiwifi.ui.DetailsScreen
import eu.tsernikov.slminiwifi.ui.GuideStep
import eu.tsernikov.slminiwifi.ui.GuideStepScreen
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

import androidx.lifecycle.ViewModelProvider
import eu.tsernikov.slminiwifi.SetupViewModel.Screen
import eu.tsernikov.slminiwifi.SetupViewModel.PermissionAction

/** Activity owns system dialogs; setup state and operations belong to the retained model. */
class MainActivity : ComponentActivity() {
    internal lateinit var model: SetupViewModel
    private lateinit var autoConnect: WifiAutoConnect
    private val saveNetwork = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.joinMessage = model.joinMessage(autoConnect.activityResult(result.resultCode, result.data))
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SetupViewModel::class.java]
        model.restore(savedInstanceState)
        handleSetupLaunch(intent)
        autoConnect = AndroidWifiAutoConnect(this, model::releaseConnection) { saveNetwork.launch(it) }
        enableEdgeToEdge()
        setContent {
            val pending = model.permissionRequest
            androidx.compose.runtime.LaunchedEffect(pending) {
                if (pending != null) {
                    model.permissionRequest = null
                    permissions.launch(pending.toTypedArray())
                }
            }
            with(model) {
                val reconnectSetup: (() -> Unit)? = if (!canConfirm && !checking && !requestingWifi) {
                    { requestAccess(PermissionAction.Connect) }
                } else null
                MiniTheme {
                    BackHandler(enabled = screen != Screen.Home) {
                        if (screen != Screen.WifiOffProgress && screen != Screen.WifiOnProgress) {
                            when {
                                screen == Screen.Details && detailsReturn != Screen.Home -> screen = detailsReturn
                                screen == Screen.WifiOnConnect -> screen = Screen.WifiOnResult
                                screen == Screen.WifiOnConfirm -> screen = Screen.WifiOn
                                screen == Screen.FieldGuide -> guideBack()
                                screen == Screen.ResetGuide -> closeHelp()
                                else -> returnHome()
                            }
                        }
                    }
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            snapshot = snapshot,
                            onShowCredentials = if (savedProfile != null) ::showSavedPassword else null,
                            isChecking = checking,
                            isConnecting = requestingWifi,
                            message = message,
                            onRefresh = { requestAccess(PermissionAction.Check) },
                            onGuide = ::openGuide,
                            onHelp = ::openHelp,
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
                        Screen.Details -> DetailsScreen(lastDetails) {
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
                            onBack = ::returnHome, message = profileError, onHelp = ::openHelp,
                            onReconnect = reconnectSetup, canConfirm = canConfirm,
                        )
                        Screen.WifiOnConfirm -> WifiOnConfirmScreen(profile.name, ::confirmWifiOn, onCancel = { screen = Screen.WifiOn }, canConfirm = canConfirm, onReconnect = reconnectSetup)
                        Screen.WifiOnProgress -> WifiOnProgressScreen(onReconnect = if (reconnectAvailable) ::reconnectForVerification else null)
                        Screen.WifiOnResult -> WifiOnResultScreen(profile,
                            onConnect = { screen = Screen.WifiOnConnect },
                            onDetails = if (hasDetails) ({ detailsReturn = Screen.WifiOnResult; screen = Screen.Details }) else null,
                            onDone = ::returnHome, connectionMessage = joinMessage, verified = wifiVerified, onHelp = ::openHelp)
                        Screen.WifiOnConnect -> WifiOnAutoConnectScreen(Build.VERSION.SDK_INT == 29,
                            onContinue = {
                                screen = Screen.WifiOnResult
                                joinMessage = autoConnect.connect(profile.name, profile.password)?.let(::joinMessage)
                            }, onBack = { screen = Screen.WifiOnResult })
                        Screen.WifiOff -> WifiOffConfirmScreen(confirmationIsWifi, ::confirmWifiOff, ::returnHome, canConfirm = canConfirm, onReconnect = reconnectSetup)
                        Screen.WifiOffProgress -> WifiOffProgressScreen()
                        Screen.WifiOffResult -> WifiOffResultScreen(likelySuccess,
                            onDetails = if (hasDetails) ({ detailsReturn = Screen.WifiOffResult; screen = Screen.Details }) else null,
                            onDone = ::returnHome)
                        Screen.WifiOffError -> WifiOffErrorScreen(wifiOffError,
                            onReconnect = if (secureFlow && reconnectAvailable) ::reconnectForVerification else null,
                            onHelp = ::openHelp,
                            unreachableBody = if (secureFlow) R.string.wifi_on_unreachable_body else R.string.wifi_off_unreachable_body,
                            onShowCredentials = if (secureFlow && profileSaved && savedProfile != null && wifiOffError == WifiOffError.CantReach) ({
                                profile = savedProfile!!
                                wifiVerified = false
                                screen = Screen.WifiOnResult
                            }) else null,
                            onNext = {
                            if (wifiOffError == WifiOffError.AlreadySetUp) openHelp() else returnHome()
                        })
                        Screen.ResetGuide -> ResetGuideScreen(onDone = ::checkAfterHelp, onBack = ::closeHelp)
                        Screen.FieldGuide -> GuideStepScreen(
                            step = guideStep, onBack = ::guideBack, onNext = ::guideNext,
                            onUseWifi = { guideStep = GuideStep.Wifi },
                            onUseCable = { guideStep = GuideStep.Cable },
                            onConnect = {
                                screen = Screen.Home
                                requestAccess(PermissionAction.Connect)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupLaunch(intent)
    }

    private fun handleSetupLaunch(intent: Intent) {
        if (intent.action == SetupLaunch.ACTION) {
            intent.action = null
            model.openFromQuickLaunch()
        }
    }

    override fun onStart() {
        super.onStart()
        model.start()
    }

    override fun onStop() {
        model.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.saveState(outState)
        super.onSaveInstanceState(outState)
    }
}
