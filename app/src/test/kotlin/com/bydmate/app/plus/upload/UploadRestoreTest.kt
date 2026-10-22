package com.bydmate.app.plus.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

/** A full backup restore swaps another file in: this head unit's upload state must survive it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UploadRestoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun open(name: String) = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    private fun store(db: AppDatabase) = UploadStore({ db.openHelper.writableDatabase }, tmp.newFolder())

    private fun recordId(db: AppDatabase, roomId: Long): String? =
        db.openHelper.writableDatabase.query(
            "SELECT record_id FROM plus_upload_rows WHERE tbl = 'trips' AND room_id = ?", arrayOf<Any>(roomId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    @org.junit.After fun resetGate() = UploadRestoreGate.resetForTest()

    @Test fun `an archive from before upload keeps the live database id and record ids`() = runBlocking {
        context.deleteDatabase("live.db")
        context.deleteDatabase("archive.db")
        // The archive: two trips, no upload state at all.
        open("archive.db").also { a ->
            a.tripDao().insert(TripEntity(startTs = 1L, distanceKm = 5.0))
            a.tripDao().insert(TripEntity(startTs = 2L, distanceKm = 6.0))
            a.close()
        }
        // The live database: upload on, three trips sent.
        val live = open("live.db")
        val liveStore = store(live)
        val databaseId = liveStore.ensureInstalled()
        repeat(3) { live.tripDao().insert(TripEntity(startTs = it + 1L)) }
        while (true) liveStore.ack(liveStore.buildBatch(2_000, "t", "x") ?: break)
        val trip1 = recordId(live, 1L)
        live.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        UploadSchema.beforeFullRestore(context.getDatabasePath("archive.db"), context.getDatabasePath("live.db"))
        live.close()

        val restored = open("archive.db")
        val restoredStore = store(restored)
        assertEquals(databaseId, restoredStore.ensureInstalled())
        assertEquals("same Room id, same record", trip1, recordId(restored, 1L))
        val batch = restoredStore.buildBatch(2_000, "t", "x")!!
        val body = JSONObject(GZIPInputStream(batch.file.inputStream()).readBytes().toString(Charsets.UTF_8))
        assertFalse("a restore deletes nothing upstream", body.has("deletions"))
        val trips = body.getJSONObject("tables").getJSONArray("trips")
        assertEquals("the archive's trips are sent again so its content wins", 2, trips.length())
        assertEquals(5.0, trips.getJSONObject(0).getDouble("distance_km"), 0.0)
        restored.close()
    }

    @Test fun `a restore with upload never enabled anywhere stays untouched`() = runBlocking {
        context.deleteDatabase("live.db")
        context.deleteDatabase("archive.db")
        open("archive.db").also { it.tripDao().insert(TripEntity(startTs = 1L)); it.close() }
        open("live.db").close()
        UploadSchema.beforeFullRestore(context.getDatabasePath("archive.db"), context.getDatabasePath("live.db"))
        val restored = open("archive.db")
        assertFalse(UploadSchema.isInstalled(restored.openHelper.writableDatabase))
        restored.close()
    }

    @Test fun `a batch built before a full restore is never sent after it`() = runBlocking {
        context.deleteDatabase("live.db")
        context.deleteDatabase("archive.db")
        open("archive.db").also { it.tripDao().insert(TripEntity(startTs = 1L)); it.close() }
        val live = open("live.db")
        val liveStore = store(live)
        liveStore.ensureInstalled()
        val id = live.tripDao().insert(TripEntity(startTs = 1L, bydId = 7L, source = "energydata"))
        while (true) liveStore.ack(liveStore.buildBatch(2_000, "t", "x") ?: break)
        live.tripDao().deleteById(id)
        val stale = liveStore.buildBatch(2_000, "t", "x")!!
        assertTrue(JSONObject(GZIPInputStream(stale.file.inputStream()).readBytes().toString(Charsets.UTF_8)).has("deletions"))
        live.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }

        UploadSchema.beforeFullRestore(context.getDatabasePath("archive.db"), context.getDatabasePath("live.db"))
        live.close()

        val restored = open("archive.db")
        val restoredStore = UploadStore({ restored.openHelper.writableDatabase }, stale.file.parentFile!!)
        assertNull("the pre-restore batch is gone", restoredStore.pending())
        restoredStore.ensureInstalled()
        val next = restoredStore.buildBatch(2_000, "t", "x")
        assertTrue(next == null || !JSONObject(GZIPInputStream(next.file.inputStream()).readBytes().toString(Charsets.UTF_8)).has("deletions"))
        restored.close()
    }
}
