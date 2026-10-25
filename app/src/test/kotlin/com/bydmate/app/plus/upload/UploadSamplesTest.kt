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

    @Test fun `a hybrid sends its soc target and tank, an EV sends none of those keys`() {
        val hybrid = snapshot().copy(fuelPercent = 86, fuelRangeKm = 1335)
        val live = UploadSamples.live(5L, hybrid, null, socTarget = 43)
        assertEquals(43, live.getInt("soc_target"))
        assertEquals(86, live.getInt("fuel_percent"))
        assertEquals(1335, live.getInt("fuel_range_km"))
        val trace = UploadSamples.trace(5L, hybrid, null, socTarget = 43)
        assertEquals(43, trace.getInt("soc_target"))
        assertEquals(86, trace.getInt("fuel_percent"))

        for (json in listOf(UploadSamples.live(5L, snapshot(), null), UploadSamples.trace(5L, snapshot(), null))) {
            assertFalse(json.has("soc_target"))
            assertFalse(json.has("fuel_percent"))
            assertFalse(json.has("fuel_range_km"))
        }
    }

    @Test fun `powertrain and climate values go out on both samples, fuel total on live only`() {
        val d = snapshot(powerState = 1).copy(
            workMode = 3, hvVoltage = 540, hvCurrent = -12.34, compressorW = 850,
            acStatus = 1, acTemp = 22, fuelTotalL = 671.2,
        )
        for (json in listOf(UploadSamples.live(5L, d, null), UploadSamples.trace(5L, d, null))) {
            assertEquals(3, json.getInt("work_mode"))
            assertEquals(1, json.getInt("power_state"))
            assertEquals(540, json.getInt("hv_voltage"))
            assertEquals(-12.3, json.getDouble("hv_current"), 0.0)
            assertEquals(850, json.getInt("compressor_w"))
            assertEquals(true, json.getBoolean("ac_on"))
            assertEquals(22, json.getInt("ac_setpoint"))
        }
        assertEquals(671.2, UploadSamples.live(5L, d, null).getDouble("fuel_total_l"), 0.0)
        assertFalse(UploadSamples.trace(5L, d, null).has("fuel_total_l"))
    }

    @Test fun `no-data words, NaN and impossible values are not sent`() {
        val d = snapshot(powerState = 255).copy(
            workMode = 255, hvVoltage = 65535, hvCurrent = Double.NaN, compressorW = 65535,
            acStatus = 2, acTemp = 5, fuelTotalL = -1.0,
        )
        for (json in listOf(UploadSamples.live(5L, d, null), UploadSamples.trace(5L, d, null))) {
            for (key in listOf("work_mode", "power_state", "hv_voltage", "hv_current", "compressor_w", "ac_on", "ac_setpoint", "fuel_total_l")) {
                assertFalse("$key must be left out", json.has(key))
            }
        }
        assertFalse(UploadSamples.live(5L, snapshot().copy(acStatus = 0), null).getBoolean("ac_on"))
    }

    @Test fun `live samples come every 30 s moving, 10 s standing with the engine running, 60 s otherwise`() {
        assertEquals(30_000L, CloudUploader.liveIntervalMs(snapshot(speed = 50)))
        assertEquals(30_000L, CloudUploader.liveIntervalMs(snapshot(speed = 50).copy(engineRpm = 1500)))
        assertEquals(60_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 0)))
        assertEquals(60_000L, CloudUploader.liveIntervalMs(snapshot(speed = null).copy(engineRpm = null)))
        assertEquals(10_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 1200)))
        assertEquals(10_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 0, hvCurrent = -8.5)))
        assertEquals(60_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 0, hvCurrent = 3.1)))
        assertEquals("EV sentinel rpm", 60_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 8191)))
        assertEquals("NaN current", 60_000L, CloudUploader.liveIntervalMs(snapshot(speed = 0).copy(engineRpm = 0, hvCurrent = Double.NaN)))
    }

    @Test fun `a target or tank outside 0 to 100 is left out`() {
        val odd = snapshot().copy(fuelPercent = 255, fuelRangeKm = -1)
        val json = UploadSamples.live(5L, odd, null, socTarget = 101)
        assertFalse(json.has("soc_target"))
        assertFalse(json.has("fuel_percent"))
        assertFalse(json.has("fuel_range_km"))
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
