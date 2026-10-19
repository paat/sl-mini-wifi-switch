package eu.tsernikov.slminiwifi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.BasicServiceSet
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.state.MiniDetails
import eu.tsernikov.slminiwifi.state.MiniSnapshot
import eu.tsernikov.slminiwifi.state.MiniState

/** Connection failures stay on the current screen. Offering settings means access is still missing, so that sentence replaces a cleared message. */
@Composable
internal fun ConnectionFeedback(
    @StringRes message: Int?,
    onPermissions: (() -> Unit)?,
    @StringRes permissionsLabel: Int = R.string.open_phone_settings,
) {
    val text = if (onPermissions != null) R.string.permission_needed else message
    text?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
    onPermissions?.let { PrimaryButton(permissionsLabel, it) }
}

/** All actions are delegated; these screens cannot issue protocol requests. */
@Composable
fun HomeScreen(
    snapshot: MiniSnapshot,
    isChecking: Boolean = false,
    isConnecting: Boolean = false,
    @StringRes message: Int? = null,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onDetails: () -> Unit,
    onSetupChoice: (wifiOff: Boolean) -> Unit,
    animateGuides: Boolean = true,
    onPermissions: (() -> Unit)? = null,
    @StringRes permissionsLabel: Int = R.string.open_phone_settings,
    onGuide: (() -> Unit)? = null,
    onShowCredentials: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    savedWifiName: String? = null,
) {
    Screen {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
        val state = snapshot.state
        val title = when (state) {
            MiniState.SetupPending -> R.string.ready_title
            MiniState.Unknown -> R.string.unknown_title
            MiniState.Configured -> R.string.configured_title
            MiniState.Bypass -> R.string.bypass_title
            MiniState.Rebooting -> R.string.rebooting_title
            MiniState.NotFound -> if (snapshot.probeTimedOut) R.string.check_timed_out_title else R.string.not_found_title
        }
        val body = when (state) {
            MiniState.SetupPending -> R.string.ready_body
            MiniState.Unknown -> R.string.unknown_body
            MiniState.Configured -> R.string.configured_body
            MiniState.Bypass -> R.string.bypass_body
            MiniState.Rebooting -> R.string.rebooting_body
            MiniState.NotFound -> if (snapshot.probeTimedOut) R.string.check_timed_out_body else R.string.not_found_body
        }
        val icon = when (state) {
            MiniState.SetupPending -> R.drawable.ic_mini_ready to R.string.ready_icon_description
            MiniState.Unknown -> R.drawable.ic_find to R.string.unknown_icon_description
            MiniState.Configured -> R.drawable.ic_mini_configured to R.string.configured_icon_description
            MiniState.Bypass -> R.drawable.ic_wifi_off to R.string.bypass_icon_description
            MiniState.Rebooting -> R.drawable.ic_waiting to R.string.rebooting_icon_description
            MiniState.NotFound -> R.drawable.ic_find to R.string.not_found_icon_description
        }
        Image(
            painterResource(icon.first), stringResource(icon.second),
            modifier = Modifier.size(56.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Heading(stringResource(title))
        Text(stringResource(body))
        if (state == MiniState.Configured) {
            WifiQuickStatus(snapshot.details.config, savedWifiName, onShowCredentials)
        } else {
            onShowCredentials?.let { SecondaryButton(R.string.wifi_on_home_password, it) }
        }
        if (state == MiniState.NotFound) onGuide?.let { PrimaryButton(R.string.field_start_here, it) }
        ConnectionFeedback(message, onPermissions, permissionsLabel)
        if (isChecking || isConnecting || state == MiniState.Rebooting) {
            if (state == MiniState.Rebooting) RestartIllustration(animateGuides)
            WaitingProgress(animateGuides)
            Text(stringResource(when {
                isConnecting -> R.string.connection_waiting
                state == MiniState.Rebooting -> R.string.rebooting_help
                else -> R.string.checking
            }))
            if (isConnecting) Text(stringResource(R.string.field_connect_longer))
            if (isChecking && !isConnecting && state != MiniState.Rebooting) {
                Text(stringResource(R.string.checking_help))
            }
        }
        when (state) {
            MiniState.SetupPending -> {
                PrimaryButton(R.string.wifi_off, { onSetupChoice(true) })
                PrimaryButton(R.string.wifi_secure, { onSetupChoice(false) })
                onGuide?.let { SecondaryButton(R.string.field_start_here, it) }
                SecondaryButton(R.string.details, onDetails)
                SecondaryButton(R.string.check_again, onRefresh, !isChecking)
            }
            MiniState.Configured, MiniState.Bypass, MiniState.Unknown -> {
                Text(stringResource(R.string.home_reset_note))
                onHelp?.let { PrimaryButton(R.string.home_reset_guide, it) }
                SecondaryButton(R.string.details, onDetails)
                SecondaryButton(R.string.check_again, onRefresh, !isChecking)
            }
            MiniState.Rebooting -> SecondaryButton(R.string.check_again, onRefresh, !isChecking)
            MiniState.NotFound -> {
                GuideCard {
                    Subheading(stringResource(R.string.cable_title))
                    CableIllustration(animateGuides)
                    Text(stringResource(R.string.cable_body))
                    PrimaryButton(R.string.check_again, onRefresh, !isChecking)
                }
                GuideCard {
                    Subheading(stringResource(R.string.wifi_title))
                    Text(stringResource(R.string.wifi_body))
                    ConnectionDialogIllustration()
                    PrimaryButton(R.string.join_starlink, onConnect, !isConnecting)
                }
            }
        }
        if (state != MiniState.Configured && state != MiniState.Bypass && state != MiniState.Unknown) {
            onHelp?.let { SecondaryButton(R.string.field_reset_help, it) }
        }
        Text(
            stringResource(R.string.read_only_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Only auth kinds reach this UI: the protocol parser discards masked passwords. */
@Composable
private fun WifiQuickStatus(config: WifiConfig?, savedWifiName: String?, onShowCredentials: (() -> Unit)?) {
    val services = config?.networks.orEmpty().flatMap { it.basicServiceSets }
    GuideCard {
        Subheading(stringResource(R.string.home_wifi_status))
        val radioStates = services.groupBy { it.band }.mapValues { (_, bands) ->
            bands.all { it.radioDisabled(config) }
        }
        val states = radioStates.values.distinct()
        if (states.size <= 1) {
            Value(R.string.field_wifi_list_title, states.singleOrNull()?.let {
                stringResource(if (it) R.string.radio_off else R.string.radio_on)
            })
        } else {
            for ((band, disabled) in radioStates) {
                val status = stringResource(if (disabled) R.string.radio_off else R.string.radio_on)
                when (band) {
                    2 -> Value(R.string.band_2ghz, status)
                    5 -> Value(R.string.band_5ghz, status)
                    6 -> Value(R.string.band_5ghz_high, status)
                    else -> Value(stringResource(R.string.band_other, band), status)
                }
            }
        }
        if (services.isEmpty()) {
            Value(R.string.wifi_on_name, null)
            Value(R.string.wifi_on_password, null)
        }
        for ((name, bands) in services.groupBy { it.ssid }) {
            Value(R.string.wifi_on_name, name)
            // Same rule as SetupViewModel.canShowHomeCredentials: every band secured, none unknown.
            // WPA2 with WPA2/WPA3 is still secured. Open mixed with secured is not.
            val line = homePasswordLine(bands.map { it.authKind })
            val showSaved = onShowCredentials?.takeIf {
                line == HomePasswordLine.Secured && name.isNotBlank() && name == savedWifiName
            }
            Value(R.string.wifi_on_password, stringResource(when (line) {
                HomePasswordLine.Open -> R.string.home_no_password
                HomePasswordLine.Secured -> if (showSaved != null) R.string.home_password_saved else R.string.home_password_hidden
                HomePasswordLine.Unavailable -> R.string.not_available
            }))
            if (showSaved != null) SecondaryButton(R.string.wifi_on_home_password, showSaved)
        }
    }
}

/** Per Wi-Fi name. Matches [eu.tsernikov.slminiwifi.SetupViewModel.canShowHomeCredentials] for the saved name. */
private enum class HomePasswordLine { Open, Secured, Unavailable }

private fun homePasswordLine(auths: List<AuthKind?>): HomePasswordLine = when {
    auths.any { it == null } -> HomePasswordLine.Unavailable
    auths.isNotEmpty() && auths.all { it == AuthKind.OPEN } -> HomePasswordLine.Open
    auths.isNotEmpty() && auths.all { it != null && it != AuthKind.OPEN } -> HomePasswordLine.Secured
    else -> HomePasswordLine.Unavailable
}

private fun BasicServiceSet.radioDisabled(config: WifiConfig?): Boolean = disable || when (band) {
    2 -> config?.disable2Ghz == true
    5 -> config?.disable5Ghz == true
    6 -> config?.disable5GhzHigh == true
    else -> false
}

@Composable
fun ComingSoonScreen(onBack: () -> Unit) {
    Screen {
        Heading(stringResource(R.string.wifi_secure))
        Text(stringResource(R.string.coming_soon), style = MaterialTheme.typography.bodyLarge)
        PrimaryButton(R.string.back, onBack)
    }
}

@Composable
fun DetailsScreen(details: MiniDetails, onBack: () -> Unit) {
    Screen {
        SecondaryButton(R.string.back, onBack)
        Heading(stringResource(R.string.details))
        Text(stringResource(R.string.details_last_readings))
        DeviceDetails(R.string.router_details, details.routerInfo)
        DeviceDetails(R.string.dish_details, details.dishInfo)
        GuideCard {
            Subheading(stringResource(R.string.network_details))
            Value(R.string.router_address, details.routerAddress)
            val addresses = details.config?.networks.orEmpty().map { it.ipv4 }.filter { it.isNotBlank() }
            Value(R.string.lan_address, addresses.joinToString("\n"))
            Value(R.string.wan_address, details.status?.ipv4WanAddress)
        }
        GuideCard {
            Subheading(stringResource(R.string.wifi_details))
            val bands = details.config?.networks.orEmpty().flatMap { it.basicServiceSets }
            if (bands.isEmpty()) Text(stringResource(R.string.not_available))
            for (band in bands) {
                Subheading(when (band.band) {
                    2 -> stringResource(R.string.band_2ghz)
                    5 -> stringResource(R.string.band_5ghz)
                    6 -> stringResource(R.string.band_5ghz_high)
                    else -> stringResource(R.string.band_other, band.band)
                })
                Value(R.string.ssid, band.ssid)
                Value(R.string.authentication, stringResource(when (band.authKind) {
                    AuthKind.OPEN -> R.string.auth_open
                    AuthKind.WPA2 -> R.string.auth_wpa2
                    AuthKind.WPA3 -> R.string.auth_wpa3
                    AuthKind.WPA2_WPA3 -> R.string.auth_wpa2_wpa3
                    null -> R.string.not_available
                }))
                val radioDisabled = band.radioDisabled(details.config)
                Value(R.string.radio_status, stringResource(if (radioDisabled) R.string.radio_off else R.string.radio_on))
            }
        }
        Text(stringResource(R.string.details_reset_note))
        Text(stringResource(R.string.details_direct_note))
        PrimaryButton(R.string.back, onBack)
    }
}

@Composable
private fun DeviceDetails(@StringRes title: Int, info: DeviceInfo?) {
    GuideCard {
        Subheading(stringResource(title))
        Value(R.string.hardware_version, info?.hardwareVersion)
        Value(R.string.software_version, info?.softwareVersion)
    }
}

@Composable
private fun Value(@StringRes label: Int, value: String?) = Value(stringResource(label), value)

@Composable
private fun Value(label: String, value: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.not_available))
    }
}

@Composable
internal fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun Heading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
}

@Composable
private fun Subheading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
internal fun PrimaryButton(@StringRes label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun SecondaryButton(@StringRes label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) { Text(stringResource(label), style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun GuideCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Original layered vectors: the adapter slides toward the phone's USB-C socket. */
@Composable
internal fun CableIllustration(animate: Boolean) {
    val description = stringResource(R.string.cable_description)
    val progress = if (rememberGuideAnimationsEnabled(animate)) {
        val transition = rememberInfiniteTransition(label = "cable insertion")
        val position by transition.animateFloat(
            initialValue = 0f, targetValue = -8f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "adapter position",
        )
        position
    } else -8f
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GuideImage(R.drawable.guide_cable)
        GuideImage(R.drawable.guide_adapter, Modifier.graphicsLayer { translationX = progress })
    }
}

@Composable
private fun GuideImage(@DrawableRes drawable: Int, modifier: Modifier = Modifier) {
    Image(
        painterResource(drawable), contentDescription = null,
        modifier = modifier.fillMaxWidth().heightIn(min = 144.dp),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

/** A drawn example, with translated text; no image from Android or Starlink is used. */
@Composable
internal fun ConnectionDialogIllustration(networkName: String? = null) {
    val description = if (networkName == null) stringResource(R.string.wifi_description)
        else stringResource(R.string.wifi_on_connect_description, networkName)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painterResource(R.drawable.guide_wifi), contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                )
                Text(stringResource(R.string.wifi_dialog_title), style = MaterialTheme.typography.titleMedium)
                Text(networkName ?: stringResource(R.string.starlink_network))
                // Illustration only, not a second interactive Connect action.
                Text(
                    stringResource(R.string.connect), modifier = Modifier.align(Alignment.End),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(stringResource(R.string.wifi_dialog_caption), style = MaterialTheme.typography.bodySmall)
    }
}
