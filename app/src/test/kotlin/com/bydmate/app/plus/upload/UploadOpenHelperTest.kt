package com.bydmate.app.plus.upload

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UploadOpenHelperTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun triggers(db: SupportSQLiteDatabase): Int =
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'plus_upload_%'")
            .use { it.moveToFirst(); it.getInt(0) }

    /** Stand-in for Room's own callback: the tables the triggers sit on, and a migration hook. */
    private class AppCallback(version: Int, val onMigrate: (SupportSQLiteDatabase) -> Unit = {}) :
        SupportSQLiteOpenHelper.Callback(version) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            UploadSchema.TRACKED.forEach {
                db.execSQL("CREATE TABLE $it (id INTEGER PRIMARY KEY AUTOINCREMENT, byd_id INTEGER, start_ts INTEGER, kwh_charged REAL)")
            }
        }
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = onMigrate(db)
    }

    private fun open(callback: AppCallback): SupportSQLiteOpenHelper =
        UploadOpenHelper.Factory(FrameworkSQLiteOpenHelperFactory()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("hook-test.db").callback(callback).build()
        )

    @org.junit.After fun resetGate() = UploadRestoreGate.resetForTest()

    @Test fun `an upstream migration never sees our triggers, and they are back after the open`() {
        context.deleteDatabase("hook-test.db")
        open(AppCallback(1)).also { h ->
            UploadSchema.install(h.writableDatabase)
            assertEquals(18, triggers(h.writableDatabase))
            h.close()
        }
        var seenDuringMigration = -1
        val helper = open(AppCallback(2) { db -> seenDuringMigration = triggers(db) })
        val db = helper.writableDatabase
        assertEquals(0, seenDuringMigration)
        assertEquals(18, triggers(db))
        helper.close()
    }

    @Test fun `a database that never enabled upload gets no triggers`() {
        context.deleteDatabase("hook-test.db")
        val helper = open(AppCallback(1))
        assertEquals(0, triggers(helper.writableDatabase))
        helper.close()
    }

    @Test fun `triggers of an older generation are replaced`() {
        context.deleteDatabase("hook-test.db")
        val helper = open(AppCallback(1))
        val db = helper.writableDatabase
        UploadSchema.install(db)
        db.execSQL("CREATE TRIGGER plus_upload_g0_trips_ins AFTER INSERT ON trips BEGIN SELECT 1; END")
        UploadSchema.ensureTriggers(db)
        val names = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        assertTrue(names.none { it.startsWith("plus_upload_g0_") })
        assertEquals(18, names.size)
        helper.close()
    }

    @Test fun `a column upstream drops later never breaks the app's own writes`() {
        context.deleteDatabase("hook-test.db")
        val helper = open(AppCallback(1))
        val db = helper.writableDatabase
        UploadSchema.install(db)
        // Upstream migrations (our onUpgrade drops the triggers first): trips loses byd_id, charges
        // loses kwh_charged, idle_drains loses its id key altogether.
        UploadSchema.dropTriggers(db)
        rebuild(db, "trips", "id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER")
        rebuild(db, "charges", "id INTEGER PRIMARY KEY AUTOINCREMENT, start_ts INTEGER")
        rebuild(db, "idle_drains", "start_ts INTEGER")
        UploadSchema.ensureTriggers(db)

        db.execSQL("INSERT INTO trips (start_ts) VALUES (1)")
        db.execSQL("UPDATE trips SET start_ts = 2")
        db.execSQL("INSERT INTO charges (start_ts) VALUES (1)")
        db.execSQL("DELETE FROM charges")
        db.execSQL("INSERT INTO idle_drains (start_ts) VALUES (1)")
        db.execSQL("DELETE FROM trips")
        assertEquals("idle_drains without an id is left untracked", 15, triggers(db))
        val reason = db.query("SELECT reason FROM plus_upload_rows WHERE tbl = 'charges'").use { it.moveToFirst(); it.getString(0) }
        assertEquals("user", reason)
        helper.close()
    }

    @Test fun `a backup export copy loses our triggers`() {
        context.deleteDatabase("hook-test.db")
        val helper = open(AppCallback(1))
        UploadSchema.install(helper.writableDatabase)
        helper.close()
        val path = context.getDatabasePath("hook-test.db").path
        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            UploadSchema.dropTriggers(it)
            val left = it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'", null).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(0, left)
        }
    }

    @Test fun `before a partial restore the copy loses its triggers and is flagged`() {
        context.deleteDatabase("hook-test.db")
        val helper = open(AppCallback(1))
        UploadSchema.install(helper.writableDatabase)
        helper.close()
        val path = context.getDatabasePath("hook-test.db").path
        android.database.sqlite.SQLiteDatabase.openDatabase(path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO plus_upload_meta (key, value) VALUES ('pending_batch', '{}')")
            UploadSchema.beforeMerge(it)
            val pending = it.rawQuery("SELECT 1 FROM plus_upload_meta WHERE key = 'pending_batch'", null).use { c -> c.moveToFirst() }
            assertTrue("a pre-restore batch is dropped", !pending)
            val left = it.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'", null).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(0, left)
            val flag = it.rawQuery("SELECT value FROM plus_upload_meta WHERE key = '${UploadSchema.FLAG_RESTORED}'", null)
                .use { c -> c.moveToFirst() }
            assertTrue(flag)
        }
    }

    private fun rebuild(db: SupportSQLiteDatabase, table: String, columns: String) {
        db.execSQL("CREATE TABLE ${table}_new ($columns)")
        db.execSQL("DROP TABLE $table")
        db.execSQL("ALTER TABLE ${table}_new RENAME TO $table")
    }
}
