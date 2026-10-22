package com.bydmate.app.plus.units

import android.content.Context

/**
 * BYDMate+: tyre pressure in psi in the Telegram report, one decimal (the car reports kPa; the
 * upstream report shows bar). On by default: the owner reads psi. Display only, nothing on the car.
 */
object PlusTyreUnit {
    const val PREFS_NAME = "plus_units"
    const val KEY_PSI = "tyre_psi"
    const val PSI_PER_KPA = 0.1450377

    /** kPa as the report shows it: psi, or bar as upstream. */
    fun shown(kpa: Int, psi: Boolean): Double = if (psi) kpa * PSI_PER_KPA else kpa / 100.0

    fun psi(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_PSI, true)

    fun setPsi(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_PSI, on).apply()
    }
}
