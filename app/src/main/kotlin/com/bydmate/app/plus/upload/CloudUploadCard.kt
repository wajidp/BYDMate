package com.bydmate.app.plus.upload

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bydmate.app.R
import com.bydmate.app.ui.settings.SettingActionRow
import com.bydmate.app.ui.settings.SettingButtonStyle
import com.bydmate.app.ui.settings.SettingHint
import com.bydmate.app.ui.settings.SettingToggleRow
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.CardBorder
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.TextMuted
import com.bydmate.app.ui.theme.TextSecondary
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** BYDMate+ «Upload to BYDMate Cloud» card. The token is write-only: a saved one is never shown. */
@Composable
fun CloudUploadCard() {
    val context = LocalContext.current
    val uploader = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, UploadEntryPoint::class.java).cloudUploader()
    }
    val settings = uploader.settingsForUi
    var enabled by remember { mutableStateOf(settings.enabled) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var tokenInput by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(settings.token.isNotBlank()) }
    var tokenInvalid by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(uploader.state) }
    var lastOk by remember { mutableStateOf(settings.lastOkAt) }
    var lastError by remember { mutableStateOf(settings.lastError) }
    var carName by remember { mutableStateOf(settings.carName) }
    var backlog by remember { mutableIntStateOf(0) }
    // The uploader works in the background: refresh what it reports while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            state = uploader.state
            lastOk = settings.lastOkAt
            lastError = settings.lastError
            carName = settings.carName
            backlog = withContext(Dispatchers.IO) { runCatching { uploader.backlog() }.getOrDefault(0) }
            delay(2_000)
        }
    }

    Spacer(Modifier.height(8.dp))
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            SettingToggleRow(
                title = stringResource(R.string.plus_upload_title),
                traceId = "plus_cloud_upload",
                description = stringResource(R.string.plus_upload_desc),
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    settings.enabled = on
                    uploader.wakeUp()
                },
            )
            if (enabled) {
                UploadField(
                    label = stringResource(R.string.plus_upload_server_label),
                    value = url,
                    onValueChange = { url = it },
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(8.dp))
                UploadField(
                    label = stringResource(R.string.plus_upload_token_label),
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    keyboardType = KeyboardType.Password,
                    placeholder = if (tokenSaved) stringResource(R.string.plus_upload_token_saved) else null,
                    secret = true,
                )
                SettingActionRow(
                    title = stringResource(R.string.plus_upload_save),
                    buttonLabel = stringResource(R.string.plus_upload_save),
                    style = SettingButtonStyle.Primary,
                    onClick = {
                        settings.serverUrl = url
                        tokenInvalid = tokenInput.isNotBlank() && !UploadSettings.isValidToken(tokenInput)
                        if (tokenInput.isNotBlank() && !tokenInvalid) settings.token = tokenInput
                        if (!tokenInvalid) tokenInput = ""
                        tokenSaved = settings.token.isNotBlank()
                        url = settings.serverUrl
                        uploader.wakeUp()
                    },
                    secondButtonLabel = stringResource(R.string.plus_upload_sync_now),
                    onSecondClick = { uploader.wakeUp() },
                )
                if (tokenInvalid) SettingHint(text = stringResource(R.string.plus_upload_token_invalid))
                SettingHint(text = stringResource(R.string.plus_upload_status, stateText(state)))
                carName?.let { SettingHint(text = stringResource(R.string.plus_upload_car, it)) }
                if (lastOk > 0) {
                    val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastOk))
                    SettingHint(text = stringResource(R.string.plus_upload_last_ok, at))
                }
                if (backlog > 0) SettingHint(text = stringResource(R.string.plus_upload_backlog, backlog))
                lastError?.let { SettingHint(text = stringResource(R.string.plus_upload_error, it)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun stateText(state: CloudUploader.State): String = stringResource(
    when (state) {
        CloudUploader.State.OFF -> R.string.plus_upload_state_off
        CloudUploader.State.NO_TOKEN -> R.string.plus_upload_state_no_token
        CloudUploader.State.TOKEN_REJECTED -> R.string.plus_upload_state_token_rejected
        CloudUploader.State.SYNCING -> R.string.plus_upload_state_syncing
        CloudUploader.State.UP_TO_DATE -> R.string.plus_upload_state_up_to_date
        CloudUploader.State.RETRYING -> R.string.plus_upload_state_retrying
        CloudUploader.State.FAILED -> R.string.plus_upload_state_failed
    }
)

@Composable
private fun UploadField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    placeholder: String? = null,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = TextMuted) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentGreen,
            unfocusedBorderColor = CardBorder,
            focusedLabelColor = AccentGreen,
            unfocusedLabelColor = TextSecondary,
            cursorColor = AccentGreen,
        ),
    )
}
