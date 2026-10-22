package com.bydmate.app.plus.upload

import android.util.Log
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Wraps the app database's open helper so the upload triggers never take part in an upstream
 * migration: they are dropped right before Room migrates and put back once Room has opened (and
 * validated) the database. Nothing here may fail the open: every step is caught.
 *
 * Hooked in AppModule.provideDatabase with one line, so upstream migrations stay untouched.
 */
internal object UploadOpenHelper {

    private const val TAG = "PlusUpload"

    fun <T : RoomDatabase> attach(builder: RoomDatabase.Builder<T>): RoomDatabase.Builder<T> =
        builder.openHelperFactory(Factory(FrameworkSQLiteOpenHelperFactory()))

    class Factory(private val delegate: SupportSQLiteOpenHelper.Factory) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            delegate.create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(WrappedCallback(configuration.callback))
                    .noBackupDirectory(configuration.useNoBackupDirectory)
                    .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                    .build()
            )
    }

    class WrappedCallback(private val delegate: SupportSQLiteOpenHelper.Callback) :
        SupportSQLiteOpenHelper.Callback(delegate.version) {

        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            safely("drop before upgrade") { UploadSchema.beforeUpgrade(db) }
            delegate.onUpgrade(db, oldVersion, newVersion)
        }

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            safely("drop before downgrade") { UploadSchema.beforeUpgrade(db) }
            delegate.onDowngrade(db, oldVersion, newVersion)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            delegate.onOpen(db)
            // A rebuilt table lost its triggers with DROP TABLE; rows written before the uploader's
            // own reconcile are caught there.
            safely("ensure triggers") { if (UploadSchema.isInstalled(db)) UploadSchema.ensureTriggers(db) }
        }

        override fun onCorruption(db: SupportSQLiteDatabase) = delegate.onCorruption(db)

        @Suppress("TooGenericExceptionCaught") // nothing of ours may ever fail the app's database open
        private inline fun safely(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "upload schema: $what failed", e)
                UploadSchemaHealth.lastError = "$what: ${e.message}"
            }
        }
    }
}

/** Last schema hook failure in this process, shown on the settings card. */
internal object UploadSchemaHealth {
    @Volatile var lastError: String? = null
}
