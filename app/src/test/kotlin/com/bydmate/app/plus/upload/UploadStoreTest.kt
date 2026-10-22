package com.bydmate.app.plus.upload

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.OdometerSampleEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.local.entity.TripTombstoneEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UploadStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var store: UploadStore
    private var clock = 1_700_000_000_000L

    private val sql: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @Before fun setUp() {
        UploadRestoreGate.resetForTest()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        store = UploadStore({ sql }, tmp.newFolder("batches"), now = { clock })
    }

    @After fun tearDown() = db.close()

    private fun build(maxRows: Int = 2_000): PendingBatch? = store.buildBatch(maxRows, "test", "2026-10-22T00:00:00Z")

    private fun body(batch: PendingBatch): JSONObject =
        JSONObject(GZIPInputStream(batch.file.inputStream()).readBytes().toString(Charsets.UTF_8))

    private fun mapping(table: String, roomId: Long): Pair<String?, Long>? =
        sql.query("SELECT record_id, version FROM plus_upload_rows WHERE tbl = ? AND room_id = ?", arrayOf<Any>(table, roomId))
            .use { if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }

    private fun drain() {
        while (true) {
            val b = build() ?: return
            store.ack(b)
        }
    }

    @Test fun `nothing is tracked until the owner turns upload on`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L))
        assertFalse(store.isInstalled())
    }

    @Test fun `rows written before install are backfilled and sent in the first batch`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1_000L, endTs = 2_000L, distanceKm = 12.5))
        db.tripDao().insert(TripEntity(startTs = 3_000L, bydId = 12345L, source = "energydata"))
        store.ensureInstalled()

        val batch = build()!!
        val trips = body(batch).getJSONObject("tables").getJSONArray("trips")
        assertEquals(2, trips.length())
        val ids = (0 until trips.length()).map { trips.getJSONObject(it).getString("id") }
        assertTrue("car-log trip uses the spec's v5 id", UploadUuid.trip(12345L).toString() in ids)
        assertEquals(12.5, trips.getJSONObject(0).getDouble("distance_km"), 0.0)
    }

    @Test fun `each change takes a higher version than any before`() = runBlocking {
        store.ensureInstalled()
        val id = db.tripDao().insert(TripEntity(startTs = 1L))
        val (_, v1) = mapping("trips", id)!!
        db.tripDao().update(TripEntity(id = id, startTs = 1L, endTs = 5L))
        val (_, v2) = mapping("trips", id)!!
        db.chargeDao().insert(ChargeEntity(startTs = 1L))
        assertTrue(v2 > v1)
        assertTrue(v1 >= clock)
    }

    @Test fun `a live trip keeps its random id when it gets a byd_id later`() = runBlocking {
        store.ensureInstalled()
        val id = db.tripDao().insert(TripEntity(startTs = 1L))
        val (before, _) = mapping("trips", id)!!
        db.tripDao().update(TripEntity(id = id, startTs = 1L, bydId = 77L))
        assertEquals(before, mapping("trips", id)!!.first)
        assertNotEquals(UploadUuid.trip(77L).toString(), before)
    }

    @Test fun `delete and re-insert of the same Room id keeps the record id`() = runBlocking {
        // What a partial backup restore does: DELETE every row, INSERT the archive's back.
        store.ensureInstalled()
        val id = db.tripDao().insert(TripEntity(startTs = 1L))
        val (before, v1) = mapping("trips", id)!!
        sql.execSQL("DELETE FROM trips")
        sql.execSQL("INSERT INTO trips (id, start_ts, source) VALUES ($id, 1, 'live')")
        val (after, v2) = mapping("trips", id)!!
        assertEquals(before, after)
        assertTrue(v2 > v1)
        val batch = body(build()!!)
        assertFalse("the revived trip is an upsert, not a deletion", batch.has("deletions"))
    }

    @Test fun `trip deletion reasons follow tombstones`() = runBlocking {
        store.ensureInstalled()
        val owner = db.tripDao().insert(TripEntity(startTs = 1L, bydId = 1L, source = "energydata"))
        val importer = db.tripDao().insert(TripEntity(startTs = 2L, bydId = 2L, source = "energydata"))
        val live = db.tripDao().insert(TripEntity(startTs = 3L))
        drain()
        db.tripTombstoneDao().insert(TripTombstoneEntity(1L))
        db.tripDao().deleteById(owner)
        db.tripDao().deleteById(importer)
        db.tripDao().deleteById(live)

        val deletions = body(build()!!).getJSONArray("deletions")
        val byReason = (0 until deletions.length()).map { deletions.getJSONObject(it) }
        val owners = byReason.single { it.optLong("byd_id") == 1L }
        assertEquals("user", owners.getString("reason"))
        assertEquals(UploadUuid.trip(1L).toString(), owners.getString("id"))
        assertEquals("system", byReason.single { it.optLong("byd_id") == 2L }.getString("reason"))
        assertEquals("user", byReason.single { !it.has("byd_id") }.getString("reason"))
    }

    @Test fun `idle drain rebuild sends a deletion and an upsert under the same v5 id`() = runBlocking {
        store.ensureInstalled()
        db.idleDrainDao().insert(IdleDrainEntity(startTs = 1729500000000L, kwhConsumed = 0.4))
        drain()
        db.idleDrainDao().deleteAll()
        db.idleDrainDao().insert(IdleDrainEntity(startTs = 1729500000000L, kwhConsumed = 0.5))

        val batch = body(build()!!)
        val id = UploadUuid.idleDrain(1729500000000L).toString()
        val deletion = batch.getJSONArray("deletions").getJSONObject(0)
        val row = batch.getJSONObject("tables").getJSONArray("idle_drains").getJSONObject(0)
        assertEquals(id, deletion.getString("id"))
        assertEquals(id, row.getString("id"))
        assertTrue("the rebuilt row wins", row.getLong("version") > deletion.getLong("version"))
    }

    @Test fun `charge deletions are system for an empty charge, user otherwise`() = runBlocking {
        store.ensureInstalled()
        db.chargeDao().insert(ChargeEntity(startTs = 1L, kwhCharged = 0.01))
        db.chargeDao().insert(ChargeEntity(startTs = 2L, kwhCharged = 20.0))
        drain()
        sql.execSQL("DELETE FROM charges")
        val deletions = body(build()!!).getJSONArray("deletions")
        val reasons = (0 until deletions.length()).map { deletions.getJSONObject(it).getString("reason") }.sorted()
        assertEquals(listOf("system", "user"), reasons)
    }

    @Test fun `points refer to their trip's record id and unattached ones carry none`() = runBlocking {
        store.ensureInstalled()
        val trip = db.tripDao().insert(TripEntity(startTs = 1L, endTs = 100L))
        db.tripPointDao().insert(TripPointEntity(tripId = trip, timestamp = 50L, lat = 1.0, lon = 2.0))
        db.tripPointDao().insert(TripPointEntity(tripId = 0, timestamp = 500L, lat = 1.0, lon = 2.0))
        val tables = body(build()!!).getJSONObject("tables")
        val tripId = tables.getJSONArray("trips").getJSONObject(0).getString("id")
        val points = tables.getJSONArray("trip_points")
        val attached = (0 until points.length()).map { points.getJSONObject(it) }.single { it.getLong("timestamp") == 50L }
        val loose = (0 until points.length()).map { points.getJSONObject(it) }.single { it.getLong("timestamp") == 500L }
        assertEquals(tripId, attached.getString("trip_id"))
        assertFalse(loose.has("trip_id"))
    }

    @Test fun `attaching points later sends them again`() = runBlocking {
        store.ensureInstalled()
        db.tripPointDao().insert(TripPointEntity(tripId = 0, timestamp = 50L, lat = 1.0, lon = 2.0))
        drain()
        val trip = db.tripDao().insert(TripEntity(startTs = 1L, endTs = 100L))
        db.tripPointDao().attachToTrip(trip, 1L, 100L)
        val points = body(build()!!).getJSONObject("tables").getJSONArray("trip_points")
        assertEquals(1, points.length())
        assertTrue(points.getJSONObject(0).has("trip_id"))
    }

    @Test fun `deleted odometer samples are forgotten, never sent as deletions`() = runBlocking {
        store.ensureInstalled()
        db.odometerSampleDao().insert(OdometerSampleEntity(mileageKm = 1.0, totalElecKwh = null, socPercent = 50, sessionId = 1, timestamp = 1L))
        drain()
        sql.execSQL("DELETE FROM odometer_samples")
        assertNull(build())
        store.ensureInstalled() // reconcile purges what is gone for good
        assertEquals(0L, sql.query("SELECT COUNT(*) FROM plus_upload_rows WHERE tbl = 'odometer_samples'").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun `snapshots and samples carry no version, mutable records do`() = runBlocking {
        store.ensureInstalled()
        db.odometerSampleDao().insert(OdometerSampleEntity(mileageKm = 1.0, totalElecKwh = 2.0, socPercent = 50, sessionId = 1, timestamp = 1L))
        db.tripDao().insert(TripEntity(startTs = 1L))
        val tables = body(build()!!).getJSONObject("tables")
        assertFalse(tables.getJSONArray("odometer_samples").getJSONObject(0).has("version"))
        assertTrue(tables.getJSONArray("trips").getJSONObject(0).has("version"))
    }

    @Test fun `a cut batch moves the watermark only to its last row, the next one continues`() = runBlocking {
        store.ensureInstalled()
        repeat(5) { db.tripDao().insert(TripEntity(startTs = it.toLong())) }
        val first = build(maxRows = 3)!!
        val firstRows = body(first).getJSONObject("tables").getJSONArray("trips")
        assertEquals(3, firstRows.length())
        assertEquals(firstRows.getJSONObject(2).getLong("version"), first.watermarks["trips"])
        assertNull("tables after the cut keep their old watermark", first.watermarks["trip_points"])
        store.ack(first)

        val second = build(maxRows = 3)!!
        assertEquals(2, body(second).getJSONObject("tables").getJSONArray("trips").length())
        store.ack(second)
        assertNull(build())
    }

    @Test fun `a drained table's watermark is the clock`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 1L))
        val batch = build()!!
        val clockNow = sql.query("SELECT last FROM plus_upload_clock").use { it.moveToFirst(); it.getLong(0) }
        assertEquals(clockNow, batch.watermarks["trips"])
        assertEquals(clockNow, batch.watermarks["charges"])
    }

    @Test fun `a pending batch survives until acknowledged and is rebuilt after drop`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 1L))
        val batch = build()!!
        assertEquals(batch.batchId, store.pending()?.batchId)
        store.dropPending()
        assertNull(store.pending())
        assertFalse(batch.file.exists())
        assertNotEquals(batch.batchId, build()!!.batchId)
    }

    @Test fun `a pending batch whose body is gone is dropped`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 1L))
        build()!!.file.delete()
        assertNull(store.pending())
    }

    @Test fun `a column upstream adds goes to extra and the table is sent again`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 1L))
        drain()
        // An upstream migration: new column, filled without firing an UPDATE trigger per row.
        sql.execSQL("DROP TRIGGER plus_upload_g1_trips_upd")
        sql.execSQL("ALTER TABLE trips ADD COLUMN future_col REAL")
        sql.execSQL("UPDATE trips SET future_col = 4.5")
        UploadSchema.ensureTriggers(sql)
        store.ensureInstalled()

        val row = body(build()!!).getJSONObject("tables").getJSONArray("trips").getJSONObject(0)
        assertEquals(4.5, row.getJSONObject("extra").getDouble("future_col"), 0.0)
        assertFalse(row.has("future_col"))
    }

    @Test fun `rows changed while the triggers were gone are caught by reconcile`() = runBlocking {
        store.ensureInstalled()
        val kept = db.tripDao().insert(TripEntity(startTs = 1L))
        drain()
        UploadSchema.dropTriggers(sql)
        db.tripDao().insert(TripEntity(startTs = 2L))
        db.tripDao().deleteById(kept)
        UploadSchema.ensureTriggers(sql)
        store.ensureInstalled()

        val batch = body(build()!!)
        assertEquals(1, batch.getJSONObject("tables").getJSONArray("trips").length())
        assertEquals("system", batch.getJSONArray("deletions").getJSONObject(0).getString("reason"))
    }

    @Test fun `server watermarks raise the clock and are adopted only without own state`() = runBlocking {
        store.ensureInstalled()
        val far = clock + 10_000_000L
        store.raiseClock(far)
        store.adoptServerWatermarks(mapOf("trips" to far))
        assertEquals(far, store.acked()["trips"])
        val id = db.tripDao().insert(TripEntity(startTs = 1L))
        assertTrue(mapping("trips", id)!!.second > far)

        store.adoptServerWatermarks(mapOf("trips" to 1L))
        assertEquals("own state wins", far, store.acked()["trips"])
    }

    @Test fun `the database id is created once and kept`() {
        val first = store.ensureInstalled()
        assertEquals(first, store.ensureInstalled())
        assertEquals(first, store.databaseId())
    }

    @Test fun `finished trips with record ids are listed for traces`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 100L, endTs = 200L, bydId = 9L, source = "energydata"))
        db.tripDao().insert(TripEntity(startTs = 300L))
        val trips = store.finishedTrips(0L, 1_000L)
        assertEquals(listOf(TripWindow(UploadUuid.trip(9L).toString(), 100L, 200L)), trips)
    }

    @Test fun `points and snapshots keep their record id through delete and re-insert`() = runBlocking {
        store.ensureInstalled()
        val point = db.tripPointDao().insert(TripPointEntity(tripId = 0, timestamp = 1L, lat = 1.0, lon = 2.0))
        val snap = db.batterySnapshotDao().insert(
            com.bydmate.app.data.local.entity.BatterySnapshotEntity(timestamp = 1L, socStart = 20, socEnd = 80, kwhCharged = 40.0)
        )
        val pointId = mapping("trip_points", point)!!.first
        val snapId = mapping("battery_snapshots", snap)!!.first
        drain()
        sql.execSQL("DELETE FROM trip_points")
        sql.execSQL("DELETE FROM battery_snapshots")
        sql.execSQL("INSERT INTO trip_points (id, trip_id, timestamp, lat, lon) VALUES ($point, 0, 1, 1.0, 2.0)")
        sql.execSQL(
            "INSERT INTO battery_snapshots (id, timestamp, soc_start, soc_end, kwh_charged) VALUES ($snap, 1, 20, 80, 40.0)"
        )
        assertEquals(pointId, mapping("trip_points", point)!!.first)
        assertEquals(snapId, mapping("battery_snapshots", snap)!!.first)
    }

    @Test fun `a partial restore never deletes anything on the server`() = runBlocking {
        store.ensureInstalled()
        val kept = db.tripDao().insert(TripEntity(startTs = 1L))
        val newer = db.tripDao().insert(TripEntity(startTs = 2L))
        drain()
        val keptId = mapping("trips", kept)!!.first
        // What BackupDatabaseFiles.merge does after UploadSchema.beforeMerge: no triggers, flag set,
        // tables replaced by an older archive that lacks the newer trip.
        UploadSchema.dropTriggers(sql)
        sql.execSQL("INSERT OR REPLACE INTO plus_upload_meta (key, value) VALUES ('${UploadSchema.FLAG_RESTORED}', '1')")
        sql.execSQL("DELETE FROM trips")
        sql.execSQL("INSERT INTO trips (id, start_ts, source) VALUES ($kept, 1, 'live')")
        UploadSchema.ensureTriggers(sql)
        store.ensureInstalled()

        val batch = build()!!
        assertFalse("no deletion for the trip the archive lacks", body(batch).has("deletions"))
        assertEquals("what the archive brought back is sent again", 1, body(batch).getJSONObject("tables").getJSONArray("trips").length())
        store.ack(batch)
        assertEquals(keptId, mapping("trips", kept)!!.first)
        // The forgotten trip is not deleted later either.
        store.ensureInstalled()
        assertTrue(build()?.let { !body(it).has("deletions") } ?: true)
        val flag = sql.query("SELECT deleted FROM plus_upload_rows WHERE tbl = 'trips' AND room_id = $newer")
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("forgotten, not deleted", -1, flag)
    }

    @Test fun `after an upstream migration the small tables are sent again, points are not`() = runBlocking {
        store.ensureInstalled()
        db.tripDao().insert(TripEntity(startTs = 1L))
        db.tripPointDao().insert(TripPointEntity(tripId = 0, timestamp = 1L, lat = 1.0, lon = 2.0))
        drain()
        sql.execSQL("INSERT OR REPLACE INTO plus_upload_meta (key, value) VALUES ('${UploadSchema.FLAG_UPGRADED}', '1')")
        store.ensureInstalled()
        val batch = build()!!
        val tables = body(batch).getJSONObject("tables")
        assertTrue(tables.has("trips"))
        assertFalse(tables.has("trip_points"))
        store.ack(batch)
        store.ensureInstalled()
        assertNull("the flag is used once", build())
    }
}
