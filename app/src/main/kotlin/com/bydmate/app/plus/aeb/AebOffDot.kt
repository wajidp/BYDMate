package com.bydmate.app.plus.aeb

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bydmate.app.ui.theme.SocRed
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

/** BYDMate+: the floating widget's "AEB is off" mark — a 7 dp red dot in the card's top right corner. */
@Composable
fun AebOffDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(top = 6.dp, end = 6.dp)
            .size(7.dp)
            .background(SocRed, CircleShape),
    )
}

/** Feeds [AebKeeper.aebOff] to the widget until its scope is cancelled. */
suspend fun collectAebOff(appCtx: Context, onChange: (Boolean) -> Unit) {
    runCatching {
        EntryPointAccessors.fromApplication(appCtx, AebEntryPoint::class.java).aebKeeper().aebOff.collect(onChange)
    }.onFailure {
        // The widget was taken down: cancelled by design, not broken.
        if (it is CancellationException) throw it
        Log.w(AebKeeper.TAG, "AEB dot subscription failed: ${it.message}")
    }
}
