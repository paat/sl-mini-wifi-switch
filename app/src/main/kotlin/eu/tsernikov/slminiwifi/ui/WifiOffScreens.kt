package eu.tsernikov.slminiwifi.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.state.WifiOffError

/** Presentation only: the sole write action is delegated by the confirmation button. */
@Composable
fun WifiOffConfirmScreen(
    isWifi: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    animateGuides: Boolean = true,
) {
    Screen {
        Heading(stringResource(R.string.wifi_off_confirm_title))
        Text(stringResource(R.string.wifi_off_confirm_body))
        RouterIllustration(animateGuides)
        Text(stringResource(R.string.wifi_off_undo))
        if (isWifi) Text(stringResource(R.string.wifi_off_disconnect_warning))
        PrimaryButton(R.string.wifi_off_confirm, onConfirm)
        SecondaryButton(R.string.cancel, onCancel)
    }
}

@Composable
fun WifiOffProgressScreen() {
    Screen {
        Image(
            painterResource(R.drawable.ic_waiting), stringResource(R.string.rebooting_icon_description),
            Modifier.size(56.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Heading(stringResource(R.string.wifi_off_progress_title))
        Text(stringResource(R.string.wifi_off_progress_body))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.wifi_off_progress_time))
        Text(stringResource(R.string.wifi_off_progress_help))
    }
}

@Composable
fun WifiOffResultScreen(
    likely: Boolean,
    onDetails: () -> Unit,
    onDone: () -> Unit,
    animateGuides: Boolean = true,
) {
    Screen {
        Image(
            painterResource(R.drawable.ic_wifi_off), stringResource(R.string.bypass_icon_description),
            Modifier.size(56.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Heading(stringResource(R.string.wifi_off_result_title))
        if (likely) Text(stringResource(R.string.wifi_off_likely))
        Text(stringResource(R.string.wifi_off_router_instruction))
        RouterIllustration(animateGuides)
        Text(stringResource(R.string.wifi_off_port_help))
        Text(stringResource(R.string.wifi_off_undo))
        PrimaryButton(R.string.done, onDone)
        SecondaryButton(R.string.details, onDetails)
    }
}

@Composable
fun WifiOffErrorScreen(error: WifiOffError, onNext: () -> Unit, animateGuides: Boolean = true) {
    val title = when (error) {
        WifiOffError.AlreadySetUp -> R.string.wifi_off_already_title
        WifiOffError.Refused -> R.string.wifi_off_refused_title
        WifiOffError.CantReach -> R.string.wifi_off_unreachable_title
    }
    val body = when (error) {
        WifiOffError.AlreadySetUp -> R.string.wifi_off_already_body
        WifiOffError.Refused -> R.string.wifi_off_refused_body
        WifiOffError.CantReach -> R.string.wifi_off_unreachable_body
    }
    Screen {
        Image(
            painterResource(R.drawable.ic_find), stringResource(R.string.not_found_icon_description),
            Modifier.size(56.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Heading(stringResource(title))
        Text(stringResource(body))
        if (error == WifiOffError.AlreadySetUp) ResetIllustration(animateGuides)
        if (error == WifiOffError.CantReach) CableIllustration(animateGuides)
        Text(stringResource(R.string.wifi_off_error_no_retry))
        PrimaryButton(
            if (error == WifiOffError.AlreadySetUp) R.string.show_reset_steps else R.string.reconnect_to_mini,
            onNext,
        )
    }
}

@Composable
fun ResetGuideScreen(onDone: () -> Unit, animateGuides: Boolean = true) {
    Screen {
        Heading(stringResource(R.string.reset_title))
        Text(stringResource(R.string.reset_press))
        ResetIllustration(animateGuides)
        Text(stringResource(R.string.reset_light))
        Image(
            painterResource(R.drawable.guide_reset_light), stringResource(R.string.reset_light_description),
            Modifier.fillMaxWidth().height(100.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
        Text(stringResource(R.string.reset_wait))
        Text(stringResource(R.string.reset_ready))
        PrimaryButton(R.string.check_again, onDone)
    }
}

/** Original vectors. The plug moves into the labelled port; labels scale with system text. */
@Composable
private fun RouterIllustration(animate: Boolean) {
    val description = stringResource(R.string.wifi_off_router_description)
    val position = insertionPosition(animate)
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Image(
                painterResource(R.drawable.guide_router), contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
            )
            Image(
                painterResource(R.drawable.guide_router_plug), contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(120.dp)
                    .graphicsLayer { translationY = position },
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.mini_label), Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.router_label) + "\n" + stringResource(R.string.internet_port_label),
                Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ResetIllustration(animate: Boolean) {
    val description = stringResource(R.string.reset_press_description)
    val position = insertionPosition(animate)
    Box(Modifier.fillMaxWidth().height(144.dp).clearAndSetSemantics { contentDescription = description }) {
        Image(
            painterResource(R.drawable.guide_reset), contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(144.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
        )
        Image(
            painterResource(R.drawable.guide_reset_finger), contentDescription = null,
            modifier = Modifier.fillMaxWidth().height(144.dp).graphicsLayer { translationY = position },
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun insertionPosition(animate: Boolean): Float {
    if (!animate) return 0f
    val transition = rememberInfiniteTransition(label = "plug or press")
    val position by transition.animateFloat(
        initialValue = 8f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "insertion position",
    )
    return position
}
