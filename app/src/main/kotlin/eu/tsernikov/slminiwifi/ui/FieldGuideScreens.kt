package eu.tsernikov.slminiwifi.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import eu.tsernikov.slminiwifi.R

/** This guide explains physical actions. Only its caller can request a connection or a read. */
enum class GuideStep { Reset, Light, Cable, Wifi, AndroidConnect, Check }

@Composable
fun GuideStepScreen(
    step: GuideStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onUseWifi: () -> Unit,
    onUseCable: () -> Unit,
    onConnect: () -> Unit,
    animateGuides: Boolean = true,
) {
    Screen {
        SecondaryButton(R.string.back, onBack)
        when (step) {
            GuideStep.Reset -> {
                Heading(stringResource(R.string.reset_title))
                Text(stringResource(R.string.field_reset_warning))
                ResetIllustration(animateGuides)
                Text(stringResource(R.string.reset_press))
                PrimaryButton(R.string.field_reset_next, onNext)
            }
            GuideStep.Light -> {
                Heading(stringResource(R.string.field_light_title))
                ResetLightIllustration(animateGuides)
                Text(stringResource(R.string.reset_light))
                WaitingProgress(animateGuides)
                Text(stringResource(R.string.reset_wait))
                PrimaryButton(R.string.field_light_next, onNext)
            }
            GuideStep.Cable -> {
                Heading(stringResource(R.string.cable_title))
                CableIllustration(animateGuides)
                Text(stringResource(R.string.cable_body))
                PrimaryButton(R.string.field_cable_next, onNext)
                SecondaryButton(R.string.field_use_wifi, onUseWifi)
            }
            GuideStep.Wifi -> {
                Heading(stringResource(R.string.field_wifi_title))
                JoinWifiIllustration()
                Text(stringResource(R.string.field_wifi_body))
                PrimaryButton(R.string.field_next, onNext)
                SecondaryButton(R.string.field_use_cable, onUseCable)
            }
            GuideStep.AndroidConnect -> {
                Heading(stringResource(R.string.field_android_title))
                ConnectionDialogIllustration()
                Text(stringResource(R.string.field_android_body))
                PrimaryButton(R.string.join_starlink, onConnect)
                Text(stringResource(R.string.field_android_manual))
                SecondaryButton(R.string.field_next, onNext)
            }
            GuideStep.Check -> {
                Heading(stringResource(R.string.field_check_title))
                Image(
                    painterResource(R.drawable.ic_find), stringResource(R.string.not_found_icon_description),
                    Modifier.size(80.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                )
                Text(stringResource(R.string.field_check_body))
                PrimaryButton(R.string.field_check_next, onNext)
            }
        }
    }
}

/** A drawing of the phone's list, with real text that follows the user's font size. */
@Composable
internal fun JoinWifiIllustration() {
    val description = stringResource(R.string.field_wifi_description)
    Column(
        Modifier.fillMaxWidth().border(3.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.large)
            .padding(24.dp).clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.field_wifi_list_title), style = MaterialTheme.typography.titleLarge)
        Image(painterResource(R.drawable.guide_wifi), null, Modifier.size(48.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        Text(stringResource(R.string.starlink_network), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.field_wifi_open))
    }
}

/** One short sequence ends with the indicator off, as observed in TESTING.md. */
@Composable
internal fun ResetLightIllustration(animate: Boolean = true) {
    val moving = rememberGuideAnimationsEnabled(animate)
    val light = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        if (moving) {
            light.snapTo(1f)
            light.animateTo(0f, keyframes {
                durationMillis = 3000
                for (time in 0 until 3000 step 250) {
                    (if (time % 500 == 0) 1f else 0f) at time
                }
                0f at 3000
            })
        } else light.snapTo(0f)
    }
    val description = stringResource(R.string.field_light_description)
    val state = stringResource(if (moving && light.value > 0f) R.string.field_light_blinking else R.string.field_light_off)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(144.dp).clearAndSetSemantics {
            contentDescription = description
            stateDescription = state
        }) {
            Image(painterResource(R.drawable.guide_reset_light), null,
                Modifier.fillMaxWidth().height(144.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
            Image(painterResource(R.drawable.guide_light_flash), null,
                Modifier.fillMaxWidth().height(144.dp).graphicsLayer { alpha = if (moving) light.value else 0f },
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        }
        Text(stringResource(R.string.field_light_caption), style = MaterialTheme.typography.bodyMedium)
    }
}

/** All waits use a static final indicator when Android animations have been removed. */
@Composable
internal fun WaitingProgress(animate: Boolean = true) {
    if (rememberGuideAnimationsEnabled(animate)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        // This is an illustration of waiting, never a claim that the actual operation has finished.
        val description = stringResource(R.string.rebooting_icon_description)
        LinearProgressIndicator(progress = { 0.5f }, modifier = Modifier.fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate })
    }
}

@Composable
internal fun RestartIllustration(animate: Boolean = true) {
    val moving = rememberGuideAnimationsEnabled(animate)
    val rotation = if (moving) {
        val transition = rememberInfiniteTransition(label = "restarting")
        val angle by transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "restart arrow")
        angle
    } else 360f
    val description = stringResource(R.string.field_restart_description)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(144.dp).clearAndSetSemantics { contentDescription = description; progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
            Image(painterResource(R.drawable.guide_restart), null, Modifier.fillMaxWidth().height(144.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface))
            Image(painterResource(R.drawable.guide_restart_arrow), null,
                Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(72.dp).graphicsLayer { rotationZ = rotation },
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary))
        }
        Text(stringResource(R.string.field_restart_caption), style = MaterialTheme.typography.bodyMedium)
    }
}
