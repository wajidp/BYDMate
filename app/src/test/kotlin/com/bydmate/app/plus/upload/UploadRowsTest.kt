package com.bydmate.app.plus.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRowsTest {

    private val none: (String, Long) -> String? = { _, _ -> null }

    @Test fun `Room id is replaced by the record id and version is added on mutable records`() {
        val json = UploadRows.toJson("trips", listOf("id" to 5L, "start_ts" to 1L), "rec", 9L, none)
        assertEquals("rec", json.getString("id"))
        assertEquals(9L, json.getLong("version"))
        assertEquals(1L, json.getLong("start_ts"))
    }

    @Test fun `cost_manual is sent as a boolean`() {
        val json = UploadRows.toJson("charges", listOf("start_ts" to 1L, "cost_manual" to 1L), "c", 1L, none)
        assertEquals(true, json.getBoolean("cost_manual"))
    }

    @Test fun `an unknown column goes to extra, a null nowhere`() {
        val json = UploadRows.toJson("trips", listOf("start_ts" to 1L, "new_col" to "x", "end_ts" to null), "t", 1L, none)
        assertEquals("x", json.getJSONObject("extra").getString("new_col"))
        assertFalse(json.has("end_ts"))
        assertFalse(json.has("new_col"))
    }

    @Test fun `a known column holding the wrong type goes to extra instead of failing the row`() {
        val json = UploadRows.toJson("trips", listOf("start_ts" to 1L, "distance_km" to "n/a"), "t", 1L, none)
        assertFalse(json.has("distance_km"))
        assertEquals("n/a", json.getJSONObject("extra").getString("distance_km"))
    }

    @Test fun `non-finite numbers are dropped`() {
        val json = UploadRows.toJson("trips", listOf("start_ts" to 1L, "cost" to Double.NaN), "t", 1L, none)
        assertFalse(json.has("cost"))
        assertFalse(json.has("extra"))
    }

    @Test fun `integer fields accept whole doubles only`() {
        val ok = UploadRows.toJson("trips", listOf("start_ts" to 1L, "exterior_temp" to 21.0), "t", 1L, none)
        assertEquals(21L, ok.getLong("exterior_temp"))
        val bad = UploadRows.toJson("trips", listOf("start_ts" to 1L, "exterior_temp" to 21.5), "t", 1L, none)
        assertEquals(21.5, bad.getJSONObject("extra").getDouble("exterior_temp"), 0.0)
    }

    @Test fun `references become record ids, unknown or zero are left out`() {
        val refs: (String, Long) -> String? = { t, id -> if (t == "trips" && id == 3L) "trip-3" else null }
        val attached = UploadRows.toJson("trip_points", listOf("trip_id" to 3L, "timestamp" to 1L), "p", 1L, refs)
        assertEquals("trip-3", attached.getString("trip_id"))
        assertFalse(UploadRows.toJson("trip_points", listOf("trip_id" to 0L), "p", 1L, refs).has("trip_id"))
        assertFalse(UploadRows.toJson("trip_points", listOf("trip_id" to 4L), "p", 1L, refs).has("trip_id"))
    }

    @Test fun `immutable records carry no version`() {
        val json = UploadRows.toJson("battery_snapshots", listOf("timestamp" to 1L), "b", 7L, none)
        assertFalse(json.has("version"))
        assertTrue(json.has("timestamp"))
    }
}
