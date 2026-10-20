package com.bydmate.app.plus.aeb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bydmate.app.R
import com.bydmate.app.ui.components.AppAlertDialog
import com.bydmate.app.ui.settings.SettingHint
import com.bydmate.app.ui.settings.SettingToggleRow
import com.bydmate.app.ui.theme.AccentOrange
import com.bydmate.app.ui.theme.CardSurface
import com.bydmate.app.ui.theme.CardSurfaceElevated
import com.bydmate.app.ui.theme.TextPrimary
import com.bydmate.app.ui.theme.TextSecondary
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay

/** BYDMate+ «Keep AEB off» card. Switching it on asks for confirmation; switching it off does not. */
@Composable
fun AebKeepOffCard() {
    val context = LocalContext.current
    val keeper = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, AebEntryPoint::class.java).aebKeeper()
    }
    var enabled by remember { mutableStateOf(keeper.enabled) }
    var confirmOpen by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(keeper.journalLines().lastOrNull()) }
    // A result can land while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            last = keeper.journalLines().lastOrNull()
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
                title = stringResource(R.string.plus_aeb_title),
                traceId = "plus_keep_aeb_off",
                description = stringResource(R.string.plus_aeb_desc),
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        confirmOpen = true
                    } else {
                        enabled = false
                        keeper.enabled = false
                    }
                },
            )
            last?.let { SettingHint(text = stringResource(R.string.plus_aeb_last, it)) }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmOpen) {
        AppAlertDialog(
            onDismissRequest = { confirmOpen = false },
            containerColor = CardSurface,
            title = { Text(stringResource(R.string.plus_aeb_confirm_title), color = TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.plus_aeb_confirm_body),
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    enabled = true
                    keeper.enabled = true
                }) { Text(stringResource(R.string.plus_aeb_confirm_enable), color = AccentOrange) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) {
                    Text(stringResource(R.string.plus_aeb_confirm_cancel), color = TextSecondary)
                }
            },
        )
    }
}
