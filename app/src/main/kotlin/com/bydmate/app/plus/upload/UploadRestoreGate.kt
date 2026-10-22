package com.bydmate.app.plus.upload

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * A backup restore and a batch upload must never overlap: a batch read from the database before
 * the swap could still carry deletions the restore undoes (a deleted car-log trip would block its
 * byd_id on the server for good). The uploader sends each batch under [lock] and checks
 * [restoring] inside it; a restore sets the flag, then waits for a batch already in flight.
 * The app restarts after a restore, which is what lifts the pause; a restore that failed before
 * the restart leaves uploads paused until the next app start.
 */
internal object UploadRestoreGate {

    val lock = ReentrantLock()

    @Volatile var restoring = false
        private set

    /**
     * Called by the restore paths before they touch anything. With [wait], it waits at most
     * [WAIT_S] for a batch in flight. The partial restore must not wait: it already holds a write
     * transaction on the live database, which that batch's acknowledgement needs.
     */
    fun enter(wait: Boolean) {
        restoring = true
        if (!wait) return
        try {
            if (lock.tryLock(WAIT_S, TimeUnit.SECONDS)) lock.unlock()
        } catch (_: InterruptedException) {
            // Never costs the owner the restore; the flag already holds the next batch back.
            Thread.currentThread().interrupt()
        }
    }

    /** Tests only: the gate is process-wide and a restore never lifts it. */
    internal fun resetForTest() {
        restoring = false
    }

    /** Longer than one whole HTTP call can take (CloudUploader's callTimeout). */
    private const val WAIT_S = 120L
}
