package com.bydmate.app.plus.upload

import org.json.JSONObject

/**
 * One Room row -> one record of api/openapi.yaml. Built from the table's actual columns, not the
 * entity: a column the spec names goes top level, every other column (one upstream adds later)
 * goes into `extra`, so nothing is lost and the server's strict decoding never sees an unknown
 * field. Pure: values come in as the cursor gave them (Long / Double / String / null).
 */
internal object UploadRows {

    enum class Kind { NUMBER, INT, STRING, BOOL }

    private val N = Kind.NUMBER
    private val I = Kind.INT
    private val S = Kind.STRING

    private val BAT_TEMPS = mapOf("bat_temp_avg" to N, "bat_temp_max" to N, "bat_temp_min" to N)

    /** Spec fields per table (Room column name -> JSON kind), `id`/`version` and references aside. */
    val FIELDS: Map<String, Map<String, Kind>> = mapOf(
        "trips" to mapOf(
            "source" to S, "byd_id" to I, "start_ts" to I, "end_ts" to I,
            "distance_km" to N, "hev_km" to N, "kwh_consumed" to N, "kwh_per_100km" to N,
            "soc_start" to N, "soc_end" to N, "odometer_start_km" to N, "odometer_end_km" to N,
            "avg_speed_kmh" to N, "temp_avg_c" to N, "exterior_temp" to I, "exterior_temp_end" to I,
            "fuel_liters" to N, "fuel_total_start" to N, "fuel_total_end" to N,
            "mileage_hev_start" to N, "mileage_hev_end" to N, "cost" to N, "fuel_cost" to N,
        ) + BAT_TEMPS,
        "trip_points" to mapOf(
            "timestamp" to I, "lat" to N, "lon" to N, "speed_kmh" to N, "altitude" to N,
        ),
        "charges" to mapOf(
            "start_ts" to I, "end_ts" to I, "status" to S, "type" to S, "soc_start" to N, "soc_end" to N,
            "kwh_charged" to N, "kwh_charged_soc" to N, "meter_kwh" to N, "max_power_kw" to N,
            "avg_power_kw" to N, "cost" to N, "cost_manual" to Kind.BOOL, "lat" to N, "lon" to N,
            "cell_voltage_min" to N, "cell_voltage_max" to N, "voltage_12v" to N, "exterior_temp" to I,
            "merged_count" to I, "lifetime_kwh_at_start" to N, "lifetime_kwh_at_finish" to N,
            "gun_state" to I, "detection_source" to S,
        ) + BAT_TEMPS,
        "battery_snapshots" to mapOf(
            "timestamp" to I, "odometer_km" to N, "soc_start" to N, "soc_end" to N, "kwh_charged" to N,
            "calculated_capacity_kwh" to N, "soh_percent" to N, "cell_delta_v" to N, "bat_temp_avg" to N,
        ),
        "idle_drains" to mapOf(
            "start_ts" to I, "end_ts" to I, "soc_start" to N, "soc_end" to N, "kwh_consumed" to N,
        ),
        "odometer_samples" to mapOf(
            "timestamp" to I, "mileage_km" to N, "total_elec_kwh" to N, "soc_percent" to N, "session_id" to I,
        ),
    )

    /** Records that change after they are written carry `version`; the others must not. */
    val MUTABLE = setOf("trips", "trip_points", "charges", "idle_drains")

    /** Room id columns that point at another tracked table: (table, column) -> target table. */
    val REFERENCES: Map<String, Pair<String, String>> = mapOf(
        "trip_points" to ("trip_id" to "trips"),
        "battery_snapshots" to ("charge_id" to "charges"),
    )

    /**
     * [columns] in table order, `id` included (it is replaced by [recordId]). [refId] resolves a
     * referenced Room id to that row's record id, or null when it is not known (then omitted).
     */
    fun toJson(
        table: String,
        columns: List<Pair<String, Any?>>,
        recordId: String,
        version: Long,
        refId: (targetTable: String, roomId: Long) -> String?,
    ): JSONObject {
        val fields = FIELDS[table].orEmpty()
        val reference = REFERENCES[table]
        val json = JSONObject().put("id", recordId)
        if (table in MUTABLE) json.put("version", version)
        val extra = JSONObject()
        for ((name, raw) in columns) {
            when {
                name == "id" || raw == null -> Unit
                reference != null && name == reference.first -> referenceId(raw, reference.second, refId)?.let { json.put(name, it) }
                else -> putValue(json, extra, name, fields[name], raw)
            }
        }
        if (extra.length() > 0) json.put("extra", extra)
        return json
    }

    /** A spec field top level when its value fits, anything else into `extra`. */
    private fun putValue(json: JSONObject, extra: JSONObject, name: String, kind: Kind?, raw: Any) {
        val value = kind?.let { convert(it, raw) }
        if (value != null) json.put(name, value) else plain(raw)?.let { extra.put(name, it) }
    }

    /** Room trip_id 0 = a point not attached to any trip yet: no reference. */
    private fun referenceId(raw: Any, target: String, refId: (String, Long) -> String?): String? =
        (raw as? Number)?.toLong()?.takeIf { it > 0 }?.let { refId(target, it) }

    /** The value as the spec field wants it, or null when the column holds something else. */
    private fun convert(kind: Kind, raw: Any): Any? = when (kind) {
        Kind.STRING -> raw as? String
        Kind.BOOL -> (raw as? Number)?.let { it.toLong() != 0L }
        Kind.INT -> when (raw) {
            is Long, is Int -> (raw as Number).toLong()
            is Double -> raw.takeIf { it.isFinite() && it == Math.floor(it) }?.toLong()
            else -> null
        }
        Kind.NUMBER -> when (raw) {
            is Long, is Int -> (raw as Number).toLong()
            is Double -> raw.takeIf { it.isFinite() }
            else -> null
        }
    }

    /** A value JSON can carry as is; blobs and non-finite doubles are dropped. */
    private fun plain(raw: Any): Any? = when (raw) {
        is Double -> raw.takeIf { it.isFinite() }
        is Float -> raw.toDouble().takeIf { it.isFinite() }
        is Number, is String, is Boolean -> raw
        else -> null
    }
}
