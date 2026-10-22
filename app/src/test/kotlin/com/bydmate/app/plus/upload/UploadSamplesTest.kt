package com.bydmate.app.plus.upload

import com.bydmate.app.data.remote.DiParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSamplesTest {

    @Suppress("LongParameterList") // one knob per field under test
    private fun snapshot(
        soc: Int? = 80, speed: Int? = 50, power: Double? = 12.5, powerState: Int? = 2,
        pedalAccel: Int? = 20, tirePressFL: Int? = 250,
    ) = DiParsData(
        soc = soc, speed = speed, mileage = 1234.5, power = power, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = 25, minBatTemp = null, chargingStatus = null,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = 13.8,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = 30, gear = 4,
        powerState = powerState, insideTemp = 22, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null, sunroof = null,
        trunk = null, hood = null, seatbeltFL = null, lockFL = null, tirePressFL = tirePressFL,
        tirePressFR = null, tirePressRL = null, tirePressRR = null, driveMode = null,
        workMode = null, autoPark = null, rain = null, lightLow = null, drl = null,
        pedalAccel = pedalAccel, engineRpm = 0, evRangeKm = 300,
    )

    @Test fun `trace sample carries the spec's short names`() {
        val json = UploadSamples.trace(1_000L, snapshot(), Fix(25.2, 55.3))
        assertEquals(1_000L, json.getLong("t"))
        assertEquals(50, json.getInt("speed"))
        assertEquals(20, json.getInt("accel"))
        assertEquals(12.5, json.getDouble("power"), 0.0)
        assertEquals(25.2, json.getDouble("lat"), 0.0)
    }

    @Test fun `out of range values are left out, not sent`() {
        val json = UploadSamples.trace(1L, snapshot(soc = 120, speed = -1, pedalAccel = 255), null)
        assertFalse(json.has("soc"))
        assertFalse(json.has("speed"))
        assertFalse(json.has("accel"))
        assertFalse(json.has("lat"))
    }

    @Test fun `negative power is kept, it is regen`() {
        assertEquals(-30.0, UploadSamples.trace(1L, snapshot(power = -30.0), null).getDouble("power"), 0.0)
    }

    @Test fun `live sample nests tyres and drops a bad fix`() {
        val json = UploadSamples.live(5L, snapshot(), Fix(91.0, 10.0))
        assertEquals(250, json.getJSONObject("tyres").getJSONObject("fl").getInt("pressure_kpa"))
        assertFalse(json.getJSONObject("tyres").has("fr"))
        assertFalse(json.has("lat"))
        assertEquals(22, json.getInt("cabin_temp"))
        assertEquals(300, json.getInt("range_ev_km"))
    }

    @Test fun `driving means drive power state or rolling`() {
        assertTrue(UploadSamples.isDriving(snapshot(powerState = 2, speed = 0)))
        assertTrue(UploadSamples.isDriving(snapshot(powerState = null, speed = 5)))
        assertFalse(UploadSamples.isDriving(snapshot(powerState = 1, speed = 0)))
    }
}
