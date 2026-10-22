package com.bydmate.app.plus.upload

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** A batch stored before it is sent: retries send this exact body under this id. */
internal data class PendingBatch(
    val batchId: String,
    val watermarks: Map<String, Long>,
    val rows: Int,
    val file: File,
)

/** A finished trip a drive trace can belong to. */
internal data class TripWindow(val recordId: String, val startTs: Long, val endTs: Long)

/**
 * Everything the uploader keeps in bydmate.db (see [UploadSchema]). Every method that reads and
 * writes runs in one transaction, so a batch is a consistent snapshot: all changes with a version
 * up to the clock value read are visible, none above it can commit inside.
 */
@Suppress("TooManyFunctions") // the single owner of the plus_upload_* tables
internal class UploadStore(
    private val db: () -> SupportSQLiteDatabase,
    private val batchDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Creates the schema on first use, then catches up on whatever the triggers missed. */
    fun ensureInstalled(): String = tx { d ->
        UploadSchema.install(d)
        if (meta(d, KEY_DATABASE_ID) == null) putMeta(d, KEY_DATABASE_ID, UUID.randomUUID().toString())
        reconcile(d)
        meta(d, KEY_DATABASE_ID)!!
    }

    fun isInstalled(): Boolean = UploadSchema.isInstalled(db())

    fun databaseId(): String? = if (isInstalled()) meta(db(), KEY_DATABASE_ID) else null

    fun acked(): Map<String, Long> = parseWatermarks(meta(db(), KEY_ACKED))

    /** Spec: the clock never stays below a watermark the server already holds. */
    fun raiseClock(atLeast: Long) = tx { d ->
        d.execSQL("UPDATE ${UploadSchema.CLOCK} SET last = MAX(last, ?) WHERE id = 1", arrayOf<Any>(atLeast))
    }

    /** The server's watermarks count only when this database has no upload state of its own. */
    fun adoptServerWatermarks(server: Map<String, Long>) = tx { d ->
        if (parseWatermarks(meta(d, KEY_ACKED)).isEmpty() && server.isNotEmpty()) {
            putMeta(d, KEY_ACKED, watermarksJson(server).toString())
        }
    }

    /** Rows changed since the last acknowledged batch (an estimate for the settings card). */
    fun backlog(): Int {
        val d = db()
        val acked = parseWatermarks(meta(d, KEY_ACKED))
        return UploadSchema.TRACKED.sumOf { t ->
            d.query("SELECT COUNT(*) FROM ${UploadSchema.ROWS} WHERE tbl = ? AND version > ?", arrayOf(t, acked[t] ?: 0L))
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
    }

    fun pending(): PendingBatch? {
        val raw = meta(db(), KEY_PENDING) ?: return null
        val o = JSONObject(raw)
        val batch = PendingBatch(
            batchId = o.getString("batch_id"),
            watermarks = parseWatermarks(o.getJSONObject("watermarks").toString()),
            rows = o.optInt("rows"),
            file = File(batchDir, o.getString("file")),
        )
        // A restored database may name a batch whose body never existed on this device.
        if (!batch.file.isFile) {
            dropPending()
            return null
        }
        return batch
    }

    fun dropPending() = tx { d ->
        d.execSQL("DELETE FROM ${UploadSchema.META} WHERE key = ?", arrayOf(KEY_PENDING))
        deleteStrayBodies(keep = null)
    }

    /** Bodies no pending row names any more (a crash between the write and the commit). */
    private fun deleteStrayBodies(keep: String?) {
        batchDir.listFiles { f -> f.name.startsWith("pending-") && f.name != keep }?.forEach { it.delete() }
    }

    /**
     * Builds the next batch (up to [maxRows] rows and deletions), stores its gzipped body and
     * marks it pending. Null when nothing changed since the last acknowledged batch.
     */
    fun buildBatch(maxRows: Int, appVersion: String, createdAt: String): PendingBatch? = tx { d ->
        resolveRecordIds(d)
        val clock = clock(d)
        val acked = parseWatermarks(meta(d, KEY_ACKED))
        val tables = JSONObject()
        val deletions = JSONArray()
        val watermarks = acked.toMutableMap()
        var budget = maxRows
        var count = 0
        for (table in UploadSchema.TRACKED) {
            val columns = tableColumns(d, table)
            if (!UploadSchema.trackable(columns) || budget == 0) continue
            val from = acked[table] ?: 0L
            val changes = readChanges(d, table, columns, from, budget)
            val (rows, deleted) = jsonOf(d, table, columns, changes)
            if (rows.length() > 0) tables.put(table, rows)
            deleted.forEach(deletions::put)
            count += changes.size
            budget -= changes.size
            // A table read to its end is complete up to the clock; a cut one up to its last row.
            val drained = budget > 0
            watermarks[table] = maxOf(from, if (drained) clock else changes.last().version)
        }
        if (count == 0) return@tx null
        val batchId = UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("batch_id", batchId)
            .put("database_id", meta(d, KEY_DATABASE_ID))
            .put("created_at", createdAt)
            .put("app_version", appVersion)
            .put("tables", tables)
            .put("watermarks", watermarksJson(watermarks))
        if (deletions.length() > 0) envelope.put("deletions", deletions)
        batchDir.mkdirs()
        val file = File(batchDir, "pending-$batchId.json.gz")
        // On disk for good before the meta row names it: the head unit loses power at ignition off.
        FileOutputStream(file).use { out ->
            val gz = GZIPOutputStream(out)
            gz.write(envelope.toString().toByteArray(Charsets.UTF_8))
            gz.finish()
            out.fd.sync()
            gz.close()
        }
        deleteStrayBodies(keep = file.name)
        val meta = JSONObject()
            .put("batch_id", batchId)
            .put("watermarks", watermarksJson(watermarks))
            .put("rows", count)
            .put("file", file.name)
        putMeta(d, KEY_PENDING, meta.toString())
        PendingBatch(batchId, watermarks, count, file)
    }

    /**
     * The server applied [batch]. Our own watermarks win over the server's (spec, Watermarks), so
     * only the batch's are stored. Deletions with a name-based id are no longer needed.
     */
    fun ack(batch: PendingBatch) = tx { d ->
        val acked = parseWatermarks(meta(d, KEY_ACKED)).toMutableMap()
        batch.watermarks.forEach { (t, v) -> acked[t] = maxOf(acked[t] ?: 0L, v) }
        putMeta(d, KEY_ACKED, watermarksJson(acked).toString())
        for ((t, v) in acked) {
            d.execSQL(
                "DELETE FROM ${UploadSchema.ROWS} WHERE tbl = ? AND deleted = 1 AND natural_key IS NOT NULL AND version <= ?",
                arrayOf<Any>(t, v),
            )
        }
        d.execSQL("DELETE FROM ${UploadSchema.META} WHERE key = ?", arrayOf(KEY_PENDING))
        batch.file.delete()
    }

    /** Finished, not deleted trips that ended inside [fromTs]..[untilTs], oldest first. */
    fun finishedTrips(fromTs: Long, untilTs: Long): List<TripWindow> = tx { d ->
        resolveRecordIds(d)
        if (!tableColumns(d, "trips").containsAll(listOf("id", "start_ts", "end_ts"))) return@tx emptyList()
        d.query(
            "SELECT r.record_id, t.start_ts, t.end_ts FROM trips t JOIN ${UploadSchema.ROWS} r " +
                "ON r.tbl = 'trips' AND r.room_id = t.id WHERE r.deleted = 0 AND r.record_id IS NOT NULL " +
                "AND t.end_ts IS NOT NULL AND t.end_ts >= ? AND t.end_ts <= ? ORDER BY t.end_ts",
            arrayOf<Any>(fromTs, untilTs),
        ).use { c ->
            buildList { while (c.moveToNext()) add(TripWindow(c.getString(0), c.getLong(1), c.getLong(2))) }
        }
    }

    // ---------------------------------------------------------------- reconcile

    /**
     * Brings the mapping in line with the tables: rows written while the triggers were absent
     * (before the schema existed, or between an upstream table rebuild and the next open) get a
     * mapping, mappings of rows gone meanwhile become deletions, and a table whose column set
     * changed (an upstream migration filled a new column without firing a trigger) is sent again.
     */
    private fun reconcile(d: SupportSQLiteDatabase) {
        val restored = meta(d, UploadSchema.FLAG_RESTORED) != null
        val upgraded = meta(d, UploadSchema.FLAG_UPGRADED) != null
        for (table in UploadSchema.TRACKED) {
            val columns = tableColumns(d, table)
            if (!UploadSchema.trackable(columns)) continue
            val signature = columns.joinToString(",")
            val stored = meta(d, "$KEY_COLUMNS_PREFIX$table")
            // The server stores snapshots and odometer samples once: re-sending those changes nothing.
            val resend = table in UploadRows.MUTABLE && (
                (stored != null && stored != signature) ||
                    (upgraded && table in RESEND_AFTER_UPGRADE) ||
                    restored
                )
            if (resend) {
                d.execSQL("UPDATE ${UploadSchema.ROWS} SET version = 0 WHERE tbl = ? AND deleted = 0", arrayOf(table))
            }
            if (stored != signature) putMeta(d, "$KEY_COLUMNS_PREFIX$table", signature)
            syncMapping(d, table, columns, restored)
            assignVersions(d, table)
        }
        d.execSQL(
            "DELETE FROM ${UploadSchema.META} WHERE key IN (?, ?)",
            arrayOf(UploadSchema.FLAG_RESTORED, UploadSchema.FLAG_UPGRADED),
        )
    }

    /**
     * Rows the mapping misses get one, rows it lost track of are revived, rows gone from the
     * table become deletions — or, after a partial restore, are only forgotten: a restore never
     * deletes anything on the server. Gone rows of tables the server never deletes are purged.
     */
    private fun syncMapping(d: SupportSQLiteDatabase, table: String, columns: List<String>, restored: Boolean) {
        val rows = UploadSchema.ROWS
        val gone = UploadSchema.GONE
        // Count and id sum: one cheap scan each, and a delete + insert swap still shows.
        val live = fingerprint(d, "SELECT COUNT(*), TOTAL(id) FROM `$table`")
        val mapped = fingerprint(d, "SELECT COUNT(*), TOTAL(room_id) FROM $rows WHERE tbl = '$table' AND deleted = 0")
        if (live != mapped) {
            d.execSQL(
                "INSERT OR IGNORE INTO $rows (tbl, room_id, record_id, version, deleted, natural_key) " +
                    "SELECT '$table', id, ${UploadSchema.newRecordId(table, "", columns)}, 0, 0, " +
                    "${UploadSchema.naturalKey(table, "", columns)} FROM `$table`"
            )
            d.execSQL(
                "UPDATE $rows SET deleted = 0, reason = NULL, version = 0 " +
                    "WHERE tbl = '$table' AND deleted <> 0 AND room_id IN (SELECT id FROM `$table`)"
            )
            val vanished = "WHERE tbl = '$table' AND deleted = 0 AND room_id NOT IN (SELECT id FROM `$table`)"
            when {
                // A restore never deletes anything upstream: what it dropped is only forgotten.
                restored -> d.execSQL("DELETE FROM $rows $vanished")
                table in UploadSchema.DELETABLE ->
                    d.execSQL("UPDATE $rows SET deleted = ${UploadSchema.DELETED}, reason = 'system', version = 0 $vanished")
                else -> d.execSQL("UPDATE $rows SET deleted = $gone $vanished")
            }
        }
        if (table !in UploadSchema.DELETABLE) {
            d.execSQL("DELETE FROM $rows WHERE tbl = '$table' AND deleted = $gone AND room_id NOT IN (SELECT id FROM `$table`)")
        }
    }

    /**
     * Versions for rows marked 0 above: base + Room id keeps them unique without a window
     * function (Android 10's SQLite has none), and the clock moves past the highest one.
     */
    private fun assignVersions(d: SupportSQLiteDatabase, table: String) {
        val rowsTable = UploadSchema.ROWS
        val maxId = d.query("SELECT MAX(room_id) FROM $rowsTable WHERE tbl = ? AND version = 0", arrayOf(table))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null } ?: return
        val base = maxOf(clock(d) + 1, now())
        d.execSQL("UPDATE $rowsTable SET version = ? + room_id WHERE tbl = ? AND version = 0", arrayOf<Any>(base, table))
        d.execSQL("UPDATE ${UploadSchema.CLOCK} SET last = MAX(last, ?) WHERE id = 1", arrayOf<Any>(base + maxId))
    }

    // ---------------------------------------------------------------- batch helpers

    private class Change(
        val recordId: String,
        val version: Long,
        val deleted: Boolean,
        val naturalKey: Long?,
        val reason: String?,
        /** Null when the row is deleted (or vanished). */
        val values: List<Pair<String, Any?>>?,
    )

    /** The rows and the deletions of one table's changes. */
    private fun jsonOf(
        d: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        changes: List<Change>,
    ): Pair<JSONArray, List<JSONObject>> {
        val refs = resolveRefs(d, table, columns, changes)
        val rows = JSONArray()
        val deletions = mutableListOf<JSONObject>()
        for (c in changes) {
            when {
                c.deleted -> if (table in UploadSchema.DELETABLE) deletions += deletionJson(d, table, c)
                c.values != null -> rows.put(UploadRows.toJson(table, c.values, c.recordId, c.version) { t, id -> refs[t to id] })
            }
        }
        return rows to deletions
    }

    private fun readChanges(
        d: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        from: Long,
        limit: Int,
    ): List<Change> {
        // Our columns are aliased: a column upstream adds to the table can never shadow them.
        val select = columns.joinToString(", ") { "t.`$it`" }
        val sql = "SELECT r.room_id AS plus_room_id, r.record_id AS plus_record_id, r.version AS plus_version, " +
            "r.deleted AS plus_deleted, r.natural_key AS plus_natural_key, r.reason AS plus_reason, $select " +
            "FROM ${UploadSchema.ROWS} r LEFT JOIN `$table` t ON t.id = r.room_id " +
            "WHERE r.tbl = ? AND r.version > ? ORDER BY r.version LIMIT ?"
        return d.query(sql, arrayOf<Any>(table, from, limit)).use { c ->
            val idIndex = OWN_COLUMNS + columns.indexOf("id")
            buildList {
                while (c.moveToNext()) {
                    val flag = c.getInt(3)
                    val deleted = flag == UploadSchema.DELETED
                    val present = flag == 0 && !c.isNull(idIndex)
                    add(
                        Change(
                            recordId = c.getString(1),
                            version = c.getLong(2),
                            deleted = deleted,
                            naturalKey = if (c.isNull(4)) null else c.getLong(4),
                            reason = c.getString(5),
                            values = if (present) {
                                columns.mapIndexed { i, name -> name to value(c, OWN_COLUMNS + i) }
                            } else null,
                        )
                    )
                }
            }
        }
    }

    private fun resolveRefs(
        d: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        changes: List<Change>,
    ): Map<Pair<String, Long>, String> {
        val (column, target) = UploadRows.REFERENCES[table] ?: return emptyMap()
        if (column !in columns) return emptyMap()
        val ids = changes.mapNotNull { ch ->
            ch.values?.firstOrNull { it.first == column }?.second?.let { (it as? Number)?.toLong() }
        }.filter { it > 0 }.distinct()
        val out = HashMap<Pair<String, Long>, String>()
        for (chunk in ids.chunked(REF_CHUNK)) {
            val marks = chunk.joinToString(",") { "?" }
            d.query(
                "SELECT room_id, record_id FROM ${UploadSchema.ROWS} WHERE tbl = ? AND record_id IS NOT NULL AND room_id IN ($marks)",
                (listOf<Any>(target) + chunk).toTypedArray(),
            ).use { c -> while (c.moveToNext()) out[target to c.getLong(0)] = c.getString(1) }
        }
        return out
    }

    private fun deletionJson(d: SupportSQLiteDatabase, table: String, c: Change): JSONObject {
        val reason = c.reason ?: when {
            // Trips: the owner's delete leaves a tombstone for a car-log trip; a live trip only
            // the owner deletes. The importer's own clean-ups are system deletions.
            table != "trips" -> "system"
            c.naturalKey == null -> "user"
            tombstoned(d, c.naturalKey) -> "user"
            else -> "system"
        }
        val json = JSONObject()
            .put("table", table)
            .put("id", c.recordId)
            .put("version", c.version)
            .put("reason", reason)
        if (table == "trips" && c.naturalKey != null) json.put("byd_id", c.naturalKey)
        return json
    }

    private fun tombstoned(d: SupportSQLiteDatabase, bydId: Long): Boolean = try {
        d.query("SELECT 1 FROM trip_tombstones WHERE byd_id = ?", arrayOf<Any>(bydId)).use { it.moveToFirst() }
    } catch (_: Exception) {
        false
    }

    /** Name-based ids (car-log trips, idle drains) are computed once and then kept. */
    private fun resolveRecordIds(d: SupportSQLiteDatabase) {
        val unresolved = d.query("SELECT tbl, room_id, natural_key FROM ${UploadSchema.ROWS} WHERE record_id IS NULL")
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(Triple(c.getString(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2)))
                }
            }
        for ((table, roomId, key) in unresolved) {
            val id = when {
                key != null && table == "trips" -> UploadUuid.trip(key)
                key != null && table == "idle_drains" -> UploadUuid.idleDrain(key)
                else -> UUID.randomUUID()
            }
            d.execSQL(
                "UPDATE ${UploadSchema.ROWS} SET record_id = ? WHERE tbl = ? AND room_id = ?",
                arrayOf<Any>(id.toString(), table, roomId),
            )
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun clock(d: SupportSQLiteDatabase): Long =
        d.query("SELECT last FROM ${UploadSchema.CLOCK} WHERE id = 1").use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun tableColumns(d: SupportSQLiteDatabase, table: String): List<String> = UploadSchema.columnsOf(d, table)

    private fun fingerprint(d: SupportSQLiteDatabase, sql: String): Pair<Long, Double> =
        d.query(sql).use { if (it.moveToFirst()) it.getLong(0) to it.getDouble(1) else 0L to 0.0 }

    private fun meta(d: SupportSQLiteDatabase, key: String): String? =
        d.query("SELECT value FROM ${UploadSchema.META} WHERE key = ?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putMeta(d: SupportSQLiteDatabase, key: String, value: String) =
        d.execSQL("INSERT OR REPLACE INTO ${UploadSchema.META} (key, value) VALUES (?, ?)", arrayOf(key, value))

    private inline fun <T> tx(block: (SupportSQLiteDatabase) -> T): T {
        val d = db()
        d.beginTransaction()
        try {
            val result = block(d)
            d.setTransactionSuccessful()
            return result
        } finally {
            d.endTransaction()
        }
    }

    companion object {
        const val KEY_DATABASE_ID = "database_id"
        const val KEY_ACKED = "acked_watermarks"
        const val KEY_PENDING = "pending_batch"
        const val KEY_COLUMNS_PREFIX = "columns_"
        private const val OWN_COLUMNS = 6
        /** Small tables an upstream data-only migration may have fixed up; points are not re-sent. */
        private val RESEND_AFTER_UPGRADE = setOf("trips", "charges", "idle_drains")
        private const val REF_CHUNK = 500

        fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            Cursor.FIELD_TYPE_STRING -> c.getString(i)
            else -> null
        }

        fun parseWatermarks(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            val o = JSONObject(raw)
            return o.keys().asSequence().filter { it in UploadSchema.TRACKED }.associateWith { o.getLong(it) }
        }

        fun watermarksJson(w: Map<String, Long>): JSONObject =
            JSONObject().also { o -> w.filterKeys { it in UploadSchema.TRACKED }.forEach { (k, v) -> o.put(k, v) } }
    }
}
