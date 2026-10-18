package eu.tsernikov.slminiwifi.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import eu.tsernikov.slminiwifi.R
import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.WifiRules
import eu.tsernikov.slminiwifi.data.wifiQrPayload

/** Stateless credentials come from foreground-only state, never a saved instance bundle. */
@Composable
fun WifiOnSetupScreen(
    profile: Profile,
    ownPassword: Boolean,
    hasSavedProfile: Boolean,
    onNameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onOwnPassword: () -> Unit,
    onGeneratedPassword: () -> Unit,
    onUseSaved: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    @StringRes message: Int? = null,
    onHelp: (() -> Unit)? = null,
) {
    var showPassword by remember { mutableStateOf(false) }
    Screen {
        Heading(stringResource(R.string.wifi_secure))
        message?.let {
            Text(stringResource(it))
            onHelp?.let { help -> SecondaryButton(R.string.show_reset_steps, help) }
        }
        // Rules precede both inputs so they are visible before editing starts.
        Text(stringResource(R.string.wifi_on_name_rule, WifiRules.MIN_NAME_BYTES, WifiRules.MAX_NAME_BYTES))
        Text(stringResource(R.string.wifi_on_password_rule, WifiRules.MIN_PASSWORD_LENGTH, WifiRules.MAX_PASSWORD_LENGTH))
        if (hasSavedProfile) SecondaryButton(R.string.wifi_on_use_saved, onUseSaved)
        OutlinedTextField(
            value = profile.name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.wifi_on_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = !WifiRules.isNameValid(profile.name),
            supportingText = if (!WifiRules.isNameValid(profile.name)) {
                { Text(stringResource(if (profile.name.isEmpty()) R.string.wifi_on_name_empty else R.string.wifi_on_name_invalid)) }
            } else null,
        )
        if (!ownPassword) Text(stringResource(R.string.wifi_on_generated_note))
        OutlinedTextField(
            value = profile.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.wifi_on_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            readOnly = !ownPassword,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            isError = ownPassword && !WifiRules.isPasswordValid(profile.password),
        )
        SecondaryButton(
            if (showPassword) R.string.wifi_on_hide_password else R.string.wifi_on_show_password,
            { showPassword = !showPassword },
        )
        SecondaryButton(
            if (ownPassword) R.string.wifi_on_generate_password else R.string.wifi_on_own_password,
            if (ownPassword) onGeneratedPassword else onOwnPassword,
        )
        PrimaryButton(R.string.wifi_on_review, onContinue, WifiRules.isValid(profile))
        SecondaryButton(R.string.back, onBack)
    }
}

@Composable
fun WifiOnConfirmScreen(
    name: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    canConfirm: Boolean = true,
) {
    Screen {
        WifiReadyIcon()
        Heading(stringResource(R.string.wifi_on_confirm_title))
        Text(name, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.wifi_on_confirm_body))
        Text(stringResource(R.string.wifi_on_restart))
        Text(stringResource(R.string.wifi_on_rejoin))
        ConnectionDialogIllustration(name)
        Text(stringResource(R.string.wifi_on_undo))
        if (!canConfirm) {
            WaitingProgress()
            Text(stringResource(R.string.confirm_checking))
        }
        PrimaryButton(R.string.wifi_on_confirm, onConfirm, canConfirm)
        SecondaryButton(R.string.cancel, onCancel)
    }
}

@Composable
fun WifiOnProgressScreen(onReconnect: (() -> Unit)? = null) = SetupProgressScreen(
    R.string.wifi_on_progress_title,
    R.string.wifi_on_progress_body,
    R.string.wifi_on_progress_time,
    R.string.wifi_on_progress_help,
    onReconnect = onReconnect,
)

@Composable
fun WifiOnResultScreen(
    profile: Profile,
    onConnect: () -> Unit,
    onDetails: (() -> Unit)?,
    onDone: () -> Unit,
    @StringRes connectionMessage: Int? = null,
    verified: Boolean = true,
    onHelp: (() -> Unit)? = null,
) {
    Screen {
        if (verified) WifiReadyIcon() else Image(
            painterResource(R.drawable.ic_find), stringResource(R.string.not_found_icon_description),
            Modifier.size(56.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        )
        Heading(stringResource(if (verified) R.string.wifi_on_result_title else R.string.wifi_on_unverified_title))
        if (!verified) Text(stringResource(R.string.wifi_on_unverified_body))
        connectionMessage?.let { Text(stringResource(it)) }
        Text(stringResource(R.string.wifi_on_name), style = MaterialTheme.typography.labelLarge)
        Text(profile.name, style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.wifi_on_password), style = MaterialTheme.typography.labelLarge)
        Text(profile.password, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
        WifiQrCode(profile)
        Text(stringResource(R.string.wifi_on_qr_help))
        PrimaryButton(R.string.wifi_on_connect, onConnect)
        onDetails?.let { SecondaryButton(R.string.details, it) }
        SecondaryButton(R.string.done, onDone)
        if (!verified || connectionMessage != null) {
            onHelp?.let { SecondaryButton(R.string.show_reset_steps, it) }
        }
    }
}

@Composable
fun WifiOnAutoConnectScreen(api29: Boolean, onContinue: () -> Unit, onBack: () -> Unit) {
    Screen {
        WifiReadyIcon()
        Heading(stringResource(R.string.wifi_on_connect_title))
        Text(stringResource(if (api29) R.string.wifi_on_connect_api29_body else R.string.wifi_on_connect_body))
        Text(stringResource(R.string.wifi_on_connect_help))
        PrimaryButton(R.string.wifi_on_open_save, onContinue)
        SecondaryButton(R.string.back, onBack)
    }
}

@Composable
private fun WifiReadyIcon() {
    Image(
        painterResource(R.drawable.ic_mini_configured), stringResource(R.string.configured_icon_description),
        Modifier.size(56.dp), colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun WifiQrCode(profile: Profile) {
    val bitmap = remember(profile.name, profile.password) {
        val matrix = QRCodeWriter().encode(
            wifiQrPayload(profile), BarcodeFormat.QR_CODE, 640, 640,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4),
        )
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }.asImageBitmap()
    }
    Image(bitmap, stringResource(R.string.wifi_on_qr_description), modifier = Modifier.size(256.dp))
}
