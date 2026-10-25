package com.bydmate.app.plus.upload

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * Powertrain and climate values the poll already reads, for the cloud's DM-i trip statistics.
 * Each goes out only when it is a plausible value: the car answers 255 / 65535 / NaN when a fid
 * does not exist on it, and the server rejects a whole sample for one out-of-range number.
 * The server derives battery power as hv_voltage x hv_current, so no power is computed here.
 */

private const val CODE_MAX = 254          // 255 is the car's "no data" word
private const val HV_VOLTAGE_MAX = 1000
private const val HV_CURRENT_MAX = 1000.0
private const val COMPRESSOR_MAX_W = 20_000
private const val SETPOINT_MIN = 10
private const val SETPOINT_MAX = 40
private const val TENTH = 10.0

private fun tenth(v: Double): Double = (v * TENTH).roundToInt() / TENTH

/** work_mode, power_state, hv_voltage, hv_current, compressor_w, ac_on, ac_setpoint. */
internal fun JSONObject.putPowertrain(d: DiParsData) {
    d.workMode?.takeIf { it in 0..CODE_MAX }?.let { put("work_mode", it) }
    d.powerState?.takeIf { it in 0..CODE_MAX }?.let { put("power_state", it) }
    d.hvVoltage?.takeIf { it in 0..HV_VOLTAGE_MAX }?.let { put("hv_voltage", it) }
    d.hvCurrent?.takeIf { it.isFinite() && it in -HV_CURRENT_MAX..HV_CURRENT_MAX }?.let { put("hv_current", tenth(it)) }
    d.compressorW?.takeIf { it in 0..COMPRESSOR_MAX_W }?.let { put("compressor_w", it) }
    d.acStatus?.takeIf { it == 0 || it == 1 }?.let { put("ac_on", it == 1) }
    d.acTemp?.takeIf { it in SETPOINT_MIN..SETPOINT_MAX }?.let { put("ac_setpoint", it) }
}

/** Lifetime litres, live sample only. */
internal fun JSONObject.putFuelTotal(d: DiParsData) {
    d.fuelTotalL?.takeIf { it.isFinite() && it >= 0.0 }?.let { put("fuel_total_l", tenth(it)) }
}
