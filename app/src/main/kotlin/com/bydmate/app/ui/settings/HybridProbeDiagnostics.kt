package com.bydmate.app.ui.settings

import com.bydmate.app.data.vehicle.BatchReadItem

/**
 * READ-side probe of the ICE-side parameters for the diagnostic dump (#184).
 *
 * Nothing in the app reads these yet: on a full EV they are expected to answer sentinels, and
 * which of them carry live values on a DM-i hybrid is exactly the open question. The dump
 * prints the raw word every address returned, sentinels included (65535 = fid/CAN link
 * missing, -10011 = wrong direction, -10013 = wrong transact), so an owner running the engine
 * can send one dump and settle it. The tx=7 rows carry IEEE-754 float bits, not a plain
 * number — the address is printed with its transact so the reader knows which is which.
 */
internal object HybridProbeDiagnostics {

    /** One printed row: the dump label plus the address it reads verbatim. */
    data class ProbeFid(val name: String, val dev: Int, val fid: Int, val tx: Int)

    val FIDS: List<ProbeFid> = listOf(
        ProbeFid("engine_rpm", 1012, 339738642, TX_GET_INT),
        ProbeFid("engine_power", 1012, 339738656, TX_GET_INT),
        ProbeFid("coolant_temp", 1012, 1320181824, TX_GET_INT),
        ProbeFid("oil_level", 1012, 89129016, TX_GET_INT),
        ProbeFid("fuel_percent", 1014, 1246785600, TX_GET_INT),
        ProbeFid("fuel_range_km", 1014, 1246773304, TX_GET_INT),
        ProbeFid("fuel_instant", 1014, 1246760996, TX_GET_FLOAT),
        ProbeFid("fuel_total", 1014, 1246760976, TX_GET_FLOAT),
        ProbeFid("fuel_avg_phm", 1014, 1246785552, TX_GET_FLOAT),
        ProbeFid("mileage_hev", 1014, 1246773264, TX_GET_INT),
        ProbeFid("mileage_ev", 1014, 1246773284, TX_GET_INT),
        ProbeFid("fuel_tank_cap", 1001, 1336934422, TX_GET_INT),
        ProbeFid("ev_range_km", 1014, 1246765118, TX_GET_INT),
        ProbeFid("elec_phm", 1014, 1032863794, TX_GET_FLOAT),
        // The SOC target on the Energy screen (15..70 %, the level the engine holds the battery
        // to): the first row answers its percent, the owner's dumps of 2026-10-25 confirmed it.
        // The statistic twin, the has-target flag and the float read were dead (-10011 / -1.0).
        ProbeFid("soc_target_dr", 1023, 873463848, TX_GET_INT),
        ProbeFid("soc_now", 1014, 1246777400, TX_GET_FLOAT),
        // Which Energy Management Mode (SAVE / AI-Energy Management) is selected is not in the
        // poll: workMode only says what the powertrain does right now. save_memory_state read 2
        // in both modes, so it is not the mode. The write-side candidates (energy_mode_set and
        // the two aiem_* rows) answered -10011 on 2026-10-25 and were dropped;
        // charging_soc_save_switch (last of this group) answered 2, like save_memory_state.
        ProbeFid("save_memory_state", 1023, 1175453710, TX_GET_INT),
        ProbeFid("charging_soc_save_switch", 1009, 1186988086, TX_GET_INT),
        // Phase 2 candidates for the DM-i trip statistics (names from the car's catalog, units
        // unknown, never read on a car before): what the engine puts into the battery, torque,
        // the EV/HEV indicators, the PTC heater, coolant, fuel per trip. The owner takes dumps
        // with the engine running in each state; reads only, nothing is polled.
        ProbeFid("engine_charge_power", 1012, 785383440, TX_GET_INT),
        ProbeFid("engine_state", 1012, 282067000, TX_GET_INT),
        ProbeFid("engine_indicated_torque_nm", 1012, 843055192, TX_GET_INT),
        ProbeFid("front_motor_torque", 1012, 1141899288, TX_GET_INT),
        // front_motor_torque and water_temp_meter_percent answered -10013 (wrong transact) as
        // ints on 2026-10-25: retried as floats.
        ProbeFid("front_motor_torque_f", 1012, 1141899288, TX_GET_FLOAT),
        ProbeFid("rear_motor_torque", 1012, 621805592, TX_GET_INT),
        ProbeFid("power_generation_value", 1006, 638582815, TX_GET_INT),
        ProbeFid("power_generation_state", 1006, 888143882, TX_GET_INT),
        ProbeFid("ev_hev_shift_key", 1006, 1250951189, TX_GET_INT),
        ProbeFid("ev_mode_indicator", 1007, 1032917038, TX_GET_INT),
        ProbeFid("hev_mode_indicator", 1007, 1032917039, TX_GET_INT),
        ProbeFid("direct_drive_energy_state", 1023, 754975065, TX_GET_INT),
        ProbeFid("ptc_power", 1000, 1320169504, TX_GET_INT),
        ProbeFid("engine_thermostat_water_temp", 1006, 843055176, TX_GET_INT),
        ProbeFid("water_temp_meter_percent", 1007, 1246793752, TX_GET_INT),
        ProbeFid("water_temp_meter_percent_f", 1007, 1246793752, TX_GET_FLOAT),
        ProbeFid("this_trip_fuel", 1014, 1246785576, TX_GET_FLOAT),
    )

    fun batchItems(): List<BatchReadItem> = FIDS.map { BatchReadItem(it.tx, it.dev, it.fid) }

    /**
     * Renders one line per address from raw (status, value) pairs in [FIDS] order. A null
     * [readings] (daemon unreachable, timeout, old daemon without batch support) or a length
     * mismatch collapses to a single "(unavailable)" line — same contract as the seats block.
     */
    fun format(readings: List<Pair<Int, Int>>?): List<String> {
        if (readings == null || readings.size != FIDS.size) return listOf("(unavailable)")
        return FIDS.mapIndexed { i, f ->
            val (status, value) = readings[i]
            val rendered = if (status == 0) value.toString() else "(status=$status)"
            "${f.name}[dev=${f.dev} fid=${f.fid} tx=${f.tx}]=$rendered"
        }
    }

    private const val TX_GET_INT = 5
    private const val TX_GET_FLOAT = 7
}
