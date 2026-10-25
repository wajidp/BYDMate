package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hybrid probe exists to answer one question from a single dump (#184): which ICE-side
 * addresses are live on a DM-i. That only works if the addresses stay exactly as measured and
 * the raw word survives printing, sentinels included.
 */
class HybridProbeDiagnosticsTest {

    @Test fun `batch items carry each address with its own transact`() {
        val items = HybridProbeDiagnostics.batchItems()
        assertEquals(HybridProbeDiagnostics.FIDS.size, items.size)
        HybridProbeDiagnostics.FIDS.forEachIndexed { i, f ->
            assertEquals("tx for ${f.name}", f.tx, items[i].tx)
            assertEquals("dev for ${f.name}", f.dev, items[i].dev)
            assertEquals("fid for ${f.name}", f.fid, items[i].fid)
        }
    }

    @Test fun `the table holds the probed addresses`() {
        assertEquals(
            listOf(
                "engine_rpm" to 339738642,
                "engine_power" to 339738656,
                "coolant_temp" to 1320181824,
                "oil_level" to 89129016,
                "fuel_percent" to 1246785600,
                "fuel_range_km" to 1246773304,
                "fuel_instant" to 1246760996,
                "fuel_total" to 1246760976,
                "fuel_avg_phm" to 1246785552,
                "mileage_hev" to 1246773264,
                "mileage_ev" to 1246773284,
                "fuel_tank_cap" to 1336934422,
                "ev_range_km" to 1246765118,
                "elec_phm" to 1032863794,
                "soc_target_dr" to 873463848,
                "soc_now" to 1246777400,
                "save_memory_state" to 1175453710,
                "charging_soc_save_switch" to 1186988086,
                "engine_charge_power" to 785383440,
                "engine_state" to 282067000,
                "engine_indicated_torque_nm" to 843055192,
                "front_motor_torque" to 1141899288,
                "front_motor_torque_f" to 1141899288,
                "rear_motor_torque" to 621805592,
                "power_generation_value" to 638582815,
                "power_generation_state" to 888143882,
                "ev_hev_shift_key" to 1250951189,
                "ev_mode_indicator" to 1032917038,
                "hev_mode_indicator" to 1032917039,
                "direct_drive_energy_state" to 754975065,
                "ptc_power" to 1320169504,
                "engine_thermostat_water_temp" to 843055176,
                "water_temp_meter_percent" to 1246793752,
                "water_temp_meter_percent_f" to 1246793752,
                "this_trip_fuel" to 1246785576,
            ),
            HybridProbeDiagnostics.FIDS.map { it.name to it.fid },
        )
        assertEquals(
            "the consumption fids, the SOC read and the float re-reads are float reads",
            listOf(
                "fuel_instant", "fuel_total", "fuel_avg_phm", "elec_phm", "soc_now",
                "front_motor_torque_f", "water_temp_meter_percent_f", "this_trip_fuel",
            ),
            HybridProbeDiagnostics.FIDS.filter { it.tx == 7 }.map { it.name },
        )
    }

    @Test fun `values are printed with their fid coordinates and transact`() {
        val lines = HybridProbeDiagnostics.format(
            HybridProbeDiagnostics.FIDS.mapIndexed { i, _ -> 0 to i }
        )
        assertEquals(HybridProbeDiagnostics.FIDS.size, lines.size)
        assertEquals("engine_rpm[dev=1012 fid=339738642 tx=5]=0", lines[0])
        assertEquals("fuel_instant[dev=1014 fid=1246760996 tx=7]=6", lines[6])
        assertEquals("fuel_tank_cap[dev=1001 fid=1336934422 tx=5]=11", lines[11])
    }

    /** On a full EV these addresses answer sentinels, and that answer is the finding. */
    @Test fun `sentinel values are printed verbatim`() {
        val readings = List(HybridProbeDiagnostics.FIDS.size) { 0 to 65535 }.toMutableList()
        readings[1] = 0 to -10013

        val lines = HybridProbeDiagnostics.format(readings)

        assertTrue("link error must survive, got: ${lines[0]}", lines[0].endsWith("=65535"))
        assertTrue("wrong-transact sentinel must survive, got: ${lines[1]}", lines[1].endsWith("=-10013"))
    }

    @Test fun `a per-item protocol failure shows its status`() {
        val readings = List(HybridProbeDiagnostics.FIDS.size) { 0 to 1 }.toMutableList()
        readings[2] = -998 to 0

        val lines = HybridProbeDiagnostics.format(readings)

        assertTrue("failed item must show the status, got: ${lines[2]}", lines[2].endsWith("=(status=-998)"))
    }

    @Test fun `no readings collapses to one unavailable line`() {
        assertEquals(listOf("(unavailable)"), HybridProbeDiagnostics.format(null))
    }

    @Test fun `a length mismatch is reported as unavailable rather than misaligned`() {
        assertEquals(listOf("(unavailable)"), HybridProbeDiagnostics.format(listOf(0 to 1, 0 to 2)))
    }
}
