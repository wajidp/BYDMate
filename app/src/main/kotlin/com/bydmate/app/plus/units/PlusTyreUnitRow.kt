package com.bydmate.app.plus.units

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bydmate.app.R
import com.bydmate.app.ui.settings.SettingToggleRow

/** BYDMate+ switch under the Telegram report settings: tyre pressure in psi or bar. */
@Composable
fun PlusTyreUnitRow() {
    val context = LocalContext.current
    var psi by remember { mutableStateOf(PlusTyreUnit.psi(context)) }
    SettingToggleRow(
        title = stringResource(R.string.plus_tyre_psi_title),
        traceId = "plus_tyre_psi",
        description = stringResource(R.string.plus_tyre_psi_desc),
        checked = psi,
        onCheckedChange = {
            psi = it
            PlusTyreUnit.setPsi(context, it)
        },
    )
}
