package com.bydmate.app.plus.upload

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * BYDMate+ cloud upload bookkeeping inside bydmate.db, deliberately outside Room: no entity, no
 * schema bump, so an upstream migration never conflicts with the fork. Plain tables + triggers:
 *
 *  - `plus_upload_clock` — the spec's hybrid logical clock (one row). Every tracked change takes
 *    `max(last + 1, now ms)` inside the same statement, so no row can commit below a watermark
 *    already sent and versions are unique (a batch can be cut at any row).
 *  - `plus_upload_rows` — one row per tracked Room row: record id, version, deleted flag. Keyed
 *    by (table, Room id) so a delete + re-insert of the same Room id (a partial backup restore
 *    does exactly that) keeps its record id instead of deleting the trip and its trace upstream.
 *    `record_id` NULL = name-based id resolved in Kotlin ([UploadUuid]) from `natural_key`.
 *  - `plus_upload_meta` — database_id, acknowledged watermarks, pending batch, column signatures.
 *
 * A trigger must never fail the app's own write. SQLite applies the OUTER statement's conflict
 * policy to statements inside a trigger (Room's @Update is UPDATE OR ABORT), so an "INSERT OR
 * IGNORE" here would abort on a duplicate: inserts are guarded with WHERE NOT EXISTS instead and
 * nothing here can hit a constraint.
 *
 * Triggers only reference their own table's columns and plus_* tables, so an upstream
 * table rebuild (CREATE new / copy / DROP / RENAME) cannot fail on them; DROP TABLE removes them
 * and [ensureTriggers] puts them back on the next open. Uses only SQLite built-ins available on
 * Android 10 (no UPSERT, no window functions).
 */
@Suppress("TooManyFunctions") // the whole SQL side of the upload bookkeeping, in one place
internal object UploadSchema {

    const val ROWS = "plus_upload_rows"

    /** plus_upload_rows.deleted: 0 = live, 1 = deleted (sent as a deletion), 2 = gone (never sent). */
    const val DELETED = 1
    const val GONE = 2

    /** Meta flags the store's reconcile acts on once. */
    const val FLAG_RESTORED = "flag_restored"
    const val FLAG_UPGRADED = "flag_upgraded"
    const val META = "plus_upload_meta"
    const val CLOCK = "plus_upload_clock"

    /** Upload order (parents first). The server takes deletions only for [DELETABLE]. */
    val TRACKED = listOf("trips", "charges", "idle_drains", "battery_snapshots", "odometer_samples", "trip_points")
    val DELETABLE = setOf("trips", "charges", "idle_drains")

    private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER)"
    const val TICK = "UPDATE $CLOCK SET last = MAX(last + 1, $NOW_MS) WHERE id = 1"
    /** 0 (= "not versioned yet", the store's reconcile assigns one) if the clock row were ever missing. */
    private const val VERSION = "COALESCE((SELECT last FROM $CLOCK WHERE id = 1), 0)"

    /** Random UUIDv4 in SQL (evaluated per row). `random() & 3`, not abs(): abs(INT64_MIN) is an error. */
    const val UUID4 = "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (random() & 3), 1) || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

    /**
     * `ref` is "NEW." / "OLD." inside a trigger, "" in an INSERT ... SELECT over the table.
     * [columns] are the table's columns right now: SQLite does not check names when a trigger is
     * created, only when it fires, so a column upstream drops later must never be named here.
     */
    fun naturalKey(table: String, ref: String, columns: Collection<String>): String = when {
        table == "trips" && "byd_id" in columns -> "${ref}byd_id"
        table == "idle_drains" && "start_ts" in columns -> "${ref}start_ts"
        else -> "NULL"
    }

    /** Car-log trips and idle drains get a v5 id later; everything else a random v4 now. */
    fun newRecordId(table: String, ref: String, columns: Collection<String>): String = when (table) {
        "trips" -> if ("byd_id" in columns) "CASE WHEN ${ref}byd_id IS NULL THEN $UUID4 END" else UUID4
        "idle_drains" -> if ("start_ts" in columns) "NULL" else UUID4
        else -> UUID4
    }

    /** A table can be tracked only while it keeps its integer `id` key. */
    fun trackable(columns: Collection<String>): Boolean = "id" in columns

    fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    fun isInstalled(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$META'").use { it.moveToFirst() }

    /** Creates the tables and triggers. Idempotent. Backfilling existing rows is the store's job. */
    fun install(db: SupportSQLiteDatabase) {
        TABLES_SQL.forEach(db::execSQL)
        ensureTriggers(db)
    }

    private val TABLES_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS $META (key TEXT PRIMARY KEY NOT NULL, value TEXT)",
        "CREATE TABLE IF NOT EXISTS $CLOCK (id INTEGER PRIMARY KEY CHECK (id = 1), last INTEGER NOT NULL)",
        "INSERT OR IGNORE INTO $CLOCK (id, last) VALUES (1, 0)",
        "CREATE TABLE IF NOT EXISTS $ROWS (tbl TEXT NOT NULL, room_id INTEGER NOT NULL, record_id TEXT, " +
            "version INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, natural_key INTEGER, reason TEXT, " +
            "PRIMARY KEY (tbl, room_id))",
        "CREATE INDEX IF NOT EXISTS ${ROWS}_version ON $ROWS (tbl, version)",
        "CREATE INDEX IF NOT EXISTS ${ROWS}_unresolved ON $ROWS (tbl) WHERE record_id IS NULL",
    )

    /** Column lists of our tables, so copying them never depends on column order. */
    private val COLUMNS = mapOf(
        ROWS to "tbl, room_id, record_id, version, deleted, natural_key, reason",
        META to "key, value",
        CLOCK to "id, last",
    )

    /**
     * A full backup restore is about to swap [target] in for [live]. When this head unit uploads,
     * its own upload state wins over the archive's: database_id, clock, watermarks and record ids
     * carry over (an archive from before upload was enabled would otherwise re-send everything
     * under new ids), mappings of rows the archive lacks are forgotten (a Room id it reuses starts
     * fresh), and the flag makes reconcile re-send what the archive brought back. When it does not,
     * the archive's state is kept: that is a reinstall or a replacement head unit of the same car,
     * which the spec wants to keep its database_id. Never throws: a failure must not cost the
     * owner the restore.
     */
    @Suppress("TooGenericExceptionCaught") // see above
    fun beforeFullRestore(target: java.io.File, live: java.io.File) {
        UploadRestoreGate.enter(wait = true)
        try {
            openFramework(target).use { db -> carryOver(db, live) }
        } catch (e: Exception) {
            android.util.Log.w("PlusUpload", "upload state did not carry over the restore", e)
            UploadSchemaHealth.lastError = "restore carry-over: ${e.message}"
            // At least never let the archive's own state send deletions or stale batches.
            runCatching { openFramework(target).use { db -> dropTriggers(db); markRestored(db) } }
        }
    }

    private fun openFramework(file: java.io.File) = android.database.sqlite.SQLiteDatabase.openDatabase(
        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
    )

    private fun frameworkColumns(db: android.database.sqlite.SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA main.table_info(`$table`)", null).use { c ->
            val name = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(name)) }
        }

    private fun hasTable(db: android.database.sqlite.SQLiteDatabase, schema: String, name: String) =
        db.rawQuery("SELECT 1 FROM $schema.sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name))
            .use { it.moveToFirst() }

    private fun carryOver(db: android.database.sqlite.SQLiteDatabase, live: java.io.File) {
        dropTriggers(db)
        val staged = live.isFile && stageLive(db, live)
        if (!staged && !hasTable(db, "main", META)) return
        db.beginTransaction()
        try {
            if (staged) applyStaged(db)
            markRestored(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Copies the live upload state into TEMP tables with autocommit reads: a transaction here would
     * also take the write lock on the attached live database and stall Room's writers for the
     * length of the copy. Rows first, then meta, then the clock, so the clock covers every copied
     * version. False when the live database never had upload on.
     */
    private fun stageLive(db: android.database.sqlite.SQLiteDatabase, live: java.io.File): Boolean {
        db.execSQL("ATTACH DATABASE ? AS live", arrayOf(live.path))
        try {
            if (!hasTable(db, "live", META)) return false
            for (t in listOf(ROWS, META, CLOCK)) {
                db.execSQL("DROP TABLE IF EXISTS temp.plus_stage_$t")
                db.execSQL("CREATE TEMP TABLE plus_stage_$t AS SELECT ${COLUMNS[t]} FROM live.$t")
            }
            return true
        } finally {
            db.execSQL("DETACH DATABASE live")
        }
    }

    /** Inside the caller's transaction: the archive's upload state becomes the staged live one. */
    private fun applyStaged(db: android.database.sqlite.SQLiteDatabase) {
        TABLES_SQL.forEach(db::execSQL)
        for (t in listOf(META, CLOCK, ROWS)) {
            db.execSQL("DELETE FROM main.$t")
            db.execSQL("INSERT INTO main.$t (${COLUMNS[t]}) SELECT ${COLUMNS[t]} FROM temp.plus_stage_$t")
        }
        for (table in TRACKED) {
            if (!hasTable(db, "main", table) || !trackable(frameworkColumns(db, table))) continue
            db.execSQL(
                "DELETE FROM main.$ROWS WHERE tbl = ? AND room_id NOT IN (SELECT id FROM main.`$table`)",
                arrayOf(table),
            )
        }
    }

    /**
     * After any restore: reconcile re-sends what came back and forgets what went, and a batch
     * built before the restore is dropped, since it may carry deletions of rows the restore brought
     * back (a deleted car-log trip would block its byd_id on the server for good).
     */
    private fun markRestored(db: android.database.sqlite.SQLiteDatabase) {
        if (!hasTable(db, "main", META)) return
        db.execSQL("INSERT OR REPLACE INTO main.$META (key, value) VALUES ('$FLAG_RESTORED', '1')")
        db.execSQL("DELETE FROM main.$META WHERE key = 'pending_batch'")
    }

    /**
     * An upstream migration is about to run: our triggers go, and the store is told to send the
     * small tables again, since a data-only migration changes rows without firing anything.
     */
    fun beforeUpgrade(db: SupportSQLiteDatabase) {
        if (isInstalled(db)) db.execSQL("INSERT OR REPLACE INTO $META (key, value) VALUES ('$FLAG_UPGRADED', '1')")
        dropTriggers(db)
    }

    /**
     * A partial backup restore is about to replace tables in [db] (a copy of the live database):
     * no trigger may turn that into server-side deletions (a deleted trip takes its points and
     * trace with it). Reconcile at the next start sees the flag: rows that vanished are only
     * forgotten, never deleted upstream.
     */
    @Suppress("TooGenericExceptionCaught") // nothing of ours may cost the owner the restore
    fun beforeMerge(db: android.database.sqlite.SQLiteDatabase) {
        UploadRestoreGate.enter(wait = false)
        try {
            dropTriggers(db)
            markRestored(db)
        } catch (e: Exception) {
            android.util.Log.w("PlusUpload", "partial restore: upload state not marked", e)
            UploadSchemaHealth.lastError = "partial restore: ${e.message}"
        }
    }

    /** Dropped before an upstream migration runs, so nothing of ours can stand in its way. */
    fun dropTriggers(db: SupportSQLiteDatabase) {
        triggerNames(db).forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    }

    /**
     * Bump when a trigger body changes: CREATE TRIGGER IF NOT EXISTS would otherwise keep the old
     * one forever. Triggers of another generation are dropped here.
     */
    private const val TRIGGER_GENERATION = 1
    private const val TRIGGER_PREFIX = "plus_upload_g${TRIGGER_GENERATION}_"

    /**
     * Rebuilt from the tables' current columns on every call (a few statements per table), so a
     * trigger never outlives a column it names. A table that lost its `id`, or is gone, is left
     * untracked; one table failing never stops the others.
     */
    fun ensureTriggers(db: SupportSQLiteDatabase) {
        val existing = triggerNames(db)
        existing.filterNot { it.startsWith(TRIGGER_PREFIX) }.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        for (table in TRACKED) {
            try {
                listOf("ins", "upd", "del").forEach { db.execSQL("DROP TRIGGER IF EXISTS `$TRIGGER_PREFIX${table}_$it`") }
                val columns = columnsOf(db, table)
                if (trackable(columns)) triggersFor(table, columns).forEach(db::execSQL)
            } catch (e: android.database.SQLException) {
                UploadSchemaHealth.lastError = "triggers on $table: ${e.message}"
            }
        }
    }

    private const val TRIGGER_QUERY =
        "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'plus\\_upload\\_%' ESCAPE '\\'"

    private fun triggerNames(db: SupportSQLiteDatabase): List<String> =
        db.query(TRIGGER_QUERY).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /**
     * For backup exports (a plain framework connection): an archive leaves without our triggers, so
     * the app that opens it — this one recreates them — never inherits ones it cannot maintain.
     */
    fun dropTriggers(db: android.database.sqlite.SQLiteDatabase) {
        val names = db.rawQuery(TRIGGER_QUERY, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        names.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    }

    /** The backup export's hook: never fails the export (triggers left in an archive are inert). */
    @Suppress("TooGenericExceptionCaught") // see above
    fun beforeExport(db: android.database.sqlite.SQLiteDatabase) {
        try {
            dropTriggers(db)
        } catch (e: Exception) {
            android.util.Log.w("PlusUpload", "export: triggers not dropped", e)
        }
    }

    internal fun triggersFor(table: String, columns: Collection<String>): List<String> {
        val upsert = """
            $TICK;
            INSERT INTO $ROWS (tbl, room_id, record_id, version, deleted)
                SELECT '$table', NEW.id, ${newRecordId(table, "NEW.", columns)}, 0, 0
                WHERE NOT EXISTS (SELECT 1 FROM $ROWS WHERE tbl = '$table' AND room_id = NEW.id);
            UPDATE $ROWS SET version = $VERSION, deleted = 0, reason = NULL, natural_key = ${naturalKey(table, "NEW.", columns)}
                WHERE tbl = '$table' AND room_id = NEW.id;
        """.trimIndent()
        val onDelete = if (table in DELETABLE) {
            // Trips: the reason is decided at send time (a tombstone = the owner deleted it).
            val reason = when (table) {
                "charges" -> if ("kwh_charged" in columns) {
                    "CASE WHEN OLD.kwh_charged IS NULL OR OLD.kwh_charged < 0.05 THEN 'system' ELSE 'user' END"
                } else "'user'"
                "idle_drains" -> "'system'"
                else -> "NULL"
            }
            """
                $TICK;
                UPDATE $ROWS SET version = $VERSION, deleted = 1, reason = $reason,
                    natural_key = ${naturalKey(table, "OLD.", columns)}
                    WHERE tbl = '$table' AND room_id = OLD.id;
            """.trimIndent()
        } else {
            // Points, samples and snapshots are never deleted on the server: the mapping is only
            // marked gone (never sent), so a re-insert of the same Room id (a partial restore)
            // revives its record id instead of minting a duplicate. Reconcile purges the rest.
            "UPDATE $ROWS SET deleted = $GONE WHERE tbl = '$table' AND room_id = OLD.id;"
        }
        return listOf(
            "CREATE TRIGGER IF NOT EXISTS $TRIGGER_PREFIX${table}_ins AFTER INSERT ON $table BEGIN $upsert END",
            "CREATE TRIGGER IF NOT EXISTS $TRIGGER_PREFIX${table}_upd AFTER UPDATE ON $table BEGIN $upsert END",
            "CREATE TRIGGER IF NOT EXISTS $TRIGGER_PREFIX${table}_del AFTER DELETE ON $table BEGIN $onDelete END",
        )
    }
}
