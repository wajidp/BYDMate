package com.bydmate.app.plus.upload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Per-second drive samples waiting for their trip. No trip row exists while driving (car-log
 * trips are imported afterwards, live ones are written at close), so samples are kept here and
 * sent once a finished trip covers them. Its own file, outside bydmate.db: backups never carry it.
 * [name] null = in memory (tests).
 */
internal class TraceStore(context: Context, name: String? = NAME) : SQLiteOpenHelper(context, name, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (t INTEGER PRIMARY KEY NOT NULL, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE sent (trip_id TEXT PRIMARY KEY NOT NULL, end_ts INTEGER NOT NULL, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Keyed by time: a second sample with the same timestamp (clock jump) is dropped. */
    fun add(t: Long, body: String) {
        val values = ContentValues().apply {
            put("t", t)
            put("body", body)
        }
        writableDatabase.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun oldest(): Long? = readableDatabase.rawQuery("SELECT MIN(t) FROM samples", null)
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    fun samples(from: Long, to: Long): List<String> =
        readableDatabase.rawQuery(
            "SELECT body FROM samples WHERE t >= ? AND t <= ? ORDER BY t",
            arrayOf(from.toString(), to.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Where the trace sent for [tripId] ended, or null when none was sent: a trip may grow later. */
    fun sentEnd(tripId: String): Long? =
        readableDatabase.rawQuery("SELECT end_ts FROM sent WHERE trip_id = ?", arrayOf(tripId))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    /** The trace of [tripId] up to [to] is on the server (or never will be): those samples go. */
    fun markSent(tripId: String, from: Long, to: Long, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "sent", null,
                ContentValues().apply {
                    put("trip_id", tripId)
                    put("end_ts", to)
                    put("at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete("samples", "t >= ? AND t <= ?", arrayOf(from.toString(), to.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Samples no trip claimed, and old sent marks, go after a while. */
    fun prune(now: Long) {
        writableDatabase.delete("samples", "t < ?", arrayOf((now - SAMPLE_KEEP_MS).toString()))
        writableDatabase.delete("sent", "at < ?", arrayOf((now - SENT_KEEP_MS).toString()))
    }

    companion object {
        const val NAME = "plus_upload_trace.db"
        private const val DAY_MS = 86_400_000L
        const val SAMPLE_KEEP_MS = 14 * DAY_MS
        const val SENT_KEEP_MS = 60 * DAY_MS
    }
}
