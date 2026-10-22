package com.bydmate.app.plus.upload

import com.bydmate.app.data.remote.DiParsData
import org.json.JSONObject

/** A position fix fresh enough to stamp on a sample. */
internal data class Fix(val lat: Double, val lon: Double)

/**
 * Drive trace and live samples (api/openapi.yaml TraceSample / LiveSample), built only from the
 * poll snapshot the app already has: no extra vehicle read. A value outside the spec's range is
 * left out rather than letting the server reject the whole sample.
 */
internal object UploadSamples {

    private const val SOC_MAX = 100.0
    private const val PEDAL_MAX = 100.0

    /** Ignition in drive, or rolling: the span a trip can cover. */
    fun isDriving(d: DiParsData): Boolean = d.powerState == 2 || (d.speed ?: 0) > 0

    fun trace(t: Long, d: DiParsData, fix: Fix?): JSONObject = JSONObject().apply {
        put("t", t)
        nonNegative(d.speed)?.let { put("speed", it) }
        inRange(d.pedalAccel, PEDAL_MAX)?.let { put("accel", it) }
        inRange(d.pedalBrake, PEDAL_MAX)?.let { put("brake", it) }
        finite(d.power)?.let { put("power", it) }
        nonNegative(d.engineRpm)?.let { put("rpm", it) }
        inRange(d.soc, SOC_MAX)?.let { put("soc", it) }
        putFix(fix)
    }

    fun live(ts: Long, d: DiParsData, fix: Fix?): JSONObject = JSONObject().apply {
        put("ts", ts)
        inRange(d.soc, SOC_MAX)?.let { put("soc", it) }
        nonNegative(d.speed)?.let { put("speed_kmh", it) }
        nonNegative(d.mileage)?.let { put("mileage_km", it) }
        nonNegative(d.evRangeKm)?.let { put("range_ev_km", it) }
        finite(d.power)?.let { put("power_kw", it) }
        nonNegative(d.engineRpm)?.let { put("engine_rpm", it) }
        d.exteriorTemp?.let { put("exterior_temp", it) }
        d.insideTemp?.let { put("cabin_temp", it) }
        d.avgBatTemp?.let { put("bat_temp", it) }
        finite(d.voltage12v)?.let { put("voltage_12v", it) }
        tyres(d)?.let { put("tyres", it) }
        putFix(fix)
    }

    private fun tyres(d: DiParsData): JSONObject? {
        val wheels = listOf(
            Triple("fl", d.tirePressFL, d.tyreTempFL),
            Triple("fr", d.tirePressFR, d.tyreTempFR),
            Triple("rl", d.tirePressRL, d.tyreTempRL),
            Triple("rr", d.tirePressRR, d.tyreTempRR),
        )
        val out = JSONObject()
        for ((name, pressure, temp) in wheels) {
            val tyre = JSONObject()
            nonNegative(pressure)?.let { tyre.put("pressure_kpa", it) }
            temp?.let { tyre.put("temp_c", it) }
            if (tyre.length() > 0) out.put(name, tyre)
        }
        return out.takeIf { it.length() > 0 }
    }

    private fun JSONObject.putFix(fix: Fix?) {
        if (fix == null || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return
        put("lat", fix.lat)
        put("lon", fix.lon)
    }

    private fun finite(v: Double?): Double? = v?.takeIf { it.isFinite() }

    private fun nonNegative(v: Number?): Number? = v?.takeIf { it.toDouble().let { d -> d.isFinite() && d >= 0 } }

    private fun inRange(v: Number?, max: Double): Number? = nonNegative(v)?.takeIf { it.toDouble() <= max }
}
